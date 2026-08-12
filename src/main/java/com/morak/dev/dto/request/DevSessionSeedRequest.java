package com.morak.dev.dto.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;

/**
 * DEV-3 과거 완주 이력 시드. {@code targetMinutes}는 생략하면 60이다 — 지급액과 세션 길이가
 * 함께 움직이므로, Streak 연속만 보려는 대부분의 게이트에서 값을 고를 이유가 없다.
 */
public record DevSessionSeedRequest(
        @NotNull Long memberId,
        @NotEmpty List<LocalDate> dates,
        Integer targetMinutes) {

    private static final int DEFAULT_TARGET_MINUTES = 60;

    public int targetMinutesOrDefault() {
        return targetMinutes == null ? DEFAULT_TARGET_MINUTES : targetMinutes;
    }
}
