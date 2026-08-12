package com.morak.auth.client;

import com.morak.common.error.BusinessException;
import com.morak.common.error.ErrorCode;
import com.morak.member.type.SocialProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 개발용 소셜 클라이언트. 소셜 서버를 호출하지 않고 authorizationCode를 그대로
 * providerUserId로 쓴다. 같은 코드로 다시 로그인하면 같은 회원이 된다.
 *
 * <p>실제 카카오·구글 구현은 앱 키 발급 후 추가한다. 그때 이 클래스는 dev 프로필에만 남고
 * 운영 프로필은 실제 구현을 쓴다.
 *
 * <p>{@code @Profile("dev")}와 {@code morak.dev.enabled} 이중 스위치다. 프로필 실수 하나로
 * 운영에서 인증 우회 로그인이 열리는 사고를 막는다.
 */
@Component
@Profile("dev")
@ConditionalOnProperty(name = "morak.dev.enabled", havingValue = "true")
public class DevSocialClient implements SocialClient {

    @Override
    public SocialUser fetch(SocialProvider provider, String authorizationCode) {
        // 검증 실패 경로(401)를 개발 중에도 재현할 수 있게 빈 코드는 거부한다
        if (authorizationCode == null || authorizationCode.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_SOCIAL_TOKEN);
        }
        return new SocialUser(authorizationCode, authorizationCode, null, null);
    }
}
