package com.morak.common.type;

// 목표 달성 뱃지(D3). 저장하지 않고 member_goal.status가 ACHIEVED인지에서 파생한다.
// 값이 하나뿐인 것은 등급이 없기 때문이다 — 달성했거나 아직 아니거나 둘뿐이다.
public enum BadgeCode {
    GOAL_ACHIEVED
}
