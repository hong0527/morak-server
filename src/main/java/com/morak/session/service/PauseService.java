package com.morak.session.service;

import com.morak.common.error.BusinessException;
import com.morak.common.error.ErrorCode;
import com.morak.session.dto.response.PauseResumeResponse;
import com.morak.session.dto.response.PauseStartResponse;
import com.morak.session.entity.LiveSession;
import com.morak.session.entity.SessionParticipant;
import com.morak.session.entity.Warning;
import com.morak.session.repository.LiveSessionRepository;
import com.morak.session.repository.SessionParticipantRepository;
import com.morak.session.repository.WarningRepository;
import com.morak.session.type.ParticipantStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SS-5·SS-6 화장실 모드. 세션당 1회 10분이며, <b>초과 복귀는 경고 1회다</b>(D9) — 그렇지
 * 않으면 자리비움 경고가 쌓이기 시작할 때 Pause를 켜는 것이 최선의 회피책이 된다.
 * 그 경고가 3회째면 Pause에서 곧바로 퇴출된다.
 *
 * <p>10분을 넘긴 채 복귀조차 하지 않은 경우의 정산은 5단계 세션 종료 배치(B1)가 같은
 * 규칙으로 한다. 여기에 별도 스위퍼를 두면 판정 주체가 둘이 되어 경고가 두 번 붙는다.
 */
@Service
@Transactional
public class PauseService {

    private static final Logger log = LoggerFactory.getLogger(PauseService.class);

    private static final int SECONDS_PER_MINUTE = 60;

    private final LiveSessionRepository liveSessionRepository;
    private final SessionParticipantRepository sessionParticipantRepository;
    private final WarningRepository warningRepository;
    private final EvictionService evictionService;
    private final Clock clock;
    private final int pauseLimitSeconds;

    public PauseService(LiveSessionRepository liveSessionRepository,
                        SessionParticipantRepository sessionParticipantRepository,
                        WarningRepository warningRepository,
                        EvictionService evictionService,
                        Clock clock,
                        @Value("${morak.session.pause-limit-minutes}") int pauseLimitMinutes) {
        this.liveSessionRepository = liveSessionRepository;
        this.sessionParticipantRepository = sessionParticipantRepository;
        this.warningRepository = warningRepository;
        this.evictionService = evictionService;
        this.clock = clock;
        this.pauseLimitSeconds = pauseLimitMinutes * SECONDS_PER_MINUTE;
    }

    /** SS-5. 상태 전이는 조건부 UPDATE 한 방으로 끝내고, 실패했을 때만 원인을 캐묻는다. */
    public PauseStartResponse start(Long memberId, Long sessionId) {
        LiveSession session = findLiveSession(sessionId);
        LocalDateTime now = LocalDateTime.now(clock);
        int updated = sessionParticipantRepository.startPause(session.getId(), memberId, now,
                ParticipantStatus.ACTIVE, ParticipantStatus.PAUSED);
        if (updated == 0) {
            throw startFailure(sessionId, memberId);
        }
        log.info("화장실 모드 시작: session={}, member={}", sessionId, memberId);
        return PauseStartResponse.of(now, pauseLimitSeconds);
    }

    /**
     * SS-6. 초과 복귀도 일단 복귀시킨 뒤 경고를 부여한다 — 순서를 뒤집어 퇴출이 먼저 걸리면
     * {@code resume()}이 EVICTED를 ACTIVE로 되돌린다.
     */
    public PauseResumeResponse resume(Long memberId, Long sessionId) {
        LiveSession session = findLiveSession(sessionId);
        SessionParticipant participant = sessionParticipantRepository
                .findBySessionIdAndMemberId(sessionId, memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_SESSION_PARTICIPANT));
        if (participant.getStatus() != ParticipantStatus.PAUSED) {
            throw new BusinessException(ErrorCode.PAUSE_NOT_ACTIVE);
        }
        LocalDateTime now = LocalDateTime.now(clock);
        long elapsedSeconds = Duration.between(participant.getPauseStartedAt(), now).getSeconds();
        participant.resume();
        boolean overrun = elapsedSeconds > pauseLimitSeconds;
        if (overrun) {
            int seq = participant.addWarning();
            warningRepository.save(Warning.fromPauseOverrun(sessionId, memberId, seq, now));
            log.info("Pause 제한 초과 경고: session={}, member={}, seq={}, 경과 {}초",
                    sessionId, memberId, seq, elapsedSeconds);
            evictionService.evictIfWarningLimitReached(session, participant, now);
        }
        return PauseResumeResponse.of(participant, elapsedSeconds, overrun);
    }

    private LiveSession findLiveSession(Long sessionId) {
        LiveSession session = liveSessionRepository.findById(sessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SESSION_NOT_FOUND));
        if (!session.isLive()) {
            throw new BusinessException(ErrorCode.SESSION_ENDED);
        }
        return session;
    }

    /**
     * 조건부 UPDATE가 0행이면 이유가 셋이다. 퇴출은 종점이라 먼저 답하고, 그다음이 소진,
     * 나머지(LEFT·이미 PAUSED가 아닌 상태)는 참가자가 아닌 것과 같은 응답으로 막는다.
     */
    private BusinessException startFailure(Long sessionId, Long memberId) {
        SessionParticipant participant = sessionParticipantRepository
                .findBySessionIdAndMemberId(sessionId, memberId)
                .orElse(null);
        if (participant == null) {
            return new BusinessException(ErrorCode.NOT_SESSION_PARTICIPANT);
        }
        if (participant.getStatus() == ParticipantStatus.EVICTED) {
            return new BusinessException(ErrorCode.ALREADY_EVICTED);
        }
        if (participant.isPauseUsed()) {
            return new BusinessException(ErrorCode.PAUSE_ALREADY_USED);
        }
        return new BusinessException(ErrorCode.NOT_SESSION_PARTICIPANT);
    }
}
