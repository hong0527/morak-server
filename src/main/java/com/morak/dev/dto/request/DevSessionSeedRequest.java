package com.morak.dev.dto.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.LocalDate;
import java.util.List;

/**
 * DEV-3 과거 완주 이력 시드. {@code targetMinutes}는 생략하면 60이다 — 지급액과 세션 길이가
 * 함께 움직이므로, Streak 연속만 보려는 대부분의 게이트에서 값을 고를 이유가 없다.
 *
 * <p>{@code targetMinutes}가 양수여야 하는 것은 완주 지급액이 이 값에서 나오기 때문이다.
 * 음수를 받으면 시드가 포인트를 <b>깎고</b>, 0이면 지급액 0으로 완주 표시가 되어 B1의 미지급
 * 조회({@code point_awarded = 0})가 그 참가자를 영원히 다시 집어 든다. 허용값 목록 검사는
 * 서비스가 한다 — 설정({@code morak.match.target-minutes-options})을 봐야 알 수 있다.
 */
public record DevSessionSeedRequest(
        @NotNull Long memberId,
        @NotEmpty List<LocalDate> dates,
        @Positive Integer targetMinutes) {

    private static final int DEFAULT_TARGET_MINUTES = 60;

    public int targetMinutesOrDefault() {
        return targetMinutes == null ? DEFAULT_TARGET_MINUTES : targetMinutes;
    }
}
