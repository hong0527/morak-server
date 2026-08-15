package com.morak.point;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.morak.common.error.BusinessException;
import com.morak.common.error.ErrorCode;
import com.morak.point.dto.request.ChargeConfirmRequest;
import com.morak.point.dto.request.ChargeCreateRequest;
import com.morak.point.dto.response.ChargeConfirmResponse;
import com.morak.point.dto.response.ChargeCreateResponse;
import com.morak.point.service.PaymentWebhookService;
import com.morak.point.service.PointChargeExpiryBatch;
import com.morak.point.service.PointChargeService;
import com.morak.point.service.PointService;
import com.morak.point.type.ChargeStatus;
import com.morak.support.IntegrationTest;
import com.morak.support.PaymentWebhookSigner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * PY-1 → PY-2 → PY-3 충전 왕복과 B5 만료.
 *
 * <p>기존 테스트({@code ChargeSettlementIdempotencyTest})는 <b>이미 승인된 건에 웹훅이 또
 * 도착했을 때</b>만 봤다. 정작 "결제창을 띄우고 승인받아 포인트가 늘어나는" 왕복 자체와,
 * 승인되지 않는 경로들은 한 번도 지나가 본 적이 없다.
 *
 * <p>이 단계가 막아야 하는 것은 하나다 — <b>돈은 빠졌는데 포인트가 없거나, 그 반대.</b>
 * 그래서 모든 검사가 원장을 함께 본다. 상태만 APPROVED로 바뀌고 원장이 없으면 사용자는
 * 결제하고 아무것도 받지 못한 것이고, 반대면 공짜 포인트다.
 *
 * <p>개발용 PG는 거래 식별자 접두사로 응답을 가른다 — {@code fail-}은 거절,
 * {@code mismatch-}는 승인은 됐지만 금액이 어긋난 응답이다({@code DevPgClient}).
 */
@DisplayName("포인트 충전 왕복")
class ChargeRoundTripTest extends IntegrationTest {

    private static final int AMOUNT = 10_000;

    @Autowired
    private PointChargeService pointChargeService;

    @Autowired
    private PointChargeExpiryBatch pointChargeExpiryBatch;

    @Autowired
    private PointService pointService;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PaymentWebhookSigner signer;

    @Value("${morak.pg.point-per-krw}")
    private int pointPerKrw;

    @Value("${morak.pg.min-amount-krw}")
    private int minAmountKrw;

    @Value("${morak.pg.max-amount-krw}")
    private int maxAmountKrw;

    @Value("${morak.pg.ready-expire-minutes}")
    private int readyExpireMinutes;

    // ── PY-1 생성 ──

    @Test
    @DisplayName("충전 건을 만들어도 포인트는 아직 늘지 않는다")
    void 생성은_적립하지_않는다() {
        // 이 테스트가 죽으면: 결제창을 띄우기만 해도 포인트가 들어온다. 결제하지 않고
        // PY-1만 반복하면 무한 충전이다.
        Long memberId = fixtures.joinVerifiedMember("py1-create");
        int before = pointService.getBalance(memberId);

        ChargeCreateResponse created = pointChargeService.create(memberId,
                new ChargeCreateRequest(AMOUNT));

        assertThat(created.status()).isEqualTo(ChargeStatus.READY);
        assertThat(created.amountKrw()).isEqualTo(AMOUNT);
        assertThat(created.pointAmount()).isEqualTo(AMOUNT * pointPerKrw);
        assertThat(created.pgOrderId()).isNotBlank();
        assertThat(pointService.getBalance(memberId)).isEqualTo(before);
        assertThat(fixtures.ledgerSum(memberId)).isEqualTo(before);
    }

    @Test
    @DisplayName("하한 미만·상한 초과 금액은 충전 건 자체를 만들지 않는다")
    void 금액_한도를_벗어나면_거절한다() {
        // 이 테스트가 죽으면: 1원 결제나 한도를 넘는 결제가 통과한다. READY 행만 남기고
        // 거절하면 B5가 매분 도는 쓰레기가 쌓인다.
        Long memberId = fixtures.joinVerifiedMember("py1-limit");

        assertThatThrownBy(() -> pointChargeService.create(memberId,
                new ChargeCreateRequest(minAmountKrw - 1)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> pointChargeService.create(memberId,
                new ChargeCreateRequest(maxAmountKrw + 1)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.VALIDATION_FAILED);

        assertThat(fixtures.countAll("point_charge")).isZero();
        // 경계값 자체는 통과해야 한다
        assertThat(pointChargeService.create(memberId, new ChargeCreateRequest(minAmountKrw))
                .status()).isEqualTo(ChargeStatus.READY);
    }

    // ── PY-2 승인 확인 ──

    @Test
    @DisplayName("승인 확인이 상태·원장·잔액을 함께 움직인다")
    void 승인_확인이_왕복을_닫는다() {
        // 이 테스트가 죽으면: 충전의 본체가 멈춘다. 상태만 바뀌고 원장이 없으면 결제한 사람이
        // 포인트를 받지 못하고, 잔액 캐시가 원장과 어긋나면 그 뒤의 모든 소비가 틀린 값을 쓴다.
        Long memberId = fixtures.joinVerifiedMember("py2-ok");
        int before = pointService.getBalance(memberId);
        ChargeCreateResponse created = pointChargeService.create(memberId,
                new ChargeCreateRequest(AMOUNT));

        ChargeConfirmResponse confirmed = pointChargeService.confirm(memberId, created.chargeId(),
                new ChargeConfirmRequest(created.pgOrderId(), "tid-py2-ok", AMOUNT));

        assertThat(confirmed.status()).isEqualTo(ChargeStatus.APPROVED);
        assertThat(confirmed.pointAmount()).isEqualTo(AMOUNT * pointPerKrw);
        assertThat(confirmed.approvedAt()).isNotNull();
        assertThat(confirmed.pointBalance()).isEqualTo(before + AMOUNT * pointPerKrw);
        assertThat(fixtures.ledgerSum(memberId)).isEqualTo(before + AMOUNT * pointPerKrw);
        assertThat(fixtures.count("point_ledger",
                "member_id = ? AND reason = 'CHARGE' AND ref_id = ?",
                memberId, created.chargeId())).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 확인을 두 번 보내도 적립은 한 번이다")
    void 승인_확인은_멱등이다() {
        // 이 테스트가 죽으면: 클라이언트의 재시도 한 번이 그대로 두 배 적립이 된다.
        Long memberId = fixtures.joinVerifiedMember("py2-idem");
        int before = pointService.getBalance(memberId);
        ChargeCreateResponse created = pointChargeService.create(memberId,
                new ChargeCreateRequest(AMOUNT));
        ChargeConfirmRequest request =
                new ChargeConfirmRequest(created.pgOrderId(), "tid-py2-idem", AMOUNT);

        pointChargeService.confirm(memberId, created.chargeId(), request);
        ChargeConfirmResponse again =
                pointChargeService.confirm(memberId, created.chargeId(), request);

        assertThat(again.status()).isEqualTo(ChargeStatus.APPROVED);
        assertThat(fixtures.ledgerSum(memberId)).isEqualTo(before + AMOUNT * pointPerKrw);
        assertThat(fixtures.count("point_ledger", "member_id = ? AND reason = 'CHARGE'",
                memberId)).isEqualTo(1);
    }

    @Test
    @DisplayName("남의 충전 건은 없는 것과 같은 응답이고 상태도 건드리지 않는다")
    void 타인의_충전_건은_404다() {
        // 이 테스트가 죽으면: 번호를 훑어 남의 결제 존재 여부를 알아낼 수 있고, 더 나쁘게는
        // 남의 결제를 내 계정으로 적립할 수 있다.
        Long owner = fixtures.joinVerifiedMember("py2-owner");
        Long stranger = fixtures.joinVerifiedMember("py2-stranger");
        ChargeCreateResponse created = pointChargeService.create(owner,
                new ChargeCreateRequest(AMOUNT));

        assertThatThrownBy(() -> pointChargeService.confirm(stranger, created.chargeId(),
                new ChargeConfirmRequest(created.pgOrderId(), "tid-steal", AMOUNT)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.CHARGE_NOT_FOUND);

        assertThat(chargeStatus(created.chargeId())).isEqualTo("READY");
        assertThat(fixtures.countAll("point_ledger")).isEqualTo(2); // 두 회원의 웰컴 지급뿐
    }

    @Test
    @DisplayName("주문번호·금액이 어긋난 확인은 충전 건을 실패로 닫지 않는다")
    void 대조_실패는_상태를_바꾸지_않는다() {
        // 이 테스트가 죽으면: 클라이언트의 오타 한 번으로 정상 결제 건이 FAILED로 닫힌다.
        // 돈은 빠졌는데 되돌릴 길이 없는 상태라 반드시 READY로 남아야 한다.
        Long memberId = fixtures.joinVerifiedMember("py2-mismatch");
        ChargeCreateResponse created = pointChargeService.create(memberId,
                new ChargeCreateRequest(AMOUNT));

        assertThatThrownBy(() -> pointChargeService.confirm(memberId, created.chargeId(),
                new ChargeConfirmRequest("wrong-order-id", "tid-x", AMOUNT)))
                .extracting("errorCode").isEqualTo(ErrorCode.PAYMENT_AMOUNT_MISMATCH);
        assertThatThrownBy(() -> pointChargeService.confirm(memberId, created.chargeId(),
                new ChargeConfirmRequest(created.pgOrderId(), "tid-x", AMOUNT + 1)))
                .extracting("errorCode").isEqualTo(ErrorCode.PAYMENT_AMOUNT_MISMATCH);

        assertThat(chargeStatus(created.chargeId())).isEqualTo("READY");
    }

    @Test
    @DisplayName("PG가 승인하지 않으면 충전 건은 FAILED로 닫히고 적립도 없다")
    void PG_거절은_실패로_기록된다() {
        // 이 테스트가 죽으면: 거절된 결제가 READY로 남아 계속 재시도할 수 있게 되거나,
        // 더 나쁘게는 승인 없이 적립된다. FAILED 기록이 커밋되지 않으면 다음 호출이 PG를 또 부른다.
        Long memberId = fixtures.joinVerifiedMember("py2-rejected");
        int before = pointService.getBalance(memberId);
        ChargeCreateResponse created = pointChargeService.create(memberId,
                new ChargeCreateRequest(AMOUNT));

        assertThatThrownBy(() -> pointChargeService.confirm(memberId, created.chargeId(),
                new ChargeConfirmRequest(created.pgOrderId(), "fail-tid", AMOUNT)))
                .extracting("errorCode").isEqualTo(ErrorCode.PAYMENT_NOT_APPROVED);

        assertThat(chargeStatus(created.chargeId())).isEqualTo("FAILED");
        assertThat(fixtures.ledgerSum(memberId)).isEqualTo(before);
        // 닫힌 건은 다시 승인되지 않는다
        assertThatThrownBy(() -> pointChargeService.confirm(memberId, created.chargeId(),
                new ChargeConfirmRequest(created.pgOrderId(), "tid-retry", AMOUNT)))
                .extracting("errorCode").isEqualTo(ErrorCode.PAYMENT_NOT_APPROVED);
    }

    @Test
    @DisplayName("PG 승인 금액이 충전 건과 다르면 적립하지 않고 닫는다")
    void PG_금액_불일치는_적립하지_않는다() {
        // 이 테스트가 죽으면: 1원 결제로 10만 포인트가 들어온다. 어느 금액을 적립해도 틀리는
        // 상황이라 적립하지 않고 사람이 대사하도록 남기는 것이 유일한 정답이다.
        Long memberId = fixtures.joinVerifiedMember("py2-amount");
        int before = pointService.getBalance(memberId);
        ChargeCreateResponse created = pointChargeService.create(memberId,
                new ChargeCreateRequest(AMOUNT));

        assertThatThrownBy(() -> pointChargeService.confirm(memberId, created.chargeId(),
                new ChargeConfirmRequest(created.pgOrderId(), "mismatch-tid", AMOUNT)))
                .extracting("errorCode").isEqualTo(ErrorCode.PAYMENT_AMOUNT_MISMATCH);

        assertThat(chargeStatus(created.chargeId())).isEqualTo("FAILED");
        assertThat(fixtures.ledgerSum(memberId)).isEqualTo(before);
    }

    // ── PY-3 웹훅 ──

    @Test
    @DisplayName("PG 웹훅만으로도 적립이 성립한다")
    void 웹훅_승인이_적립한다() throws Exception {
        // 이 테스트가 죽으면: 결제 후 앱이 죽거나 사용자가 창을 닫으면 포인트가 영원히 오지
        // 않는다. 웹훅은 클라이언트가 이탈해도 도착하는 유일한 경로다.
        Long memberId = fixtures.joinVerifiedMember("py3-webhook");
        int before = pointService.getBalance(memberId);
        ChargeCreateResponse created = pointChargeService.create(memberId,
                new ChargeCreateRequest(AMOUNT));

        sendWebhook(doneBody(created.pgOrderId(), "tid-py3", AMOUNT))
                .andExpect(status().isOk());

        assertThat(chargeStatus(created.chargeId())).isEqualTo("APPROVED");
        assertThat(fixtures.ledgerSum(memberId)).isEqualTo(before + AMOUNT * pointPerKrw);
    }

    @Test
    @DisplayName("서명이 없거나 틀린 웹훅은 충전 건을 건드리지 못한다")
    void 서명이_틀린_웹훅은_401이다() throws Exception {
        // 이 테스트가 죽으면: 아무나 우리 주문번호를 흉내 내 포인트를 적립시킬 수 있다.
        // 웹훅은 JWT 게이트를 전부 건너뛰므로 서명이 유일한 신원 보장이다.
        Long memberId = fixtures.joinVerifiedMember("py3-forged");
        int before = pointService.getBalance(memberId);
        ChargeCreateResponse created = pointChargeService.create(memberId,
                new ChargeCreateRequest(AMOUNT));
        String body = doneBody(created.pgOrderId(), "tid-forged", AMOUNT);

        perform(body, "deadbeef")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_WEBHOOK_SIGNATURE"));

        assertThat(chargeStatus(created.chargeId())).isEqualTo("READY");
        assertThat(fixtures.ledgerSum(memberId)).isEqualTo(before);
    }

    @Test
    @DisplayName("취소 통보는 충전 건을 실패로 닫는다")
    void 취소_통보는_실패로_닫는다() throws Exception {
        // 이 테스트가 죽으면: 사용자가 결제를 취소해도 READY가 남아 B5가 돌 때까지 승인
        // 가능한 상태로 열려 있다.
        Long memberId = fixtures.joinVerifiedMember("py3-canceled");
        ChargeCreateResponse created = pointChargeService.create(memberId,
                new ChargeCreateRequest(AMOUNT));

        sendWebhook("""
                {"eventType":"PAYMENT_STATUS_CHANGED",
                 "data":{"orderId":"%s","paymentKey":"tid-c","status":"CANCELED",
                         "totalAmount":%d}}
                """.formatted(created.pgOrderId(), AMOUNT))
                .andExpect(status().isOk());

        assertThat(chargeStatus(created.chargeId())).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("모르는 주문번호의 웹훅도 200으로 흡수한다")
    void 모르는_주문번호는_200이다() throws Exception {
        // 이 테스트가 죽으면: 우리가 모르는 주문에 4xx·5xx를 돌려주게 되고, PG가 그 웹훅을
        // 무한히 재시도한다.
        sendWebhook(doneBody("unknown-order-id", "tid-unknown", AMOUNT))
                .andExpect(status().isOk());

        assertThat(fixtures.countAll("point_charge")).isZero();
    }

    // ── B5 만료 ──

    @Test
    @DisplayName("기한을 넘긴 READY는 B5가 닫고, 승인된 건은 건드리지 않는다")
    void B5가_방치된_충전을_닫는다() {
        // 이 테스트가 죽으면: 결제창을 띄우고 닫은 건이 영원히 READY로 쌓여 매일 사람이
        // 판단해야 하는 미결이 된다. 반대로 APPROVED까지 닫으면 적립된 포인트의 근거가 뒤집힌다.
        Long memberId = fixtures.joinVerifiedMember("b5-member");
        clock.fixAt(BASE_TIME);
        ChargeCreateResponse stale = pointChargeService.create(memberId,
                new ChargeCreateRequest(AMOUNT));
        ChargeCreateResponse approved = pointChargeService.create(memberId,
                new ChargeCreateRequest(AMOUNT));
        pointChargeService.confirm(memberId, approved.chargeId(),
                new ChargeConfirmRequest(approved.pgOrderId(), "tid-b5", AMOUNT));

        clock.fixAt(BASE_TIME.plusMinutes(readyExpireMinutes + 1));
        // 방금 만든 건은 아직 기한 안이라 닫히지 않아야 한다
        ChargeCreateResponse fresh = pointChargeService.create(memberId,
                new ChargeCreateRequest(AMOUNT));
        pointChargeExpiryBatch.run();

        assertThat(chargeStatus(stale.chargeId())).isEqualTo("FAILED");
        assertThat(chargeStatus(approved.chargeId())).isEqualTo("APPROVED");
        assertThat(chargeStatus(fresh.chargeId())).isEqualTo("READY");
    }

    @Test
    @DisplayName("B5를 다시 돌려도 이미 닫힌 건의 상태는 그대로다")
    void B5는_재실행이_안전하다() {
        // 이 테스트가 죽으면: 배치 재실행이 안전하지 않다는 뜻이다(§5 공통 규칙). 수동
        // 트리거(DEV-4)와 스케줄이 겹쳐 도는 순간 같은 건이 두 번 처리된다.
        Long memberId = fixtures.joinVerifiedMember("b5-rerun");
        clock.fixAt(BASE_TIME);
        ChargeCreateResponse stale = pointChargeService.create(memberId,
                new ChargeCreateRequest(AMOUNT));

        clock.fixAt(BASE_TIME.plusMinutes(readyExpireMinutes + 1));
        pointChargeExpiryBatch.run();
        pointChargeExpiryBatch.run();

        assertThat(chargeStatus(stale.chargeId())).isEqualTo("FAILED");
        assertThat(fixtures.countAll("point_charge")).isEqualTo(1);
    }

    @Test
    @DisplayName("기한이 지난 충전은 PG에 묻지 않고 그 자리에서 닫힌다")
    void 기한_초과_확인은_PG를_부르지_않는다() {
        // 이 테스트가 죽으면: 30일 지난 주문번호로 승인을 시도하는 요청에 문이 계속 열려 있다.
        // 승인될 리 없는 요청이라 PG를 부르는 것 자체가 낭비이자 위험이다.
        Long memberId = fixtures.joinVerifiedMember("py2-stale");
        clock.fixAt(BASE_TIME);
        ChargeCreateResponse created = pointChargeService.create(memberId,
                new ChargeCreateRequest(AMOUNT));

        clock.fixAt(BASE_TIME.plusMinutes(readyExpireMinutes + 1));
        // 개발용 PG는 이 tid를 승인한다. 그런데도 실패해야 기한 판정이 PG보다 앞선 것이다
        assertThatThrownBy(() -> pointChargeService.confirm(memberId, created.chargeId(),
                new ChargeConfirmRequest(created.pgOrderId(), "tid-would-approve", AMOUNT)))
                .extracting("errorCode").isEqualTo(ErrorCode.PAYMENT_NOT_APPROVED);

        assertThat(chargeStatus(created.chargeId())).isEqualTo("FAILED");
    }

    private String doneBody(String pgOrderId, String pgTid, int amountKrw) {
        return """
                {"eventType":"PAYMENT_STATUS_CHANGED",
                 "data":{"orderId":"%s","paymentKey":"%s","status":"DONE","totalAmount":%d}}
                """.formatted(pgOrderId, pgTid, amountKrw);
    }

    private ResultActions sendWebhook(String body) {
        return perform(body, signer.sign(body));
    }

    private ResultActions perform(String body, String signature) {
        try {
            return mockMvc.perform(post("/api/webhooks/payment")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(PaymentWebhookService.SIGNATURE_HEADER, signature)
                    .content(body));
        } catch (Exception e) {
            throw new IllegalStateException("웹훅 요청에 실패했다", e);
        }
    }

    private String chargeStatus(Long chargeId) {
        return fixtures.queryString("SELECT status FROM point_charge WHERE id = ?", chargeId);
    }
}
