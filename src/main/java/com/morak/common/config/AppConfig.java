package com.morak.common.config;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AppConfig {

    /**
     * 모든 시각을 이 시계로 읽는다.
     *
     * <p>코드에서 {@code LocalDateTime.now()}를 직접 부르면 테스트에서 시각을 고정할 수 없다.
     * 챌린지 시작일·인증 마감·매칭 만료가 전부 시각 판정이라, 시계를 갈아끼울 수 있어야
     * "자정 직전 매칭" 같은 경계를 테스트할 수 있다.
     */
    @Bean
    public Clock clock(@Value("${morak.timezone}") String timezone) {
        return Clock.system(ZoneId.of(timezone));
    }
}
