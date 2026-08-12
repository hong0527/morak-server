package com.morak.point.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * PY-2 승인 확인 요청. openapi.yaml ChargeConfirmRequest 스키마와 1:1 대응.
 *
 * <p>세 값 모두 클라이언트가 PG 결제창에서 받아 그대로 전달하는 값이고, <b>서버는 그중 어느
 * 것도 근거로 삼지 않는다</b>. {@code pgOrderId}·{@code amountKrw}는 충전 건과 맞는지 대조하는
 * 용도이고(어긋나면 {@code PAYMENT_AMOUNT_MISMATCH}), 실제 승인 여부와 금액은 PG에 다시 묻는다.
 *
 * <p>64자는 {@code point_charge}의 컬럼 길이다. 넘치면 대조 단계에서 잘린 값끼리 비교하게
 * 되므로 400으로 먼저 거른다.
 */
public record ChargeConfirmRequest(
        @NotBlank @Size(max = 64) String pgOrderId,
        @NotBlank @Size(max = 64) String pgTid,
        @NotNull Integer amountKrw) {
}
