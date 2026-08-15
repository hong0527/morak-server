package com.morak.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.morak.common.batch.BatchGuard;
import java.time.Clock;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * 배치가 대상 하나의 실패로 나머지를 버리지 않는지, 그리고 낫지 않는 실패가 조용히 묻히지
 * 않는지 본다.
 *
 * <p>격리는 세션 종료 배치에서 실측으로 드러난 결함이고(종료 대상 2건 중 1건이 웹훅과
 * 부딪히자 나머지가 22회 중 14회 남았다), 같은 구조가 매칭 만료·탈퇴 파기에도 있었다.
 * 승격은 그 뒤에 남은 문제다 — 탈퇴 파기의 무결성 위반은 경합과 같은 예외 계열이라
 * 종류로는 갈리지 않고, 갈리지 않으면 낫지 않는 실패가 매 회차 경고 한 줄로 지나간다.
 */
@DisplayName("배치 대상 격리")
class BatchGuardTest {

    private static final org.slf4j.Logger log = LoggerFactory.getLogger(BatchGuardTest.class);

    private BatchGuard batchGuard;

    @BeforeEach
    void setUp() {
        batchGuard = new BatchGuard(Clock.fixed(
                ZonedDateTime.of(2026, 3, 10, 9, 0, 0, 0, ZoneId.of("Asia/Seoul")).toInstant(),
                ZoneId.of("Asia/Seoul")));
    }

    @Test
    @DisplayName("한 대상이 실패해도 나머지 대상은 처리된다")
    void 실패한_대상만_건너뛴다() {
        // 이 테스트가 죽으면: 한 건의 예외가 루프 밖으로 나가 그 회차의 나머지 대상이
        // 통째로 밀린다. 다음 회차가 거두더라도 그때까지 세션은 LIVE로 남는다.
        List<Long> processed = new ArrayList<>();

        int total = 0;
        for (long targetId : List.of(1L, 2L, 3L)) {
            total += batchGuard.guarded(log, "테스트", targetId, () -> {
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
            int result = batchGuard.guarded(log, "테스트", 1L, () -> {
                throw new IllegalStateException("데이터 결손");
            });
            assertThat(result).isZero();
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("같은 대상이 연달아 실패해도 건너뛰는 동작은 그대로다")
    void 연속_실패해도_나머지를_막지_않는다() {
        // 승격은 로그 레벨만 바꾼다. 승격 로직을 넣다가 예외를 다시 던지게 되면 그 순간
        // 원래 막으려던 문제(한 대상이 회차를 통째로 죽이는 것)가 되살아난다.
        for (int attempt = 0; attempt < 5; attempt++) {
            int result = batchGuard.guarded(log, "테스트", 1L, () -> {
                throw new DataIntegrityViolationException("낫지 않는 실패");
            });
            assertThat(result).isZero();
        }
    }

    @Test
    @DisplayName("한 번 성공하면 앞선 실패는 잊는다")
    void 성공하면_연속_기록이_초기화된다() {
        // 이 테스트가 죽으면: 드문드문 난 실패가 평생 쌓여 멀쩡한 경합이 고장으로 승격된다.
        // 승격이 흔해지면 그 로그를 아무도 안 보게 되고, 그러면 승격을 만든 이유가 사라진다.
        batchGuard.guarded(log, "테스트", 1L, () -> {
            throw new DataIntegrityViolationException("경합");
        });
        batchGuard.guarded(log, "테스트", 1L, () -> 1);

        int afterSuccess = batchGuard.guarded(log, "테스트", 1L, () -> {
            throw new DataIntegrityViolationException("경합");
        });

        assertThat(afterSuccess).isZero();
    }

    @Test
    @DisplayName("서로 다른 대상의 실패는 섞이지 않는다")
    void 대상별로_따로_센다() {
        // 이 테스트가 죽으면: 여러 대상이 한 번씩 실패한 것이 한 대상의 연속 실패로 읽혀,
        // 실제로는 아무것도 고장나지 않았는데 오류가 뜬다.
        for (long targetId : List.of(1L, 2L, 3L, 4L, 5L)) {
            int result = batchGuard.guarded(log, "테스트", targetId, () -> {
                throw new DataIntegrityViolationException("경합");
            });
            assertThat(result).isZero();
        }
    }
}
