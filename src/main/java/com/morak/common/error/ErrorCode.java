package com.morak.common.error;

import org.springframework.http.HttpStatus;

// API명세서 v2.0 §6-1 에러 코드 표와 1:1 대응. 코드 추가·삭제 시 명세서를 함께 갱신한다.
// 코드 하나에 status 하나만 둔다. 같은 코드가 상황에 따라 다른 status를 가지면
// 프론트가 HTTP status가 아니라 error.code로 분기하는 구조가 깨진다.
public enum ErrorCode {

    // ── 공통 ──
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "인증이 필요합니다."),
    TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "토큰이 만료되었습니다."),
    FORBIDDEN_ROLE(HttpStatus.FORBIDDEN, "접근 권한이 없습니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "본인의 요청만 처리할 수 있습니다."),
    ENDPOINT_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 경로입니다."),
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "요청 값이 올바르지 않습니다."),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다."),

    // ── 회원·인증 ──
    INVALID_SOCIAL_TOKEN(HttpStatus.UNAUTHORIZED, "소셜 인증에 실패했습니다."),
    REJOIN_BLOCKED(HttpStatus.FORBIDDEN, "이용이 제한된 계정입니다."),
    WITHDRAWAL_PENDING(HttpStatus.FORBIDDEN, "탈퇴 처리 중에는 이용할 수 없습니다."),
    NOT_WITHDRAWING(HttpStatus.CONFLICT, "탈퇴 신청 상태가 아닙니다."),
    ALREADY_VERIFIED(HttpStatus.CONFLICT, "이미 연령 확인이 완료되었습니다."),
    AGE_NOT_VERIFIED(HttpStatus.FORBIDDEN, "연령 확인이 필요합니다."),
    MEMBER_SANCTIONED(HttpStatus.FORBIDDEN, "이용이 제한된 계정입니다."),
    UNDER_AGE_SIGNUP_BLOCKED(HttpStatus.FORBIDDEN, "만 14세 미만은 가입할 수 없습니다."),
    AGREEMENT_REQUIRED(HttpStatus.BAD_REQUEST, "필수 약관에 동의해 주세요."),
    GOAL_ALREADY_ACTIVE(HttpStatus.CONFLICT, "이미 진행 중인 목표가 있습니다."),

    // ── 매칭 ──
    DUPLICATE_MATCH_REQUEST(HttpStatus.CONFLICT, "이미 진행 중인 매칭 요청이 있습니다."),
    ALREADY_IN_ACTIVE_SESSION(HttpStatus.CONFLICT, "이미 참여 중인 세션이 있습니다."),
    NO_ACTIVE_MATCH_REQUEST(HttpStatus.NOT_FOUND, "진행 중인 매칭 요청이 없습니다."),
    ALREADY_MATCHED(HttpStatus.CONFLICT, "이미 매칭이 완료되었습니다."),
    LOCK_ACQUISITION_FAILED(HttpStatus.SERVICE_UNAVAILABLE, "요청이 몰려 처리하지 못했습니다. 잠시 후 다시 시도해 주세요."),
    REMATCH_COOLDOWN(HttpStatus.CONFLICT, "퇴출 후 일정 시간이 지나야 다시 매칭할 수 있습니다."),

    // ── 세션 ──
    SESSION_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 세션입니다."),
    NOT_SESSION_PARTICIPANT(HttpStatus.FORBIDDEN, "해당 세션의 참가자가 아닙니다."),
    SESSION_ENDED(HttpStatus.CONFLICT, "종료된 세션입니다."),
    SESSION_NOT_ENDED(HttpStatus.CONFLICT, "아직 진행 중인 세션입니다."),
    ALREADY_LEFT(HttpStatus.CONFLICT, "이미 나간 세션입니다."),
    REASON_REQUIRED(HttpStatus.BAD_REQUEST, "사유를 선택해 주세요."),
    CONSENT_REQUIRED(HttpStatus.FORBIDDEN, "캠 영상 온디바이스 분석 동의가 필요합니다."),
    DUPLICATE_ABSENCE_EVENT(HttpStatus.CONFLICT, "이미 접수된 이벤트입니다."),
    ABSENCE_RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "요청이 너무 잦습니다."),
    ALREADY_EVICTED(HttpStatus.CONFLICT, "이미 퇴출된 참가자입니다."),
    PAUSE_ALREADY_USED(HttpStatus.CONFLICT, "화장실 모드는 세션당 한 번만 쓸 수 있습니다."),
    PAUSE_NOT_ACTIVE(HttpStatus.CONFLICT, "화장실 모드 상태가 아닙니다."),
    INVALID_WEBHOOK_SIGNATURE(HttpStatus.UNAUTHORIZED, "서명이 올바르지 않습니다."),

    // ── 포인트·커머스 ──
    INSUFFICIENT_POINT(HttpStatus.CONFLICT, "포인트가 부족합니다."),
    PRODUCT_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 상품입니다."),
    OUT_OF_STOCK(HttpStatus.CONFLICT, "재고가 부족합니다."),
    DUPLICATE_ORDER(HttpStatus.CONFLICT, "이미 접수된 주문입니다."),
    ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 주문입니다."),

    // ── 결제 ──
    CHARGE_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 충전 건입니다."),
    PAYMENT_AMOUNT_MISMATCH(HttpStatus.BAD_REQUEST, "결제 금액이 일치하지 않습니다."),
    PAYMENT_NOT_APPROVED(HttpStatus.CONFLICT, "승인되지 않은 결제입니다."),

    // ── 신고·운영 ──
    DUPLICATE_REPORT(HttpStatus.CONFLICT, "이미 신고한 대상입니다."),
    TARGET_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 신고 대상입니다."),
    REPORT_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 신고입니다."),
    ALREADY_PROCESSED(HttpStatus.CONFLICT, "이미 처리된 신고입니다."),
    APPEAL_ALREADY_FILED(HttpStatus.CONFLICT, "이미 이의를 신청했습니다."),
    APPEAL_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 이의입니다.");

    private final HttpStatus status;
    private final String message;

    ErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getMessage() {
        return message;
    }
}
