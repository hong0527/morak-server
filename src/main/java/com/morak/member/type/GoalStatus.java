package com.morak.member.type;

// 활성 1건 제약은 DB가 아니라 로직으로 둔다 — UNIQUE(member_id, status)로는
// ACHIEVED가 여러 건 쌓이는 것까지 막힌다.
//
// CANCELLED는 예약값이고 v1에 전이 경로가 없다. 진행 중인 목표를 중도 변경·취소하는 API를
// 두지 않아서(현재는 의도 — open-decisions D-9) 이 상태로 갈 방법 자체가 없다.
// 지우지 않는 것은 열어 줄 때 값을 다시 만들면 이미 쌓인 행의 해석이 바뀌기 때문이다.
public enum GoalStatus {
    ACTIVE, ACHIEVED, CANCELLED
}
