package com.morak.proof.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 인증 촬영물. proof와 1:1이라 별도 식별자 없이 proof_id를 PK로 쓴다.
 *
 * <p>{@code delete_scheduled_at}은 삭제 예정 시각, {@code deleted_at}은 실제 삭제 시각이다.
 * B5는 둘을 함께 보고 예정일이 지났으면서 아직 삭제되지 않은 행만 처리한다.
 */
@Entity
@Table(
        name = "proof_media",
        indexes = @Index(
                name = "idx_pm_delete",
                columnList = "deleted_at, delete_scheduled_at"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProofMedia {

    @Id
    @Column(name = "proof_id")
    private Long proofId;

    /** {groupId}/{proofId}/{uuid}.{ext} */
    @Column(name = "storage_key", nullable = false, length = 500)
    private String storageKey;

    /** 확장자가 아니라 매직바이트 검사 결과다. */
    @Column(name = "content_type", nullable = false, length = 50)
    private String contentType;

    @Column(name = "file_size", nullable = false)
    private Long fileSize;

    /** 제출 시점의 동의 스냅샷. 이후 동의 철회가 있어도 제출 당시 근거는 남는다. */
    @Column(name = "consent_at", nullable = false)
    private LocalDateTime consentAt;

    /** B1이 챌린지 종료 시 기록한다. 종료 후 참여자 열람 차단 판정에 쓴다. */
    @Column(name = "participant_view_end_at")
    private LocalDateTime participantViewEndAt;

    @Column(name = "delete_scheduled_at", nullable = false)
    private LocalDateTime deleteScheduledAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    private ProofMedia(Long proofId, String storageKey, String contentType, Long fileSize,
                       LocalDateTime consentAt, LocalDateTime deleteScheduledAt) {
        this.proofId = proofId;
        this.storageKey = storageKey;
        this.contentType = contentType;
        this.fileSize = fileSize;
        this.consentAt = consentAt;
        this.deleteScheduledAt = deleteScheduledAt;
    }

    public static ProofMedia store(Long proofId, String storageKey, String contentType, Long fileSize,
                                   LocalDateTime consentAt, LocalDateTime deleteScheduledAt) {
        return new ProofMedia(proofId, storageKey, contentType, fileSize, consentAt, deleteScheduledAt);
    }

    /** B1이 챌린지 종료 시점을 찍는다. */
    public void endParticipantView(LocalDateTime endAt) {
        this.participantViewEndAt = endAt;
    }

    /** B4가 탈퇴 확정 회원의 촬영물 삭제 예정일을 앞당긴다. 뒤로 미루지는 않는다. */
    public void advanceDeleteSchedule(LocalDateTime newScheduledAt) {
        if (newScheduledAt.isBefore(this.deleteScheduledAt)) {
            this.deleteScheduledAt = newScheduledAt;
        }
    }

    public void markDeleted(LocalDateTime now) {
        this.deletedAt = now;
    }

    /**
     * B5 삭제 대상 판정.
     *
     * <p>legal hold(미처리 신고 케이스의 대상이거나 미복구 BLOCKED)는 이 엔티티가 알 수 없는
     * 다른 테이블의 상태이므로 호출부가 계산해 넘긴다. 보류는 어디까지나 보류라서 케이스 종결
     * 또는 차단 확정 후 보관 기간이 지나면 해제된다 — 가장 민감한 이미지만 영구 잔존하는 상태를
     * 만들지 않는다.
     */
    public boolean isDeletable(LocalDateTime now, boolean legalHold) {
        return this.deletedAt == null && !legalHold && !now.isBefore(this.deleteScheduledAt);
    }

    public boolean isDeleted() {
        return this.deletedAt != null;
    }
}
