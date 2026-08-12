package com.morak.store.dto.response;

import com.morak.store.entity.Product;
import com.morak.store.entity.StoreOrder;
import com.morak.store.type.OrderStatus;
import com.morak.store.type.ProductType;
import java.time.LocalDateTime;

/**
 * SR-4 목록 한 줄. openapi.yaml OrderSummary 스키마와 1:1 대응.
 *
 * <p>상품 이름과 종류는 {@code product}에서 읽고 금액은 주문 행에서 읽는다. 이름은 지금 값을
 * 보여주는 것이 자연스럽지만 <b>금액은 주문 시점 스냅샷이어야 한다</b> — 가격이 오른 뒤
 * 과거 주문을 열었을 때 결제한 적 없는 금액이 보이면 안 된다.
 */
public record OrderSummaryResponse(
        Long orderId,
        Long productId,
        String productName,
        ProductType type,
        int quantity,
        int pointAmount,
        OrderStatus status,
        LocalDateTime orderedAt) {

    public static OrderSummaryResponse of(StoreOrder order, Product product) {
        return new OrderSummaryResponse(
                order.getId(),
                order.getProductId(),
                product.getName(),
                product.getType(),
                order.getQuantity(),
                order.getPointAmount(),
                order.getStatus(),
                order.getOrderedAt());
    }
}
