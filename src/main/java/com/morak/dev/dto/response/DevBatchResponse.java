package com.morak.dev.dto.response;

/** DEV-4 응답. 개발 전용이라 공개 계약이 아니다 — 명세에는 트리거 동작만 정의돼 있다. */
public record DevBatchResponse(String batch, int processed) {
}
