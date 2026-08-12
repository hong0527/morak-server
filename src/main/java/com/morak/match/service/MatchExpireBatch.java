package com.morak.match.service;

import com.morak.dev.DevBatch;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * B2 매칭 대기 만료. 10분 안에 6명이 모이지 않은 대기를 EXPIRED로 끝낸다.
 *
 * <p>조건 단위로 트랜잭션을 나누기 위해 실제 처리는 {@link MatchService#expireWaiting}에
 * 위임한다. 같은 빈 안에서 부르면 프록시를 타지 않아 조건별 트랜잭션 경계가 서지 않는다.
 *
 * <p>재실행해도 안전하다. 두 번째 실행은 조건부 UPDATE의 {@code WHERE status='WAITING'}에
 * 걸려 0행을 바꾸므로, 멱등의 근거는 이 클래스의 코드가 아니라 그 조건이다.
 */
@Component
@RequiredArgsConstructor
public class MatchExpireBatch implements DevBatch {

    private static final Logger log = LoggerFactory.getLogger(MatchExpireBatch.class);

    private final MatchService matchService;
    private final Clock clock;

    @Override
    public String name() {
        return "B2";
    }

    /** 매분 0초. 대기 만료는 10분 단위 판정이라 분 해상도면 충분하다. */
    @Scheduled(cron = "0 * * * * *")
    public void schedule() {
        run();
    }

    @Override
    public int run() {
        LocalDateTime now = LocalDateTime.now(clock);
        int expired = 0;
        for (int targetMinutes : matchService.findConditionsWithExpired(now)) {
            expired += matchService.expireWaiting(targetMinutes, now);
        }
        if (expired > 0) {
            log.info("매칭 대기 만료 {}건", expired);
        }
        return expired;
    }
}
