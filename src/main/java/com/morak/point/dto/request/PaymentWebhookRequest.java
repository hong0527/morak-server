package com.morak.point.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * PY-3 웹훅 페이로드. openapi.yaml PaymentWebhookRequest 스키마와 1:1 대응.
 *
 * <p>{@code ignoreUnknown}을 켠 것은 PG 표준 페이로드가 우리가 쓰지 않는 필드를 잔뜩 싣고
 * 오기 때문이다. 우리 쪽 요청 DTO였다면 오히려 막아야 하지만, 이건 남이 정한 스키마라
 * 필드가 늘어날 때마다 웹훅이 통째로 죽으면 안 된다.
 *
 * <p>모든 필드가 nullable이다. 검증은 역직렬화가 아니라 처리 단계에서 한다 — 여기서
 * 400으로 튕기면 PG가 재시도를 반복한다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PaymentWebhookRequest(String eventType, Data data) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Data(String orderId, String paymentKey, String status, Integer totalAmount) {
    }
}
