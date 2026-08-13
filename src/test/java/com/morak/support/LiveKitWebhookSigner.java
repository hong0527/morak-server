package com.morak.support;

import com.google.protobuf.util.JsonFormat;
import io.livekit.server.AccessToken;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import livekit.LivekitWebhook.WebhookEvent;

/**
 * LiveKit이 웹훅을 보낼 때 하는 서명을 테스트에서 만든다. 서명은 본문 <b>바이트</b>의 SHA-256을
 * claim에 넣고 API 시크릿으로 서명한 JWT라, 본문을 한 글자라도 다르게 보내면 검증이 실패한다.
 *
 * <p>서비스 메서드를 직접 부르지 않고 이 경로를 쓰는 이유는 검증이 컨트롤러의 첫 줄이라는 사실
 * 자체가 확인 대상이기 때문이다. 웹훅은 JWT 게이트를 전부 건너뛰므로 서명이 유일한 신원 보장이다.
 */
public class LiveKitWebhookSigner {

    private final String apiKey;
    private final String apiSecret;

    public LiveKitWebhookSigner(String apiKey, String apiSecret) {
        this.apiKey = apiKey;
        this.apiSecret = apiSecret;
    }

    /** 프로토버프 JSON. LiveKit이 보내는 본문과 같은 형식이어야 수신 측 파서가 읽는다. */
    public String toJson(WebhookEvent event) {
        try {
            return JsonFormat.printer().print(event);
        } catch (Exception e) {
            throw new IllegalStateException("웹훅 본문을 만들지 못했다", e);
        }
    }

    public String authorization(String body) {
        AccessToken token = new AccessToken(apiKey, apiSecret);
        token.setSha256(Base64.getEncoder().encodeToString(sha256(body)));
        return token.toJwt();
    }

    private static byte[] sha256(String body) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(body.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 쓸 수 없다", e);
        }
    }
}
