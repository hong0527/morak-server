package com.morak.dev.dto.request;

import java.time.LocalDateTime;

// fixedAt·offsetMinutes·reset 중 정확히 하나만 받는다. 둘 이상 오면 어느 조작을
// 의도했는지 알 수 없으므로 컨트롤러가 VALIDATION_FAILED로 거부한다.
public record DevClockRequest(LocalDateTime fixedAt, Long offsetMinutes, Boolean reset) {

    public boolean hasExactlyOneCommand() {
        int given = 0;
        if (fixedAt != null) given++;
        if (offsetMinutes != null) given++;
        if (reset != null) given++;
        // reset:false는 "복귀하지 말라"는 뜻이라 조작 명령이 아니다
        return given == 1 && (reset == null || reset);
    }
}
