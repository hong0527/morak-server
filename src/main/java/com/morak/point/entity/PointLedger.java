package com.morak.point.entity;

import com.morak.point.type.PointReason;
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
 * 포인트 원장. 잔액의 진실은 여기 있고 {@code member.point_balance}는 캐시일 뿐이다.
 * 둘이 어긋나면 원장이 옳다.
 *
 * <p>{@code uk_pl_dedup(member_id, reason, ref_type, ref_id)}가 중복 지급의 유일한 방어선이다.
 * B1 재실행·PG 웹훅 중복 수신·주문 더블클릭이 전부 이 하나의 제약에서 막힌다. "먼저 조회하고
 * 없으면 INSERT"는 동시 실행에서 둘 다 통과하므로 서비스 코드로는 막을 수 없다.
 *
 * <p>{@code refType}·{@code refId}가 NOT NULL인 것은 필수다. MySQL·H2 모두 UNIQUE 안의 NULL을
 * 서로 다른 값으로 보기 때문에, NULL을 허용하는 순간 그 사유의 중복 방어가 통째로 사라진다.
 * 사유마다 참조 대상이 하나로 정해져 있어({@link #refTypeOf}) 호출부가 짝을 틀릴 수 없다.
 *
 * <p>정정은 UPDATE·DELETE가 아니라 반대 부호의 새 행(역분개)이다. 기록된 행은 수정하지 않는다.
 * 통화는 하나뿐이고 "스파크 포인트"는 별도 잔액이 아니라 {@code GOAL_ACHIEVED} 라벨이다(★D5).
 */
@Entity
@Table(
        name = "point_ledger",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_pl_dedup",
                columnNames = {"member_id", "reason", "ref_type", "ref_id"}),
        indexes = @Index(
                name = "idx_pl_member",
                columnList = "member_id, created_at"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PointLedger {

    public static final String REF_MEMBER = "MEMBER";
    public static final String REF_SESSION_PARTICIPANT = "SESSION_PARTICIPANT";
    public static final String REF_EVICTION = "EVICTION";
    public static final String REF_GOAL = "GOAL";
    public static final String REF_ORDER = "ORDER";
    public static final String REF_CHARGE = "CHARGE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    /** 부호 있는 증감. 0은 기록하지 않는다. */
    @Column(nullable = false)
    private int delta;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private PointReason reason;

    @Column(name = "ref_type", nullable = false, length = 30)
    private String refType;

    @Column(name = "ref_id", nullable = false)
    private Long refId;

    /** 감사용 스냅샷. 잔액 계산의 근거가 아니라 검증 재료다. */
    @Column(name = "balance_after", nullable = false)
    private int balanceAfter;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    private PointLedger(Long memberId, int delta, PointReason reason, String refType, Long refId,
                        int balanceAfter, LocalDateTime createdAt) {
        this.memberId = memberId;
        this.delta = delta;
        this.reason = reason;
        this.refType = refType;
        this.refId = refId;
        this.balanceAfter = balanceAfter;
        this.createdAt = createdAt;
    }

    /**
     * 원장 기록. {@code refType}을 호출부에서 받지 않는 이유는 사유별 참조 대상이 규약으로
     * 고정돼 있어서다 — 짝이 어긋나면 멱등키가 달라져 중복 지급이 뚫린다.
     */
    public static PointLedger record(Long memberId, int delta, PointReason reason, Long refId,
                                     int balanceAfter, LocalDateTime createdAt) {
        if (delta == 0) {
            throw new IllegalArgumentException("증감이 0인 원장은 기록하지 않는다");
        }
        return new PointLedger(memberId, delta, reason, refTypeOf(reason), refId, balanceAfter,
                createdAt);
    }

    /**
     * 사유별 ref 규약(db-schema). 제약과 함께 중복 지급을 두 겹으로 막는다.
     *
     * <p>공개하는 이유는 멱등 검사가 같은 규약을 봐야 하기 때문이다 — 조회가 다른 refType으로
     * 물으면 "없다"는 답을 받고 INSERT까지 가서 제약에 부딪힌다.
     */
    public static String refTypeOf(PointReason reason) {
        return switch (reason) {
            case WELCOME -> REF_MEMBER;
            case SESSION_COMPLETE -> REF_SESSION_PARTICIPANT;
            case EVICTION_PENALTY, APPEAL_REFUND -> REF_EVICTION;
            case GOAL_ACHIEVED -> REF_GOAL;
            case ORDER_SPEND, ORDER_CANCEL -> REF_ORDER;
            case CHARGE -> REF_CHARGE;
        };
    }
}
