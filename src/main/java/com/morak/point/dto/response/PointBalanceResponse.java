package com.morak.point.dto.response;

import com.morak.common.dto.PageResponse;

/**
 * PT-1 포인트 잔액·원장 조회 응답. openapi.yaml PointBalanceResponse 스키마와 1:1 대응.
 *
 * <p>{@code balance}는 {@code member.point_balance} 캐시에서 읽고 {@code ledger}는 원장에서
 * 읽는다. 잔액을 매번 원장 합으로 계산하지 않는 이유는 홈·스토어에서 반복 호출되는 값이라
 * 회원 원장 전체를 훑을 자리가 아니기 때문이다. 둘이 어긋나면 원장이 옳고, 내역이 같은
 * 응답에 있어 어긋남이 화면에서 바로 드러난다.
 */
public record PointBalanceResponse(int balance, PageResponse<PointLedgerItemResponse> ledger) {
}
