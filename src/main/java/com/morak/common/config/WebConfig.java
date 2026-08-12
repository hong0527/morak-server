package com.morak.common.config;

import com.morak.common.security.AuthInterceptor;
import com.morak.common.security.LoginMemberArgumentResolver;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;
    private final LoginMemberArgumentResolver loginMemberArgumentResolver;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 제외 목록은 §0-2 ①의 JWT 면제 경로. 웹훅 2경로(POST /api/webhooks/livekit,
        // POST /api/webhooks/payment)는 메서드 구분이 필요해 AuthInterceptor의 예외 표에서
        // 처리하고, 각 컨트롤러가 서명을 검증한다.
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/auth/**", "/api/dev/**", "/h2-console/**", "/error");
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(loginMemberArgumentResolver);
    }
}
