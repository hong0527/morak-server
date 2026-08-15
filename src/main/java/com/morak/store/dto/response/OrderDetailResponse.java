package com.morak.store.dto.response;

import com.morak.store.entity.Product;
import com.morak.store.entity.StoreOrder;
import com.morak.store.type.OrderStatus;
import com.morak.store.type.ProductType;
import java.time.LocalDateTime;

/**
 * SR-5 주문 상세. openapi.yaml OrderDetail 스키마(OrderSummary + 멱등키·원장 참조)와 1:1 대응.
 *
 * <p>{@code pointLedgerId}는 이 주문의 {@code ORDER_SPEND} 원장 행이다. 화면이 주문에서
 * 포인트 내역으로 건너갈 수 있게 하는 값이라, 원장을 못 찾으면 {@code null}로 내려간다 —
 * 정상 주문에는 언제나 짝이 되는 행이 있다(같은 트랜잭션에서 함께 만들어진다).
 */
public record OrderDetailResponse(
        Long orderId,
        Long productId,
        String productName,
        ProductType type,
        int quantity,
        int pointAmount,
        OrderStatus status,
        LocalDateTime orderedAt,
        String idempotencyKey,
        Long pointLedgerId) {

    public static OrderDetailResponse of(StoreOrder order, Product product, Long pointLedgerId) {
        return new OrderDetailResponse(
                order.getId(),
                order.getProductId(),
                product.getName(),
                product.getType(),
                order.getQuantity(),
                order.getPointAmount(),
                order.getStatus(),
                order.getOrderedAt(),
                order.getIdempotencyKey(),
                pointLedgerId);
    }
}
