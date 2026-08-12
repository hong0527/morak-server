package com.morak.report.entity;

import com.morak.report.type.ReportStatus;
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
 * 관리자가 케이스를 처리한 기록. 케이스 자체는 현재 상태만 갖고 있어서
 * 누가 언제 무슨 판단을 했는지는 여기에만 남는다. 제재 이의 제기 시 근거가 된다.
 *
 * <p>추가만 하고 수정하지 않는다. 그래서 상태 변경 메서드가 없다.
 */
@Entity
@Table(
        name = "report_history",
        indexes = @Index(
                name = "idx_rh_case",
                columnList = "case_id, processed_at"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReportHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "case_id", nullable = false)
    private Long caseId;

    @Column(name = "admin_id", nullable = false)
    private Long adminId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReportStatus status;

    @Column(name = "review_note", length = 1000)
    private String reviewNote;

    @Column(name = "processed_at", nullable = false)
    private LocalDateTime processedAt;

    private ReportHistory(Long caseId, Long adminId, ReportStatus status, String reviewNote,
                          LocalDateTime processedAt) {
        this.caseId = caseId;
        this.adminId = adminId;
        this.status = status;
        this.reviewNote = reviewNote;
        this.processedAt = processedAt;
    }

    public static ReportHistory process(Long caseId, Long adminId, ReportStatus status,
                                        String reviewNote, LocalDateTime processedAt) {
        return new ReportHistory(caseId, adminId, status, reviewNote, processedAt);
    }
}
