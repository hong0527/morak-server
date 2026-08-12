package com.morak.report.dto.response;

import com.morak.report.entity.Sanction;
import com.morak.report.type.SanctionType;
import java.time.LocalDateTime;

/** AD-4 응답. PERMANENT면 {@code endsAt}이 null이고 그것이 곧 "끝나지 않음"이다. */
public record SanctionCreateResponse(
        Long sanctionId,
        Long memberId,
        SanctionType type,
        LocalDateTime startsAt,
        LocalDateTime endsAt) {

    public static SanctionCreateResponse from(Sanction sanction) {
        return new SanctionCreateResponse(sanction.getId(), sanction.getMemberId(),
                sanction.getType(), sanction.getStartsAt(), sanction.getEndsAt());
    }
}
