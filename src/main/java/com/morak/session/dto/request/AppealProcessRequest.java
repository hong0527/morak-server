package com.morak.session.dto.request;

import com.morak.common.error.BusinessException;
import com.morak.common.error.ErrorCode;
import com.morak.session.type.AppealStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * AD-6 이의 처리. {@code note}는 관리자 판단 기록이고 신청자의 {@code reasonText}를
 * 덮어쓰지 않는다.
 */
public record AppealProcessRequest(
        @NotNull AppealStatus decision,
        @Size(max = 1000) String note) {

    /** PENDING은 "처리 결과"가 아니다. 이 값으로 들어오면 상태를 되돌리는 요청이 된다. */
    private static final Set<AppealStatus> DECIDABLE =
            EnumSet.of(AppealStatus.ACCEPTED, AppealStatus.REJECTED);

    public void validateShape() {
        if (!DECIDABLE.contains(decision)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    Map.of("decision", "확정 가능한 상태는 " + DECIDABLE + "입니다."));
        }
    }
}
