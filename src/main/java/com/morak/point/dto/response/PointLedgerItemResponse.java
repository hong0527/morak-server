package com.morak.point.dto.response;

import com.morak.point.entity.PointLedger;
import com.morak.point.type.PointReason;
import java.time.LocalDateTime;

/**
 * PT-1 원장 한 줄. openapi.yaml PointLedgerItem 스키마와 1:1 대응.
 *
 * <p>{@code reasonLabel}은 {@link PointReason}에서 파생하는 표시용 문자열이며 저장하지 않는다.
 * {@code reason}을 함께 내리는 이유는 화면이 사유별로 분기할 때 문구를 비교하게 두면 문구
 * 하나 고칠 때마다 클라이언트가 깨지기 때문이다.
 */
public record PointLedgerItemResponse(
        Long ledgerId,
        int delta,
        PointReason reason,
        String reasonLabel,
        String refType,
        Long refId,
        int balanceAfter,
        LocalDateTime createdAt) {

    public static PointLedgerItemResponse from(PointLedger ledger) {
        return new PointLedgerItemResponse(
                ledger.getId(),
                ledger.getDelta(),
                ledger.getReason(),
                ledger.getReason().label(),
                ledger.getRefType(),
                ledger.getRefId(),
                ledger.getBalanceAfter(),
                ledger.getCreatedAt());
    }
}
