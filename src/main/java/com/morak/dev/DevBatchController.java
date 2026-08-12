package com.morak.dev;

import com.morak.common.error.BusinessException;
import com.morak.common.error.ErrorCode;
import com.morak.dev.dto.response.DevBatchResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * DEV-4 배치 수동 트리거. {@code @Profile("dev")}와 {@code morak.dev.enabled} 이중 스위치다.
 *
 * <p>배치는 스케줄에 걸려 있지만 게이트에서 매분을 기다릴 수는 없다. 스케줄러와 이 경로가
 * 같은 메서드를 부르므로, 여기서 확인한 동작이 곧 스케줄 실행의 동작이다.
 */
@RestController
@RequestMapping("/api/dev/batches")
@Profile("dev")
@ConditionalOnProperty(name = "morak.dev.enabled", havingValue = "true")
public class DevBatchController {

    private final Map<String, DevBatch> batches = new LinkedHashMap<>();

    public DevBatchController(List<DevBatch> batches) {
        for (DevBatch batch : batches) {
            this.batches.put(batch.name(), batch);
        }
    }

    @PostMapping("/{name}")
    public DevBatchResponse run(@PathVariable String name) {
        DevBatch batch = batches.get(name.toUpperCase(Locale.ROOT));
        if (batch == null) {
            // 폐지된 배치(B3)와 오타를 구분하지 않는다. 어느 쪽이든 이 서버에 그 이름의
            // 배치는 없다.
            throw new BusinessException(ErrorCode.ENDPOINT_NOT_FOUND);
        }
        return new DevBatchResponse(batch.name(), batch.run());
    }
}
