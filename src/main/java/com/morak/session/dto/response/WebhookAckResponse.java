package com.morak.session.dto.response;

/**
 * SS-10 수신 확인. 처리 실패도 이 응답을 200으로 돌려준다 — 5xx를 내면 LiveKit이 재시도를
 * 반복해 중복 처리가 늘어난다. 실패는 서버 로그로만 남긴다.
 */
public record WebhookAckResponse(boolean received) {

    public static WebhookAckResponse ok() {
        return new WebhookAckResponse(true);
    }
}
