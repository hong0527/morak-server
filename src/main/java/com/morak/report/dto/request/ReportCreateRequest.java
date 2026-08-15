package com.morak.report.dto.request;

import com.morak.common.error.BusinessException;
import com.morak.common.error.ErrorCode;
import com.morak.report.type.ReportReasonCode;
import com.morak.report.type.ReportTargetType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;

/**
 * RP-1 신고 접수.
 *
 * <p>{@code sessionId}는 두 유형 모두 필수다. MEMBER 신고에서도 "어느 세션에서 겪었는가"가
 * 신고 자격의 근거이고, 관리자가 맥락을 여는 입구이기도 하다.
 */
public record ReportCreateRequest(
        @NotNull ReportTargetType targetType,
        Long targetMemberId,
        @NotNull Long sessionId,
        @NotNull ReportReasonCode reasonCode,
        @Size(max = 500) String detail) {

    /**
     * 유형별 필수·금지 필드는 애너테이션으로 표현할 수 없어 여기서 본다.
     * SESSION 신고에 대상자를 실어 보내는 것을 통과시키면 "세션 신고인데 개인이 차단되는"
     * 요청이 성립한다 — 어느 쪽 의도인지 서버가 정할 일이 아니라 400으로 끊는다.
     */
    public void validateShape() {
        if (targetType == ReportTargetType.MEMBER && targetMemberId == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    Map.of("targetMemberId", "회원 신고에는 대상 회원이 필요합니다."));
        }
        if (targetType == ReportTargetType.SESSION && targetMemberId != null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    Map.of("targetMemberId", "세션 신고에는 대상 회원을 보내지 않습니다."));
        }
    }
}
