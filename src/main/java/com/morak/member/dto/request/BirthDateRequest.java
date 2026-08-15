package com.morak.member.dto.request;

import com.fasterxml.jackson.annotation.JsonCreator;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import java.time.LocalDate;

public record BirthDateRequest(
        // @Past: UNDER_AGE 판정은 되돌릴 수 없으므로 미래 날짜 오타가 계정을 영구히 잠그기 전에 400으로 거른다
        @NotNull @Past LocalDate birthDate) {

    // 필드가 하나뿐인 record는 Jackson이 JSON 전체를 그 값으로 취급하므로 프로퍼티 방식으로 고정한다
    @JsonCreator
    public BirthDateRequest {}
}
