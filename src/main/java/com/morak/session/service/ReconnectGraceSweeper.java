package com.morak.session.service;

import com.morak.session.service.ReconnectGraceRegistry.Key;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 열린 유예 창이 90초를 넘겼는지 훑는다(D13).
 *
 * <p><b>지연 실행이 아니라 훑기인 이유.</b> 끊긴 시점에 90초짜리 지연 작업을 걸면 그 작업이
 * 시스템 시각을 쥐고 있어 {@code Clock}을 갈아끼워도 앞당길 수 없다. 판정 시각을 매번
 * {@code Clock}에서 읽는 훑기라야 개발용 시계 조작(DEV-2)으로 유예 초과를 실측할 수 있고,
 * 운영에서도 판정 기준이 코드 한 곳(설정값)에만 남는다.
 *
 * <p>이것은 명세의 배치(B1·B2·B4·B5)가 아니다. 세션 하나의 수명 안에서만 의미가 있는 정리라
 * {@code DevBatch}로 등록하지 않는다.
 */
@Component
public class ReconnectGraceSweeper {

    private static final Logger log = LoggerFactory.getLogger(ReconnectGraceSweeper.class);

    private static final long SWEEP_INTERVAL_MILLIS = 1000L;

    private final ReconnectGraceRegistry graceRegistry;
    private final SessionExitService sessionExitService;
    private final Clock clock;
    private final int graceSeconds;

    public ReconnectGraceSweeper(ReconnectGraceRegistry graceRegistry,
                                 SessionExitService sessionExitService,
                                 Clock clock,
                                 @Value("${morak.session.reconnect-grace-seconds}")
                                 int graceSeconds) {
        this.graceRegistry = graceRegistry;
        this.sessionExitService = sessionExitService;
        this.clock = clock;
        this.graceSeconds = graceSeconds;
    }

    @Scheduled(fixedDelay = SWEEP_INTERVAL_MILLIS)
    public void sweep() {
        List<Key> expired = graceRegistry.expired(LocalDateTime.now(clock), graceSeconds);
        for (Key key : expired) {
            // 창을 먼저 닫는다. 처리 중 예외가 나도 같은 창을 매초 다시 집어 들지 않는다.
            graceRegistry.close(key.sessionId(), key.memberId());
            try {
                sessionExitService.leaveOnGraceExpired(key.sessionId(), key.memberId());
            } catch (Exception e) {
                log.error("유예 초과 처리 실패: session={}, member={}",
                        key.sessionId(), key.memberId(), e);
            }
        }
    }
}
