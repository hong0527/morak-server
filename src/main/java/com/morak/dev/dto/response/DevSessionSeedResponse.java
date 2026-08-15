package com.morak.dev.dto.response;

import java.util.List;

/** DEV-3 결과. 개발 전용이라 공개 계약이 아니고, 게이트에서 눈으로 확인할 값만 담는다. */
public record DevSessionSeedResponse(
        Long memberId,
        List<Long> sessionIds,
        int currentStreak,
        int pointBalance) {
}
