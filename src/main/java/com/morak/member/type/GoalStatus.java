package com.morak.member.type;

// 활성 1건 제약은 DB가 아니라 로직으로 둔다 — UNIQUE(member_id, status)로는
// ACHIEVED가 여러 건 쌓이는 것까지 막힌다.
public enum GoalStatus {
    ACTIVE, ACHIEVED, CANCELLED
}
