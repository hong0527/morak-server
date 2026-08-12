package com.morak.session.service;

import com.morak.session.entity.Eviction;
import com.morak.session.entity.LiveSession;
import com.morak.session.entity.SessionParticipant;
import com.morak.session.repository.EvictionRepository;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 3회 경고 퇴출의 단일 경로. 경고를 만드는 자리는 둘이지만(자리비움 판정 SS-4, Pause 초과
 * 복귀 SS-6) 퇴출 절차는 여기 하나뿐이다 — 나뉘면 한쪽만 조기 종료 검사를 빠뜨리거나
 * {@code eviction} 행 없이 상태만 EVICTED가 되는 경로가 생긴다.
 *
 * <p><b>임계 판정도 호출자가 아니라 이 클래스가 한다.</b> 호출자는 경고를 부여한 뒤 무조건
 * 물어보고, "3회인가"는 정책값을 쥔 여기서만 답한다.
 */
@Service
@Transactional
public class EvictionService {

    private static final Logger log = LoggerFactory.getLogger(EvictionService.class);

    private final EvictionRepository evictionRepository;
    private final SessionExitService sessionExitService;
    private final int evictWarningCount;
    private final int pointPenalty;

    public EvictionService(EvictionRepository evictionRepository,
                           SessionExitService sessionExitService,
                           @Value("${morak.session.evict-warning-count}") int evictWarningCount,
                           @Value("${morak.point.eviction-penalty}") int pointPenalty) {
        this.evictionRepository = evictionRepository;
        this.sessionExitService = sessionExitService;
        this.evictWarningCount = evictWarningCount;
        this.pointPenalty = pointPenalty;
    }

    /**
     * 경고 부여 직후 호출한다. 누적이 임계에 닿았으면 퇴출하고 만든 행을, 아니면 {@code null}을
     * 돌려준다.
     *
     * <p><b>포인트 차감은 여기서 하지 않는다.</b> 원장에 -300을 넣는 주체는 B1
     * ({@link SessionClosingService#settleEvictionPenalty})로 일원화했다 — 퇴출 경로가 직접
     * 차감하면 배치와 주체가 둘이 되어, 어느 쪽이 넣었는지 모르는 행이 생기고 한쪽만 고친
     * 정책이 다른 쪽에 반영되지 않는다. 이 행의 {@code point_penalty}가 그 근거를 들고 있다.
     */
    public Eviction evictIfWarningLimitReached(LiveSession session, SessionParticipant participant,
                                               LocalDateTime now) {
        if (participant.getWarningCount() < evictWarningCount) {
            return null;
        }
        Eviction eviction = evictionRepository.save(Eviction.of(
                session.getId(), participant.getMemberId(), participant.getWarningCount(),
                pointPenalty, now));
        // 상태 전이가 Pause 중에도 일어날 수 있어(D9) evict()가 pause_started_at을 함께 비운다
        participant.evict(now);
        log.info("경고 누적 퇴출: session={}, member={}, warningCount={}",
                session.getId(), participant.getMemberId(), participant.getWarningCount());
        // TODO(12단계): LiveKit RemoveParticipant 호출. 3단계의 LEFT 경로도 같은 자리를 비워 뒀다
        sessionExitService.endIfUnderMinimum(session, now);
        return eviction;
    }

    public int getPointPenalty() {
        return pointPenalty;
    }
}
