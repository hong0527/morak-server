package com.morak.point;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.morak.common.error.BusinessException;
import com.morak.common.error.ErrorCode;
import com.morak.point.service.PointService;
import com.morak.point.type.PointReason;
import com.morak.support.Concurrently;
import com.morak.support.IntegrationTest;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

/**
 * 포인트 원장의 불변식. 잔액 캐시는 원장 합과 같고, 같은 근거로는 두 번 움직이지 않으며,
 * 지급과 사용은 잔액 부족을 다르게 다룬다.
 */
@DisplayName("포인트 원장 불변식")
class PointLedgerInvariantTest extends IntegrationTest {

    private static final int AWARD_AMOUNT = 100;

    @Autowired
    private PointService pointService;

    @Value("${morak.point.welcome}")
    private int welcomePoint;

    @Test
    @DisplayName("동시 지급 20건 뒤에도 잔액 캐시가 원장 합과 같다")
    void 동시_지급_뒤_잔액_캐시는_원장_합과_같다() {
        // 이 테스트가 죽으면: 잔액 증감이 SQL 한 문장을 벗어나 읽고-더하고-쓰는 형태가 된 것이다.
        // 동시 지급이 서로의 결과를 덮어써 원장은 남는데 잔액만 사라진다(8단계 실측 결함).
        Long memberId = fixtures.joinMember();
        LocalDateTime now = now();
        int concurrentAwards = 20;

        List<Throwable> failures = Concurrently.run(concurrentAwards, index ->
                pointService.award(memberId, AWARD_AMOUNT, PointReason.SESSION_COMPLETE,
                        (long) index, now));

        assertThat(failures).isEmpty();
        assertThat(fixtures.count("point_ledger", "member_id = ?", memberId))
                .isEqualTo(concurrentAwards + 1);
        assertThat(fixtures.member(memberId).getPointBalance())
                .isEqualTo(welcomePoint + concurrentAwards * AWARD_AMOUNT)
                .isEqualTo(fixtures.ledgerSum(memberId));
    }

    @Test
    @DisplayName("같은 4열 멱등키의 재지급은 조용히 무시된다")
    void 같은_멱등키의_순차_재지급은_원장을_늘리지_않는다() {
        // 이 테스트가 죽으면: B1 재실행·웹훅 중복 수신이 그대로 이중 지급이 된 것이다.
        Long memberId = fixtures.joinMember();
        LocalDateTime now = now();

        boolean first = pointService.award(
                memberId, AWARD_AMOUNT, PointReason.SESSION_COMPLETE, 1L, now);
        boolean second = pointService.award(
                memberId, AWARD_AMOUNT, PointReason.SESSION_COMPLETE, 1L, now);

        assertThat(first).isTrue();
        assertThat(second).isFalse();
        assertThat(fixtures.count("point_ledger",
                "member_id = ? AND reason = 'SESSION_COMPLETE' AND ref_id = 1", memberId))
                .isEqualTo(1);
        assertThat(fixtures.member(memberId).getPointBalance())
                .isEqualTo(welcomePoint + AWARD_AMOUNT)
                .isEqualTo(fixtures.ledgerSum(memberId));
    }

    @Test
    @DisplayName("같은 멱등키의 동시 지급은 제약에 걸려 한 건만 남는다")
    void 같은_멱등키의_동시_지급은_한_건만_남는다() {
        // 이 테스트가 죽으면: 중복 방어를 사전 조회에만 기대게 된 것이다. 동시 실행은 조회를
        // 함께 통과하므로 uk_pl_dedup이 없으면 두 줄이 남고 잔액도 두 번 오른다.
        Long memberId = fixtures.joinMember();
        LocalDateTime now = now();

        List<Throwable> failures = Concurrently.run(8, index ->
                pointService.award(memberId, AWARD_AMOUNT, PointReason.SESSION_COMPLETE, 7L, now));

        // 진 쪽은 제약 위반으로 트랜잭션째 롤백된다. 예외가 나는 것 자체는 정상이고,
        // 남으면 안 되는 것은 두 번째 원장 줄과 두 번 오른 잔액이다.
        assertThat(failures.size()).isLessThan(8);
        assertThat(fixtures.count("point_ledger",
                "member_id = ? AND reason = 'SESSION_COMPLETE' AND ref_id = 7", memberId))
                .isEqualTo(1);
        assertThat(fixtures.member(memberId).getPointBalance())
                .isEqualTo(welcomePoint + AWARD_AMOUNT)
                .isEqualTo(fixtures.ledgerSum(memberId));
    }

    @Test
    @DisplayName("잔액을 넘는 사용은 거절되고 원장도 남지 않는다")
    void 잔액을_넘는_spend는_거절된다() {
        // 이 테스트가 죽으면: 사용자가 쓰는 포인트가 award로 깎이게 된 것이다. 잔액이 마이너스로
        // 내려간 채 주문이 성사된다.
        Long memberId = fixtures.joinMember();
        LocalDateTime now = now();

        assertThatThrownBy(() -> pointService.spend(
                memberId, welcomePoint + 1, PointReason.ORDER_SPEND, 1L, now))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.INSUFFICIENT_POINT);

        assertThat(fixtures.count("point_ledger", "member_id = ? AND reason = 'ORDER_SPEND'",
                memberId)).isZero();
        assertThat(fixtures.member(memberId).getPointBalance())
                .isEqualTo(welcomePoint)
                .isEqualTo(fixtures.ledgerSum(memberId));
    }

    @Test
    @DisplayName("퇴출 패널티는 잔액이 모자라도 음수로 기록된다")
    void 패널티_지급은_잔액을_넘겨도_기록된다() {
        // 이 테스트가 죽으면: 잔액을 비워 두면 퇴출 패널티를 피할 수 있게 된 것이다.
        Long memberId = fixtures.joinMember();
        int penalty = welcomePoint + 300;

        boolean recorded = pointService.award(
                memberId, -penalty, PointReason.EVICTION_PENALTY, 1L, now());

        assertThat(recorded).isTrue();
        assertThat(fixtures.member(memberId).getPointBalance())
                .isEqualTo(-300)
                .isEqualTo(fixtures.ledgerSum(memberId));
    }
}
