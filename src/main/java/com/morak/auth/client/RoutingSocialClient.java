package com.morak.auth.client;

import com.morak.common.error.BusinessException;
import com.morak.common.error.ErrorCode;
import com.morak.member.type.SocialProvider;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * provider별로 실제 구현에 위임하는 단일 진입점. {@link com.morak.auth.service.AuthService}는
 * 이것 하나만 주입받는다.
 *
 * <p>구현이 프로필·키 유무에 따라 있기도 없기도 해서 라우팅이 필요하다 —
 * {@link DevSocialClient}는 dev·demo 프로필 + 플래그일 때만, {@link KakaoSocialClient}는
 * 카카오 키가 주입됐을 때만 뜬다. 그래서 위임처를 {@link Optional}로 받아 없으면
 * 401 {@code INVALID_SOCIAL_TOKEN}으로 거절한다. 거절이 기본값인 이유는 종전의
 * RejectingSocialClient와 같다 — 통과시키는 폴백은 인증 없는 로그인이 된다.
 *
 * <p>빈 충돌은 {@code @Primary}로 피한다. Dev·Kakao도 {@code SocialClient} 구현이라
 * 타입 주입이 모호해질 수 있는데, 주입점은 언제나 이 컴포지트이고 나머지는 여기의
 * 생성자 의존으로만 쓰인다.
 */
@Primary
@Component
public class RoutingSocialClient implements SocialClient {

    private static final Logger log = LoggerFactory.getLogger(RoutingSocialClient.class);

    private final SocialClient kakao;
    private final SocialClient dev;

    public RoutingSocialClient(Optional<KakaoSocialClient> kakao,
                               Optional<DevSocialClient> dev) {
        this.kakao = kakao.orElse(null);
        this.dev = dev.orElse(null);
    }

    @Override
    public SocialUser fetch(SocialProvider provider, String authorizationCode) {
        SocialClient delegate = switch (provider) {
            case KAKAO -> kakao;
            case DEV -> dev;
            // NAVER·GOOGLE·APPLE은 구현 전. enum에 있는 이유는 API 계약(AU-1)이 먼저 잡혀서다
            default -> null;
        };
        if (delegate == null) {
            log.warn("소셜 로그인 구현이 없어 거절한다: provider={}", provider);
            throw new BusinessException(ErrorCode.INVALID_SOCIAL_TOKEN);
        }
        return delegate.fetch(provider, authorizationCode);
    }
}
