package com.morak.member.dto.response;

import com.morak.member.entity.MemberGoal;
import com.morak.member.type.GoalStatus;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * AU-7 응답이자 AU-2의 {@code goal} 필드. openapi.yaml GoalResponse 스키마와 1:1 대응.
 *
 * <p>{@code progressDays}는 달성 판정(§0-6)의 두 조건 중 작은 쪽이다 — 연속 캐시만 내리면
 * 달성 직후 새로 건 목표가 이전 연속 때문에 "7/7 직전"으로 그려진다. 서버가 이중 지급을
 * 막으려고 시작일 이후 완주일 수를 함께 보는데, 화면이 그 값을 모르면 진행률이 거짓말을 한다.
 */
public record GoalResponse(
        Long goalId,
        int periodDays,
        LocalDate startedOn,
        int progressDays,
        GoalStatus status,
        LocalDateTime achievedAt) {

    public static GoalResponse of(MemberGoal goal, int progressDays) {
        return new GoalResponse(
                goal.getId(),
                goal.getPeriodDays(),
                goal.getStartedOn(),
                progressDays,
                goal.getStatus(),
                goal.getAchievedAt());
    }
}
