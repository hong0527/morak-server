package com.morak.point;

import static org.assertj.core.api.Assertions.assertThat;

import com.morak.point.dto.request.ChargeConfirmRequest;
import com.morak.point.dto.request.ChargeCreateRequest;
import com.morak.point.dto.response.ChargeCreateResponse;
import com.morak.point.service.PointChargeService;
import com.morak.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

/**
 * 충전 승인이 두 경로(PY-2 확인·PY-3 웹훅)로 들어와도 적립은 한 번이고, <b>승인 정보가 나중
 * 요청의 값으로 덮이지 않는지</b> 본다.
 *
 * <p>덮이면 원장에 적립을 만든 거래와 충전 건에 적힌 거래가 달라진다. 그 상태는 아무 예외도
 * 내지 않고 잔액도 맞아서, PG 정산서와 대사할 때에야 드러난다.
 */
@DisplayName("충전 승인 멱등")
class ChargeSettlementIdempotencyTest extends IntegrationTest {

    private static final int AMOUNT_KRW = 10_000;
    private static final String FIRST_TID = "tid-first";
    private static final String SECOND_TID = "tid-second";

    @Autowired
    private PointChargeService pointChargeService;

    @Value("${morak.point.welcome}")
    private int welcomePoint;

    @Value("${morak.pg.point-per-krw}")
    private int pointPerKrw;

    @Test
    @DisplayName("확인 뒤 도착한 웹훅은 적립도 거래 식별자도 바꾸지 않는다")
    void 웹훅_재도달은_적립을_늘리지_않는다() {
        // 이 테스트가 죽으면: 두 경로가 각자 적립하거나, 나중에 온 쪽이 pg_tid를 덮어쓴다.
        clock.fixAt(BASE_TIME);
        Long memberId = fixtures.joinMember();
        ChargeCreateResponse created = pointChargeService.create(
                memberId, new ChargeCreateRequest(AMOUNT_KRW));
        pointChargeService.confirm(memberId, created.chargeId(), new ChargeConfirmRequest(
                created.pgOrderId(), FIRST_TID, AMOUNT_KRW));

        pointChargeService.approveByPgOrderId(created.pgOrderId(), SECOND_TID, AMOUNT_KRW);

        assertExactlyOneCharge(memberId, created.chargeId());
    }

    @Test
    @DisplayName("상태가 READY로 보여도 원장에 이미 있으면 승인 정보를 덮지 않는다")
    void 원장이_이미_있으면_승인을_다시_쓰지_않는다() {
        // 이 테스트가 죽으면: 멱등 판정이 충전 건 상태(스냅샷)에만 기대는 상태로 돌아간 것이다.
        // 격리 수준에 따라 먼저 커밋한 승인을 못 보는 트랜잭션이 실재하고, 그때 이중 적립과
        // pg_tid 덮어쓰기가 함께 일어난다. 그 스냅샷을 SQL로 재현해 원장 쪽 판정만 남긴다.
        clock.fixAt(BASE_TIME);
        Long memberId = fixtures.joinMember();
        ChargeCreateResponse created = pointChargeService.create(
                memberId, new ChargeCreateRequest(AMOUNT_KRW));
        pointChargeService.confirm(memberId, created.chargeId(), new ChargeConfirmRequest(
                created.pgOrderId(), FIRST_TID, AMOUNT_KRW));
        // 승인 사실만 되돌린다. 원장은 그대로 두어 "적립은 있는데 상태는 READY"를 만든다.
        fixtures.execute("UPDATE point_charge SET status = 'READY', approved_at = NULL WHERE id = ?",
                created.chargeId());

        pointChargeService.approveByPgOrderId(created.pgOrderId(), SECOND_TID, AMOUNT_KRW);

        assertExactlyOneCharge(memberId, created.chargeId());
    }

    private void assertExactlyOneCharge(Long memberId, Long chargeId) {
        assertThat(fixtures.count("point_ledger", "member_id = ? AND reason = 'CHARGE'", memberId))
                .isEqualTo(1);
        assertThat(fixtures.count("point_charge", "id = ? AND pg_tid = ?", chargeId, FIRST_TID))
                .isEqualTo(1);
        assertThat(fixtures.member(memberId).getPointBalance())
                .isEqualTo(welcomePoint + AMOUNT_KRW * pointPerKrw)
                .isEqualTo(fixtures.ledgerSum(memberId));
    }
}
