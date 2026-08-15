package com.morak.session.dto.request;

import com.fasterxml.jackson.annotation.JsonCreator;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** SS-3 오늘 할 일. 빈 문자열·50자 초과는 400 {@code VALIDATION_FAILED}다. */
public record SessionGoalRequest(@NotBlank @Size(max = 50) String goalText) {

    // 필드가 하나뿐인 record는 Jackson이 JSON 전체를 그 값으로 취급하므로 프로퍼티 방식으로 고정한다
    @JsonCreator
    public SessionGoalRequest {}
}
