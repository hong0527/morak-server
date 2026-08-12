package com.morak.store.controller;

import com.morak.common.dto.PageResponse;
import com.morak.store.dto.response.ProductDetailResponse;
import com.morak.store.dto.response.ProductSummaryResponse;
import com.morak.store.service.ProductService;
import com.morak.store.type.ProductType;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** SR-1·SR-2 상품 조회. */
@RestController
@RequestMapping("/api/store/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    @GetMapping
    public PageResponse<ProductSummaryResponse> getProducts(
            @RequestParam(required = false) ProductType type,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return productService.getProducts(type, page, size);
    }

    @GetMapping("/{productId}")
    public ProductDetailResponse getProduct(@PathVariable Long productId) {
        return productService.getProduct(productId);
    }
}
