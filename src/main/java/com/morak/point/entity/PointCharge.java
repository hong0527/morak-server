package com.morak.point.entity;

import com.morak.point.type.ChargeStatus;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * PG 포인트 충전 건. 패키지가 point인 것은 충전과 웹훅이 포인트 잔액을 움직이는 경로이기
 * 때문이다 — payment는 별도 도메인이 아니다.
 *
 * <p>{@code uk_pc_tid}가 PY-2 확인 응답과 PY-3 웹훅이 같은 거래를 중복 적립하는 것을 막는다.
 * {@code pgTid}가 NULL을 허용하는 것은 승인 전 READY 행 여러 건이 공존해야 하기 때문이고,
 * UNIQUE 안의 NULL이 서로 다른 값으로 취급되므로 READY끼리는 충돌하지 않는다.
 * 이것이 뚫려도 {@code point_ledger}의 UNIQUE가 2차 방어선이다.
 *
 * <p>승인 금액은 PG가 알려준 값을 믿는다. 클라이언트가 보낸 금액과 다르면 적립하지 않는다.
 */
@Entity
@Table(
        name = "point_charge",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_pc_order", columnNames = "pg_order_id"),
                @UniqueConstraint(name = "uk_pc_tid", columnNames = "pg_tid")
        },
        indexes = @Index(
                name = "idx_pc_member",
                columnList = "member_id, created_at"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PointCharge {

    private static final String PG_ORDER_ID_PREFIX = "molock-chg-";
    private static final DateTimeFormatter PG_ORDER_ID_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Column(name = "amount_krw", nullable = false)
    private int amountKrw;

    @Column(name = "point_amount", nullable = false)
    private int pointAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ChargeStatus status;

    /** 서버가 채번해 PG에 전달하는 우리 쪽 키. */
    @Column(name = "pg_order_id", nullable = false, length = 64)
    private String pgOrderId;

    /** PG가 발급하는 거래 식별자. 승인 후에 채워진다. */
    @Column(name = "pg_tid", length = 64)
    private String pgTid;

    /** PY-1 생성 시각. PG 대사 기준이자 READY 방치 건 정리 기준. */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    private PointCharge(Long memberId, int amountKrw, int pointAmount, LocalDateTime createdAt) {
        this.memberId = memberId;
        this.amountKrw = amountKrw;
        this.pointAmount = pointAmount;
        this.status = ChargeStatus.READY;
        // 주문번호에 충전 건 번호를 넣는데 id는 INSERT가 끝나야 정해진다. 컬럼이 NOT NULL·
        // UNIQUE라 빈 값으로 넣을 수 없어 임시 유일값을 넣고 같은 트랜잭션에서
        // assignPgOrderId()가 덮는다(LiveSession의 방 이름과 같은 방식).
        this.pgOrderId = UUID.randomUUID().toString();
        this.createdAt = createdAt;
    }

    public static PointCharge ready(Long memberId, int amountKrw, int pointAmount,
                                    LocalDateTime createdAt) {
        return new PointCharge(memberId, amountKrw, pointAmount, createdAt);
    }

    /** 저장 직후 같은 트랜잭션에서 호출한다. 주문번호 규칙은 이 클래스에만 둔다. */
    public void assignPgOrderId() {
        if (this.id == null) {
            throw new IllegalStateException("id가 정해진 뒤에 주문번호를 붙여야 한다");
        }
        this.pgOrderId = pgOrderIdOf(this.createdAt.toLocalDate(), this.id);
    }

    /**
     * PG 대사의 기준 키다. 생성일과 충전 건 번호를 그대로 담아, PG 콘솔에 찍힌 주문번호
     * 하나만으로 우리 쪽 행을 되짚을 수 있게 한다.
     */
    public static String pgOrderIdOf(LocalDate createdOn, Long chargeId) {
        return PG_ORDER_ID_PREFIX + createdOn.format(PG_ORDER_ID_DATE) + "-"
                + "%06d".formatted(chargeId);
    }

    /**
     * 승인. status·pgTid·approvedAt은 항상 함께 확정된다.
     *
     * <p>READY에서만 넘어갈 수 있다. PY-2 확인과 PY-3 웹훅이 같은 거래를 중복 전달하므로
     * 가드가 없으면 이미 승인된 건의 pgTid를 덮어써 대사가 어긋난다.
     */
    public void approve(String pgTid, LocalDateTime approvedAt) {
        requireReady();
        this.status = ChargeStatus.APPROVED;
        this.pgTid = pgTid;
        this.approvedAt = approvedAt;
    }

    /** 승인된 건을 실패로 되돌리는 경로는 없다. */
    public void fail() {
        requireReady();
        this.status = ChargeStatus.FAILED;
    }

    private void requireReady() {
        if (this.status != ChargeStatus.READY) {
            throw new IllegalStateException("승인 대기 중인 충전이 아니다: " + this.status);
        }
    }

    public boolean isApproved() {
        return this.status == ChargeStatus.APPROVED;
    }
}
