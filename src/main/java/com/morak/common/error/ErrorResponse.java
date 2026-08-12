package com.morak.common.error;

import java.util.Map;

// 에러 공통 포맷: {"error":{"code","message","details"}} (API명세서 v0.3 §0)
public record ErrorResponse(ErrorBody error) {

    public record ErrorBody(String code, String message, Map<String, Object> details) {
    }

    /**
     * 담을 것이 없는 {@code details}는 빈 객체가 아니라 null이다(명세 §0-1).
     *
     * <p>프론트가 {@code if (details)}로 부가 정보 유무를 가르는데, {@code {}}는 JS에서
     * truthy라 없는 정보를 그리려다 빈 화면을 만든다. 호출부마다 null을 챙기는 대신 응답을
     * 만드는 이 자리에서 한 번에 맞춘다.
     */
    public static ErrorResponse of(ErrorCode errorCode, Map<String, Object> details) {
        return new ErrorResponse(new ErrorBody(errorCode.name(), errorCode.getMessage(),
                details == null || details.isEmpty() ? null : details));
    }
}
