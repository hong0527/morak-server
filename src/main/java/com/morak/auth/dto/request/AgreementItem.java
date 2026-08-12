package com.morak.auth.dto.request;

import com.morak.member.type.AgreementType;
import jakarta.validation.constraints.NotNull;

/**
 * AU-1 요청에 함께 싣는 약관 동의 한 건.
 *
 * <p>{@code agreed}가 원시 타입이 아니라 {@code Boolean}인 이유는, 값이 빠졌을 때 false로
 * 조용히 채워지지 않고 400 {@code VALIDATION_FAILED}로 떨어지게 하기 위해서다.
 */
public record AgreementItem(
        @NotNull AgreementType type,
        @NotNull Boolean agreed
) {
}
