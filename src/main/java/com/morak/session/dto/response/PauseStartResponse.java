package com.morak.session.dto.response;

import com.morak.session.type.ParticipantStatus;
import java.time.LocalDateTime;

/**
 * SS-5 화장실 모드 시작. 남은 시간을 클라이언트가 계산하도록 마감 시각을 함께 내린다 —
 * 상한값만 주면 단말 시계가 어긋난 만큼 카운트다운이 틀어진다.
 */
public record PauseStartResponse(ParticipantStatus status, LocalDateTime pausedAt,
                                 int pauseLimitSeconds, LocalDateTime resumeDueAt) {

    public static PauseStartResponse of(LocalDateTime pausedAt, int pauseLimitSeconds) {
        return new PauseStartResponse(ParticipantStatus.PAUSED, pausedAt, pauseLimitSeconds,
                pausedAt.plusSeconds(pauseLimitSeconds));
    }
}
