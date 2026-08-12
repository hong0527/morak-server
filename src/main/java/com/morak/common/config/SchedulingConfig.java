package com.morak.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * {@code @Scheduled}는 이 설정이 없으면 조용히 무시된다 — 메서드에 애너테이션만 붙여 두고
 * 배치가 돌지 않는 상태를 알아채기 어렵다.
 *
 * <p>배치는 B1·B2·B4 셋이고 각각 도메인 안에 있다. 여기서는 스케줄러를 켜기만 한다.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
