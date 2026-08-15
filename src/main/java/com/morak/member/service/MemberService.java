package com.morak.member.service;

import com.morak.common.error.BusinessException;
import com.morak.common.error.ErrorCode;
import com.morak.common.type.BadgeCode;
import com.morak.match.entity.MatchLock;
import com.morak.match.repository.MatchLockRepository;
import com.morak.match.service.MatchService;
import com.morak.member.dto.request.BirthDateRequest;
import com.morak.member.dto.request.GoalRequest;
import com.morak.member.dto.request.MediaConsentRequest;
import com.morak.member.dto.response.AgeVerificationResponse;
import com.morak.member.dto.response.GoalResponse;
import com.morak.member.dto.response.MemberMeResponse;
import com.morak.member.dto.response.WithdrawalResponse;
import com.morak.member.entity.MediaConsent;
import com.morak.member.entity.Member;
import com.morak.member.entity.MemberGoal;
import com.morak.member.repository.MediaConsentRepository;
import com.morak.member.repository.MemberGoalRepository;
import com.morak.member.repository.MemberRepository;
import com.morak.member.repository.StreakDayRepository;
import com.morak.member.type.AgeVerification;
import com.morak.member.type.GoalStatus;
import com.morak.member.type.MemberStatus;
import com.morak.report.entity.Sanction;
import com.morak.report.repository.SanctionRepository;
import com.morak.session.service.SessionExitService;
import com.morak.session.service.SessionService;
import com.morak.session.type.LeftReason;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Period;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@Transactional(readOnly = true)
public class MemberService {

    /** 만 나이 하한. api-spec §4 AU-3의 "만 14세 미만 차단" 기준. */
    private static final int MINIMUM_AGE = 14;

    /** AU-7 목표 기간 선택지. §0-4의 periodDays {7, 14, 30}. */
    private static final Set<Integer> ALLOWED_PERIOD_DAYS = Set.of(7, 14, 30);

    private final MemberRepository memberRepository;
    private final MemberGoalRepository memberGoalRepository;
    private final StreakDayRepository streakDayRepository;
    private final MediaConsentRepository mediaConsentRepository;
    private final SanctionRepository sanctionRepository;
    private final MatchLockRepository matchLockRepository;
    private final MatchService matchService;
    private final SessionExitService sessionExitService;
    private final SessionService sessionService;
    private final MemberAccountPurger memberAccountPurger;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;
    private final int withdrawalGraceDays;

    public MemberService(MemberRepository memberRepository,
                         MemberGoalRepository memberGoalRepository,
                         StreakDayRepository streakDayRepository,
                         MediaConsentRepository mediaConsentRepository,
                         SanctionRepository sanctionRepository,
                         MatchLockRepository matchLockRepository,
                         MatchService matchService,
                         SessionExitService sessionExitService,
                         SessionService sessionService,
                         MemberAccountPurger memberAccountPurger,
                         Clock clock,
                         PlatformTransactionManager transactionManager,
                         @Value("${morak.withdrawal.grace-days}") int withdrawalGraceDays) {
        this.memberRepository = memberRepository;
        this.memberGoalRepository = memberGoalRepository;
        this.streakDayRepository = streakDayRepository;
        this.mediaConsentRepository = mediaConsentRepository;
        this.sanctionRepository = sanctionRepository;
        this.matchLockRepository = matchLockRepository;
        this.matchService = matchService;
        this.sessionExitService = sessionExitService;
        this.sessionService = sessionService;
        this.memberAccountPurger = memberAccountPurger;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.withdrawalGraceDays = withdrawalGraceDays;
    }

    public MemberMeResponse getMe(Long memberId) {
        Member member = findMember(memberId);
        LocalDateTime now = LocalDateTime.now(clock);
        List<Sanction> effective = sanctionRepository.findByMemberId(memberId).stream()
                .filter(sanction -> sanction.isEffectiveAt(now))
                .toList();
        // 종류·종료 시각 계산은 인터셉터 403 응답과 같은 규칙이어야 해서 Sanction에 모았다
        MemberMeResponse.Sanction sanction = effective.isEmpty()
                ? null
                : new MemberMeResponse.Sanction(
                        Sanction.representativeType(effective), Sanction.latestEndsAt(effective));
        GoalResponse goal = memberGoalRepository.findFirstByMemberIdOrderByIdDesc(memberId)
                .map(latest -> GoalResponse.of(latest,
                        goalProgressDays(member, latest, now.toLocalDate())))
                .orElse(null);
        // Streak와 포인트 잔액은 member의 캐시 컬럼에서 읽는다. 진실은 streak_day·point_ledger지만
        // 홈 화면 조회마다 역방향 연속 일수를 세고 원장을 합산할 비용이 아니다. 다만 연속이
        // 끊긴 날 캐시를 0으로 쓰는 배치가 없어서, 끊겼는지만 오늘 날짜로 판정해 내린다.
        // 동의 여부는 행의 존재 그 자체다 — 미동의를 저장하지 않으므로 false 행이 없다(AU-6).
        return MemberMeResponse.from(member, now.toLocalDate(),
                mediaConsentRepository.existsById(memberId), goal,
                sessionService.getMyActiveSession(memberId), sanction, badges(memberId));
    }

    /**
     * 목표 진행도. 달성 판정(§0-6)이 연속 캐시 AND 시작일 이후 완주일 수의 두 조건이므로
     * 진행률도 둘의 최솟값이다 — 연속만 내리면 달성 직후 새로 건 목표가 이전 연속 때문에
     * "기간/기간 직전"으로 그려지고, 완주일 수만 보면 중간에 끊긴 날이 무시된다.
     *
     * <p>ACHIEVED 목표는 기간값을 그대로 내린다. 달성 뒤에도 완주가 쌓이면 최솟값이 기간을
     * 넘어가는데, 이미 닫힌 목표의 진행이 계속 자라는 것은 화면에 의미가 없다.
     */
    private int goalProgressDays(Member member, MemberGoal goal, LocalDate today) {
        if (goal.getStatus() == GoalStatus.ACHIEVED) {
            return goal.getPeriodDays();
        }
        int daysSinceStart = streakDayRepository.countByMemberIdAndCompletedOnGreaterThanEqual(
                member.getId(), goal.getStartedOn());
        return Math.min(member.currentStreakOn(today), daysSinceStart);
    }

    /** 보유 뱃지. ACHIEVED 목표 행에서 파생하며, 같은 코드는 첫 획득 시각으로 한 번만 싣는다. */
    private List<MemberMeResponse.Badge> badges(Long memberId) {
        return memberGoalRepository.findByMemberIdAndStatus(memberId, GoalStatus.ACHIEVED)
                .stream()
                .map(MemberGoal::getAchievedAt)
                .min(LocalDateTime::compareTo)
                .map(earnedAt -> List.of(
                        new MemberMeResponse.Badge(BadgeCode.GOAL_ACHIEVED, earnedAt)))
                .orElse(List.of());
    }

    /**
     * AU-6 캠 영상 온디바이스 분석 동의. 미동의(false)는 저장하지 않고 400으로 거부한다 —
     * 철회가 v1 범위 밖이라 false 행은 "동의를 취소했다"로도 "아직 안 했다"로도 읽히는
     * 값이 된다.
     *
     * <p><b>첫 동의가 동시에 둘 오면 한쪽이 PK({@code member_id})에 걸린다.</b> "없으면 넣는다"는
     * 사전 조회를 둘이 함께 통과하기 때문이고, 실제 방어선은 그 제약이다. 제약 위반은
     * 트랜잭션이 끝나야 손에 들어오므로 경계를 프로그래밍 방식으로 긋고 밖에서 잡는다 —
     * 애너테이션만 붙이면 잡을 자리가 트랜잭션 안이라 500이 나간다(PY-2·SR-3과 같은 이유).
     *
     * <p>상대가 이미 같은 동의를 남긴 것이라 재시도는 시각 갱신으로 끝난다. 클래스에 걸린
     * {@code readOnly} 트랜잭션에 합류하면 커밋이 이 메서드 밖에서 일어나 그 자리가 다시
     * 사라지므로 {@code NOT_SUPPORTED}로 바깥 트랜잭션을 두지 않는다.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void agreeMediaConsent(Long memberId, MediaConsentRequest request) {
        if (!request.agreed()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    Map.of("agreed", "동의해야 세션에 참여할 수 있습니다."));
        }
        LocalDateTime now = LocalDateTime.now(clock);
        try {
            transactionTemplate.executeWithoutResult(status -> upsertMediaConsent(memberId, now));
        } catch (DataIntegrityViolationException e) {
            transactionTemplate.executeWithoutResult(status -> upsertMediaConsent(memberId, now));
        }
    }

    private void upsertMediaConsent(Long memberId, LocalDateTime now) {
        findMember(memberId);
        mediaConsentRepository.findById(memberId)
                .ifPresentOrElse(
                        consent -> consent.renew(now),
                        () -> mediaConsentRepository.save(MediaConsent.agree(memberId, now)));
    }

    @Transactional
    public AgeVerificationResponse verifyAge(Long memberId, BirthDateRequest request) {
        Member member = findMember(memberId);
        // 검증 결과를 재입력으로 뒤집을 수 없어야 하므로 REQUIRED가 아니면 전부 거부한다
        if (member.getAgeVerification() != AgeVerification.REQUIRED) {
            throw new BusinessException(ErrorCode.ALREADY_VERIFIED);
        }
        int age = Period.between(request.birthDate(), LocalDate.now(clock)).getYears();
        if (age < MINIMUM_AGE) {
            // 파기를 별도 트랜잭션에 맡기고 나서 던진다. 같은 트랜잭션에서 지우면 이 예외의
            // 롤백이 삭제를 되돌려 계정이 남는다(★D7, MemberAccountPurger 주석 참조).
            memberAccountPurger.purge(memberId);
            throw new BusinessException(ErrorCode.UNDER_AGE_SIGNUP_BLOCKED);
        }
        member.verifyAge(request.birthDate(), AgeVerification.VERIFIED);
        return AgeVerificationResponse.from(member);
    }

    @Transactional
    public GoalResponse setGoal(Long memberId, GoalRequest request) {
        if (!ALLOWED_PERIOD_DAYS.contains(request.periodDays())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    Map.of("periodDays", "허용값은 7, 14, 30입니다."));
        }
        Member member = findMember(memberId);
        // 활성 1건 제약이 DB에 없어서(부분 인덱스를 못 쓴다) 이 잠금이 유일한 방어선이다.
        // 잠그지 않으면 동시 요청 두 건이 각각 "활성 목표 없음"을 보고 둘 다 통과한다.
        matchLockRepository.findByLockKey(MatchLock.memberKey(memberId))
                .orElseThrow(() -> new IllegalStateException(
                        "회원 잠금 행이 없다. 가입 트랜잭션이 깨진 계정이다: " + memberId));
        if (memberGoalRepository.existsByMemberIdAndStatus(memberId, GoalStatus.ACTIVE)) {
            throw new BusinessException(ErrorCode.GOAL_ALREADY_ACTIVE);
        }
        LocalDate today = LocalDate.now(clock);
        MemberGoal goal = memberGoalRepository.save(
                MemberGoal.start(memberId, request.periodDays(), today));
        // 설정 직후에도 0이 아닐 수 있다 — 오늘 완주를 마친 뒤 목표를 걸면 시작일(오늘)의
        // 완주가 이미 있고, 달성 판정(>= started_on)도 그 하루를 세기 때문이다.
        return GoalResponse.of(goal, goalProgressDays(member, goal, today));
    }

    @Transactional
    public WithdrawalResponse requestWithdrawal(Long memberId) {
        // 존재·파기 확인이 먼저다. 잠금 행은 회원과 함께 만들어지므로, 없는 회원을 잠그려
        // 하면 잠금 행이 없다는 500이 나간다 — 그 자리의 정답은 401이다.
        findMember(memberId);
        // 잠금 순서는 회원 행 → 조건 행 고정이다(MatchService 주석). 조건 행은 아래 호출이 잡는다.
        matchLockRepository.findByLockKey(MatchLock.memberKey(memberId))
                .orElseThrow(() -> new IllegalStateException(
                        "회원 잠금 행이 없다. 가입 트랜잭션이 깨진 계정이다: " + memberId));
        // 중복 신청 판정은 잠금을 얻은 뒤에 한다. 앞에서 읽은 값은 이 트랜잭션이 회원 행을
        // 잡기 전의 것이라, 동시에 들어온 다른 신청이 커밋한 결과를 보지 못한다. 엔티티가
        // 아니라 상태만 다시 읽는 이유는 영속성 컨텍스트에 남은 인스턴스가 그 값을 덮기
        // 때문이다(MemberRepository#findStatusById 주석).
        if (memberRepository.findStatusById(memberId).orElseThrow(
                () -> new BusinessException(ErrorCode.UNAUTHORIZED))
                == MemberStatus.WITHDRAW_PENDING) {
            throw new BusinessException(ErrorCode.WITHDRAWAL_PENDING);
        }
        // 순서는 SanctionService.apply와 같다 — 퇴장 먼저, 매칭 요청 해제가 나중이다. 두 경로가
        // 같은 두 자원을 반대 순서로 건드리면 동시에 실행됐을 때 서로를 기다린다.
        // 진행 중인 세션에 남겨 두면 탈퇴한 회원이 남의 세션에서 계속 자리를 차지한다.
        // 이 퇴장으로 잔여 인원이 최소치 미만이 되면 세션도 함께 조기 종료된다(D12).
        sessionExitService.leaveAll(memberId, LeftReason.WITHDRAWAL);
        // 대기 중인 요청을 남기면 탈퇴한 회원이 남의 세션에 6번째로 들어간다
        matchService.cancelActiveRequest(memberId);

        // 위 호출의 조건부 UPDATE가 영속성 컨텍스트를 비우므로 회원을 다시 읽어 상태를 바꾼다.
        // 비워지기 전에 읽은 엔티티는 준영속이라 변경이 반영되지 않는다.
        Member member = findMember(memberId);
        LocalDateTime now = LocalDateTime.now(clock);
        member.requestWithdrawal(now, now.plusDays(withdrawalGraceDays));
        return WithdrawalResponse.from(member);
    }

    @Transactional
    public void cancelWithdrawal(Long memberId) {
        Member member = findMember(memberId);
        if (member.getStatus() != MemberStatus.WITHDRAW_PENDING) {
            throw new BusinessException(ErrorCode.NOT_WITHDRAWING);
        }
        // 삭제 예정 시각이 지났으면 B4 배치 실행 전이라도 삭제된 계정으로 본다.
        // 명세의 "DELETED → 401 UNAUTHORIZED" 규칙과 맞춘 판단이다.
        if (!member.getDeleteScheduledAt().isAfter(LocalDateTime.now(clock))) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        member.cancelWithdrawal();
    }

    private Member findMember(Long memberId) {
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
        // 인터셉터가 막지 못한 경로로 들어와도 삭제된 계정은 어떤 회원 API도 쓸 수 없다
        if (member.getStatus() == MemberStatus.DELETED) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        return member;
    }
}
