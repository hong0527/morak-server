package com.morak.session.dto.response;

import com.morak.session.entity.AppealCase;
import com.morak.session.entity.Eviction;
import com.morak.session.type.AppealStatus;
import java.time.LocalDateTime;

/**
 * AP-2 내 이의 목록 항목. AD-6의 인용·기각을 당사자가 확인하는 유일한 화면이다.
 *
 * <p>관리자 {@code note}는 싣지 않는다 — 사용자에게 보일 것을 전제하지 않고 쓰인 내부 판단
 * 기록이라, 결과는 {@code status}와 원복 사실({@code pointRefunded}·
 * {@code sessionCompletedRestored})로 설명한다.
 *
 * <p>원복 두 필드는 저장 컬럼이 아니라 원장·참가 행에서 파생한다. AD-6 인용이 실제로 남긴
 * 기록을 되짚는 것이라, 처리 응답과 이 목록이 다른 값을 낼 수 없다.
 */
public record MyAppealResponse(
        Long appealId,
        Long evictionId,
        Long sessionId,
        LocalDateTime evictedAt,
        int pointPenalty,
        AppealStatus status,
        String reasonText,
        LocalDateTime createdAt,
        LocalDateTime slaDueAt,
        LocalDateTime decidedAt,
        Integer pointRefunded,
        Boolean sessionCompletedRestored) {

    public static MyAppealResponse of(AppealCase appeal, Eviction eviction,
                                      Integer pointRefunded, Boolean sessionCompletedRestored) {
        return new MyAppealResponse(
                appeal.getId(),
                appeal.getEvictionId(),
                eviction.getSessionId(),
                eviction.getCreatedAt(),
                eviction.getPointPenalty(),
                appeal.getStatus(),
                appeal.getReasonText(),
                appeal.getCreatedAt(),
                appeal.getSlaDueAt(),
                appeal.getDecidedAt(),
                pointRefunded,
                sessionCompletedRestored);
    }
}
