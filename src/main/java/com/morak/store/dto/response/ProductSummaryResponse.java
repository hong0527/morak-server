package com.morak.store.dto.response;

import com.morak.store.entity.Product;
import com.morak.store.type.ProductStatus;
import com.morak.store.type.ProductType;

/**
 * SR-1 목록 한 줄. openapi.yaml ProductSummary 스키마와 1:1 대응.
 *
 * <p>{@code description}·{@code imageUrl}이 빠진 것은 누락이 아니다. 목록은 20건씩 내려가는데
 * 상세 설명까지 실으면 화면이 쓰지 않는 데이터가 페이지마다 따라온다 — 상세는 SR-2가 준다.
 */
public record ProductSummaryResponse(
        Long productId,
        ProductType type,
        String name,
        int pricePoint,
        int stock,
        ProductStatus status) {

    public static ProductSummaryResponse from(Product product) {
        return new ProductSummaryResponse(
                product.getId(),
                product.getType(),
                product.getName(),
                product.getPricePoint(),
                product.getStock(),
                product.getStatus());
    }
}
