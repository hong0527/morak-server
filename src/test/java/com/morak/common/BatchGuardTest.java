package com.morak.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.morak.common.batch.BatchGuard;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * 배치가 대상 하나의 실패로 나머지를 버리지 않는지 본다.
 *
 * <p>세션 종료 배치에서 실측으로 드러난 결함이고(종료 대상 2건 중 1건이 웹훅과 부딪히자
 * 나머지가 22회 중 14회 남았다), 같은 구조가 매칭 만료·탈퇴 파기에도 있었다. 세 배치가
 * 같은 감싸기를 쓰므로 여기서 한 번 지키면 셋 다 지켜진다.
 */
@DisplayName("배치 대상 격리")
class BatchGuardTest {

    private static final org.slf4j.Logger log = LoggerFactory.getLogger(BatchGuardTest.class);

    @Test
    @DisplayName("한 대상이 실패해도 나머지 대상은 처리된다")
    void 실패한_대상만_건너뛴다() {
        // 이 테스트가 죽으면: 한 건의 예외가 루프 밖으로 나가 그 회차의 나머지 대상이
        // 통째로 밀린다. 다음 회차가 거두더라도 그때까지 세션은 LIVE로 남는다.
        List<Long> processed = new ArrayList<>();

        int total = 0;
        for (long targetId : List.of(1L, 2L, 3L)) {
            total += BatchGuard.guarded(log, "테스트", targetId, () -> {
                if (targetId == 2L) {
                    throw new DataIntegrityViolationException("경합");
                }
                processed.add(targetId);
                return 1;
            });
        }

        assertThat(processed).containsExactly(1L, 3L);
        assertThat(total).isEqualTo(2);
    }

    @Test
    @DisplayName("경합이 아닌 실패도 루프를 끊지 않는다")
    void 예상하지_못한_실패도_삼킨다() {
        // 로그 레벨은 갈리지만(경합은 경고, 나머지는 스택과 함께 오류) 나머지 대상을
        // 처리해야 하는 것은 같다. 여기서 예외가 새면 배치 한 회차가 통째로 죽는다.
        assertThatCode(() -> {
            int result = BatchGuard.guarded(log, "테스트", 1L, () -> {
                throw new IllegalStateException("데이터 결손");
            });
            assertThat(result).isZero();
        }).doesNotThrowAnyException();
    }
}
