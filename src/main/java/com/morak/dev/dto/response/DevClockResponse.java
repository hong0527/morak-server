package com.morak.dev.dto.response;

import com.morak.dev.AdjustableClock;
import java.time.LocalDateTime;

public record DevClockResponse(LocalDateTime now, AdjustableClock.Mode mode, Long offsetMinutes) {

    public static DevClockResponse from(AdjustableClock clock) {
        return new DevClockResponse(LocalDateTime.now(clock), clock.getMode(), clock.getOffsetMinutes());
    }
}
