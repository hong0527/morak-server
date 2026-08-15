package com.morak.report.dto.request;

import com.morak.common.error.BusinessException;
import com.morak.common.error.ErrorCode;
import com.morak.report.type.ReportStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * AD-3 신고 처리. 확정할 상태와 사유, 그리고 SANCTIONED일 때의 제재 내용을 함께 받는다.
 */
public record ReportProcessRequest(
        @NotNull ReportStatus status,
        @Size(max = 1000) String reviewNote,
        SanctionCommand sanction) {

    /** PENDING은 "처리 결과"가 아니다. 이 값으로 들어오면 상태를 되돌리는 요청이 된다. */
    private static final Set<ReportStatus> CLOSABLE =
            EnumSet.of(ReportStatus.RESOLVED, ReportStatus.REJECTED, ReportStatus.SANCTIONED);

    public void validateShape() {
        if (!CLOSABLE.contains(status)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    Map.of("status", "확정 가능한 상태는 " + CLOSABLE + "입니다."));
        }
        if (status == ReportStatus.SANCTIONED && sanction == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    Map.of("sanction", "제재 확정에는 제재 내용이 필요합니다."));
        }
        if (status != ReportStatus.SANCTIONED && sanction != null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    Map.of("sanction", "제재 확정이 아닐 때는 제재 내용을 보내지 않습니다."));
        }
        if (sanction != null) {
            sanction.validate();
        }
    }
}
