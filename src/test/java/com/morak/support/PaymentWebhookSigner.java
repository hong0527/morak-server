package com.morak.support;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * PG가 결제 웹훅에 붙이는 서명을 테스트에서 만든다. 본문 <b>바이트</b>의 HMAC-SHA256을 소문자
 * hex로 담으므로, 본문을 한 글자라도 다르게 보내면 검증이 실패한다.
 *
 * <p>{@link LiveKitWebhookSigner}와 같은 이유로 서비스를 직접 부르지 않고 이 경로를 쓴다 —
 * 웹훅은 JWT 게이트를 전부 건너뛰므로 서명이 유일한 신원 보장이고, 그 검증이 컨트롤러의 첫
 * 줄이라는 사실 자체가 확인 대상이다.
 */
public class PaymentWebhookSigner {

    private static final String ALGORITHM = "HmacSHA256";

    private final SecretKeySpec key;

    public PaymentWebhookSigner(String secretKey) {
        this.key = new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), ALGORITHM);
    }

    public String sign(String body) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("웹훅 서명을 만들지 못했다", e);
        }
    }
}
