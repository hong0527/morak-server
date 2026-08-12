package com.morak.report.entity;

import com.morak.report.type.AccessReason;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 관리자의 인증 촬영물 열람 기록. 타인의 사진을 보는 행위라 사유와 함께 감사 추적을 남긴다.
 *
 * <p>추가만 하고 수정하지 않는다. 그래서 상태 변경 메서드가 없다.
 */
@Entity
@Table(
        name = "media_access_log",
        indexes = @Index(
                name = "idx_mal_proof",
                columnList = "proof_id, accessed_at"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MediaAccessLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "admin_id", nullable = false)
    private Long adminId;

    @Column(name = "proof_id", nullable = false)
    private Long proofId;

    /**
     * 신고가 없는 열람이 있어 NULL을 허용한다. AI가 자동 차단한 건을 관리자가 확인할 때는
     * 케이스가 존재하지 않는다. 여기를 NOT NULL로 두면 AI 오탐을 확인할 길이 막혀 구제가 불가능해진다.
     */
    @Column(name = "case_id")
    private Long caseId;

    @Enumerated(EnumType.STRING)
    @Column(name = "access_reason", nullable = false, length = 30)
    private AccessReason accessReason;

    @Column(name = "accessed_at", nullable = false)
    private LocalDateTime accessedAt;

    private MediaAccessLog(Long adminId, Long proofId, Long caseId, AccessReason accessReason,
                           LocalDateTime accessedAt) {
        this.adminId = adminId;
        this.proofId = proofId;
        this.caseId = caseId;
        this.accessReason = accessReason;
        this.accessedAt = accessedAt;
    }

    public static MediaAccessLog access(Long adminId, Long proofId, Long caseId,
                                        AccessReason accessReason, LocalDateTime accessedAt) {
        return new MediaAccessLog(adminId, proofId, caseId, accessReason, accessedAt);
    }
}
