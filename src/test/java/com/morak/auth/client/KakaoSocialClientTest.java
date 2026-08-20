package com.morak.auth.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withUnauthorizedRequest;

import com.morak.common.error.BusinessException;
import com.morak.common.error.ErrorCode;
import com.morak.member.type.SocialProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * 카카오 호출 2단(토큰 교환 → 사용자 조회)의 매핑과 오류 변환을 본다.
 *
 * <p>실서버 없이 확인해야 해서 {@link MockRestServiceServer}를 RestClient에 묶는다.
 * 검증 대상은 우리가 보내는 요청의 형태(폼 파라미터·Bearer 헤더)와, 카카오 응답을
 * {@link SocialUser}로 옮기는 규칙, 그리고 카카오 4xx가 전부 401
 * {@code INVALID_SOCIAL_TOKEN}으로 바뀌는지다.
 */
@DisplayName("카카오 소셜 클라이언트")
class KakaoSocialClientTest {

    private static final String TOKEN_URL = "https://kauth.kakao.com/oauth/token";
    private static final String ME_URL = "https://kapi.kakao.com/v2/user/me";
    private static final String REDIRECT_URI = "https://morak.example/login/kakao";

    private MockRestServiceServer server;
    private KakaoSocialClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new KakaoSocialClient(builder.build(), "rest-key", "secret", REDIRECT_URI);
    }

    @Test
    @DisplayName("토큰 교환과 사용자 조회를 거쳐 SocialUser로 매핑된다")
    void 정상_흐름_매핑() {
        // 이 테스트가 죽으면: 요청 형식이 카카오 규격에서 벗어났거나 응답 파싱이 깨진 것이다.
        // 실서버에서는 전 사용자의 카카오 로그인이 한꺼번에 막힌다.
        MultiValueMap<String, String> expectedForm = new LinkedMultiValueMap<>();
        expectedForm.add("grant_type", "authorization_code");
        expectedForm.add("client_id", "rest-key");
        expectedForm.add("client_secret", "secret");
        expectedForm.add("redirect_uri", REDIRECT_URI);
        expectedForm.add("code", "auth-code");
        server.expect(requestTo(TOKEN_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().formData(expectedForm))
                .andRespond(withSuccess(
                        "{\"access_token\":\"AT-1\",\"token_type\":\"bearer\",\"expires_in\":21599}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(ME_URL))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer AT-1"))
                .andRespond(withSuccess(
                        "{\"id\":4242,\"connected_at\":\"2026-08-20T00:00:00Z\","
                                + "\"properties\":{\"nickname\":\"모락이\","
                                + "\"profile_image\":\"https://img.kakao/p.jpg\"}}",
                        MediaType.APPLICATION_JSON));

        SocialUser user = client.fetch(SocialProvider.KAKAO, "auth-code");

        assertThat(user.providerUserId()).isEqualTo("4242");
        assertThat(user.nickname()).isEqualTo("모락이");
        assertThat(user.profileImageUrl()).isEqualTo("https://img.kakao/p.jpg");
        // 생년월일은 v1에서 받지 않는다. null이어야 AU-3 입력 화면이 열린다
        assertThat(user.birthDate()).isNull();
        server.verify();
    }

    @Test
    @DisplayName("프로필 동의를 껐으면 properties가 없어도 id만으로 로그인된다")
    void properties_없는_계정() {
        // 이 테스트가 죽으면: 닉네임·사진 제공을 거부한 카카오 계정이 로그인 자체를 못 한다.
        server.expect(requestTo(TOKEN_URL))
                .andRespond(withSuccess("{\"access_token\":\"AT-1\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(ME_URL))
                .andRespond(withSuccess("{\"id\":7}", MediaType.APPLICATION_JSON));

        SocialUser user = client.fetch(SocialProvider.KAKAO, "auth-code");

        assertThat(user.providerUserId()).isEqualTo("7");
        assertThat(user.nickname()).isNull();
        assertThat(user.profileImageUrl()).isNull();
    }

    @Test
    @DisplayName("토큰 교환 400은 INVALID_SOCIAL_TOKEN이 된다")
    void 토큰_교환_거절() {
        // 이 테스트가 죽으면: 만료·재사용 인가 코드(KOE320)가 500으로 새서
        // 프론트의 "다시 로그인" 안내 대신 오류 화면이 뜬다.
        server.expect(requestTo(TOKEN_URL))
                .andRespond(withBadRequest()
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"invalid_grant\",\"error_code\":\"KOE320\"}"));

        assertThatThrownBy(() -> client.fetch(SocialProvider.KAKAO, "used-code"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_SOCIAL_TOKEN);
    }

    @Test
    @DisplayName("사용자 조회 401도 INVALID_SOCIAL_TOKEN이 된다")
    void 사용자_조회_거절() {
        server.expect(requestTo(TOKEN_URL))
                .andRespond(withSuccess("{\"access_token\":\"AT-1\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(ME_URL))
                .andRespond(withUnauthorizedRequest()
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"msg\":\"this access token does not exist\",\"code\":-401}"));

        assertThatThrownBy(() -> client.fetch(SocialProvider.KAKAO, "auth-code"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_SOCIAL_TOKEN);
    }

    @Test
    @DisplayName("access_token이 비어 있으면 INVALID_SOCIAL_TOKEN이 된다")
    void 토큰_없는_응답() {
        server.expect(requestTo(TOKEN_URL))
                .andRespond(withSuccess("{\"token_type\":\"bearer\"}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.fetch(SocialProvider.KAKAO, "auth-code"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_SOCIAL_TOKEN);
    }
}
