package com.morak.store.dto.response;

import com.morak.store.entity.Product;
import com.morak.store.entity.StoreOrder;
import com.morak.store.type.OrderStatus;
import java.time.LocalDateTime;

/**
 * SR-3 주문 접수 응답. openapi.yaml OrderCreateResponse 스키마와 1:1 대응.
 *
 * <p>{@code pointBalance}를 함께 내리는 이유는 주문 직후 화면이 잔액을 다시 묻지 않아도
 * 되게 하기 위해서다. 차감 후 값이다.
 */
public record OrderCreateResponse(
        Long orderId,
        Long productId,
        String productName,
        int quantity,
        int pointAmount,
        OrderStatus status,
        LocalDateTime orderedAt,
        int pointBalance) {

    public static OrderCreateResponse of(StoreOrder order, Product product, int pointBalance) {
        return new OrderCreateResponse(
                order.getId(),
                order.getProductId(),
                product.getName(),
                order.getQuantity(),
                order.getPointAmount(),
                order.getStatus(),
                order.getOrderedAt(),
                pointBalance);
    }
}
