package com.morak.common.error;

import java.util.Map;

// 에러 공통 포맷: {"error":{"code","message","details"}} (API명세서 v0.3 §0)
public record ErrorResponse(ErrorBody error) {

    public record ErrorBody(String code, String message, Map<String, Object> details) {
    }

    public static ErrorResponse of(ErrorCode errorCode, Map<String, Object> details) {
        return new ErrorResponse(new ErrorBody(errorCode.name(), errorCode.getMessage(), details));
    }
}
