package com.morak.point.dto.request;

import com.fasterxml.jackson.annotation.JsonCreator;
import jakarta.validation.constraints.NotNull;

/**
 * PY-1 충전 생성 요청. openapi.yaml ChargeCreateRequest 스키마와 1:1 대응.
 *
 * <p>금액 하한·상한은 여기 애너테이션이 아니라 {@code morak.pg}의 정책값으로 검사한다.
 * 팀 확정 대기 값이라 코드가 아니라 설정에서 바뀌어야 한다(API명세서 §0-5).
 *
 * <p>필드가 하나뿐인 record라 {@code @JsonCreator}가 필요하다. 없으면 Jackson이 JSON 전체를
 * {@code amountKrw} 값으로 취급해 역직렬화가 깨진다.
 */
public record ChargeCreateRequest(@NotNull Integer amountKrw) {

    @JsonCreator
    public ChargeCreateRequest {
    }
}
