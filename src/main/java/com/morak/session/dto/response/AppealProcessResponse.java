package com.morak.session.dto.response;

import com.morak.session.entity.AppealCase;
import com.morak.session.type.AppealStatus;
import com.morak.session.type.DecidedBy;
import java.time.LocalDateTime;

/**
 * AD-6 처리 결과. 인용은 세 가지 원복을 함께 하므로 <b>무엇이 실제로 되돌아갔는지</b>를
 * 응답에 싣는다 — 관리자가 결과 화면에서 확인하지 못하면 원장을 직접 열어야 한다.
 *
 * @param pointRefunded            역분개로 되돌린 금액. 기각이면 0이다
 * @param sessionCompletedRestored 완주가 소급됐는가
 * @param streakAfter              소급 반영 후의 연속 일수. AU-2가 보여줄 값과 같다
 */
public record AppealProcessResponse(
        Long appealId,
        AppealStatus status,
        DecidedBy decidedBy,
        LocalDateTime decidedAt,
        int pointRefunded,
        boolean sessionCompletedRestored,
        int streakAfter) {

    public static AppealProcessResponse of(AppealCase appeal, int pointRefunded,
                                           boolean sessionCompletedRestored, int streakAfter) {
        return new AppealProcessResponse(
                appeal.getId(),
                appeal.getStatus(),
                appeal.getDecidedBy(),
                appeal.getDecidedAt(),
                pointRefunded,
                sessionCompletedRestored,
                streakAfter);
    }
}
