package com.morak.store.repository;

import com.morak.store.entity.Product;
import com.morak.store.type.ProductStatus;
import com.morak.store.type.ProductType;
import java.util.Collection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductRepository extends JpaRepository<Product, Long> {

    /**
     * SR-1 목록. 감출 상태를 제외(<code>&lt;&gt; HIDDEN</code>)하지 않고 보일 상태를 나열하는
     * 이유는 {@code idx_pd_list(status, type)}를 타기 위해서다. 상태가 늘어나면 노출 대상을
     * 여기 한 곳에서 정한다.
     */
    Page<Product> findByStatusIn(Collection<ProductStatus> statuses, Pageable pageable);

    Page<Product> findByStatusInAndType(Collection<ProductStatus> statuses, ProductType type,
                                        Pageable pageable);

    /**
     * SR-3 재고 차감. <b>재고를 읽고 비교한 뒤 깎으면 동시 주문 두 건이 같은 재고를 보고 둘 다
     * 통과해 재고가 음수가 된다.</b> 조건을 UPDATE 안에 넣어야 DB가 행을 잠근 상태로 판정한다.
     *
     * <p>{@code status = ON_SALE}까지 조건에 넣은 것은 조회와 차감 사이에 상품이 감춰지거나
     * 품절 처리되는 경우 때문이다. 서비스의 상태 검사는 에러 코드를 갈라 주는 지름길이고,
     * 실제 방어선은 이 조건이다.
     *
     * <p>영향 행 0은 재고 부족이다 → 409 {@code OUT_OF_STOCK}.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Product p
               SET p.stock = p.stock - :quantity
             WHERE p.id = :productId
               AND p.status = :onSale
               AND p.stock >= :quantity
            """)
    int decreaseStock(@Param("productId") Long productId, @Param("quantity") int quantity,
                      @Param("onSale") ProductStatus onSale);
}
