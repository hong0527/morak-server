package com.morak.store.entity;

import com.morak.store.type.ProductStatus;
import com.morak.store.type.ProductType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 스토어 상품. 결제 수단은 포인트 전액뿐이라 원화 가격 컬럼이 없다(D16).
 *
 * <p>재고 차감은 조건부 UPDATE({@code WHERE stock >= ?})로만 한다. 조회 후 차감하는 2단계는
 * 동시 주문에서 재고를 음수로 만든다.
 *
 * <p>가격은 주문 시점에 {@code store_order.point_amount}로 스냅샷되므로, 여기 값을 바꿔도
 * 과거 주문 금액은 흔들리지 않는다.
 */
@Entity
@Table(
        name = "product",
        indexes = @Index(
                name = "idx_pd_list",
                columnList = "status, type"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ProductType type;

    @Column(nullable = false, length = 100)
    private String name;

    /** SR-2 상세에서만 쓴다. SR-1 목록 응답에는 싣지 않는다. */
    @Column(length = 500)
    private String description;

    /** 외부 URL 문자열만 보관한다. 이미지 업로드·저장 경로는 v1에 없다. */
    @Column(name = "image_url", length = 500)
    private String imageUrl;

    @Column(name = "price_point", nullable = false)
    private int pricePoint;

    @Column(nullable = false)
    private int stock;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ProductStatus status;

    private Product(ProductType type, String name, String description, String imageUrl,
                    int pricePoint, int stock) {
        this.type = type;
        this.name = name;
        this.description = description;
        this.imageUrl = imageUrl;
        this.pricePoint = pricePoint;
        this.stock = stock;
        this.status = ProductStatus.ON_SALE;
    }

    public static Product onSale(ProductType type, String name, String description,
                                 String imageUrl, int pricePoint, int stock) {
        return new Product(type, name, description, imageUrl, pricePoint, stock);
    }

    public void markSoldOut() {
        this.status = ProductStatus.SOLD_OUT;
    }

    /** 목록에서 감춘다. 기존 주문은 유지된다. */
    public void hide() {
        this.status = ProductStatus.HIDDEN;
    }

    public boolean isOrderable() {
        return this.status == ProductStatus.ON_SALE;
    }
}
