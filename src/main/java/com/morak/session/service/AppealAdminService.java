package com.morak.session.service;

import com.morak.common.dto.PageParams;
import com.morak.common.dto.PageResponse;
import com.morak.common.error.BusinessException;
import com.morak.common.error.ErrorCode;
import com.morak.common.sla.SlaOverdue;
import com.morak.member.repository.MemberNickname;
import com.morak.member.repository.MemberRepository;
import com.morak.point.service.PointService;
import com.morak.point.type.PointReason;
import com.morak.session.dto.request.AppealProcessRequest;
import com.morak.session.dto.response.AppealDetailResponse;
import com.morak.session.dto.response.AppealProcessResponse;
import com.morak.session.dto.response.AppealSummaryResponse;
import com.morak.session.entity.AbsenceEvent;
import com.morak.session.entity.AppealCase;
import com.morak.session.entity.Eviction;
import com.morak.session.entity.LiveSession;
import com.morak.session.entity.SessionParticipant;
import com.morak.session.entity.Warning;
import com.morak.session.repository.AbsenceEventRepository;
import com.morak.session.repository.AppealCaseRepository;
import com.morak.session.repository.EvictionRepository;
import com.morak.session.repository.LiveSessionRepository;
import com.morak.session.repository.SessionParticipantRepository;
import com.morak.session.repository.WarningRepository;
import com.morak.session.type.AbsenceEventType;
import com.morak.session.type.AppealStatus;
import com.morak.session.type.DecidedBy;
import com.morak.session.type.ParticipantStatus;
import com.morak.session.type.WarningBasis;
import jakarta.persistence.criteria.Predicate;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 이의 콘솔 (API명세서 AD-5·AD-6·AD-9).
 *
 * <p>관리자 여부는 여기서 보지 않는다. {@code /api/admin/**} 전체를 전역 인터셉터의 ③ 역할
 * 검사가 막으므로 서비스마다 다시 확인하면 검사가 두 곳이 되고 언젠가 한쪽이 빠진다.
 *
 * <p>{@code overdue}는 신고 큐(AD-1)와 같은 {@link SlaOverdue}의 식을 쓴다. 두 콘솔이 각자
 * 조건을 적으면 한쪽만 고친 날 SLA 판정이 갈린다.
 *
 * <p><b>이 서비스는 이미 확정된 결과를 되돌리는 유일한 지점이다.</b> 인용은 세 가지를 함께
 * 한다 — 퇴출 취소, 포인트 역분개, 완주 소급. 어느 것도 행을 지우지 않는다.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class AppealAdminService {

    private static final Logger log = LoggerFactory.getLogger(AppealAdminService.class);

    /** 기한이 임박한 이의가 위로 온다. 신고 큐와 같은 정렬이라 두 화면의 우선순위가 같다. */
    private static final Sort QUEUE_SORT =
            Sort.by(Sort.Order.asc("slaDueAt"), Sort.Order.asc("id"));

    private final AppealCaseRepository appealCaseRepository;
    private final EvictionRepository evictionRepository;
    private final SessionParticipantRepository sessionParticipantRepository;
    private final LiveSessionRepository liveSessionRepository;
    private final WarningRepository warningRepository;
    private final AbsenceEventRepository absenceEventRepository;
    private final WarningTraceService warningTraceService;
    private final MemberRepository memberRepository;
    private final SessionClosingService sessionClosingService;
    private final PointService pointService;
    private final Clock clock;

    /** AD-5 이의 큐. 생략된 필터는 조건 자체를 만들지 않는다. */
    public PageResponse<AppealSummaryResponse> getAppeals(AppealStatus status, Boolean overdue,
                                                          Integer page, Integer size) {
        LocalDateTime now = LocalDateTime.now(clock);
        PageParams params = PageParams.of(page, size);
        Page<AppealCase> appeals = appealCaseRepository.findAll(
                filter(status, overdue, now), params.toPageable(QUEUE_SORT));

        // 세션·경고 수는 근거 퇴출에, 닉네임은 회원에 있다. 한 페이지분을 한 번에 읽어
        // 항목마다 조회가 나가지 않게 한다.
        Map<Long, Eviction> evictions = evictionRepository
                .findAllById(appeals.getContent().stream().map(AppealCase::getEvictionId).toList())
                .stream()
                .collect(Collectors.toMap(Eviction::getId, Function.identity()));
        Map<Long, String> nicknames = memberRepository
                .findByIdIn(appeals.getContent().stream().map(AppealCase::getMemberId).toList())
                .stream()
                .collect(Collectors.toMap(MemberNickname::getId, MemberNickname::getNickname));

        return PageResponse.of(appeals, appeal -> AppealSummaryResponse.of(appeal,
                evictions.get(appeal.getEvictionId()), nicknames.get(appeal.getMemberId()), now));
    }

    /**
     * AD-9 이의 심사 상세. 당사자 진술({@code reasonText})과 경고 3건의 근거 구간을 한 번에
     * 모은다 — 진술은 AP-1이 필수로 받아 저장까지 해 놓고 관리자에게 내려 주는 응답이 없어,
     * 심사자가 큐의 메타데이터만 보고 인용·기각을 골라야 했다.
     */
    public AppealDetailResponse getAppeal(Long appealId) {
        LocalDateTime now = LocalDateTime.now(clock);
        AppealCase appeal = appealCaseRepository.findById(appealId)
                .orElseThrow(() -> new BusinessException(ErrorCode.APPEAL_NOT_FOUND));
        Eviction eviction = evictionRepository.findById(appeal.getEvictionId())
                .orElseThrow(() -> new IllegalStateException(
                        "이의의 근거 퇴출이 없다: appeal=" + appealId));
        LiveSession session = liveSessionRepository.findById(eviction.getSessionId())
                .orElseThrow(() -> new IllegalStateException(
                        "퇴출의 근거 세션이 없다: eviction=" + eviction.getId()));
        String nickname = memberRepository.findByIdIn(List.of(appeal.getMemberId())).stream()
                .findFirst()
                .map(MemberNickname::getNickname)
                .orElse(null);
        // concurrentReporterCount의 분모. "6명 중 4명"과 "2명 중 1명"은 같은 수치가 아니다
        int participantCount = sessionParticipantRepository
                .findBySessionIdOrderByIdAsc(eviction.getSessionId()).size();
        // 구간 되짚기는 SS-8과 공유한다 — 관리자와 당사자가 다른 구간을 보면 안 된다
        Long sessionId = eviction.getSessionId();
        Long memberId = appeal.getMemberId();
        List<AppealDetailResponse.WarningItem> warnings = warningTraceService
                .traceAll(sessionId, memberId, session)
                .stream()
                .map(trace -> toWarningItem(trace, sessionId, memberId))
                .toList();
        return AppealDetailResponse.of(appeal, eviction, nickname, participantCount, warnings,
                now);
    }

    /**
     * 공유 되짚기 결과에 관리자 전용 분석(동시 보고 집계)만 얹는다. 세션·회원은 되짚기 결과가
     * 아니라 호출부에서 받는다 — 한 번의 조회가 한 사람의 경고 전량이라 전 항목이 같은 값이고,
     * 행마다 들고 다니면 같은 값이 3벌로 실린다.
     */
    private AppealDetailResponse.WarningItem toWarningItem(WarningTraceService.WarningTrace trace,
                                                           Long sessionId, Long memberId) {
        Integer concurrentReporterCount = trace.startedAt() == null || trace.endedAt() == null
                ? null
                : (int) absenceEventRepository.countOtherReporters(sessionId, memberId,
                        trace.startedAt(), trace.endedAt());
        return new AppealDetailResponse.WarningItem(trace.seq(), trace.basis(), trace.issuedAt(),
                trace.startedAt(), trace.endedAt(), trace.absentSeconds(),
                trace.reportSkewSeconds(), concurrentReporterCount);
    }

    /**
     * AD-6 이의 처리. 종결된 이의는 재오픈하지 않는다 — 재검토는 새 판단이 아니라 이미
     * 되돌린 것을 한 번 더 되돌리는 일이 되고, 원장에 두 번째 환급이 생길 자리를 연다.
     *
     * <p><b>인용의 순서가 곧 명세다.</b> ① 퇴출 취소 ② 포인트 역분개 ③ 완주 소급. ①이
     * 먼저인 것은 그 시점부터 재매칭 쿨다운(D14)이 풀리기 때문이고, ③이 나중인 것은 완주
     * 지급이 자기 멱등키를 갖는 별개의 기록이라서다.
     *
     * <p><b>지급 호출은 영속성 컨텍스트를 비운다</b>({@link PointService#award}가 잔액 캐시를
     * 벌크 UPDATE로 갱신한다). 그래서 원복을 시작하기 전에 상태 변경을 flush하고, 응답에
     * 실을 값은 전부 원복이 끝난 뒤 다시 읽는다 — 미리 잡아 둔 참조는 그 뒤로 준영속이라
     * 거기서 읽은 값은 원복 이전 상태다.
     *
     * <p>동시에 들어온 두 번의 처리를 막는 것은 이 메서드의 상태 검사가 아니라
     * {@code uk_pl_dedup}이다. 상태 검사는 흔한 재요청을 409로 되돌려 주는 지름길이다.
     */
    @Transactional
    public AppealProcessResponse process(Long adminId, Long appealId,
                                         AppealProcessRequest request) {
        request.validateShape();
        LocalDateTime now = LocalDateTime.now(clock);
        AppealCase appeal = appealCaseRepository.findById(appealId)
                .orElseThrow(() -> new BusinessException(ErrorCode.APPEAL_NOT_FOUND));
        if (appeal.getStatus() != AppealStatus.PENDING) {
            throw new BusinessException(ErrorCode.ALREADY_PROCESSED);
        }
        if (request.decision() == AppealStatus.ACCEPTED) {
            rejectIfSessionLive(appeal);
        }
        appeal.decide(request.decision(), DecidedBy.ADMIN, request.note(), now);

        if (request.decision() == AppealStatus.REJECTED) {
            log.info("이의 기각: appeal={}, admin={}", appealId, adminId);
            return AppealProcessResponse.of(appeal, 0, false, currentStreak(appeal.getMemberId(), now));
        }
        return accept(adminId, appeal, now);
    }

    /**
     * 진행 중인 세션의 퇴출은 인용할 수 없다(409 {@code SESSION_NOT_ENDED}).
     *
     * <p>완주 소급은 세션이 끝난 뒤에만 성립한다 — 아직 진행 중이면 완주 여부가 정해지지 않아
     * {@link SessionClosingService#restoreCompletion}이 false를 돌려주고, 그 사이 이의는 이미
     * ACCEPTED로 종결돼 재처리 경로가 없다. 퇴출만 취소되고 완주는 영영 복구되지 않는 상태다.
     *
     * <p>그래서 여기서 끊어 이의를 PENDING으로 남긴다. 관리자는 세션이 끝난 뒤 같은 이의를
     * 다시 처리하면 되고, 퇴출 취소·환급·완주 소급이 그때 한꺼번에 성립한다.
     */
    private void rejectIfSessionLive(AppealCase appeal) {
        Eviction eviction = evictionRepository.findById(appeal.getEvictionId())
                .orElseThrow(() -> new IllegalStateException(
                        "이의의 근거 퇴출이 없다: appeal=" + appeal.getId()));
        boolean live = liveSessionRepository.findById(eviction.getSessionId())
                .map(LiveSession::isLive)
                .orElse(false);
        if (live) {
            throw new BusinessException(ErrorCode.SESSION_NOT_ENDED);
        }
    }

    private AppealProcessResponse accept(Long adminId, AppealCase appeal, LocalDateTime now) {
        Long appealId = appeal.getId();
        Long memberId = appeal.getMemberId();
        Eviction eviction = evictionRepository.findById(appeal.getEvictionId())
                .orElseThrow(() -> new IllegalStateException(
                        "이의의 근거 퇴출이 없다: appeal=" + appealId));
        eviction.revoke(now);
        // 아래 지급이 컨텍스트를 비우므로 여기까지의 변경(이의 종결·퇴출 취소)을 먼저 내보낸다.
        appealCaseRepository.flush();

        int pointRefunded = refundPenalty(eviction, now);
        boolean completedRestored = restoreCompletion(eviction);

        log.info("이의 인용: appeal={}, eviction={}, admin={}, 환급={}, 완주 소급={}",
                appealId, eviction.getId(), adminId, pointRefunded, completedRestored);
        // 원복이 컨텍스트를 비웠다. 응답에 실을 값은 여기서 다시 읽는다.
        return AppealProcessResponse.of(
                appealCaseRepository.findById(appealId)
                        .orElseThrow(() -> new IllegalStateException("처리한 이의가 사라졌다: " + appealId)),
                pointRefunded, completedRestored, currentStreak(memberId, now));
    }

    /**
     * 역분개. 원래의 {@code EVICTION_PENALTY} 행은 그대로 두고 반대 부호의 새 행을 더한다 —
     * 지우면 {@code balance_after} 연쇄가 깨지고 무슨 일이 있었는지 사라진다.
     *
     * <p><b>실제로 빠져나간 적이 없으면 되돌릴 것도 없다.</b> 퇴출 -300은 퇴출 트랜잭션이
     * 즉시 넣지만, 그 트랜잭션이 원장을 남기지 못하고 끊겼다면 B1의 안전망
     * ({@code settleEvictionPenalty})이 채우기 전까지 차감이 없는 상태가 존재한다. 그때 인용하면
     * 차감된 적 없는 300을 환급하는 셈이 되므로, 되돌릴 대상이 원장에 있는지를 보고 판단한다.
     */
    private int refundPenalty(Eviction eviction, LocalDateTime now) {
        boolean penaltyCharged = pointService.findLedgerId(eviction.getMemberId(),
                PointReason.EVICTION_PENALTY, eviction.getId()) != null;
        if (!penaltyCharged) {
            log.info("퇴출 패널티가 아직 원장에 없어 환급을 건너뛴다: eviction={}", eviction.getId());
            return 0;
        }
        int amount = eviction.getPointPenalty();
        // ref가 eviction.id라 패널티 행과 멱등키가 겹치지 않는다. 재처리가 뚫려도 이 제약이
        // 두 번째 환급을 막는다.
        pointService.award(eviction.getMemberId(), amount, PointReason.APPEAL_REFUND,
                eviction.getId(), now);
        return amount;
    }

    /**
     * 완주 소급 재판정(★D1). 퇴출이 없었다면 종료 시각까지 남아 있었을 사람이므로 완주다.
     *
     * <p>{@code EVICTED}가 아닌 참가자는 대상이 아니다. 자율 퇴장한 사람이 그 뒤 퇴출까지
     * 당하는 경로는 없으므로, 상태가 다르다면 탈락 사유가 퇴출 말고 또 있었다는 뜻이고
     * 그때는 퇴출을 취소해도 완주가 되지 않는다.
     */
    private boolean restoreCompletion(Eviction eviction) {
        SessionParticipant participant = sessionParticipantRepository
                .findBySessionIdAndMemberId(eviction.getSessionId(), eviction.getMemberId())
                .orElse(null);
        if (participant == null || participant.getStatus() != ParticipantStatus.EVICTED) {
            return false;
        }
        return sessionClosingService.restoreCompletion(participant.getId());
    }

    /** AU-2가 보여줄 값과 같아야 한다 — 관리자 화면과 당사자 화면이 다른 연속 일수를 말하면 안 된다. */
    private int currentStreak(Long memberId, LocalDateTime now) {
        return memberRepository.findById(memberId)
                .map(member -> member.currentStreakOn(now.toLocalDate()))
                .orElse(0);
    }

    /** AD-5의 필터 조립. {@code overdue}만 컬럼 비교가 아니라 두 컬럼과 현재 시각의 식이다. */
    private Specification<AppealCase> filter(AppealStatus status, Boolean overdue,
                                             LocalDateTime now) {
        return (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (status != null) {
                predicates.add(builder.equal(root.get("status"), status));
            }
            if (overdue != null) {
                predicates.add(SlaOverdue.predicate(builder, root.get("status"),
                        root.get("slaDueAt"), AppealStatus.PENDING, now, overdue));
            }
            return builder.and(predicates.toArray(Predicate[]::new));
        };
    }
}
