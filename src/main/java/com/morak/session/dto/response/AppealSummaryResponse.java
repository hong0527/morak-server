package com.morak.session.dto.response;

import com.morak.session.entity.AppealCase;
import com.morak.session.entity.Eviction;
import com.morak.session.type.AppealStatus;
import java.time.LocalDateTime;

/**
 * AD-5 이의 큐 항목.
 *
 * <p>{@code sessionId}·{@code warningCount}는 이의의 컬럼이 아니라 근거 퇴출의 값이다.
 * 관리자가 큐에서 바로 보는 것이 "어느 세션에서 몇 회 경고로 나갔는가"라 목록에 끌어올린다.
 *
 * <p>{@code overdue}는 저장 컬럼이 아니라 조회 시점 계산이다(AD-1과 같은 규칙).
 */
public record AppealSummaryResponse(
        Long appealId,
        Long evictionId,
        Long memberId,
        String nickname,
        Long sessionId,
        int warningCount,
        AppealStatus status,
        boolean overdue,
        LocalDateTime createdAt,
        LocalDateTime slaDueAt) {

    public static AppealSummaryResponse of(AppealCase appeal, Eviction eviction, String nickname,
                                           LocalDateTime now) {
        return new AppealSummaryResponse(
                appeal.getId(),
                appeal.getEvictionId(),
                appeal.getMemberId(),
                nickname,
                eviction.getSessionId(),
                eviction.getWarningCount(),
                appeal.getStatus(),
                appeal.isOverdue(now),
                appeal.getCreatedAt(),
                appeal.getSlaDueAt());
    }
}
