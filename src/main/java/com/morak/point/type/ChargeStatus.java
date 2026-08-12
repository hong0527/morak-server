package com.morak.point.type;

// READY는 PG 결제창을 띄운 시점이고, 포인트 적립은 APPROVED 전이에서만 일어난다.
public enum ChargeStatus {
    READY, APPROVED, FAILED
}
