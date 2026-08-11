package com.morak.proof.entity;

import com.morak.proof.type.ProofAiStatus;
import com.morak.proof.type.ProofMethod;
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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 하루치 인증 제출 기록.
 *
 * <p>제출 직후 상태는 항상 {@link ProofAiStatus#SCREENING}이다. AI 호출은 트랜잭션 밖에서
 * 수행하고 그 결과를 별도 트랜잭션에서 반영하므로, 행이 먼저 존재하고 판정이 나중에 붙는다.
 *
 * <p>재업로드는 기존 행을 고치지 않고 새 행을 INSERT한다. 구 행에는 {@link #supersededBy}로
 * 새 행만 가리키게 한다.
 */
@Entity
@Table(
        name = "proof",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_proof_daily",
                columnNames = {"group_id", "proof_date", "daily_slot"}),
        indexes = {
                @Index(name = "idx_proof_date", columnList = "group_id, proof_date"),
                @Index(name = "idx_proof_phash", columnList = "member_id, image_phash")
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Proof {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "group_id", nullable = false)
    private Long groupId;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Column(name = "proof_date", nullable = false)
    private LocalDate proofDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ProofMethod method;

    @Enumerated(EnumType.STRING)
    @Column(name = "ai_status", nullable = false, length = 20)
    private ProofAiStatus aiStatus;

    /** 정확 일치 대조용. 해밍 거리 비교는 member로 좁힌 뒤 애플리케이션이 계산한다. */
    @Column(name = "image_phash")
    private Long imagePhash;

    @Column(name = "superseded_by_id")
    private Long supersededById;

    @Column(name = "hidden_by_case_id")
    private Long hiddenByCaseId;

    @Column(name = "hidden_by_admin_id")
    private Long hiddenByAdminId;

    @Column(name = "hidden_at")
    private LocalDateTime hiddenAt;

    /** 마감 판정 기준은 AI 완료 시각이 아니라 접수 시각이다. */
    @Column(name = "submitted_at", nullable = false)
    private LocalDateTime submittedAt;

    /**
     * DB가 계산하는 생성 컬럼. 애플리케이션은 절대 값을 쓰지 않는다.
     *
     * <p>{@code uk_proof_daily}(group_id, proof_date, daily_slot)로 "하루에 유효한 인증 1건"을
     * 강제하되, SCREENING·HOLD·BLOCKED는 NULL이 되어 슬롯을 점유하지 않는다. 이유는 두 가지다.
     * ① AI 처리 중 서버가 죽어 SCREENING 행이 남아도 그날 인증이 409로 영구히 잠기지 않는다.
     * ② 검열 오탐 한 번(BLOCKED)이 그날 인증을 영구 상실시키지 않는다.
     *
     * <p>반대로 APPROVED·PENDING_REVIEW는 슬롯을 점유한다. PENDING_REVIEW가 점유하는 것은
     * 의도된 것으로, 관리자 판단 전에 같은 날 재제출이 끼어드는 것을 막는다.
     */
    @Column(name = "daily_slot", insertable = false, updatable = false,
            columnDefinition = "BIGINT GENERATED ALWAYS AS "
                    + "(CASE WHEN ai_status IN ('APPROVED','PENDING_REVIEW') THEN member_id ELSE NULL END)")
    private Long dailySlot;

    private Proof(Long groupId, Long memberId, LocalDate proofDate, ProofMethod method,
                  Long imagePhash, LocalDateTime submittedAt) {
        this.groupId = groupId;
        this.memberId = memberId;
        this.proofDate = proofDate;
        this.method = method;
        this.imagePhash = imagePhash;
        this.aiStatus = ProofAiStatus.SCREENING;
        this.submittedAt = submittedAt;
    }

    public static Proof submit(Long groupId, Long memberId, LocalDate proofDate, ProofMethod method,
                               Long imagePhash, LocalDateTime submittedAt) {
        return new Proof(groupId, memberId, proofDate, method, imagePhash, submittedAt);
    }

    /** 검열·진위 통과. */
    public void approve() {
        requireScreening();
        this.aiStatus = ProofAiStatus.APPROVED;
    }

    /** 진위 판정이 경계 구간이라 관리자 확인이 필요한 경우. 슬롯을 점유한다. */
    public void requestReview() {
        requireScreening();
        this.aiStatus = ProofAiStatus.PENDING_REVIEW;
    }

    /** 진위 미달(재촬영 안내) 또는 B7의 판정 지연 회수. */
    public void hold() {
        requireScreening();
        this.aiStatus = ProofAiStatus.HOLD;
    }

    /** 검열 차단. 미디어는 legal hold 대상이 된다. */
    public void block() {
        requireScreening();
        this.aiStatus = ProofAiStatus.BLOCKED;
    }

    /** AD-7 CONFIRM: 관리자가 AI 판정을 유지한다. */
    public void confirmReview() {
        requirePendingReview();
        this.aiStatus = ProofAiStatus.HOLD;
    }

    /** AD-7 OVERRIDE: 관리자가 AI 판정을 뒤집어 인정한다. */
    public void overrideReview() {
        requirePendingReview();
        this.aiStatus = ProofAiStatus.APPROVED;
    }

    /** AD-6 hide: 신고 케이스 근거로 노출을 내린다. */
    public void hide(Long caseId, Long adminId, LocalDateTime now) {
        this.aiStatus = ProofAiStatus.BLOCKED;
        this.hiddenByCaseId = caseId;
        this.hiddenByAdminId = adminId;
        this.hiddenAt = now;
    }

    /**
     * AD-6 unhide. 이미 재업로드로 대체된 건이면 APPROVED로 되돌릴 수 없다.
     *
     * <p>슬롯을 되찾으면 그날 유효 인증이 두 건이 되어 uk_proof_daily를 위반하기 때문이다.
     */
    public void unhide() {
        this.aiStatus = this.supersededById == null ? ProofAiStatus.APPROVED : ProofAiStatus.HOLD;
        this.hiddenByCaseId = null;
        this.hiddenByAdminId = null;
        this.hiddenAt = null;
    }

    /**
     * 재업로드된 새 인증을 가리킨다. 상태는 그대로 둔다.
     *
     * <p>구 행을 UPDATE로 되살리지 않고 포인터만 남기는 이유는, 차단된 원본과 그 뒤에 올라온
     * 대체본의 감사 연결을 보존해야 하기 때문이다. 원본을 덮으면 무엇이 왜 차단됐는지 사라진다.
     */
    public void supersededBy(Long newProofId) {
        this.supersededById = newProofId;
    }

    public boolean isHidden() {
        return this.hiddenAt != null;
    }

    /** 완주율 집계에서 인증으로 세는 상태. */
    public boolean isApproved() {
        return this.aiStatus == ProofAiStatus.APPROVED;
    }

    private void requireScreening() {
        if (this.aiStatus != ProofAiStatus.SCREENING) {
            throw new IllegalStateException("이미 판정된 인증입니다: " + this.aiStatus);
        }
    }

    private void requirePendingReview() {
        if (this.aiStatus != ProofAiStatus.PENDING_REVIEW) {
            throw new IllegalStateException("검토 대기 상태가 아닙니다: " + this.aiStatus);
        }
    }
}
