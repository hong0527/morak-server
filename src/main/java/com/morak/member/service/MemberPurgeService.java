package com.morak.member.service;

import com.morak.common.security.SocialHasher;
import com.morak.match.entity.MatchLock;
import com.morak.match.repository.MatchBlockRepository;
import com.morak.match.repository.MatchLockRepository;
import com.morak.match.repository.MatchRequestRepository;
import com.morak.match.service.MatchService;
import com.morak.member.entity.BlockedSocialHash;
import com.morak.member.entity.Member;
import com.morak.member.repository.BlockedSocialHashRepository;
import com.morak.member.repository.MediaConsentRepository;
import com.morak.member.repository.MemberAgreementRepository;
import com.morak.member.repository.MemberGoalRepository;
import com.morak.member.repository.MemberRepository;
import com.morak.member.repository.StreakDayRepository;
import com.morak.member.type.MemberStatus;
import com.morak.report.repository.SanctionRepository;
import com.morak.report.type.SanctionType;
import com.morak.session.entity.AppealCase;
import com.morak.session.entity.SessionParticipant;
import com.morak.session.repository.AbsenceEventRepository;
import com.morak.session.repository.AppealCaseRepository;
import com.morak.session.repository.SessionParticipantRepository;
import com.morak.session.repository.WarningRepository;
import com.morak.session.service.SessionExitService;
import com.morak.session.type.AppealStatus;
import com.morak.session.type.LeftReason;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 탈퇴 확정 파기(B4). 유예 30일이 지난 회원 한 명을 한 트랜잭션에서 처리한다.
 *
 * <p>{@link MemberAccountPurger}(만 14세 미만)와 대상이 겹치지 않는다. 그쪽은 <b>회원 행까지
 * 지운다</b> — 가입이 성립하지 않아 남길 근거가 아무것도 없기 때문이다. 여기서는 회원 행을
 * 남기고 식별자만 덮는다. 세션 이력·거래 기록이 이 회원을 참조하고 있어 행을 지우면 남의
 * 세션과 법정 보존 대상인 주문이 함께 무너진다. 두 경로가 하는 일이 반대라 재사용하지 않는다.
 *
 * <p>지우는 것과 남기는 것의 기준은 db-schema '파기·보존 정책'이다. 남기는 쪽(커머스·원장·
 * 신고·제재·세션 참가 행)은 법적 보존 의무와 다른 회원의 이력 정합성이 근거이고, 지우는
 * 쪽은 그 근거가 없는 개인 기록이다.
 */
@Service
@RequiredArgsConstructor
public class MemberPurgeService {

    private static final Logger log = LoggerFactory.getLogger(MemberPurgeService.class);

    /** 관리자 화면이 "왜 심사되지 않았나"에 답할 유일한 재료라 사유를 남긴다. */
    private static final String PURGED_APPEAL_NOTE =
            "신청자 계정이 파기되어 심사 근거가 남아 있지 않습니다. 심사 없이 종결합니다.";

    private final MemberRepository memberRepository;
    private final MemberAgreementRepository memberAgreementRepository;
    private final MemberGoalRepository memberGoalRepository;
    private final MediaConsentRepository mediaConsentRepository;
    private final StreakDayRepository streakDayRepository;
    private final BlockedSocialHashRepository blockedSocialHashRepository;
    private final MatchRequestRepository matchRequestRepository;
    private final MatchBlockRepository matchBlockRepository;
    private final MatchLockRepository matchLockRepository;
    private final SessionParticipantRepository sessionParticipantRepository;
    private final AbsenceEventRepository absenceEventRepository;
    private final WarningRepository warningRepository;
    private final SanctionRepository sanctionRepository;
    private final AppealCaseRepository appealCaseRepository;
    private final SessionExitService sessionExitService;
    private final MatchService matchService;
    private final SocialHasher socialHasher;
    private final Clock clock;

    /**
     * 회원 한 명을 파기한다. 처리했으면 1, 대상이 아니면 0이다.
     *
     * <p>대상 조회와 이 메서드 사이에 상태가 바뀔 수 있어 조건을 여기서 다시 본다. 재실행
     * 멱등의 근거가 이 재확인이다 — 이미 DELETED인 회원은 배치가 집어 오지도 않지만,
     * 집어 왔더라도 여기서 걸린다.
     */
    @Transactional
    public int purgeWithdrawn(Long memberId) {
        Member member = memberRepository.findById(memberId).orElse(null);
        if (member == null || member.getStatus() != MemberStatus.WITHDRAW_PENDING) {
            return 0;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        // 유예가 남았으면 건드리지 않는다. 파기는 되돌릴 수 없어 시각 판정을 대상 조회에만
        // 맡기지 않는다.
        if (member.getDeleteScheduledAt() == null
                || member.getDeleteScheduledAt().isAfter(now)) {
            return 0;
        }

        // 재가입 차단 등재가 익명화보다 먼저다. 익명화가 provider_user_id를 덮고 나면
        // 같은 해시를 다시 만들 수 없어 차단이 영영 성립하지 않는다.
        blockRejoinIfPermanentlySanctioned(member, now);

        // AU-4가 신청 시점에 이미 내보냈지만, 유예 30일 사이에 다시 들어갈 경로가 없다는
        // 보장을 이 배치가 지지 않는다. 남은 참가가 없으면 아무 일도 일어나지 않는다.
        sessionExitService.leaveAll(memberId, LeftReason.WITHDRAWAL);
        // 같은 이유로 활성 매칭 요청도 방어적으로 놓아 준다. 아래에서 요청 행을 통째로 지우지만,
        // 그 삭제는 조건 행을 잠그지 않아 6인 확정을 진행 중인 트랜잭션과 겹칠 수 있다.
        // cancelActiveRequest는 조건 행 잠금·조건부 UPDATE를 함께 하므로 그 경합을 직렬화한다.
        matchService.cancelActiveRequest(memberId);

        // 심사 근거를 지우기 전에 미심사 이의를 종결한다. 순서가 뒤면 근거가 사라진 이의가
        // 큐에 남는다.
        closePendingAppeals(memberId, now);

        memberAgreementRepository.deleteByMemberId(memberId);
        memberGoalRepository.deleteByMemberId(memberId);
        // 회원당 1행이라 PK로 지운다. 없으면 아무 일도 일어나지 않는다.
        mediaConsentRepository.deleteById(memberId);
        streakDayRepository.deleteByMemberId(memberId);
        matchRequestRepository.deleteByMemberId(memberId);
        // 차단은 방향이 있는 2행이라 양쪽을 다 지운다. 한쪽만 남기면 탈퇴한 회원을 가리키는
        // 배제 관계가 남아 상대의 매칭 후보를 계속 깎는다.
        matchBlockRepository.deleteByMemberIdOrBlockedMemberId(memberId, memberId);
        absenceEventRepository.deleteByMemberId(memberId);
        warningRepository.deleteByMemberId(memberId);
        // 잠글 일이 없어진 회원의 잠금 행. 남기면 회원 없는 고아 행이 쌓인다.
        matchLockRepository.deleteById(MatchLock.memberKey(memberId));

        // 세션 참가 행은 남긴다. 지우면 같은 세션에 있던 다른 참가자의 이력에서 인원이
        // 어긋난다. 개인이 쓴 '오늘 할 일'만 비운다.
        for (SessionParticipant participant
                : sessionParticipantRepository.findByMemberId(memberId)) {
            participant.clearPersonalText();
        }

        // 위 퇴장·요청 해제가 지급과 벌크 UPDATE로 영속성 컨텍스트를 비웠을 수 있다. 처음 읽은
        // 인스턴스는 그때부터 준영속이라 거기 찍은 익명화는 flush되지 않고 사라진다
        // (MemberService.requestWithdrawal이 같은 이유로 재조회한다).
        Member target = memberRepository.findById(memberId)
                .orElseThrow(() -> new IllegalStateException("파기 대상 회원이 사라졌다: " + memberId));
        target.anonymize(now);
        log.info("탈퇴 회원 파기: member={}", memberId);
        return 1;
    }

    /**
     * 미심사 이의를 심사 불가로 종결한다(AD-5 큐에서 내려간다).
     *
     * <p><b>파기와 이의 인용은 함께 성립할 수 없다.</b> 인용은 세 가지를 하는데 그중 둘이
     * 방금 지운 개인 기록을 다시 세운다 — 완주 소급이 {@code streak_day}를 되살리고, 그것이
     * 회원 행의 연속 일수·마지막 완주일까지 되돌려 놓는다. 실제로 그랬다: 파기된 회원에게
     * {@code current_streak 1}과 완주일이 다시 생겼고, 파기했다고 말한 기록이 파기되지 않은
     * 상태가 됐다.
     *
     * <p>남은 하나(포인트 환급)만 살려 인용을 반쪽으로 성립시키지 않는다. 심사 근거인
     * 경고·자리비움 이벤트를 아래에서 지우므로 <b>인용할지 기각할지를 판단할 재료 자체가
     * 없고</b>, 근거를 보지 못한 채 내린 인용으로 원장에 환급을 남기는 것은 기록을 더
     * 부정확하게 만든다. 게다가 DELETED 계정은 로그인 경로가 없어 되돌려 준 잔액을 쓸 수도
     * 없다. 탈퇴 유예 30일이 이 이의를 마무리할 창이었다.
     *
     * <p>REJECTED가 아니라 CLOSED인 이유는 {@link AppealStatus} 주석에 있다.
     */
    private void closePendingAppeals(Long memberId, LocalDateTime now) {
        for (AppealCase appeal
                : appealCaseRepository.findByMemberIdAndStatus(memberId, AppealStatus.PENDING)) {
            appeal.closeUnreviewable(PURGED_APPEAL_NOTE, now);
            log.info("파기 회원의 미심사 이의 종결: member={}, appeal={}", memberId, appeal.getId());
        }
    }

    /**
     * PERMANENT 제재 이력자만 재가입 차단 목록에 올린다. 유효 제재가 아니라 이력을
     * 보는 이유는, 영구 제재를 받고 탈퇴하면 그 뒤로는 제재를 조회할 회원 식별자 자체가
     * 사라지기 때문이다. TEMP까지 올리면 기간 제재가 영구 재가입 차단으로 승격된다.
     */
    private void blockRejoinIfPermanentlySanctioned(Member member, LocalDateTime now) {
        boolean permanentlySanctioned = sanctionRepository.findByMemberId(member.getId()).stream()
                .anyMatch(sanction -> sanction.getType() == SanctionType.PERMANENT);
        if (!permanentlySanctioned) {
            return;
        }
        String socialHash = socialHasher.hash(member.getProvider(), member.getProviderUserId());
        // 이미 등재돼 있으면 그대로 둔다. 덮어쓰면 최초 등재 시각이 사라져 보존 기한
        // 기준이 매 실행마다 뒤로 밀린다.
        if (blockedSocialHashRepository.existsById(socialHash)) {
            return;
        }
        // 만료를 두지 않는다. 기한은 법무 확인 후 확정한다(BlockedSocialHash 주석).
        blockedSocialHashRepository.save(
                BlockedSocialHash.sanctionPermanent(socialHash, now, null));
        log.info("영구 제재 이력자 재가입 차단 등재: member={}", member.getId());
    }
}
