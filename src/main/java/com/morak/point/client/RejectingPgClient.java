package com.morak.point.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * dev가 아닌 프로필의 기본 PG 클라이언트. 어떤 거래도 승인으로 보지 않으므로 충전 건은
 * FAILED로 닫히고 호출부는 409 {@code PAYMENT_NOT_APPROVED}를 받는다.
 *
 * <p>{@link RejectingSocialClient}와 같은 이유로 있다 — {@link DevPgClient}는 dev 프로필에만
 * 있어서 이 빈이 없으면 운영 프로필이 {@code PgClient} 주입 실패로 기동하지 못한다.
 *
 * <p>승인하지 않는 쪽을 기본값으로 두는 것이 핵심이다. 통과시키는 스텁이 운영에 남으면 결제
 * 없이 포인트가 적립된다. 실제 토스페이먼츠 구현은 12단계에서 이 빈을 대체한다.
 *
 * <p>예외를 던지지 않고 거절 응답을 돌려주는 것은 {@link PgClient} 계약을 지키기 위해서다.
 * 던지면 호출부의 트랜잭션이 롤백돼 "PG가 승인하지 않았다"는 FAILED 기록까지 사라지고,
 * 다음 요청이 같은 자리를 다시 두드린다(PointChargeService#settle 주석).
 */
@Component
@Profile("!dev & !demo")
public class RejectingPgClient implements PgClient {

    private static final Logger log = LoggerFactory.getLogger(RejectingPgClient.class);

    @Override
    public PgPayment confirm(String pgOrderId, String pgTid, int amountKrw) {
        log.warn("PG 연동 구현이 아직 없어 승인하지 않는다: order={}", pgOrderId);
        return PgPayment.rejected("PG 연동 구현이 아직 없다");
    }
}
