package com.morak.common.error;

import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusiness(BusinessException e) {
        return ResponseEntity.status(e.getErrorCode().getStatus())
                .body(ErrorResponse.of(e.getErrorCode(), e.getDetails()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
        Map<String, Object> details = new HashMap<>();
        e.getBindingResult().getFieldErrors()
                .forEach(error -> details.put(error.getField(), error.getDefaultMessage()));
        return ResponseEntity.status(ErrorCode.VALIDATION_FAILED.getStatus())
                .body(ErrorResponse.of(ErrorCode.VALIDATION_FAILED, details));
    }

    /**
     * 경로·쿼리 파라미터의 타입이 맞지 않는 경우(enum에 없는 {@code status}, 숫자가 아닌
     * 세션 번호 등). 500으로 흘려보내면 클라이언트는 서버 장애로 읽지만 실제로는 요청이
     * 잘못된 것이라 400이 맞다.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        Map<String, Object> details = new HashMap<>();
        details.put(e.getName(), "값의 형식이 올바르지 않습니다.");
        return ResponseEntity.status(ErrorCode.VALIDATION_FAILED.getStatus())
                .body(ErrorResponse.of(ErrorCode.VALIDATION_FAILED, details));
    }

    /**
     * 본문을 읽을 수 없는 요청(enum에 없는 값, 형식이 깨진 시각 문자열, JSON 문법 오류).
     * 잘못 보낸 쪽은 클라이언트인데 500으로 흘려보내면 서버 장애로 읽고 재시도한다.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableBody(HttpMessageNotReadableException e) {
        Map<String, Object> details = new HashMap<>();
        details.put("body", "요청 본문의 형식이 올바르지 않습니다.");
        return ResponseEntity.status(ErrorCode.VALIDATION_FAILED.getStatus())
                .body(ErrorResponse.of(ErrorCode.VALIDATION_FAILED, details));
    }

    /**
     * 매칭 잠금(FOR UPDATE) 획득 실패. H2·MySQL 모두 LOCK_TIMEOUT 초과를 이 예외 계열로
     * 번역한다. 500으로 흘려보내면 클라이언트가 다시 시도해도 되는 상황인지 알 수 없는데,
     * 이건 잠시 뒤 재시도하면 대개 성공하는 일시적 혼잡이라 503으로 구분해 내린다.
     */
    @ExceptionHandler(PessimisticLockingFailureException.class)
    public ResponseEntity<ErrorResponse> handleLockFailure(PessimisticLockingFailureException e) {
        log.warn("잠금 획득 실패", e);
        return ResponseEntity.status(ErrorCode.LOCK_ACQUISITION_FAILED.getStatus())
                .body(ErrorResponse.of(ErrorCode.LOCK_ACQUISITION_FAILED, Map.of()));
    }

    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    public ResponseEntity<ErrorResponse> handleNotFound(Exception e) {
        return ResponseEntity.status(ErrorCode.ENDPOINT_NOT_FOUND.getStatus())
                .body(ErrorResponse.of(ErrorCode.ENDPOINT_NOT_FOUND, Map.of()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        // 500은 클라이언트에 원인을 숨기므로 서버 로그가 유일한 추적 수단이다
        log.error("처리되지 않은 예외", e);
        return ResponseEntity.status(ErrorCode.INTERNAL_SERVER_ERROR.getStatus())
                .body(ErrorResponse.of(ErrorCode.INTERNAL_SERVER_ERROR, Map.of()));
    }
}
