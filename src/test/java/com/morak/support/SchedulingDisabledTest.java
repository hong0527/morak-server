package com.morak.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.TaskScheduler;

/**
 * 나머지 테스트 전부가 기대는 전제 하나를 못 박는다.
 */
@DisplayName("테스트 컨텍스트 전제")
class SchedulingDisabledTest extends IntegrationTest {

    @Autowired
    private TaskScheduler taskScheduler;

    @Test
    @DisplayName("스케줄러가 실행하지 않는 구현으로 바뀌어 있다")
    void 배치는_저절로_돌지_않는다() {
        // 이 테스트가 죽으면: 배치와 유예 스위퍼가 테스트 도중 저절로 돌기 시작한 것이다.
        // 시계를 밀고 다니는 테스트가 옆 테스트의 세션을 닫아 재현되지 않는 실패가 생긴다.
        assertThat(taskScheduler).isInstanceOf(NoopTaskScheduler.class);
    }
}
