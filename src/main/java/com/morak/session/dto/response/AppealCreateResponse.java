package com.morak.session.dto.response;

import com.morak.session.entity.AppealCase;
import com.morak.session.type.AppealStatus;
import java.time.LocalDateTime;

/**
 * AP-1 접수 결과. {@code slaDueAt}을 함께 내리는 것은 신청자가 언제까지 답을 받는지
 * 알 수 있어야 하기 때문이다(NFR-402).
 */
public record AppealCreateResponse(
        Long appealId,
        Long evictionId,
        AppealStatus status,
        String reasonText,
        LocalDateTime createdAt,
        LocalDateTime slaDueAt) {

    public static AppealCreateResponse from(AppealCase appeal) {
        return new AppealCreateResponse(
                appeal.getId(),
                appeal.getEvictionId(),
                appeal.getStatus(),
                appeal.getReasonText(),
                appeal.getCreatedAt(),
                appeal.getSlaDueAt());
    }
}
