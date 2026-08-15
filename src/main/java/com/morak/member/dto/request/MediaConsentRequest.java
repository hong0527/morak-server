package com.morak.member.dto.request;

import com.fasterxml.jackson.annotation.JsonCreator;
import jakarta.validation.constraints.NotNull;

/**
 * AU-6 캠 영상 온디바이스 분석 동의. {@code true}만 받는다 — 미동의는 저장하지 않고
 * 400으로 거부한다(동의 철회는 v1 범위 밖).
 */
public record MediaConsentRequest(@NotNull Boolean agreed) {

    // 필드가 하나뿐인 record는 Jackson이 JSON 전체를 그 값으로 취급하므로 프로퍼티 방식으로 고정한다
    @JsonCreator
    public MediaConsentRequest {}
}
