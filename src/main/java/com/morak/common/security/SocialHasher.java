package com.morak.common.security;

import com.morak.member.type.SocialProvider;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 재가입 차단용 소셜 식별자 해시.
 *
 * <p>로그인(조회)과 탈퇴 처리(등재)가 같은 값을 계산해야 하므로 계산을 한 곳에 모은다.
 * 입력 조립 방식이 한 글자라도 어긋나면 차단이 통째로 무력화된다.
 *
 * <p>순수 SHA-256은 provider + providerUserId 조합의 경우의 수가 좁아 역산할 수 있어,
 * pepper를 키로 쓰는 HMAC-SHA256을 쓴다.
 */
@Component
public class SocialHasher {

    private static final String ALGORITHM = "HmacSHA256";

    private final SecretKeySpec key;

    public SocialHasher(@Value("${morak.security.social-hash-pepper}") String pepper) {
        this.key = new SecretKeySpec(pepper.getBytes(StandardCharsets.UTF_8), ALGORITHM);
    }

    /** HMAC-SHA256(provider + providerUserId, pepper)의 소문자 16진수 64자. */
    public String hash(SocialProvider provider, String providerUserId) {
        try {
            // Mac 인스턴스는 스레드 안전하지 않아 호출마다 만든다
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            byte[] digest = mac.doFinal(
                    (provider.name() + providerUserId).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256을 사용할 수 없습니다.", e);
        }
    }
}
