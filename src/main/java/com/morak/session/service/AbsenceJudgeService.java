package com.morak.session.service;

import com.morak.common.error.BusinessException;
import com.morak.common.error.ErrorCode;
import com.morak.session.dto.request.AbsenceEventRequest;
import com.morak.session.dto.response.AbsenceEventResponse;
import com.morak.session.entity.AbsenceEvent;
import com.morak.session.entity.Eviction;
import com.morak.session.entity.LiveSession;
import com.morak.session.entity.SessionParticipant;
import com.morak.session.entity.Warning;
import com.morak.session.repository.AbsenceEventRepository;
import com.morak.session.repository.LiveSessionRepository;
import com.morak.session.repository.SessionParticipantRepository;
import com.morak.session.repository.WarningRepository;
import com.morak.session.type.AbsenceEventType;
import com.morak.session.type.ParticipantStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SS-4 자리비움 판정. <b>클라이언트는 자기 얼굴이 안 보이기 시작했다·다시 보인다만 말하고,
 * 60초 초과인지와 3회째인지는 서버가 계산한다</b>(★D4).
 *
 * <p>신뢰할 수 없는 입력을 다루는 방어선이 셋이고 하나만 빠져도 뚫린다.
 * <ul>
 *   <li>멱등키({@code clientSeq}) — 같은 이벤트를 재전송해 경고를 쌓지 못하게 한다
 *   <li>레이트리밋 — 짧은 간격의 START/END 폭주로 판정 경로를 두드리지 못하게 한다
 *   <li>시각 검증 — 세션 시작 이전이나 미래의 {@code occurredAt}으로 없던 구간을 만들지 못하게 한다
 * </ul>
 *
 * <p><b>미보고는 판정하지 않는다</b>(D13). 이벤트가 끊긴 것을 자리비움으로 추정하면 3단계의
 * 재접속 유예와 이중으로 걸려, 돌아온 사람이 경고를 안고 복귀한다. 연결이 끊긴 축은
 * {@link SessionExitService}가 {@code LEFT(DEVICE_ISSUE)}로만 정산한다.
 */
@Service
@Transactional
public class AbsenceJudgeService {

    private static final Logger log = LoggerFactory.getLogger(AbsenceJudgeService.class);

    /** 단말 시계가 조금 앞선 정도는 정상이다. 이 폭을 넘는 미래 시각만 조작으로 본다. */
    private static final int CLOCK_SKEW_TOLERANCE_SECONDS = 5;

    private final LiveSessionRepository liveSessionRepository;
    private final SessionParticipantRepository sessionParticipantRepository;
    private final AbsenceEventRepository absenceEventRepository;
    private final WarningRepository warningRepository;
    private final EvictionService evictionService;
    private final Clock clock;
    private final int thresholdSeconds;
    private final int minIntervalSeconds;

    public AbsenceJudgeService(LiveSessionRepository liveSessionRepository,
                               SessionParticipantRepository sessionParticipantRepository,
                               AbsenceEventRepository absenceEventRepository,
                               WarningRepository warningRepository,
                               EvictionService evictionService,
                               Clock clock,
                               @Value("${morak.session.absence-threshold-seconds}")
                               int thresholdSeconds,
                               @Value("${morak.session.absence-min-interval-seconds}")
                               int minIntervalSeconds) {
        this.liveSessionRepository = liveSessionRepository;
        this.sessionParticipantRepository = sessionParticipantRepository;
        this.absenceEventRepository = absenceEventRepository;
        this.warningRepository = warningRepository;
        this.evictionService = evictionService;
        this.clock = clock;
        this.thresholdSeconds = thresholdSeconds;
        this.minIntervalSeconds = minIntervalSeconds;
    }

    public AbsenceEventResponse report(Long memberId, Long sessionId,
                                       AbsenceEventRequest request) {
        LiveSession session = liveSessionRepository.findById(sessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SESSION_NOT_FOUND));
        if (!session.isLive()) {
            throw new BusinessException(ErrorCode.SESSION_ENDED);
        }
        SessionParticipant participant = sessionParticipantRepository
                .findBySessionIdAndMemberId(sessionId, memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_SESSION_PARTICIPANT));
        rejectIfNotJudgeable(participant);

        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime occurredAt = toServerTime(request);
        validateOccurredAt(occurredAt, session, now);

        // 순서가 곧 명세다. 재전송(409)을 레이트리밋(429)보다 먼저 답해야 재시도한 클라이언트가
        // "이미 접수됨"을 보고 조용히 끝낼 수 있다.
        if (absenceEventRepository.existsBySessionIdAndMemberIdAndClientSeq(
                sessionId, memberId, request.clientSeq())) {
            throw new BusinessException(ErrorCode.DUPLICATE_ABSENCE_EVENT);
        }
        AbsenceEvent previous = absenceEventRepository
                .findFirstBySessionIdAndMemberIdOrderByIdDesc(sessionId, memberId)
                .orElse(null);
        rejectIfTooFrequent(previous, now);

        AbsenceEvent event = save(AbsenceEvent.report(sessionId, memberId, request.type(),
                request.clientSeq(), occurredAt, now));
        if (!isAbsenceClosed(request, previous, participant)) {
            return AbsenceEventResponse.of(participant.getWarningCount(), null,
                    evictionService.getPointPenalty());
        }
        long absentSeconds = Duration.between(previous.getOccurredAt(), occurredAt).getSeconds();
        if (absentSeconds <= thresholdSeconds) {
            return AbsenceEventResponse.of(participant.getWarningCount(), null,
                    evictionService.getPointPenalty());
        }
        return warn(session, participant, event, absentSeconds, now);
    }

    /**
     * 퇴출은 종점이고(409), 이미 나간 사람은 세션 밖이다(403). 어느 쪽이든 경고를 만들지
     * 않는 것이 핵심이다 — 유예 초과 판정과 이 요청의 도착 순서는 보장되지 않아, 끊겨서
     * LEFT된 사람의 이벤트가 뒤늦게 도착하는 일이 정상적으로 일어난다.
     */
    private void rejectIfNotJudgeable(SessionParticipant participant) {
        if (participant.getStatus() == ParticipantStatus.EVICTED) {
            throw new BusinessException(ErrorCode.ALREADY_EVICTED);
        }
        if (!participant.isPresent()) {
            throw new BusinessException(ErrorCode.NOT_SESSION_PARTICIPANT);
        }
    }

    /** 단말 타임존이 서버와 다를 수 있어 같은 순간을 서버 시각으로 환산해 저장한다. */
    private LocalDateTime toServerTime(AbsenceEventRequest request) {
        return request.occurredAt().atZoneSameInstant(clock.getZone()).toLocalDateTime();
    }

    /**
     * 세션 시작 이전이나 미래의 시각은 없던 자리비움을 만들 수 있어 거부한다. 판정을
     * {@code occurredAt} 간격으로 하는 이상 이 검증이 그 값의 유일한 울타리다.
     *
     * <p>양쪽 경계에 같은 허용폭을 준다. 단말은 초 단위로 끊은 시각을 보내는데 세션 시작
     * 시각에는 밀리초가 남아 있어, 입장 직후의 정상 보고가 시작 시각보다 몇백 밀리초
     * 이르게 찍히는 일이 실제로 생긴다.
     */
    private void validateOccurredAt(LocalDateTime occurredAt, LiveSession session,
                                    LocalDateTime now) {
        if (occurredAt.isBefore(session.getStartedAt().minusSeconds(CLOCK_SKEW_TOLERANCE_SECONDS))
                || occurredAt.isAfter(now.plusSeconds(CLOCK_SKEW_TOLERANCE_SECONDS))) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }

    /** 정상 클라이언트는 검출 상태가 바뀔 때만 보내므로 초 단위로 몰아치는 요청은 위조 신호다. */
    private void rejectIfTooFrequent(AbsenceEvent previous, LocalDateTime now) {
        if (previous != null
                && previous.getReportedAt().plusSeconds(minIntervalSeconds).isAfter(now)) {
            throw new BusinessException(ErrorCode.ABSENCE_RATE_LIMITED);
        }
    }

    private AbsenceEvent save(AbsenceEvent event) {
        try {
            // 멱등 검사를 통과한 동시 요청 둘이 여기서 만난다. uk_ae 위반은 서버 잘못이 아니라
            // 재전송이므로 500이 아니라 409로 답한다.
            return absenceEventRepository.saveAndFlush(event);
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.DUPLICATE_ABSENCE_EVENT);
        }
    }

    /**
     * 자리비움 구간이 닫혔는가. 짝이 없는 END(직전이 START가 아님)는 정상 입력이고 판정하지
     * 않는다. PAUSED 구간도 판정에서 빠진다 — 화장실 모드는 자리를 비우라고 만든 기능이라
     * 여기서 경고를 주면 SS-5가 함정이 된다.
     */
    private boolean isAbsenceClosed(AbsenceEventRequest request, AbsenceEvent previous,
                                    SessionParticipant participant) {
        return request.type() == AbsenceEventType.END
                && previous != null
                && previous.getType() == AbsenceEventType.START
                && participant.getStatus() == ParticipantStatus.ACTIVE;
    }

    private AbsenceEventResponse warn(LiveSession session, SessionParticipant participant,
                                      AbsenceEvent event, long absentSeconds, LocalDateTime now) {
        int seq = participant.addWarning();
        warningRepository.save(Warning.fromAbsence(
                session.getId(), participant.getMemberId(), seq, event.getId(), now));
        log.info("자리비움 경고: session={}, member={}, seq={}, 지속 {}초",
                session.getId(), participant.getMemberId(), seq, absentSeconds);
        Eviction eviction = evictionService.evictIfWarningLimitReached(session, participant, now);
        return AbsenceEventResponse.of(participant.getWarningCount(),
                eviction == null ? null : eviction.getId(), evictionService.getPointPenalty());
    }
}
