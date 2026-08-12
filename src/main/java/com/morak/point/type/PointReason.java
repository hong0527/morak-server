package com.morak.point.type;

// point_ledger의 멱등키(member_id, reason, ref_type, ref_id)를 구성하는 값이라
// 사유마다 참조 대상이 하나로 정해져 있다(db-schema의 reason별 ref 규약 표).
public enum PointReason {
    WELCOME("웰컴 포인트"),
    SESSION_COMPLETE("세션 완주"),
    GOAL_ACHIEVED("스파크 포인트(목표 달성)"),
    EVICTION_PENALTY("퇴출 패널티"),
    ORDER_SPEND("상품 구매"),
    ORDER_CANCEL("주문 취소 환급"),
    CHARGE("포인트 충전"),
    APPEAL_REFUND("이의 인용 환급");

    /**
     * PT-1이 내려주는 표시용 문자열. 원장에 저장하지 않는 이유는 문구가 바뀌면 이미 쌓인
     * 행까지 손대야 하기 때문이다 — 저장하는 것은 사유(enum)뿐이고 문구는 파생값이다.
     */
    private final String label;

    PointReason(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
