package com.morak.member.dto.request;

import com.fasterxml.jackson.annotation.JsonCreator;
import jakarta.validation.constraints.NotNull;

/** AU-7 목표 기간 설정. 허용값 {@code {7, 14, 30}}은 서비스가 검사한다. */
public record GoalRequest(@NotNull Integer periodDays) {

    // 필드가 하나뿐인 record는 Jackson이 JSON 전체를 그 값으로 취급하므로 프로퍼티 방식으로 고정한다
    @JsonCreator
    public GoalRequest {}
}
