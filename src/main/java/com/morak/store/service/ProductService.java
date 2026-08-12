package com.morak.store.service;

import com.morak.common.dto.PageParams;
import com.morak.common.dto.PageResponse;
import com.morak.common.error.BusinessException;
import com.morak.common.error.ErrorCode;
import com.morak.store.dto.response.ProductDetailResponse;
import com.morak.store.dto.response.ProductSummaryResponse;
import com.morak.store.entity.Product;
import com.morak.store.repository.ProductRepository;
import com.morak.store.type.ProductStatus;
import com.morak.store.type.ProductType;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SR-1·SR-2 상품 조회.
 *
 * <p><b>{@code HIDDEN}은 목록에서 빼는 것만으로 부족하다.</b> 상세를 직접 호출하면 감춘 상품이
 * 그대로 보이므로 SR-2도 404 {@code PRODUCT_NOT_FOUND}로 막는다. 감춰진 것과 없는 것을 같은
 * 응답으로 답하는 이유는 둘을 구분해 주면 id를 훑어 미공개 상품의 존재를 알아낼 수 있어서다.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class ProductService {

    /** SR-1에 실리는 상태. HIDDEN만 빠진다 — 품절은 감추지 않고 상태로 알린다. */
    private static final Set<ProductStatus> VISIBLE =
            Set.of(ProductStatus.ON_SALE, ProductStatus.SOLD_OUT);

    /**
     * 등록순. 정렬 기준이 없으면 페이지마다 순서가 달라져 같은 상품이 두 번 보이거나 빠진다.
     * 노출 우선순위는 팀이 상품 목록을 확정한 뒤에 정한다.
     */
    private static final Sort PRODUCT_SORT = Sort.by(Sort.Direction.ASC, "id");

    private final ProductRepository productRepository;

    public PageResponse<ProductSummaryResponse> getProducts(ProductType type, Integer page,
                                                            Integer size) {
        PageParams params = PageParams.of(page, size);
        Page<Product> products = type == null
                ? productRepository.findByStatusIn(VISIBLE, params.toPageable(PRODUCT_SORT))
                : productRepository.findByStatusInAndType(VISIBLE, type,
                        params.toPageable(PRODUCT_SORT));
        return PageResponse.of(products, ProductSummaryResponse::from);
    }

    public ProductDetailResponse getProduct(Long productId) {
        return ProductDetailResponse.from(findVisible(productId));
    }

    private Product findVisible(Long productId) {
        return productRepository.findById(productId)
                .filter(product -> VISIBLE.contains(product.getStatus()))
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));
    }
}
