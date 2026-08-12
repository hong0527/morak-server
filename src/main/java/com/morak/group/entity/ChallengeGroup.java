package com.morak.group.entity;

import com.morak.common.type.GoalCategory;
import com.morak.group.type.GroupStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 챌린지 그룹. 매칭이 성사되는 순간 생성되며 조건(카테고리·일일 목표·기간)은 이후 바뀌지 않는다.
 *
 * <p>시작일·종료일은 매칭 성사 시각과 운영 정책에 따라 달라지므로 계산은 서비스가 하고
 * 엔티티는 결정된 값을 받아 저장만 한다.
 */
@Entity
@Table(
        name = "challenge_group",
        indexes = @Index(
                name = "idx_cg_batch",
                columnList = "status, end_date"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChallengeGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private GoalCategory category;

    @Column(name = "daily_target_minutes", nullable = false)
    private int dailyTargetMinutes;

    @Column(name = "period_days", nullable = false)
    private int periodDays;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private GroupStatus status;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    private ChallengeGroup(String name, GoalCategory category, int dailyTargetMinutes, int periodDays,
                           LocalDate startDate, LocalDate endDate, LocalDateTime createdAt) {
        this.name = name;
        this.category = category;
        this.dailyTargetMinutes = dailyTargetMinutes;
        this.periodDays = periodDays;
        this.startDate = startDate;
        this.endDate = endDate;
        this.status = GroupStatus.ACTIVE;
        this.createdAt = createdAt;
    }

    public static ChallengeGroup start(String name, GoalCategory category, int dailyTargetMinutes,
                                       int periodDays, LocalDate startDate, LocalDate endDate,
                                       LocalDateTime createdAt) {
        return new ChallengeGroup(name, category, dailyTargetMinutes, periodDays,
                startDate, endDate, createdAt);
    }

    /** 종료 배치(B1) 전용 전이. ACTIVE로 되돌리는 경로는 없다. */
    public void end() {
        this.status = GroupStatus.ENDED;
    }

    /** 기간이 지났는지. 종료일 당일까지는 인증할 수 있으므로 다음 날부터 참이다. */
    public boolean isEnded(LocalDate today) {
        return this.endDate.isBefore(today);
    }

    public boolean isActive() {
        return this.status == GroupStatus.ACTIVE;
    }
}
