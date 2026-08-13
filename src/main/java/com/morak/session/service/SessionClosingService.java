package com.morak.session.service;

import com.morak.member.service.StreakService;
import com.morak.point.service.PointService;
import com.morak.point.type.PointReason;
import com.morak.session.entity.AbsenceEvent;
import com.morak.session.entity.Eviction;
import com.morak.session.entity.LiveSession;
import com.morak.session.entity.SessionParticipant;
import com.morak.session.entity.Warning;
import com.morak.session.repository.AbsenceEventRepository;
import com.morak.session.repository.EvictionRepository;
import com.morak.session.repository.LiveSessionRepository;
import com.morak.session.repository.SessionParticipantRepository;
import com.morak.session.repository.WarningRepository;
import com.morak.session.type.AbsenceEventType;
import com.morak.session.type.ParticipantStatus;
import com.morak.session.type.SessionEndReason;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 세션 종료 루틴. <b>세션이 어떤 이유로 끝나든 여기를 지난다</b> — 예정 시각 도래(B1),
 * 잔여 인원 미달 조기 종료(D12), {@code room_finished} 웹훅(SS-10)이 모두
 * {@link #closeSession}의 같은 순서를 탄다(명세 §5).
 *
 * <p><b>진입점마다 따로 끝내면 종료 사유에 따라 결과가 달라진다.</b> 실제로 그랬다 —
 * 조기 종료와 {@code room_finished}는 완주 마킹까지만 하고 지급을 B1에 미뤄, 그 사이 SS-8이
 * {@code pointAwarded=0}을 내렸다. 더 나쁜 것은 미결 정산이 통째로 빠진 것이다: 10분 넘게
 * 자리를 비운 사람도, 돌아오지 않은 PAUSED도 남들이 먼저 나가 세션이 조기 종료되면 경고 없이
 * 완주했다. "남이 나가면 내 경고가 사라진다"는 회피 경로가 성립한다.
 *
 * <p>메서드 하나가 트랜잭션 하나다 — 한 세션의 정산이 실패해도 같은 배치 실행의 다른 세션은
 * 처리돼야 한다.
 *
 * <p><b>순서가 곧 명세다.</b> ① 세션을 닫는다 ② 미결 정산(경고·퇴출) ③ 남은 사람 완주 판정
 * ④ 지급·완주일·목표 검사. ②가 ③보다 먼저인 것이 핵심이다 — 뒤집으면 정산으로 퇴출될
 * 사람이 이미 완주로 집계된 뒤라 되돌릴 자리가 없다. ①이 맨 앞인 것은 재귀 차단이다:
 * ②의 퇴출이 잔여 인원을 최소 미만으로 떨어뜨려 조기 종료 검사를 다시 부르는데, 세션이 이미
 * ENDED라 그 호출이 곧바로 되돌아 나간다.
 *
 * <p><b>지급과 퇴출은 영속성 컨텍스트를 비운다</b>({@link PointService#award}가 잔액 캐시를
 * 벌크 UPDATE로 갱신한다). 그래서 이 클래스는 그 호출을 사이에 두고 엔티티 참조를 들고 있지
 * 않는다 — 목록이 아니라 id로 루프를 돌고, 쓸 행은 그때그때 다시 읽는다. 목록을 미리 읽어
 * 돌면 첫 지급 이후의 참가자가 전부 준영속이 되어 완주 표시와 경고가 조용히 사라진다.
 *
 * <p><b>재실행 안전이 이 클래스의 유일한 합격 기준이다.</b> 근거는 코드가 아니라 제약이다:
 * 지급은 {@code uk_pl_dedup}, 완주일은 {@code uk_streak_day}, 사후 정산 경고는
 * {@code uk_warning}이 막는다. 상태 검사(LIVE인가·이미 지급됐나)는 재실행이 제약에
 * 부딪혀 트랜잭션을 깨뜨리지 않게 하는 지름길이지 방어선이 아니다.
 */
@Service
@Transactional
public class SessionClosingService {

    /**
     * 세션 안에서 잠금을 잡는 순서. <b>참가자 상태를 바꾸면서 종료 판정까지 하는 경로는 예외
     * 없이 세션 행부터 잡는다</b>({@link LiveSessionRepository#findByIdForUpdate}) — SS-4 자리비움,
     * SS-5·SS-6 화장실 모드, SS-7 자율 퇴장, SS-10 유예 초과·{@code room_finished}, AU-4 탈퇴,
     * B1이 전부 그 경로다.
     *
     * <p><b>순서를 정해 두지 않으면 교착이 생긴다.</b> 퇴출은 참가자 행을 먼저 고치고 종료
     * 판정으로 넘어오는데, 같은 순간 B1이 세션 행을 잡은 채 그 참가자 행을 정산하려 하면
     * 둘이 서로를 기다린다. 세션 행이 언제나 첫 자리면 그 교차가 성립하지 않는다.
     *
     * <p>매칭 쪽 규약({@code member:{id} → match:{minutes}},
     * {@link com.morak.match.service.MatchService})과는 겹치는 쌍이 없다 — 종료 경로는
     * {@code match_lock}을 잡지 않고, 매칭 경로는 세션 행을
     * 잡지 않는다. 두 규약이 만나는 곳은 AU-4 하나이고, 거기서도 회원 행이 먼저이며 세션 행은
     * 그 안쪽이라 순환이 없다.
     */
    private static final String LOCK_ORDER = "live_session → session_participant → member";

    private static final Logger log = LoggerFactory.getLogger(SessionClosingService.class);

    private static final Set<ParticipantStatus> PRESENT =
            Set.of(ParticipantStatus.ACTIVE, ParticipantStatus.PAUSED);

    private static final int MINUTES_PER_HOUR = 60;
    private static final int SECONDS_PER_MINUTE = 60;

    private final LiveSessionRepository liveSessionRepository;
    private final SessionParticipantRepository sessionParticipantRepository;
    private final AbsenceEventRepository absenceEventRepository;
    private final WarningRepository warningRepository;
    private final EvictionRepository evictionRepository;
    private final EvictionService evictionService;
    private final ReconnectGraceRegistry graceRegistry;
    private final PointService pointService;
    private final StreakService streakService;
    private final int absenceThresholdSeconds;
    private final int pauseLimitSeconds;
    private final int minParticipants;
    private final int completePointPerHour;

    public SessionClosingService(LiveSessionRepository liveSessionRepository,
                                 SessionParticipantRepository sessionParticipantRepository,
                                 AbsenceEventRepository absenceEventRepository,
                                 WarningRepository warningRepository,
                                 EvictionRepository evictionRepository,
                                 EvictionService evictionService,
                                 ReconnectGraceRegistry graceRegistry,
                                 PointService pointService,
                                 StreakService streakService,
                                 @Value("${morak.session.absence-threshold-seconds}")
                                 int absenceThresholdSeconds,
                                 @Value("${morak.session.pause-limit-minutes}")
                                 int pauseLimitMinutes,
                                 @Value("${morak.session.min-participants}")
                                 int minParticipants,
                                 @Value("${morak.point.session-complete-per-hour}")
                                 int completePointPerHour) {
        this.liveSessionRepository = liveSessionRepository;
        this.sessionParticipantRepository = sessionParticipantRepository;
        this.absenceEventRepository = absenceEventRepository;
        this.warningRepository = warningRepository;
        this.evictionRepository = evictionRepository;
        this.evictionService = evictionService;
        this.graceRegistry = graceRegistry;
        this.pointService = pointService;
        this.streakService = streakService;
        this.absenceThresholdSeconds = absenceThresholdSeconds;
        this.pauseLimitSeconds = pauseLimitMinutes * SECONDS_PER_MINUTE;
        this.minParticipants = minParticipants;
        this.completePointPerHour = completePointPerHour;
    }

    /**
     * 예정 시각이 지난 세션을 닫는다(B1).
     *
     * <p>종료 시각은 배치가 도는 시각이 아니라 {@code ends_at}이다. 배치가 몇 분 늦게
     * 돌았다는 이유로 자리비움 구간이 길어져 경고가 붙으면, 같은 세션을 언제 처리했느냐에
     * 따라 결과가 달라진다.
     *
     * <p>사유는 {@code NORMAL}로 고정한다. 정산 중의 퇴출이 잔여 인원을 최소 미만으로
     * 떨어뜨려도 마찬가지다 — 그 세션은 예정된 시간을 다 채웠으므로 조기 종료가 아니다.
     */
    public int closeDueSession(Long sessionId) {
        LiveSession session = liveSessionRepository.findById(sessionId).orElse(null);
        if (session == null || !session.isLive()) {
            return 0;
        }
        return closeSession(sessionId, SessionEndReason.NORMAL, session.getEndsAt());
    }

    /**
     * 잔여 인원이 최소 미만이면 조기 종료한다(D12). PAUSED를 세는 것은 Pause가 재실로
     * 인정되기 때문이다(★D1) — 빼면 화장실 간 사람 때문에 세션이 끝난다.
     *
     * <p>사람이 PRESENT에서 빠지는 경로마다 이 검사가 뒤따라야 한다. 퇴장은
     * {@link SessionExitService}가, 퇴출은 {@link EvictionService}의 호출자가 부른다.
     */
    public void closeIfUnderMinimum(Long sessionId, LocalDateTime now) {
        // 세는 것보다 잠그는 것이 먼저다. 이 순서를 뒤집으면 동시에 빠지는 사람들이 서로의
        // 미커밋 상태를 "남아 있는 사람"으로 세어 전원이 종료를 건너뛴다(LOCK_ORDER 주석).
        LiveSession session = lockSession(sessionId);
        if (session == null || !session.isLive()) {
            return;
        }
        if (presentParticipantIds(sessionId).size() >= minParticipants) {
            return;
        }
        closeSession(sessionId, SessionEndReason.EARLY_UNDER_MIN, now);
    }

    /**
     * 종료 루틴 본체. 이미 끝난 세션에는 아무것도 하지 않는다 — 웹훅은 중복 수신되고,
     * 조기 종료가 먼저 닿았을 수도 있다.
     *
     * @param endedAt 정시 종료는 {@code ends_at}, 조기 종료는 실제로 인원이 미달한 시각
     * @return 실제로 닫았으면 1, 이미 닫혀 있었으면 0
     */
    public int closeSession(Long sessionId, SessionEndReason reason, LocalDateTime endedAt) {
        LiveSession session = lockSession(sessionId);
        if (session == null || !session.isLive()) {
            return 0;
        }
        if (reason == SessionEndReason.NORMAL) {
            session.endNormally(endedAt);
        } else {
            session.endUnderMinimum(endedAt);
        }
        // 지급이 시작되면 여기서 읽은 세션은 준영속이 된다. 뒤에 쓸 값은 지금 꺼내 둔다.
        int targetMinutes = session.getTargetMinutes();
        // 끝난 세션의 유예 창은 판정할 이유가 없다. 두면 스위퍼가 이미 끝난 세션을 집어 든다.
        graceRegistry.discardSession(sessionId);

        settleUnresolved(sessionId, endedAt);

        List<Long> completerIds = presentParticipantIds(sessionId);
        for (Long participantId : completerIds) {
            settleCompletion(participantId, sessionId, targetMinutes, endedAt, false);
        }
        log.info("세션 종료 처리: session={}, reason={}, 완주 {}명",
                sessionId, reason, completerIds.size());
        return 1;
    }

    /**
     * 이미 끝난 세션의 미지급 완주자를 흡수 지급한다(B1). 대상 선별은 배치가 한다.
     *
     * <p>종료 경로가 지급까지 함께 하도록 통일된 뒤로 이 자리는 안전망이다 — 종료 트랜잭션이
     * 지급을 남기지 못하고 끊긴 경우에만 대상이 생긴다. 안전망을 지우지 않는 이유는 그 경우에
     * 완주자가 영구 미지급으로 남기 때문이다.
     */
    public int awardCompletion(Long participantId) {
        SessionParticipant participant =
                sessionParticipantRepository.findById(participantId).orElse(null);
        if (participant == null || !participant.isCompleted() || participant.getPointAwarded() > 0) {
            return 0;
        }
        // 지급은 참가자·회원 행을 고친다. 세션 행이 첫 자리다(LOCK_ORDER).
        LiveSession session = lockSession(participant.getSessionId());
        if (session == null) {
            throw new IllegalStateException("참가자의 세션이 없다: participant=" + participantId);
        }
        settleCompletion(participantId, session.getId(), session.getTargetMinutes(),
                session.getEndedAt(), false);
        return 1;
    }

    /**
     * 이의 인용(AD-6)의 완주 소급. 퇴출이 없었다면 세션 종료 시각까지 남아 있었을 사람이므로
     * ★D1 기준으로 완주다 — 지급·완주 표시·완주일·목표 검사가 {@link #closeSession}과
     * 같은 자리를 지난다.
     *
     * <p><b>지급 경로를 따로 만들지 않는 이유는 멱등키 때문이다.</b> 완주 지급의 근거는
     * {@code (memberId, SESSION_COMPLETE, SESSION_PARTICIPANT, participantId)}이고, B1이
     * 나중에 같은 참가자를 흡수 지급 대상으로 집어 들어도 이 4튜플이 겹쳐 두 번 지급되지
     * 않는다. 여기서 다른 ref로 지급하면 그 방어가 사라진다.
     *
     * <p>참가자 상태는 {@code EVICTED}로 남긴다(명세 AD-6). 취소된 사실은 상태가 아니라
     * {@code eviction.revoked_at}이 표현하고, 상태를 되돌리면 그 세션에 퇴출이 있었다는
     * 감사 기록이 참가자 행에서 사라진다.
     *
     * @return 실제로 완주를 세웠으면 true. 이미 완주였거나 세션이 아직 끝나지 않았으면 false
     */
    public boolean restoreCompletion(Long participantId) {
        SessionParticipant participant =
                sessionParticipantRepository.findById(participantId).orElse(null);
        if (participant == null || participant.isCompleted()) {
            return false;
        }
        LiveSession session = lockSession(participant.getSessionId());
        if (session == null) {
            throw new IllegalStateException("참가자의 세션이 없다: participant=" + participantId);
        }
        // 아직 진행 중인 세션은 완주 여부가 정해지지 않았다. 지금 완주로 찍으면 남은 시간을
        // 채우지 않은 사람이 완주자가 된다 — 판정은 종료 시각에 종료 루틴이 한다.
        if (session.getEndedAt() == null) {
            return false;
        }
        settleCompletion(participantId, session.getId(), session.getTargetMinutes(),
                session.getEndedAt(), true);
        return true;
    }

    /**
     * 퇴출 패널티 소급 차감(B1). 차감의 제자리는 퇴출 트랜잭션이고
     * ({@link EvictionService#evictIfWarningLimitReached}), 여기는 그 트랜잭션이 원장을
     * 남기지 못한 채 끝난 경우에만 걸리는 안전망이다.
     *
     * <p>이중 차감은 원장 멱등키가 막는다 — 두 자리가 같은
     * {@code (memberId, EVICTION_PENALTY, EVICTION, evictionId)}로 쓰기 때문에, 이미 있으면
     * {@link PointService#award}가 조용히 false를 돌려준다.
     */
    public int settleEvictionPenalty(Long evictionId) {
        Eviction eviction = evictionRepository.findById(evictionId).orElse(null);
        if (eviction == null || eviction.isRevoked()) {
            return 0;
        }
        boolean settled = pointService.award(eviction.getMemberId(), -eviction.getPointPenalty(),
                PointReason.EVICTION_PENALTY, eviction.getId(), eviction.getCreatedAt());
        return settled ? 1 : 0;
    }

    /**
     * 완주 확정 — 지급·완주 금액 기록·완주일·목표 검사가 한 트랜잭션이다. 중간에 끊기면
     * 원장만 있고 Streak가 없는 회원이 남는다.
     *
     * <p><b>참가자 행은 엔티티가 아니라 id로 받아 지급 뒤에 읽는다.</b>
     * {@link PointService#award}는 잔액 캐시를 벌크 UPDATE로 갱신하면서 영속성 컨텍스트를
     * 비우므로(그래야 원장의 {@code balance_after}가 증감 후 값이 된다), 지급 이전에 잡아 둔
     * 참가자 참조는 그 뒤로 준영속이다. 그 객체에 {@code complete()}를 불러도 flush되지 않아
     * <b>원장과 Streak만 남고 완주 표시가 통째로 사라진다</b> — 8단계에서 실제로 그렇게 됐고,
     * 완주 표시가 없으면 흡수 지급 대상 조회에도 잡히지 않아 영구 미표시가 된다.
     *
     * <p>지급을 먼저 하고 표시를 나중에 하는 순서는 유지한다. 순서를 뒤집어도 지금은
     * {@code flushAutomatically} 덕에 동작하지만, 그때는 정합성이 남의 도메인 리포지터리
     * 애너테이션에 매달린다. 지급 뒤에 다시 읽은 행에 쓰는 방식은 그 설정과 무관하게 남는다.
     *
     * <p>금액은 실제 재실 시간이 아니라 {@code target_minutes} 기준이다(D15 보충).
     * 조기 종료로 30분 만에 끝난 60분 세션도 남아 있던 사람에게는 +100이다 — 세션이 일찍
     * 끝난 것은 남은 사람의 책임이 아니다.
     *
     * @param backfilled 이미 지나간 날의 완주를 뒤늦게 세우는가(AD-6 인용). Streak 캐시를
     *                   증분이 아니라 재계산으로 갱신해야 하는 유일한 차이다
     */
    private void settleCompletion(Long participantId, Long sessionId, int targetMinutes,
                                  LocalDateTime endedAt, boolean backfilled) {
        Long memberId = loadParticipant(participantId).getMemberId();
        int amount = completePointPerHour * targetMinutes / MINUTES_PER_HOUR;
        pointService.award(memberId, amount, PointReason.SESSION_COMPLETE, participantId, endedAt);
        loadParticipant(participantId).complete(amount);
        LocalDate completedOn = endedAt.toLocalDate();
        if (backfilled) {
            streakService.recordBackfilledCompletion(memberId, completedOn, sessionId, endedAt);
        } else {
            streakService.recordCompletion(memberId, completedOn, sessionId, endedAt);
        }
    }

    /**
     * 세션 행 잠금. {@value #LOCK_ORDER}의 첫 자리이므로 이 호출보다 앞에서 참가자·회원 행을
     * 잡는 경로를 만들면 안 된다.
     *
     * <p>없는 세션에 {@code null}을 돌려주는 것은 호출부가 전부 "이미 끝났거나 사라진 세션은
     * 할 일이 없다"로 답하기 때문이다 — 여기서 예외를 던지면 웹훅·배치가 남의 사정으로 죽는다.
     */
    private LiveSession lockSession(Long sessionId) {
        return liveSessionRepository.findByIdForUpdate(sessionId).orElse(null);
    }

    private SessionParticipant loadParticipant(Long participantId) {
        return sessionParticipantRepository.findById(participantId)
                .orElseThrow(() -> new IllegalStateException(
                        "지급 대상 참가자가 사라졌다: " + participantId));
    }

    /** 지금 세션에 남아 있는 참가자. 조기 종료 판정과 완주 판정이 같은 질문을 쓴다. */
    private List<Long> presentParticipantIds(Long sessionId) {
        return sessionParticipantRepository.findBySessionIdAndStatusIn(sessionId, PRESENT).stream()
                .map(SessionParticipant::getId)
                .toList();
    }

    /**
     * 세션이 끝나는 순간 남는 두 종류의 미결 상태를 세션 종료 시각 기준으로 정산한다 —
     * 복귀하지 않은 PAUSED(SS-6 미호출)와 END가 오지 않은 자리비움(SS-4 START만 도착).
     *
     * <p><b>둘 다 판정 주체가 사용자 요청이라, 요청이 영영 오지 않으면 경고도 영영 붙지
     * 않는다.</b> 10분 넘게 자리를 비우고 그대로 이탈한 사람이 아무 대가 없이 끝나는 셈이라
     * 판정만 진행 중과 같은 임계로 여기서 마저 한다.
     *
     * <p>한 참가자에게 두 규칙을 함께 적용하지 않는다. PAUSED는 자리를 비우라고 만든
     * 상태(SS-5)라 진행 중 판정도 자리비움 경고를 주지 않는데, 종료 정산에서만 둘 다
     * 걸면 화장실 모드를 쓴 사람이 경고를 두 배로 받는다.
     *
     * <p>목록이 아니라 id로 도는 것은 퇴출 때문이다. 3회째 경고가 붙은 참가자의 차감이
     * 영속성 컨텍스트를 비우므로, 미리 읽어 둔 뒷사람들이 준영속이 되어 그들의 경고가
     * 사라진다.
     */
    private void settleUnresolved(Long sessionId, LocalDateTime endedAt) {
        for (Long participantId : presentParticipantIds(sessionId)) {
            SessionParticipant participant = loadParticipant(participantId);
            if (participant.getStatus() == ParticipantStatus.PAUSED) {
                settleUnreturnedPause(sessionId, participant, endedAt);
            } else {
                settleUnclosedAbsence(sessionId, participant, endedAt);
            }
        }
    }

    /** 미복귀 PAUSED. 판정식은 SS-6 초과 복귀와 같다(D9) — 돌아오지 않은 쪽이 더 관대하면 안 된다. */
    private void settleUnreturnedPause(Long sessionId, SessionParticipant participant,
                                       LocalDateTime endedAt) {
        long elapsedSeconds =
                Duration.between(participant.getPauseStartedAt(), endedAt).getSeconds();
        if (elapsedSeconds <= pauseLimitSeconds) {
            return;
        }
        log.info("미복귀 Pause 정산: session={}, member={}, 경과 {}초",
                sessionId, participant.getMemberId(), elapsedSeconds);
        warn(sessionId, participant, null, endedAt);
    }

    /** 짝 없는 자리비움 START. 세션 종료 시각을 END로 간주해 SS-4와 같은 임계로 판정한다. */
    private void settleUnclosedAbsence(Long sessionId, SessionParticipant participant,
                                       LocalDateTime endedAt) {
        AbsenceEvent last = absenceEventRepository
                .findFirstBySessionIdAndMemberIdOrderByIdDesc(sessionId, participant.getMemberId())
                .orElse(null);
        if (last == null || last.getType() != AbsenceEventType.START) {
            return;
        }
        long absentSeconds = Duration.between(last.getOccurredAt(), endedAt).getSeconds();
        if (absentSeconds <= absenceThresholdSeconds) {
            return;
        }
        log.info("미종료 자리비움 정산: session={}, member={}, 지속 {}초",
                sessionId, participant.getMemberId(), absentSeconds);
        warn(sessionId, participant, last.getId(), endedAt);
    }

    /**
     * 경고 부여와 퇴출 검사. 3회째면 그 자리에서 퇴출되고, 완주 판정이 그 결과를 본다 —
     * {@link #closeSession}이 정산을 끝낸 뒤에 남은 사람을 세는 이유가 이것이다.
     *
     * <p>다른 경고 경로와 달리 퇴출 뒤에 조기 종료 검사를 잇지 않는다. 이 메서드는 이미
     * ENDED로 표시한 세션의 정산 중에만 불리므로 다시 닫을 세션이 없다.
     */
    private void warn(Long sessionId, SessionParticipant participant, Long absenceEventId,
                      LocalDateTime at) {
        int seq = participant.addWarning();
        Long memberId = participant.getMemberId();
        warningRepository.save(absenceEventId == null
                ? Warning.fromPauseOverrun(sessionId, memberId, seq, at)
                : Warning.fromUnclosedAbsence(sessionId, memberId, seq, absenceEventId, at));
        evictionService.evictIfWarningLimitReached(sessionId, participant, at);
    }
}
