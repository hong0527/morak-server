package com.morak.session.dto.request;

import com.morak.session.type.AbsenceEventType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.OffsetDateTime;

/**
 * SS-4 자리비움 보고. 대상은 언제나 요청자 본인이라 {@code memberId}를 두지 않는다 —
 * 두는 순간 남을 신고하는 구조가 되고 ★D4가 무너진다.
 *
 * <p>{@code occurredAt}을 {@code OffsetDateTime}으로 받는 이유는 명세 §0-1의 요청 예시가
 * 오프셋을 포함하기 때문이다. 단말 타임존이 서버와 다를 수 있으므로 서버 타임존으로
 * 환산한 뒤 저장한다.
 */
public record AbsenceEventRequest(@NotNull AbsenceEventType type,
                                  @NotNull @PositiveOrZero Long clientSeq,
                                  @NotNull OffsetDateTime occurredAt) {
}
