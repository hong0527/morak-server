package com.morak.store.type;

// HIDDEN은 목록·상세 모두에서 없는 것으로 취급한다(SR-2·SR-3 → 404 PRODUCT_NOT_FOUND).
// SOLD_OUT은 노출은 되지만 주문이 막힌다.
public enum ProductStatus {
    ON_SALE, SOLD_OUT, HIDDEN
}
