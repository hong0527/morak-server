package com.morak.ai.entity;

import com.morak.ai.type.AiReviewStatus;
import com.morak.ai.type.AiReviewType;
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
 * 관리자 검토 큐. AI가 스스로 끝내지 못한 판정이 관리자에게 도달하는 유일한 경로다.
 *
 * <p>{@code judgmentId}는 UNIQUE 키에서 제외한다. 같은 대상을 다시 판정하면 judgment는 새로
 * 생기지만 큐 항목은 여전히 한 건이어야 하기 때문이다.
 */
@Entity
@Table(
        name = "ai_review_queue",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_arq",
                columnNames = {"type", "target_id", "member_key"}),
        indexes = @Index(
                name = "idx_arq_status",
                columnList = "status, type, created_at"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AiReviewQueue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AiReviewType type;

    /** proof.id 또는 challenge_group.id. 어느 쪽인지는 {@link #type}이 정한다. */
    @Column(name = "target_id", nullable = false)
    private Long targetId;

    /** COMPLETION 개인 경계 건의 대상 회원. 그룹 경계 건은 NULL이다. */
    @Column(name = "member_id")
    private Long memberId;

    /**
     * DB가 계산하는 생성 컬럼. 애플리케이션은 절대 값을 쓰지 않는다.
     *
     * <p>{@code uk_arq}(type, target_id, member_key)는 B1을 여러 번 돌려도 같은 항목이 중복
     * 적재되지 않게 하는 멱등성 장치다. member_id를 그대로 키에 넣으면 NULL 행끼리는 UNIQUE가
     * 걸리지 않아 그룹 경계 건이 실행 횟수만큼 쌓인다. COALESCE로 0을 채워 이를 막는다.
     */
    @Column(name = "member_key", insertable = false, updatable = false,
            columnDefinition = "BIGINT GENERATED ALWAYS AS (COALESCE(member_id, 0))")
    private Long memberKey;

    /** B1도 COMPLETION 판정을 ai_judgment에 먼저 INSERT한 뒤 그 id로 적재한다. */
    @Column(name = "judgment_id", nullable = false)
    private Long judgmentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AiReviewStatus status;

    @Column(name = "admin_id")
    private Long adminId;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    private AiReviewQueue(AiReviewType type, Long targetId, Long memberId, Long judgmentId,
                          LocalDateTime createdAt) {
        this.type = type;
        this.targetId = targetId;
        this.memberId = memberId;
        this.judgmentId = judgmentId;
        this.status = AiReviewStatus.PENDING;
        this.createdAt = createdAt;
    }

    /** 대상 자체를 검토 대기시킨다(인증 검열·진위, 그룹 완주 경계). */
    public static AiReviewQueue enqueue(AiReviewType type, Long targetId, Long judgmentId,
                                        LocalDateTime createdAt) {
        return new AiReviewQueue(type, targetId, null, judgmentId, createdAt);
    }

    /** 같은 그룹 안에서 특정 회원의 완주 경계만 검토 대기시킨다. */
    public static AiReviewQueue enqueueForMember(AiReviewType type, Long targetId, Long memberId,
                                                 Long judgmentId, LocalDateTime createdAt) {
        return new AiReviewQueue(type, targetId, memberId, judgmentId, createdAt);
    }

    /** 관리자가 AI 판정을 유지한다. */
    public void confirm(Long adminId, LocalDateTime now) {
        decide(AiReviewStatus.CONFIRMED, adminId, now);
    }

    /** 관리자가 AI 판정을 뒤집는다. */
    public void overrule(Long adminId, LocalDateTime now) {
        decide(AiReviewStatus.OVERRIDDEN, adminId, now);
    }

    public boolean isPending() {
        return this.status == AiReviewStatus.PENDING;
    }

    private void decide(AiReviewStatus decision, Long adminId, LocalDateTime now) {
        if (this.status != AiReviewStatus.PENDING) {
            throw new IllegalStateException("이미 처리된 검토 항목입니다: " + this.status);
        }
        this.status = decision;
        this.adminId = adminId;
        this.decidedAt = now;
    }
}
