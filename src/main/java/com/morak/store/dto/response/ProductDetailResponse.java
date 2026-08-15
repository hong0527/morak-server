package com.morak.store.dto.response;

import com.morak.store.entity.Product;
import com.morak.store.type.ProductStatus;
import com.morak.store.type.ProductType;

/**
 * SR-2 상품 상세. openapi.yaml ProductDetail 스키마와 1:1 대응.
 *
 * <p>{@code description}·{@code imageUrl}은 등록되지 않았으면 {@code null}로 내려간다.
 */
public record ProductDetailResponse(
        Long productId,
        ProductType type,
        String name,
        String description,
        String imageUrl,
        int pricePoint,
        int stock,
        ProductStatus status) {

    public static ProductDetailResponse from(Product product) {
        return new ProductDetailResponse(
                product.getId(),
                product.getType(),
                product.getName(),
                product.getDescription(),
                product.getImageUrl(),
                product.getPricePoint(),
                product.getStock(),
                product.getStatus());
    }
}
