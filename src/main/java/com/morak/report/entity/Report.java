package com.morak.report.entity;

import com.morak.report.type.ReportReasonCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 개별 신고 접수 건. 여러 건이 하나의 케이스에 묶인다.
 *
 * <p>TODO(12단계): {@code detail} 텍스트 검열. 지금은 받은 서술을 그대로 저장한다. 검열을 넣더라도
 * 위험 판정은 detail만 비우고 접수 자체는 진행해야 한다 — 신고 사유 서술이 걸렸다는 이유로 신고를
 * 막으면 피해자가 신고할 길이 없어진다.
 *
 * <p>외부에 노출하는 식별자는 {@code caseId}다. {@code id}는 응답에 내보내지 않는다.
 */
@Entity
@Table(
        name = "report",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_report",
                columnNames = {"case_id", "reporter_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Report {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "case_id", nullable = false)
    private Long caseId;

    @Column(name = "reporter_id", nullable = false)
    private Long reporterId;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason_code", nullable = false, length = 30)
    private ReportReasonCode reasonCode;

    @Column(length = 500)
    private String detail;

    @Column(name = "received_at", nullable = false)
    private LocalDateTime receivedAt;

    private Report(Long caseId, Long reporterId, ReportReasonCode reasonCode, String detail,
                   LocalDateTime receivedAt) {
        this.caseId = caseId;
        this.reporterId = reporterId;
        this.reasonCode = reasonCode;
        this.detail = detail;
        this.receivedAt = receivedAt;
    }

    /** {@code detail}이 null이면 서술 없이 접수만 남는다(검열 도입 시 그 경로가 여기로 온다). */
    public static Report file(Long caseId, Long reporterId, ReportReasonCode reasonCode,
                              String detail, LocalDateTime receivedAt) {
        return new Report(caseId, reporterId, reasonCode, detail, receivedAt);
    }
}
