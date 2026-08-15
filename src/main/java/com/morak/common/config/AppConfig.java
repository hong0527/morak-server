package com.morak.common.config;

import com.morak.dev.AdjustableClock;
import java.time.Clock;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
public class AppConfig {

    /**
     * 모든 시각을 이 시계로 읽는다.
     *
     * <p>코드에서 {@code LocalDateTime.now()}를 직접 부르면 테스트에서 시각을 고정할 수 없다.
     * Streak 일자 경계·세션 종료 예정·Pause 10분·매칭 만료가 전부 시각 판정이라, 시계를
     * 갈아끼울 수 있어야 "자정 직전 완주" 같은 경계를 테스트할 수 있다.
     *
     * <p>dev 프로필은 DEV-2 API로 조작 가능한 {@link AdjustableClock}을 쓰고, 그 외 프로필은
     * 시스템 시계만 쓴다. 운영에서는 조작형 시계 빈 자체가 등록되지 않으므로 시각 조작이
     * 불가능하다.
     */
    @Bean
    @Profile("!dev")
    public Clock clock(@Value("${morak.timezone}") String timezone) {
        return Clock.system(ZoneId.of(timezone));
    }

    // morak.dev.enabled와 무관하게 dev 프로필이면 이 빈이 뜬다. 조건을 걸면 dev에서
    // 스위치를 끌 때 Clock 빈이 사라져 기동이 깨진다. 조작 경로(DevClockController)만
    // 이중 스위치로 잠그면 이 시계는 조작 전까지 시스템 시계와 동일하다.
    @Bean
    @Profile("dev")
    public AdjustableClock adjustableClock(@Value("${morak.timezone}") String timezone) {
        return new AdjustableClock(ZoneId.of(timezone));
    }
}
