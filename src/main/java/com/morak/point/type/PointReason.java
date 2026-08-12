package com.morak.point.type;

// point_ledger의 멱등키(member_id, reason, ref_type, ref_id)를 구성하는 값이라
// 사유마다 참조 대상이 하나로 정해져 있다(db-schema의 reason별 ref 규약 표).
public enum PointReason {
    WELCOME,
    SESSION_COMPLETE,
    GOAL_ACHIEVED,
    EVICTION_PENALTY,
    ORDER_SPEND,
    ORDER_CANCEL,
    CHARGE,
    APPEAL_REFUND
}
