package com.morak.common.error;

import org.springframework.http.HttpStatus;

// API명세서 v1.0 §6 에러 코드 표와 1:1 대응. 코드 추가·삭제 시 명세서를 함께 갱신한다.
// REPORT_NOT_READY(202)는 예외로 던지지 않고 FR-1 컨트롤러가 직접 반환한다.
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

    // ── 매칭 ──
    DUPLICATE_MATCH_REQUEST(HttpStatus.CONFLICT, "이미 진행 중인 매칭 요청이 있습니다."),
    ALREADY_IN_ACTIVE_GROUP(HttpStatus.CONFLICT, "이미 참여 중인 챌린지가 있습니다."),
    NO_ACTIVE_MATCH_REQUEST(HttpStatus.NOT_FOUND, "진행 중인 매칭 요청이 없습니다."),
    ALREADY_MATCHED(HttpStatus.CONFLICT, "이미 매칭이 완료되었습니다."),
    LOCK_ACQUISITION_FAILED(HttpStatus.SERVICE_UNAVAILABLE, "요청이 몰려 처리하지 못했습니다. 잠시 후 다시 시도해 주세요."),

    // ── 그룹 ──
    GROUP_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 챌린지입니다."),
    NOT_GROUP_MEMBER(HttpStatus.FORBIDDEN, "해당 챌린지의 참여자가 아닙니다."),
    ALREADY_LEFT(HttpStatus.CONFLICT, "이미 나간 챌린지입니다."),
    REASON_REQUIRED(HttpStatus.BAD_REQUEST, "사유를 선택해 주세요."),
    GROUP_ENDED(HttpStatus.CONFLICT, "종료된 챌린지입니다."),
    GROUP_NOT_ENDED(HttpStatus.CONFLICT, "아직 진행 중인 챌린지입니다."),

    // ── 인증(Proof) ──
    CONSENT_REQUIRED(HttpStatus.FORBIDDEN, "촬영물 처리 동의가 필요합니다."),
    OUT_OF_CHALLENGE_PERIOD(HttpStatus.BAD_REQUEST, "챌린지 기간이 아닙니다."),
    PROOF_DEADLINE_PASSED(HttpStatus.BAD_REQUEST, "오늘 인증 시간이 지났습니다."),
    DUPLICATE_DAILY_PROOF(HttpStatus.CONFLICT, "오늘 인증을 이미 제출했습니다."),
    PROOF_HIDDEN_BY_ADMIN(HttpStatus.CONFLICT, "운영자 조치가 적용된 날짜입니다."),
    FACE_DETECTED_RETRY(HttpStatus.UNPROCESSABLE_ENTITY, "얼굴이 나오지 않게 다시 촬영해 주세요."),
    FACE_RETRY_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "오늘 재촬영 횟수를 초과했습니다."),
    CONTENT_BLOCKED(HttpStatus.UNPROCESSABLE_ENTITY, "검토 중인 콘텐츠입니다."),
    AI_SCREENING_UNAVAILABLE(HttpStatus.BAD_GATEWAY, "인증 검사에 실패했습니다. 잠시 후 다시 시도해 주세요."),
    MEDIA_UPLOAD_FAILED(HttpStatus.SERVICE_UNAVAILABLE, "파일 저장에 실패했습니다. 다시 시도해 주세요."),
    INVALID_FILE(HttpStatus.BAD_REQUEST, "지원하지 않는 파일 형식이거나 크기를 초과했습니다."),
    VIEW_BLOCKED_GROUP_ENDED(HttpStatus.FORBIDDEN, "종료된 챌린지의 인증은 열람할 수 없습니다."),
    INVALID_MEDIA_TOKEN(HttpStatus.FORBIDDEN, "만료되었거나 올바르지 않은 열람 요청입니다."),
    MEDIA_DELETED(HttpStatus.GONE, "보관 기간이 지나 삭제되었습니다."),
    PROOF_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 인증입니다."),

    // ── 리포트 ──
    REPORT_NOT_READY(HttpStatus.ACCEPTED, "리포트를 준비 중입니다."),

    // ── 신고·운영 ──
    DUPLICATE_REPORT(HttpStatus.CONFLICT, "이미 신고한 대상입니다."),
    TARGET_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 신고 대상입니다."),
    REPORT_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 신고입니다."),
    ALREADY_PROCESSED(HttpStatus.CONFLICT, "이미 처리된 신고입니다."),
    NOT_REPORTED_PROOF(HttpStatus.BAD_REQUEST, "열람 조건을 충족하지 않는 인증입니다."),

    // ── 게시판 ──
    POST_REPORT_REQUIRED(HttpStatus.CONFLICT, "완주 리포트가 아직 준비되지 않았습니다."),
    DUPLICATE_POST(HttpStatus.CONFLICT, "이 챌린지는 이미 게시글을 작성했습니다."),
    POST_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 게시글입니다.");

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
