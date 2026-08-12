package com.morak.point.dto.response;

import com.morak.point.entity.PointCharge;
import com.morak.point.type.ChargeStatus;
import java.time.LocalDateTime;

/**
 * PY-2 승인 확인 응답. openapi.yaml ChargeConfirmResponse 스키마와 1:1 대응.
 *
 * <p>이미 승인된 건을 다시 확인해도 같은 응답이 나가야 한다(멱등). 그래서 응답의 어느
 * 값도 "이번 호출에서 일어난 일"이 아니라 <b>충전 건의 현재 상태</b>에서만 만든다 —
 * "방금 적립한 포인트" 같은 필드를 두면 재호출에서 답이 갈라진다.
 */
public record ChargeConfirmResponse(
        Long chargeId,
        ChargeStatus status,
        int amountKrw,
        int pointAmount,
        int pointBalance,
        LocalDateTime approvedAt) {

    public static ChargeConfirmResponse of(PointCharge charge, int pointBalance) {
        return new ChargeConfirmResponse(
                charge.getId(),
                charge.getStatus(),
                charge.getAmountKrw(),
                charge.getPointAmount(),
                pointBalance,
                charge.getApprovedAt());
    }
}
