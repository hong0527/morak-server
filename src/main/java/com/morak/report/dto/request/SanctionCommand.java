package com.morak.report.dto.request;

import com.morak.common.error.BusinessException;
import com.morak.common.error.ErrorCode;
import com.morak.report.type.SanctionType;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 적용할 제재의 내용. AD-3(신고 처리에 딸린 제재)과 AD-4(단독 적용)가 같은 값을 받고
 * 같은 서비스 메서드로 들어간다 — 요청 본문 모양만 다르고 의미는 하나다.
 *
 * <p>기간 계산을 여기 둔 이유는 {@code type}과 {@code days}의 관계가 이 레코드의 불변식이기
 * 때문이다. PERMANENT는 끝이 없어야 하는데 호출부가 각자 계산하면 언젠가 한쪽이
 * {@code days}를 무시하지 않고 종료 시각을 만든다.
 */
public record SanctionCommand(SanctionType type, Integer days) {

    private static final int MIN_DAYS = 1;
    private static final int MAX_DAYS = 3650;

    public void validate() {
        if (type == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    Map.of("type", "제재 종류가 필요합니다."));
        }
        if (type == SanctionType.TEMP && (days == null || days < MIN_DAYS || days > MAX_DAYS)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    Map.of("days", "%d 이상 %d 이하여야 합니다.".formatted(MIN_DAYS, MAX_DAYS)));
        }
        if (type == SanctionType.PERMANENT && days != null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    Map.of("days", "영구 제재에는 기간을 보내지 않습니다."));
        }
    }

    /** PERMANENT는 null이다. 그 null이 곧 sanction.ends_at의 "끝나지 않음"이다. */
    public LocalDateTime endsAt(LocalDateTime startsAt) {
        return type == SanctionType.TEMP ? startsAt.plusDays(days) : null;
    }
}
