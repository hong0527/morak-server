package com.morak.session.dto.response;

/**
 * SS-2 접속 토큰. {@code canPublishAudio}는 항상 false다 — 마이크 차단을 클라이언트 UI가
 * 아니라 토큰 grant로 강제하기 때문이다(D23). 클라이언트는 이 값을 보고 마이크 버튼을
 * 비활성으로 그린다.
 */
public record LivekitTokenResponse(
        String url,
        String roomName,
        String token,
        String identity,
        boolean canPublishAudio,
        int expiresInSeconds) {
}
