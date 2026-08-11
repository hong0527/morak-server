package com.morak.report.entity;

import com.morak.report.type.ReportSeverity;
import com.morak.report.type.ReportStatus;
import com.morak.report.type.ReportTargetType;
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
 * 신고 케이스. 같은 대상에 대한 여러 신고를 하나로 모아 관리자가 한 번만 판단하게 한다.
 *
 * <p>{@code openTargetId}는 미처리일 때만 {@code targetId}와 같은 값을 갖고 종결되면 NULL이 된다.
 * uk_rc_open이 (target_type, open_target_id) 조합을 막으므로 대상당 미처리 케이스는 항상 1건이다.
 * 같은 대상에 새 신고가 들어오면 케이스를 새로 만들지 않고 기존 케이스에 병합한다.
 *
 * <p>종결된 케이스는 재오픈하지 않는다. 재검토가 필요하면 새 케이스를 만든다.
 * 재오픈을 허용하는 순간 uk_rc_open 충돌 경로가 되살아나기 때문에 reopen 메서드를 두지 않는다.
 */
@Entity
@Table(
        name = "report_case",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_rc_open",
                columnNames = {"target_type", "open_target_id"}),
        indexes = @Index(
                name = "idx_rc_console",
                columnList = "status, severity, overdue, sla_due_at"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReportCase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 10)
    private ReportTargetType targetType;

    @Column(name = "target_id", nullable = false)
    private Long targetId;

    /** 미처리 상태를 표현하는 값. 종결 시 NULL이 되어 같은 대상의 다음 신고를 허용한다. */
    @Column(name = "open_target_id")
    private Long openTargetId;

    /** POST 신고는 게시판이 앱 전체 공개라 소속 그룹이 없을 수 있다. */
    @Column(name = "group_id")
    private Long groupId;

    /** 신고 시점의 표시명 스냅샷. 이후 닉네임이 바뀌어도 관리자가 무엇을 봤는지 남아야 한다. */
    @Column(name = "target_nickname", nullable = false, length = 30)
    private String targetNickname;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ReportSeverity severity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReportStatus status;

    @Column(name = "sla_due_at", nullable = false)
    private LocalDateTime slaDueAt;

    @Column(nullable = false)
    private boolean overdue;

    @Column(name = "restriction_review", nullable = false)
    private boolean restrictionReview;

    @Column(name = "received_at", nullable = false)
    private LocalDateTime receivedAt;

    private ReportCase(ReportTargetType targetType, Long targetId, Long groupId,
                       String targetNickname, ReportSeverity severity, LocalDateTime slaDueAt,
                       LocalDateTime receivedAt) {
        this.targetType = targetType;
        this.targetId = targetId;
        this.openTargetId = targetId;
        this.groupId = groupId;
        this.targetNickname = targetNickname;
        this.severity = severity;
        this.status = ReportStatus.PENDING;
        this.slaDueAt = slaDueAt;
        this.overdue = false;
        this.restrictionReview = false;
        this.receivedAt = receivedAt;
    }

    public static ReportCase open(ReportTargetType targetType, Long targetId, Long groupId,
                                  String targetNickname, ReportSeverity severity,
                                  LocalDateTime slaDueAt, LocalDateTime receivedAt) {
        return new ReportCase(targetType, targetId, groupId, targetNickname, severity,
                slaDueAt, receivedAt);
    }

    /**
     * 케이스를 종결한다.
     *
     * <p>불변식: status 변경과 openTargetId를 NULL로 비우는 일은 반드시 함께 일어나야 한다.
     * 하나라도 빠지면 그 대상은 uk_rc_open에 영원히 걸려 다시 신고할 수 없게 된다.
     * 종결은 되돌릴 수 없으므로 이미 종결된 케이스에는 적용하지 않는다.
     */
    public void close(ReportStatus closedStatus) {
        if (closedStatus == ReportStatus.PENDING) {
            throw new IllegalArgumentException("종결 상태가 아니다: " + closedStatus);
        }
        if (this.status != ReportStatus.PENDING) {
            throw new IllegalStateException("이미 종결된 케이스다: " + this.status);
        }
        this.status = closedStatus;
        this.openTargetId = null;
    }

    /**
     * 병합 과정에서 더 높은 severity가 들어왔을 때 케이스를 상향한다.
     *
     * <p>SLA 시간 계산은 서비스 책임이라 계산된 만료 시각을 받는다.
     */
    public void escalate(ReportSeverity higher, LocalDateTime newDueAt) {
        this.severity = higher;
        this.slaDueAt = newDueAt;
    }

    /** B3가 SLA를 넘긴 케이스를 표시한다. 관리자 콘솔 정렬과 알림의 근거가 된다. */
    public void markOverdue() {
        this.overdue = true;
    }

    /** B3에서 HIGH 누적이 기준을 넘었을 때 이용 제한 검토 대상으로 올린다. */
    public void flagRestrictionReview() {
        this.restrictionReview = true;
    }
}
