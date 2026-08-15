package com.morak.report.dto.response;

import com.morak.report.entity.ReportCase;
import com.morak.report.type.ReportSeverity;
import java.time.LocalDateTime;

/**
 * RP-1 응답.
 *
 * <p>{@code severity}는 이번 신고 사유의 등급이 아니라 <b>병합 후 케이스의 등급</b>이다.
 * NORMAL 사유로 합류해도 케이스가 HIGH면 HIGH가 나간다 — 클라이언트가 보는 것은 접수된
 * 신고의 처리 우선순위이지 자기 사유의 분류가 아니다.
 *
 * <p>{@code blockedMemberId}는 SESSION 신고에서 null이다. 차단할 개인이 특정되지 않는다.
 */
public record ReportCreateResponse(
        Long caseId,
        ReportSeverity severity,
        LocalDateTime receivedAt,
        Long blockedMemberId) {

    public static ReportCreateResponse of(ReportCase reportCase, LocalDateTime receivedAt,
                                          Long blockedMemberId) {
        return new ReportCreateResponse(reportCase.getId(), reportCase.getSeverity(), receivedAt,
                blockedMemberId);
    }
}
