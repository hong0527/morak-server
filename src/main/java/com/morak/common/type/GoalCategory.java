package com.morak.common.type;

// 목표 분야. label은 그룹명("{label} {periodDays}일 챌린지")과 화면 표기에 사용한다.
public enum GoalCategory {
    EXERCISE("운동"),
    STUDY("공부"),
    READING("독서"),
    DIET("식단"),
    SLEEP("수면"),
    ETC("기타");

    private final String label;

    GoalCategory(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
