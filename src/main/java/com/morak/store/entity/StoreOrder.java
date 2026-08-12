package com.morak.store.entity;

import com.morak.store.type.OrderStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 주문 접수. {@code uk_so_idem}이 버튼 더블클릭과 네트워크 재시도로 인한 이중 차감을 막는다.
 * 클라이언트는 주문 화면 진입 시 키를 만들어 재시도까지 같은 값으로 보낸다.
 *
 * <p>주문 트랜잭션은 셋을 함께 한다 — 재고 조건부 차감, 포인트 조건부 차감 + 원장 기록,
 * 이 행 생성. 하나라도 실패하면 전부 롤백이다.
 *
 * <p>이 테이블은 주문 접수까지만 담는다. 발송 코드·수령 연락처·배송 상태 컬럼이 없는 것은
 * 누락이 아니라 절단선이다. 이행 정보를 붙이면 접수 기록(법정 보존)과 이행 기록(개인정보
 * 최소 보관)의 수명이 뒤엉킨다. v1에서 주문 이후 처리는 운영자가 서비스 밖에서 한다.
 */
@Entity
@Table(
        name = "store_order",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_so_idem",
                columnNames = "idempotency_key"),
        indexes = @Index(
                name = "idx_so_member",
                columnList = "member_id, ordered_at"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StoreOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(nullable = false)
    private int quantity;

    /** 총 차감 포인트 스냅샷. 상품 가격이 바뀌어도 고정이다. */
    @Column(name = "point_amount", nullable = false)
    private int pointAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus status;

    @Column(name = "idempotency_key", nullable = false, length = 64)
    private String idempotencyKey;

    @Column(name = "ordered_at", nullable = false)
    private LocalDateTime orderedAt;

    private StoreOrder(Long memberId, Long productId, int quantity, int pointAmount,
                       String idempotencyKey, LocalDateTime orderedAt) {
        this.memberId = memberId;
        this.productId = productId;
        this.quantity = quantity;
        this.pointAmount = pointAmount;
        this.status = OrderStatus.ORDERED;
        this.idempotencyKey = idempotencyKey;
        this.orderedAt = orderedAt;
    }

    public static StoreOrder place(Long memberId, Long productId, int quantity, int pointAmount,
                                   String idempotencyKey, LocalDateTime orderedAt) {
        return new StoreOrder(memberId, productId, quantity, pointAmount, idempotencyKey,
                orderedAt);
    }

    /**
     * 관리자 취소. 환불(FR-506)은 v2 보류라 이 경로뿐이다. 금액은 건드리지 않는다 —
     * 되돌리기는 원장의 ORDER_CANCEL 역분개와 재고 복구로 한다.
     */
    public void cancel() {
        if (this.status != OrderStatus.ORDERED) {
            throw new IllegalStateException("이미 취소된 주문이다: " + this.status);
        }
        this.status = OrderStatus.CANCELLED;
    }
}
