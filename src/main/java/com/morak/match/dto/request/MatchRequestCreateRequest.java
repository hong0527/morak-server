package com.morak.match.dto.request;

import com.fasterxml.jackson.annotation.JsonCreator;
import jakarta.validation.constraints.NotNull;

/** MT-1 요청. 허용값 {@code {60, 120, 180, 240}}(D8)은 정책값이라 서비스가 설정에서 읽어 검사한다. */
public record MatchRequestCreateRequest(@NotNull Integer targetMinutes) {

    // 필드가 하나뿐인 record는 Jackson이 JSON 전체를 그 값으로 취급하므로 프로퍼티 방식으로 고정한다
    @JsonCreator
    public MatchRequestCreateRequest {}
}
