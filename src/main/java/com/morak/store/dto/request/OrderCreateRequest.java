package com.morak.store.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * SR-3 주문 생성 요청. openapi.yaml OrderCreateRequest 스키마와 1:1 대응.
 *
 * <p>{@code idempotencyKey}는 클라이언트가 주문 화면 진입 시 만들어 재시도까지 같은 값으로
 * 보낸다. 네트워크 재시도와 버튼 더블클릭이 같은 의도의 요청임을 서버는 알 수 없다 —
 * 아는 쪽은 클라이언트뿐이라 키를 클라이언트가 만든다.
 *
 * <p>{@code quantity}가 {@code Integer}인 것은 값이 빠졌을 때 0으로 조용히 채워지지 않고
 * 400 {@code VALIDATION_FAILED}로 떨어지게 하기 위해서다.
 */
public record OrderCreateRequest(
        @NotNull Long productId,
        @NotNull @Min(1) Integer quantity,
        // 64자는 store_order.idempotency_key 컬럼 길이다. 넘치면 INSERT에서 잘리거나 깨지는
        // 대신 400으로 거른다.
        @NotBlank @Size(max = 64) String idempotencyKey) {
}
