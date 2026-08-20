package com.morak.auth.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.morak.common.error.BusinessException;
import com.morak.common.error.ErrorCode;
import com.morak.member.type.SocialProvider;
import java.net.http.HttpClient;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * 카카오 실구현. 인가 코드를 액세스 토큰으로 바꾸고(kauth) 사용자 정보를 읽는다(kapi).
 *
 * <p>키는 환경변수로만 온다(MORAK_KAKAO_REST_KEY 등 — application.yml의 morak.kakao 주석).
 * 키가 없으면 이 빈 자체가 뜨지 않고, {@link RoutingSocialClient}가 KAKAO 로그인을
 * 401로 거절한다. 키 발급 전에도 dev·demo 기동과 DEV 로그인은 깨지지 않아야 해서다.
 *
 * <p>카카오가 4xx로 답하면 전부 {@code INVALID_SOCIAL_TOKEN}으로 바꾼다. 만료·재사용된
 * 인가 코드(KOE320), redirect_uri 불일치 — 원인이 무엇이든 사용자에게는 "다시 로그인"
 * 하나뿐이라 코드를 나누지 않는다. 5xx와 타임아웃은 우리 쪽에서 재로그인으로 해결되는
 * 문제가 아니므로 그대로 던져 500으로 남긴다.
 */
@Component
@ConditionalOnProperty(name = "morak.kakao.rest-key")
public class KakaoSocialClient implements SocialClient {

    private static final Logger log = LoggerFactory.getLogger(KakaoSocialClient.class);

    private static final String TOKEN_URL = "https://kauth.kakao.com/oauth/token";
    private static final String ME_URL = "https://kapi.kakao.com/v2/user/me";

    /** 소셜 호출은 트랜잭션 밖이지만(AuthService.login 주석) 요청 스레드는 잡고 있어서 짧게 끊는다. */
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final RestClient restClient;
    private final String restKey;
    private final String clientSecret;
    private final String redirectUri;

    @Autowired
    public KakaoSocialClient(
            @Value("${morak.kakao.rest-key}") String restKey,
            // client_secret은 카카오 콘솔에서 "사용"으로 켠 앱만 요구하는 선택 항목이다
            @Value("${morak.kakao.client-secret:}") String clientSecret,
            @Value("${morak.kakao.redirect-uri}") String redirectUri) {
        this(defaultRestClient(), restKey, clientSecret, redirectUri);
    }

    /** 테스트가 MockRestServiceServer를 묶은 RestClient를 밀어 넣는 통로. */
    KakaoSocialClient(RestClient restClient, String restKey, String clientSecret,
                      String redirectUri) {
        this.restClient = restClient;
        this.restKey = restKey;
        this.clientSecret = clientSecret;
        this.redirectUri = redirectUri;
    }

    private static RestClient defaultRestClient() {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(TIMEOUT).build());
        factory.setReadTimeout(TIMEOUT);
        return RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public SocialUser fetch(SocialProvider provider, String authorizationCode) {
        String accessToken = exchangeToken(authorizationCode);
        KakaoMe me = fetchMe(accessToken);
        KakaoMe.Profile profile = me.properties();
        return new SocialUser(
                // 카카오 id는 숫자다. DB providerUserId는 문자열이라 여기서 한 번만 바꾼다
                String.valueOf(me.id()),
                profile == null ? null : profile.nickname(),
                profile == null ? null : profile.profileImage(),
                // 생년월일은 카카오 검수 항목(kakao_account)이라 v1은 받지 않는다. AU-3이 입력받는다
                null);
    }

    private String exchangeToken(String authorizationCode) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("client_id", restKey);
        if (StringUtils.hasText(clientSecret)) {
            form.add("client_secret", clientSecret);
        }
        // 인가 요청 때의 redirect_uri와 정확히 같아야 한다. 다르면 카카오가 KOE303으로 거절한다
        form.add("redirect_uri", redirectUri);
        form.add("code", authorizationCode);

        KakaoToken token;
        try {
            token = restClient.post()
                    .uri(TOKEN_URL)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(KakaoToken.class);
        } catch (RestClientResponseException e) {
            throw invalidTokenFrom("토큰 교환", e);
        }
        if (token == null || !StringUtils.hasText(token.accessToken())) {
            throw new BusinessException(ErrorCode.INVALID_SOCIAL_TOKEN);
        }
        return token.accessToken();
    }

    private KakaoMe fetchMe(String accessToken) {
        KakaoMe me;
        try {
            me = restClient.get()
                    .uri(ME_URL)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .retrieve()
                    .body(KakaoMe.class);
        } catch (RestClientResponseException e) {
            throw invalidTokenFrom("사용자 조회", e);
        }
        if (me == null || me.id() == null) {
            throw new BusinessException(ErrorCode.INVALID_SOCIAL_TOKEN);
        }
        return me;
    }

    private BusinessException invalidTokenFrom(String step, RestClientResponseException e) {
        if (e.getStatusCode().is4xxClientError()) {
            // 본문의 KOE 코드가 원인 판별의 전부라 남긴다. 코드·토큰은 싣지 않는다
            log.warn("카카오 {} 거절: status={} body={}",
                    step, e.getStatusCode().value(), e.getResponseBodyAsString());
            return new BusinessException(ErrorCode.INVALID_SOCIAL_TOKEN);
        }
        throw e;
    }

    /** 응답의 나머지 필드(refresh_token 등)는 안 쓴다. 모르는 필드는 Jackson 3 기본값이 무시다. */
    record KakaoToken(@JsonProperty("access_token") String accessToken) {}

    /** properties는 닉네임·프로필 동의를 다 껐으면 아예 없을 수 있다. */
    record KakaoMe(Long id, Profile properties) {
        record Profile(String nickname, @JsonProperty("profile_image") String profileImage) {}
    }
}
