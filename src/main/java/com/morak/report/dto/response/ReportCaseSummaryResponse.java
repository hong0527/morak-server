package com.morak.report.dto.response;

import com.morak.report.entity.Report;
import com.morak.report.entity.ReportCase;
import com.morak.report.type.ReportReasonCode;
import com.morak.report.type.ReportSeverity;
import com.morak.report.type.ReportStatus;
import com.morak.report.type.ReportTargetType;
import java.time.LocalDateTime;
import java.util.List;

/**
 * AD-1 목록 항목.
 *
 * <p>{@code reasonCode}는 케이스의 컬럼이 아니다 — report_case에는 사유가 없고 개별 신고에만
 * 있다. 여러 사유가 한 케이스에 섞이므로 <b>케이스를 연 첫 신고의 사유</b>를 대표로 내보낸다.
 * 등급(severity)은 병합 과정에서 상향되므로 사유와 등급이 어긋나 보일 수 있는데, 그때
 * 옳은 것은 등급이다.
 *
 * <p>{@code overdue}는 저장 컬럼이 아니라 조회 시점 계산이다.
 */
public record ReportCaseSummaryResponse(
        Long caseId,
        ReportTargetType targetType,
        String targetNickname,
        ReportReasonCode reasonCode,
        ReportSeverity severity,
        ReportStatus status,
        boolean overdue,
        int reportCount,
        LocalDateTime receivedAt,
        LocalDateTime slaDueAt) {

    public static ReportCaseSummaryResponse of(ReportCase reportCase, List<Report> reports,
                                               LocalDateTime now) {
        return new ReportCaseSummaryResponse(
                reportCase.getId(),
                reportCase.getTargetType(),
                reportCase.getTargetNickname(),
                reports.isEmpty() ? null : reports.getFirst().getReasonCode(),
                reportCase.getSeverity(),
                reportCase.getStatus(),
                reportCase.isOverdue(now),
                reports.size(),
                reportCase.getReceivedAt(),
                reportCase.getSlaDueAt());
    }
}
