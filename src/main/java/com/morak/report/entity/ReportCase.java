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
 *
 * <p>지연(overdue)은 저장하지 않고 조회 시점에 파생한다(미종결 AND {@code slaDueAt < now}).
 * 저장 플래그는 그것을 세우는 배치가 밀리면 이미 기한을 넘긴 케이스가 큐에서 정상으로 보인다.
 * {@code appeal_case}도 같은 규칙이라 두 큐의 지연 판정이 하나의 식을 쓴다.
 */
@Entity
@Table(
        name = "report_case",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_rc_open",
                columnNames = {"target_type", "open_target_id"}),
        indexes = @Index(
                name = "idx_rc_console",
                columnList = "status, severity, sla_due_at"))
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

    /** 신고가 발생한 세션. 관리자가 참가자·경고 로그로 맥락을 열 때 쓴다. */
    @Column(name = "session_id")
    private Long sessionId;

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

    @Column(name = "restriction_review", nullable = false)
    private boolean restrictionReview;

    @Column(name = "received_at", nullable = false)
    private LocalDateTime receivedAt;

    private ReportCase(ReportTargetType targetType, Long targetId, Long sessionId,
                       String targetNickname, ReportSeverity severity, LocalDateTime slaDueAt,
                       LocalDateTime receivedAt) {
        this.targetType = targetType;
        this.targetId = targetId;
        this.openTargetId = targetId;
        this.sessionId = sessionId;
        this.targetNickname = targetNickname;
        this.severity = severity;
        this.status = ReportStatus.PENDING;
        this.slaDueAt = slaDueAt;
        this.restrictionReview = false;
        this.receivedAt = receivedAt;
    }

    public static ReportCase open(ReportTargetType targetType, Long targetId, Long sessionId,
                                  String targetNickname, ReportSeverity severity,
                                  LocalDateTime slaDueAt, LocalDateTime receivedAt) {
        return new ReportCase(targetType, targetId, sessionId, targetNickname, severity,
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

    /** 저장 컬럼이 아니라 조회 시점 계산이다. 마킹 배치가 없는 이유는 클래스 주석 참조. */
    public boolean isOverdue(LocalDateTime now) {
        return this.status == ReportStatus.PENDING && this.slaDueAt.isBefore(now);
    }

    /** AD-3이 신고를 기각할 때 신고자를 이용 제한 검토 대상으로 올린다. */
    public void flagRestrictionReview() {
        this.restrictionReview = true;
    }
}
