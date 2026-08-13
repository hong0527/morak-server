package com.morak.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.morak.support.IntegrationTest;
import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.ObjectMapper;

/**
 * 응답 시각의 표기를 고정한다.
 *
 * <p>명세 §0-1은 시각을 초 단위 ISO-8601로 적는다. 그런데 DB가 준 값에는 나노초가 붙어 있어
 * 그대로 내보내면 소수점 자릿수가 값마다 달라진다. 표준 파서를 쓰는 클라이언트에는 무해하나
 * 고정 포맷으로 파싱하거나 문자열을 비교하는 구현은 전부 깨지고, <b>자릿수가 가변이라 고정
 * 포맷을 정할 수조차 없다.</b>
 */
@DisplayName("응답 시각 표기")
class TimestampFormatTest extends IntegrationTest {

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("나노초가 붙은 시각도 초 단위 오프셋 표기로 나간다")
    void 시각은_초_단위로_직렬화된다() {
        // 이 테스트가 죽으면: 응답 시각에 소수점 초가 되살아난 것이다. 프론트가 고정 포맷으로
        // 파싱하고 있으면 그 순간 전 화면의 시각 표시가 깨진다.
        LocalDateTime withNanos = LocalDateTime.of(2026, 8, 14, 2, 42, 44, 845_157_000);

        String json = objectMapper.writeValueAsString(Map.of("at", withNanos));

        assertThat(json).isEqualTo("{\"at\":\"2026-08-14T02:42:44+09:00\"}");
    }

    @Test
    @DisplayName("자정 정각도 초 자리를 생략하지 않는다")
    void 정각도_초까지_적는다() {
        // ISO-8601은 00초를 생략하는 표기를 허용하지만, 자리 수가 값마다 달라지면
        // 앞 테스트가 막으려던 문제가 형태만 바꿔 돌아온다.
        LocalDateTime midnight = LocalDateTime.of(2026, 8, 14, 0, 0);

        String json = objectMapper.writeValueAsString(Map.of("at", midnight));

        assertThat(json).isEqualTo("{\"at\":\"2026-08-14T00:00:00+09:00\"}");
    }
}
