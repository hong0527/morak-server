package com.morak.report.entity;

import com.morak.common.type.BadgeCode;
import com.morak.report.type.DecidedBy;
import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 완주 리포트. 신고(report_case)와는 무관하며, 챌린지 종료 시점의 완주 판정 결과다.
 *
 * <p>생성 대상은 COMPLETED와 REPORT_EXIT뿐이다. 자진 이탈(LEFT)은 리포트를 만들지 않는다.
 *
 * <p>{@code criteria_personal_rate}·{@code criteria_group_rate}는 판정 당시의 완주 기준
 * 스냅샷이다. 완주 기준 비율은 아직 확정되지 않아 나중에 바뀔 수 있는데, 기준이 바뀌었다고
 * 과거 완주 판정이 소급해 뒤집히면 이미 배지를 받고 게시판에 글을 올린 사람의 기록이
 * 무너진다. 그래서 판정에 쓴 기준을 결과와 함께 박아둔다.
 *
 * <p>인증률은 {@code DECIMAL(5,4)}를 {@link BigDecimal}로 받는다. 완주 판정 경계에서
 * 이진 부동소수점 오차가 사람의 완주 여부를 뒤집기 때문이다.
 */
@Entity
@Table(
        name = "final_report",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_fr",
                columnNames = {"group_id", "member_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FinalReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "group_id", nullable = false)
    private Long groupId;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Column(name = "proved_days", nullable = false)
    private int provedDays;

    /** 판정 시점의 period_days 스냅샷. 인증률의 분모다. */
    @Column(name = "total_days", nullable = false)
    private int totalDays;

    @Column(name = "proof_rate", nullable = false, precision = 5, scale = 4)
    private BigDecimal proofRate;

    @Column(name = "personal_met", nullable = false)
    private boolean personalMet;

    /** COMPLETED가 0명이면 0.0000이다. */
    @Column(name = "group_avg_rate", nullable = false, precision = 5, scale = 4)
    private BigDecimal groupAvgRate;

    @Column(name = "group_met", nullable = false)
    private boolean groupMet;

    @Column(nullable = false)
    private boolean completed;

    @Column(name = "criteria_personal_rate", nullable = false, precision = 5, scale = 4)
    private BigDecimal criteriaPersonalRate;

    @Column(name = "criteria_group_rate", nullable = false, precision = 5, scale = 4)
    private BigDecimal criteriaGroupRate;

    /** AI 단독 확정인지 운영자 확정인지. 경계 사례 재검토 시 판정 주체를 구분해야 한다. */
    @Column(name = "decided_by", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private DecidedBy decidedBy;

    @Column(name = "calculated_at", nullable = false)
    private LocalDateTime calculatedAt;

    private FinalReport(Long groupId, Long memberId, int provedDays, int totalDays,
                        BigDecimal proofRate, boolean personalMet, BigDecimal groupAvgRate,
                        boolean groupMet, boolean completed, BigDecimal criteriaPersonalRate,
                        BigDecimal criteriaGroupRate, DecidedBy decidedBy,
                        LocalDateTime calculatedAt) {
        this.groupId = groupId;
        this.memberId = memberId;
        this.provedDays = provedDays;
        this.totalDays = totalDays;
        this.proofRate = proofRate;
        this.personalMet = personalMet;
        this.groupAvgRate = groupAvgRate;
        this.groupMet = groupMet;
        this.completed = completed;
        this.criteriaPersonalRate = criteriaPersonalRate;
        this.criteriaGroupRate = criteriaGroupRate;
        this.decidedBy = decidedBy;
        this.calculatedAt = calculatedAt;
    }

    /**
     * 완주 판정 결과를 기록한다. 판정에 사용한 기준({@code criteria*})을 결과와 같은 행에 남긴다.
     *
     * <p>{@code completed}는 개인·그룹 충족 여부에서 유도하지 않고 받는다. 두 기준을 어떻게
     * 결합할지가 아직 확정되지 않아, 결합 규칙을 엔티티에 박으면 규칙이 바뀔 때 과거 행의
     * 의미까지 달라진다.
     */
    public static FinalReport decide(Long groupId, Long memberId, int provedDays, int totalDays,
                                     BigDecimal proofRate, boolean personalMet,
                                     BigDecimal groupAvgRate, boolean groupMet, boolean completed,
                                     BigDecimal criteriaPersonalRate, BigDecimal criteriaGroupRate,
                                     DecidedBy decidedBy, LocalDateTime calculatedAt) {
        return new FinalReport(groupId, memberId, provedDays, totalDays, proofRate, personalMet,
                groupAvgRate, groupMet, completed, criteriaPersonalRate, criteriaGroupRate,
                decidedBy, calculatedAt);
    }

    /**
     * 운영자 정정(B6). uk_fr가 회원·그룹당 1건을 강제하므로 새 행을 만들지 않고 덮어쓴다.
     *
     * <p>기준 스냅샷도 함께 다시 박는다. 정정 시점의 기준으로 판정했다는 사실이 남아야
     * 나중에 이 행만 보고 판정을 재현할 수 있다.
     */
    public void correct(int provedDays, int totalDays, BigDecimal proofRate, boolean personalMet,
                        BigDecimal groupAvgRate, boolean groupMet, boolean completed,
                        BigDecimal criteriaPersonalRate, BigDecimal criteriaGroupRate,
                        DecidedBy decidedBy, LocalDateTime calculatedAt) {
        this.provedDays = provedDays;
        this.totalDays = totalDays;
        this.proofRate = proofRate;
        this.personalMet = personalMet;
        this.groupAvgRate = groupAvgRate;
        this.groupMet = groupMet;
        this.completed = completed;
        this.criteriaPersonalRate = criteriaPersonalRate;
        this.criteriaGroupRate = criteriaGroupRate;
        this.decidedBy = decidedBy;
        this.calculatedAt = calculatedAt;
    }

    /**
     * 배지는 저장하지 않고 매번 계산한다. 저장해 두면 배지 기준이 바뀌었을 때 과거 행의
     * 인증률과 배지가 서로 어긋난 채 남는다.
     */
    public BadgeCode getBadgeCode() {
        return BadgeCode.of(completed, proofRate.doubleValue());
    }
}
