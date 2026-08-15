package com.morak.common.config;

import com.morak.common.security.AuthInterceptor;
import com.morak.common.security.LoginMemberArgumentResolver;
import java.util.ArrayList;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    /** §0-2 ①의 JWT 면제 경로. 프로필과 무관하게 언제나 열려 있어야 하는 것들이다. */
    private static final List<String> ALWAYS_EXCLUDED =
            List.of("/api/auth/**", "/h2-console/**", "/error");

    private final AuthInterceptor authInterceptor;
    private final LoginMemberArgumentResolver loginMemberArgumentResolver;
    private final boolean devProfile;

    public WebConfig(AuthInterceptor authInterceptor,
                     LoginMemberArgumentResolver loginMemberArgumentResolver,
                     Environment environment) {
        this.authInterceptor = authInterceptor;
        this.loginMemberArgumentResolver = loginMemberArgumentResolver;
        this.devProfile = environment.matchesProfiles("dev");
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 웹훅 2경로(POST /api/webhooks/livekit, POST /api/webhooks/payment)는 메서드 구분이
        // 필요해 여기가 아니라 AuthInterceptor의 예외 표에서 처리하고, 각 컨트롤러가 서명을
        // 검증한다.
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns(excludedPatterns());
    }

    /**
     * {@code /api/dev/**}는 dev 프로필에서만 면제한다.
     *
     * <p>개발용 컨트롤러 자체가 {@code @Profile("dev")}와 {@code morak.dev.enabled} 이중
     * 스위치라 운영에는 그 핸들러가 없지만, <b>면제 규칙이 프로필과 무관하게 남아 있으면 그
     * 경로에 컨트롤러가 하나라도 생기는 날 인증 없이 열린다.</b> 스위치를 두 곳에 두고 한 곳만
     * 잠그는 상태를 만들지 않는 것이 여기 조건을 붙이는 이유다.
     */
    private String[] excludedPatterns() {
        List<String> patterns = new ArrayList<>(ALWAYS_EXCLUDED);
        if (devProfile) {
            patterns.add("/api/dev/**");
        }
        return patterns.toArray(String[]::new);
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(loginMemberArgumentResolver);
    }
}
