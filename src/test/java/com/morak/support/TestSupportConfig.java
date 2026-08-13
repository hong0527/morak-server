package com.morak.support;

import com.morak.auth.service.AuthService;
import com.morak.member.repository.MemberRepository;
import com.morak.session.repository.LiveSessionRepository;
import com.morak.session.repository.SessionParticipantRepository;
import com.morak.store.repository.ProductRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 통합 테스트 컨텍스트에만 얹는 빈. {@link com.morak.support.IntegrationTest}가 이 설정을 함께
 * 올리므로 모든 테스트가 같은 컨텍스트 하나를 쓴다.
 *
 * <p>{@code taskScheduler}를 여기서 정의하면 부트의 기본 스케줄러가 물러난다
 * ({@code @ConditionalOnMissingBean}). 배치가 테스트 도중 스스로 도는 것을 막는 유일한 방법이
 * 이것이다 — {@code @EnableScheduling}은 조건 없이 켜져 있어 속성으로 끌 수 없다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestSupportConfig {

    @Bean
    public TaskScheduler taskScheduler() {
        return new NoopTaskScheduler();
    }

    @Bean
    public LiveKitWebhookSigner liveKitWebhookSigner(
            @Value("${morak.livekit.api-key}") String apiKey,
            @Value("${morak.livekit.api-secret}") String apiSecret) {
        return new LiveKitWebhookSigner(apiKey, apiSecret);
    }

    @Bean
    public DatabaseCleaner databaseCleaner(JdbcTemplate jdbcTemplate) {
        return new DatabaseCleaner(jdbcTemplate);
    }

    @Bean
    public TestFixtures testFixtures(AuthService authService,
                                     MemberRepository memberRepository,
                                     LiveSessionRepository liveSessionRepository,
                                     SessionParticipantRepository sessionParticipantRepository,
                                     ProductRepository productRepository,
                                     PlatformTransactionManager transactionManager,
                                     JdbcTemplate jdbcTemplate) {
        return new TestFixtures(authService, memberRepository, liveSessionRepository,
                sessionParticipantRepository, productRepository, transactionManager, jdbcTemplate);
    }
}
