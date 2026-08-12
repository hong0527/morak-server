package com.morak.point.client;

/**
 * PG에 결제 승인 결과를 물어본다.
 *
 * <p>구현이 둘이라 인터페이스를 둔다({@code SocialClient}와 같은 기준). 테스트 키가 나오기
 * 전에도 충전 왕복 전체를 만들어야 하고, 실제 토스페이먼츠 구현은 12단계에서 이 자리에
 * 갈아끼운다.
 *
 * <p><b>계약은 "물어본다" 하나뿐이다.</b> 결제창을 띄우는 것도, 카드 정보를 다루는 것도
 * 서버의 일이 아니다(NFR-204). 서버가 PG에 하는 일은 "이 주문이 정말 승인됐고 얼마인가"를
 * 확인하는 것뿐이라 메서드가 하나면 충분하다.
 */
public interface PgClient {

    /**
     * 승인 조회. 응답의 금액을 그대로 믿지 않고 호출부가 충전 건 금액과 다시 맞춰 본다 —
     * 여기서 넘긴 {@code amountKrw}는 PG가 대조에 쓰라고 주는 값이지 결과가 아니다.
     *
     * @param pgOrderId PY-1이 채번한 우리 쪽 주문번호
     * @param pgTid PG가 발급한 거래 식별자(클라이언트 확인 요청 또는 웹훅에서 받은 값)
     * @param amountKrw 우리가 아는 결제 금액(원)
     */
    PgPayment confirm(String pgOrderId, String pgTid, int amountKrw);
}
