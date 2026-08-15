package com.morak.common.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * {@code @Scheduled}는 이 설정이 없으면 조용히 무시된다 — 메서드에 애너테이션만 붙여 두고
 * 배치가 돌지 않는 상태를 알아채기 어렵다.
 *
 * <p>배치는 B1·B2·B4·B5 넷이고 각각 도메인 안에 있다. 재접속 유예 스위퍼도 여기에 딸린다.
 * 이 설정은 스케줄러를 켜기만 한다.
 *
 * <p>{@code morak.scheduling.enabled=false}면 통째로 빠진다. <b>끄는 자리가 필요한 것은
 * 테스트 때문이다</b> — 배치가 도중에 스스로 돌면 시계를 밀고 다니는 테스트가 옆 테스트의
 * 세션을 닫아 재현되지 않는 실패를 만든다. 예전에는 그 차단을 테스트가 {@code TaskScheduler}
 * 빈을 실행하지 않는 구현으로 갈아끼워 했는데, 스케줄링이 켜져 있다는 사실은 그대로 두고
 * 실행만 막는 우회라 무엇이 왜 안 도는지가 설정이 아니라 테스트 코드에만 있었다.
 * 기본값은 켜짐이므로 운영·개발 설정에는 아무것도 적지 않아도 된다.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "morak.scheduling.enabled", havingValue = "true",
        matchIfMissing = true)
public class SchedulingConfig {
}
