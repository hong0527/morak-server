package com.morak.member.dto.response;

import com.morak.member.entity.MemberGoal;
import com.morak.member.type.GoalStatus;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** AU-7 응답이자 AU-2의 {@code goal} 필드. openapi.yaml GoalResponse 스키마와 1:1 대응. */
public record GoalResponse(
        Long goalId,
        int periodDays,
        LocalDate startedOn,
        GoalStatus status,
        LocalDateTime achievedAt) {

    public static GoalResponse from(MemberGoal goal) {
        return new GoalResponse(
                goal.getId(),
                goal.getPeriodDays(),
                goal.getStartedOn(),
                goal.getStatus(),
                goal.getAchievedAt());
    }
}
