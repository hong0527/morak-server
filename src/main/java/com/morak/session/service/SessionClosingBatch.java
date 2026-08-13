package com.morak.session.service;

import com.morak.common.batch.BatchGuard;
import com.morak.dev.DevBatch;
import com.morak.point.entity.PointLedger;
import com.morak.point.type.PointReason;
import com.morak.session.repository.EvictionRepository;
import com.morak.session.repository.LiveSessionRepository;
import com.morak.session.repository.SessionParticipantRepository;
import com.morak.session.type.SessionStatus;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * B1 세션 종료 배치. 대상을 고르는 일만 하고 처리는 {@link SessionClosingService}에 맡긴다 —
 * 같은 빈 안에서 부르면 프록시를 타지 않아 대상별 트랜잭션 경계가 서지 않는다.
 *
 * <p>세 갈래를 순서대로 돈다. ① 예정 시각이 지난 세션 종료 ② 이미 끝났는데 지급이 남은
 * 완주자 흡수 ③ 원장에 반영되지 않은 퇴출 패널티 소급 차감. ②③은 다른 경로가 만들어 둔
 * 미결을 거두는 자리라, 한 번의 실행에서 ①이 만든 결과까지 같은 패스로 정리된다.
 *
 * <p>재실행해도 결과가 같다. 근거는 이 클래스가 아니라 {@code uk_pl_dedup}·
 * {@code uk_streak_day}·{@code uk_warning}이다.
 */
@Component
@RequiredArgsConstructor
public class SessionClosingBatch implements DevBatch {

    private static final Logger log = LoggerFactory.getLogger(SessionClosingBatch.class);

    private final SessionClosingService closingService;
    private final LiveSessionRepository liveSessionRepository;
    private final SessionParticipantRepository sessionParticipantRepository;
    private final EvictionRepository evictionRepository;
    private final BatchGuard batchGuard;
    private final Clock clock;

    @Override
    public String name() {
        return "B1";
    }

    /** 매분 0초. 세션 종료 예정 시각은 분 단위라 이보다 촘촘할 이유가 없다. */
    @Scheduled(cron = "0 * * * * *")
    public void schedule() {
        run();
    }

    @Override
    public int run() {
        LocalDateTime now = LocalDateTime.now(clock);
        int processed = 0;
        for (Long sessionId : liveSessionRepository.findIdsToClose(SessionStatus.LIVE, now)) {
            processed += batchGuard.guarded(log, "세션 종료", sessionId,
                    () -> closingService.closeDueSession(sessionId));
        }
        for (Long participantId
                : sessionParticipantRepository.findIdsAwaitingAward(SessionStatus.ENDED)) {
            processed += batchGuard.guarded(log, "완주 흡수 지급", participantId,
                    () -> closingService.awardCompletion(participantId));
        }
        for (Long evictionId : evictionRepository.findIdsToSettle(
                PointReason.EVICTION_PENALTY, PointLedger.refTypeOf(PointReason.EVICTION_PENALTY))) {
            processed += batchGuard.guarded(log, "퇴출 패널티 소급", evictionId,
                    () -> closingService.settleEvictionPenalty(evictionId));
        }
        if (processed > 0) {
            log.info("세션 종료 배치 처리 {}건", processed);
        }
        return processed;
    }

}
