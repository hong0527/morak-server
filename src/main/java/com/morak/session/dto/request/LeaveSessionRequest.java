package com.morak.session.dto.request;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.morak.common.error.BusinessException;
import com.morak.common.error.ErrorCode;
import com.morak.session.type.LeftReason;
import java.util.EnumSet;
import java.util.Set;

/**
 * SS-7 자율 퇴장 사유.
 *
 * <p>{@code LeftReason}에는 서버 전용 값({@code WITHDRAWAL}·{@code SANCTION})이 함께 있어
 * 그대로 받으면 클라이언트가 "제재로 나감"을 스스로 기록할 수 있다. 요청으로 받을 수 있는
 * 값을 여기서 한정해 400으로 끊는다.
 */
public record LeaveSessionRequest(LeftReason reason) {

    private static final Set<LeftReason> REQUESTABLE = EnumSet.of(
            LeftReason.PERSONAL, LeftReason.DEVICE_ISSUE, LeftReason.UNPLEASANT, LeftReason.ETC);

    // 필드가 하나뿐인 record는 Jackson이 JSON 전체를 그 값으로 취급하므로 프로퍼티 방식으로 고정한다
    @JsonCreator
    public LeaveSessionRequest {}

    /**
     * 본문 자체가 없는 요청({@code DELETE}는 본문을 생략하기 쉽다)도 사유 누락과 같은
     * 취급이라 정적 메서드로 둔다 — 컨트롤러가 null 검사를 따로 하지 않게 한다.
     */
    public static LeftReason requireRequestableReason(LeaveSessionRequest request) {
        if (request == null || request.reason() == null) {
            throw new BusinessException(ErrorCode.REASON_REQUIRED);
        }
        if (!REQUESTABLE.contains(request.reason())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return request.reason();
    }
}
