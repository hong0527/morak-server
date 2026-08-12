package com.morak.point.dto.response;

import com.morak.point.entity.PointCharge;
import com.morak.point.type.ChargeStatus;

/**
 * PY-1 충전 생성 응답. openapi.yaml ChargeCreateResponse 스키마와 1:1 대응.
 *
 * <p>{@code pointAmount}를 이 시점에 내려주는 것은 "얼마 결제하면 얼마가 들어오는지"를
 * 결제창을 띄우기 전에 확정해 보여주기 위해서다. 실제 적립은 PY-2·PY-3의 승인 확인
 * 이후이고, 이 응답은 아직 포인트를 늘리지 않는다.
 *
 * <p>{@code provider}는 클라이언트가 어느 PG SDK로 결제창을 띄울지 고르는 값이다.
 * {@code morak.pg.provider} 설정을 그대로 내린다.
 */
public record ChargeCreateResponse(
        Long chargeId,
        String pgOrderId,
        int amountKrw,
        int pointAmount,
        ChargeStatus status,
        String provider) {

    public static ChargeCreateResponse of(PointCharge charge, String provider) {
        return new ChargeCreateResponse(
                charge.getId(),
                charge.getPgOrderId(),
                charge.getAmountKrw(),
                charge.getPointAmount(),
                charge.getStatus(),
                provider);
    }
}
