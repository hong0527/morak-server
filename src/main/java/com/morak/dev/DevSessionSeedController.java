package com.morak.dev;

import com.morak.dev.dto.request.DevSessionSeedRequest;
import com.morak.dev.dto.response.DevSessionSeedResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * DEV-3 과거 완주 이력 시드. {@code @Profile("dev")}와 {@code morak.dev.enabled} 이중 스위치다.
 */
@RestController
@RequestMapping("/api/dev/sessions/seed")
@Profile("dev")
@ConditionalOnProperty(name = "morak.dev.enabled", havingValue = "true")
@RequiredArgsConstructor
public class DevSessionSeedController {

    private final DevSessionSeedService devSessionSeedService;

    @PostMapping
    public DevSessionSeedResponse seed(@Valid @RequestBody DevSessionSeedRequest request) {
        return devSessionSeedService.seed(request);
    }
}
