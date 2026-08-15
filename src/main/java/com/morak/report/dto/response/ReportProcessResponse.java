package com.morak.report.dto.response;

import com.morak.report.entity.ReportCase;
import com.morak.report.entity.Sanction;
import com.morak.report.type.ReportStatus;
import java.time.LocalDateTime;

/** AD-3 응답. {@code sanctionId}는 SANCTIONED로 확정했을 때만 값이 있다. */
public record ReportProcessResponse(
        Long caseId,
        ReportStatus status,
        LocalDateTime processedAt,
        Long sanctionId) {

    public static ReportProcessResponse of(ReportCase reportCase, Sanction sanction,
                                           LocalDateTime processedAt) {
        return new ReportProcessResponse(reportCase.getId(), reportCase.getStatus(), processedAt,
                sanction == null ? null : sanction.getId());
    }
}
