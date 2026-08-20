package com.morak.auth.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.morak.common.error.BusinessException;
import com.morak.common.error.ErrorCode;
import com.morak.member.type.SocialProvider;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * provider 라우팅과 "없으면 거절" 기본값을 본다.
 *
 * <p>위임처가 프로필·키에 따라 빠질 수 있는 구조라, 빠진 조합마다 통과가 아니라 401이
 * 되는지가 보안의 핵심이다 — 폴백이 통과면 인증 없는 로그인이 열린다.
 */
@DisplayName("소셜 클라이언트 라우팅")
class RoutingSocialClientTest {

    private final DevSocialClient dev = new DevSocialClient();
    /** 호출되지 않는 자리 채움. 호출되면 실서버로 나가기 전에 목 없는 RestClient라 실패한다. */
    private final KakaoSocialClient kakao =
            new KakaoSocialClient(RestClient.builder().build(), "k", "", "https://r");

    @Test
    @DisplayName("DEV는 개발용 스텁으로 위임된다")
    void dev_위임() {
        // 이 테스트가 죽으면: 심사 계정의 코드 로그인이 막힌다.
        RoutingSocialClient routing = new RoutingSocialClient(Optional.empty(), Optional.of(dev));

        SocialUser user = routing.fetch(SocialProvider.DEV, "reviewer-1");

        assertThat(user.providerUserId()).isEqualTo("reviewer-1");
    }

    @Test
    @DisplayName("카카오 키가 없으면 KAKAO는 401로 거절된다")
    void 카카오_미설정_거절() {
        // 이 테스트가 죽으면: 키 없는 환경에서 KAKAO 로그인이 500으로 새거나, 더 나쁘게는
        // 다른 구현으로 흘러 인가 코드가 그대로 계정이 된다.
        RoutingSocialClient routing = new RoutingSocialClient(Optional.empty(), Optional.of(dev));

        assertThatThrownBy(() -> routing.fetch(SocialProvider.KAKAO, "real-code"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_SOCIAL_TOKEN);
    }

    @Test
    @DisplayName("운영처럼 DEV 스텁이 없으면 DEV는 401로 거절된다")
    void dev_부재_거절() {
        RoutingSocialClient routing = new RoutingSocialClient(Optional.of(kakao), Optional.empty());

        assertThatThrownBy(() -> routing.fetch(SocialProvider.DEV, "any"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_SOCIAL_TOKEN);
    }

    @Test
    @DisplayName("구현 없는 provider는 무엇이 있어도 401이다")
    void 미구현_provider_거절() {
        RoutingSocialClient routing =
                new RoutingSocialClient(Optional.of(kakao), Optional.of(dev));

        assertThatThrownBy(() -> routing.fetch(SocialProvider.NAVER, "any"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_SOCIAL_TOKEN);
    }
}
