package com.morak.session.entity;

import com.morak.session.type.AppealStatus;
import com.morak.session.type.DecidedBy;
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
 * 퇴출 이의신청. 퇴출 1건당 1회이고({@code uk_ap_eviction}) 신청 자격은 퇴출 당사자뿐이다.
 *
 * <p>지연(overdue)은 저장하지 않고 조회 시점에 파생한다(PENDING AND {@code slaDueAt < now}).
 * {@code report_case}와 같은 규칙이라 신고 큐와 이의 큐의 지연 판정이 하나의 식을 쓴다.
 *
 * <p>{@code reasonText}(신청자 진술)와 {@code note}(관리자 판단)는 서로 덮어쓰지 않는다.
 * 전자는 관리자가 판단할 유일한 당사자 진술이라 NOT NULL이고, AD-6 처리는 후자만 채운다.
 *
 * <p>인용(ACCEPTED) 처리는 셋을 함께 한다 — {@code eviction.revokedAt} 기록, 포인트 역분개,
 * 그날 완주 소급 재판정에 따른 {@code streak_day} INSERT. 종결된 이의는 재오픈하지 않는다.
 */
@Entity
@Table(
        name = "appeal_case",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_ap_eviction",
                columnNames = "eviction_id"),
        indexes = @Index(
                name = "idx_ap_queue",
                columnList = "status, sla_due_at"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AppealCase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "eviction_id", nullable = false)
    private Long evictionId;

    /** 신청자 = 퇴출 당사자. */
    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AppealStatus status;

    @Column(name = "reason_text", nullable = false, length = 200)
    private String reasonText;

    /** 접수 시각. 감사 기준이자 slaDueAt의 계산 원점이라 사후 검증이 가능하다. */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "sla_due_at", nullable = false)
    private LocalDateTime slaDueAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "decided_by", length = 20)
    private DecidedBy decidedBy;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    /** 관리자 처리 사유. 신청자의 reasonText와 구분한다. */
    @Column(length = 1000)
    private String note;

    private AppealCase(Long evictionId, Long memberId, String reasonText,
                       LocalDateTime createdAt, LocalDateTime slaDueAt) {
        this.evictionId = evictionId;
        this.memberId = memberId;
        this.status = AppealStatus.PENDING;
        this.reasonText = reasonText;
        this.createdAt = createdAt;
        this.slaDueAt = slaDueAt;
    }

    public static AppealCase file(Long evictionId, Long memberId, String reasonText,
                                  LocalDateTime createdAt, LocalDateTime slaDueAt) {
        return new AppealCase(evictionId, memberId, reasonText, createdAt, slaDueAt);
    }

    /** AD-6 처리. 인용이든 기각이든 되돌리지 않는다. */
    public void decide(AppealStatus decision, DecidedBy decidedBy, String note,
                       LocalDateTime decidedAt) {
        if (decision == AppealStatus.PENDING) {
            throw new IllegalArgumentException("종결 상태가 아니다: " + decision);
        }
        if (this.status != AppealStatus.PENDING) {
            throw new IllegalStateException("이미 종결된 이의다: " + this.status);
        }
        this.status = decision;
        this.decidedBy = decidedBy;
        this.note = note;
        this.decidedAt = decidedAt;
    }

    /** 저장 컬럼이 아니라 조회 시점 계산이다. 마킹 배치가 없는 이유는 클래스 주석 참조. */
    public boolean isOverdue(LocalDateTime now) {
        return this.status == AppealStatus.PENDING && this.slaDueAt.isBefore(now);
    }
}
