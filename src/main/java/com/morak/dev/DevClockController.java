package com.morak.dev;

import com.morak.common.error.BusinessException;
import com.morak.common.error.ErrorCode;
import com.morak.dev.dto.request.DevClockRequest;
import com.morak.dev.dto.response.DevClockResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * DEV-2 시각 조작 API. {@code @Profile("dev")}와 {@code morak.dev.enabled} 이중 스위치다
 * (명세 DEV-1~4 활성 조건). 프로필 실수 하나로 운영 시계가 조작되는 사고를 막는다.
 */
@RestController
@RequestMapping("/api/dev/clock")
@Profile("dev")
@ConditionalOnProperty(name = "morak.dev.enabled", havingValue = "true")
@RequiredArgsConstructor
public class DevClockController {

    private final AdjustableClock clock;

    @PostMapping
    public DevClockResponse adjust(@RequestBody DevClockRequest request) {
        if (!request.hasExactlyOneCommand()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        if (request.fixedAt() != null) {
            clock.fixAt(request.fixedAt());
        } else if (request.offsetMinutes() != null) {
            clock.setOffsetMinutes(request.offsetMinutes());
        } else {
            clock.reset();
        }
        return DevClockResponse.from(clock);
    }

    @GetMapping
    public DevClockResponse current() {
        return DevClockResponse.from(clock);
    }
}
