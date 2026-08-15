package com.morak.common.dto;

/**
 * 웹훅 수신 확인(openapi.yaml WebhookAck). SS-10 LiveKit과 PY-3 PG가 함께 쓴다 —
 * 명세가 스키마 하나로 정의한 응답이라 도메인마다 따로 두면 갈라진다.
 *
 * <p>처리 실패도 이 응답을 200으로 돌려준다. 5xx를 내면 상대 서버가 재시도를 반복해
 * 중복 처리가 늘어난다. non-2xx는 서명 검증 실패 하나뿐이다.
 */
public record WebhookAckResponse(boolean received) {

    public static WebhookAckResponse ok() {
        return new WebhookAckResponse(true);
    }
}
