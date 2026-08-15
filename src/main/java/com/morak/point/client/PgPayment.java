package com.morak.point.client;

/**
 * PG 승인 조회 결과.
 *
 * <p>미승인을 예외가 아니라 값으로 돌려주는 이유는 그것이 오류가 아니라 정상적인 결과이기
 * 때문이다 — 사용자가 결제창을 닫은 것도 카드사가 거절한 것도 서버가 예상하는 흐름이고,
 * 그때 충전 건을 FAILED로 닫아야 한다. 예외로 던지면 그 기록을 남길 자리가 사라진다.
 *
 * @param approved PG가 승인을 확인해 줬는가
 * @param pgTid 승인된 거래 식별자. 미승인이면 null
 * @param amountKrw PG가 알려준 실제 결제 금액(원). 미승인이면 0
 * @param failureReason 미승인 사유. 로그와 실패 안내에만 쓴다
 */
public record PgPayment(boolean approved, String pgTid, int amountKrw, String failureReason) {

    public static PgPayment approved(String pgTid, int amountKrw) {
        return new PgPayment(true, pgTid, amountKrw, null);
    }

    public static PgPayment rejected(String failureReason) {
        return new PgPayment(false, null, 0, failureReason);
    }
}
