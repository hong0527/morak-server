package com.morak.support;

import tools.jackson.databind.ObjectMapper;
import com.morak.auth.service.AuthService;
import com.morak.common.security.JwtProvider;
import com.morak.member.repository.MemberRepository;
import com.morak.session.repository.EvictionRepository;
import com.morak.session.repository.LiveSessionRepository;
import com.morak.session.repository.SessionParticipantRepository;
import com.morak.store.repository.ProductRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 통합 테스트 컨텍스트에만 얹는 빈. {@link com.morak.support.IntegrationTest}가 이 설정을 함께
 * 올리므로 모든 테스트가 같은 컨텍스트 하나를 쓴다.
 *
 * <p>배치를 멈추는 일은 여기서 하지 않는다. {@code morak.scheduling.enabled=false}가
 * {@code SchedulingConfig}를 통째로 빼고, 그 속성은 {@link IntegrationTest}가 건다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestSupportConfig {

    @Bean
    public LiveKitWebhookSigner liveKitWebhookSigner(
            @Value("${morak.livekit.api-key}") String apiKey,
            @Value("${morak.livekit.api-secret}") String apiSecret) {
        return new LiveKitWebhookSigner(apiKey, apiSecret);
    }

    @Bean
    public ApiClient apiClient(MockMvc mockMvc, ObjectMapper objectMapper) {
        return new ApiClient(mockMvc, objectMapper);
    }

    @Bean
    public PaymentWebhookSigner paymentWebhookSigner(
            @Value("${morak.pg.secret-key}") String secretKey) {
        return new PaymentWebhookSigner(secretKey);
    }

    @Bean
    public DatabaseCleaner databaseCleaner(JdbcTemplate jdbcTemplate) {
        return new DatabaseCleaner(jdbcTemplate);
    }

    @Bean
    public TestFixtures testFixtures(AuthService authService,
                                     JwtProvider jwtProvider,
                                     MemberRepository memberRepository,
                                     LiveSessionRepository liveSessionRepository,
                                     SessionParticipantRepository sessionParticipantRepository,
                                     EvictionRepository evictionRepository,
                                     ProductRepository productRepository,
                                     PlatformTransactionManager transactionManager,
                                     JdbcTemplate jdbcTemplate) {
        return new TestFixtures(authService, jwtProvider, memberRepository, liveSessionRepository,
                sessionParticipantRepository, evictionRepository, productRepository,
                transactionManager, jdbcTemplate);
    }
}
