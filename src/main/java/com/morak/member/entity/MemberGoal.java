package com.morak.member.entity;

import com.morak.member.type.GoalStatus;
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
 * 회원의 목표 기간(7·14·30일). 재설정은 기존 행 수정이 아니라 새 행이다.
 *
 * <p>회원당 ACTIVE 행은 최대 1개지만 이것을 DB 제약으로 두지 않는다.
 * {@code UNIQUE(member_id, status)}로 막으면 ACHIEVED가 여러 건 쌓이는 것까지 막아버리고,
 * 부분 인덱스를 쓸 수 없는 환경이라 대안이 없다. 대신 AU-7이 회원 행({@code match_lock}의
 * {@code member:{id}})을 FOR UPDATE로 잡은 뒤 활성 목표를 확인한다. 잠금을 빠뜨리면 뚫린다.
 *
 * <p>미완주일이 생겨도 목표는 ACTIVE로 남는다. 끊기는 것은 Streak이지 목표가 아니다(★D3).
 */
@Entity
@Table(
        name = "member_goal",
        indexes = @Index(
                name = "idx_mg_member",
                columnList = "member_id, status"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemberGoal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    /** 7 | 14 | 30 */
    @Column(name = "period_days", nullable = false)
    private int periodDays;

    /** 연속 판정의 기준점이라 시각이 아니라 날짜다. */
    @Column(name = "started_on", nullable = false)
    private LocalDate startedOn;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private GoalStatus status;

    @Column(name = "achieved_at")
    private LocalDateTime achievedAt;

    private MemberGoal(Long memberId, int periodDays, LocalDate startedOn) {
        this.memberId = memberId;
        this.periodDays = periodDays;
        this.startedOn = startedOn;
        this.status = GoalStatus.ACTIVE;
    }

    public static MemberGoal start(Long memberId, int periodDays, LocalDate startedOn) {
        return new MemberGoal(memberId, periodDays, startedOn);
    }

    /**
     * 목표 달성(B1). 달성한 목표는 다시 ACTIVE가 되지 않는다 — 재도전은 새 행이다.
     */
    public void achieve(LocalDateTime achievedAt) {
        if (this.status != GoalStatus.ACTIVE) {
            throw new IllegalStateException("진행 중인 목표가 아니다: " + this.status);
        }
        this.status = GoalStatus.ACHIEVED;
        this.achievedAt = achievedAt;
    }

    public void cancel() {
        if (this.status != GoalStatus.ACTIVE) {
            throw new IllegalStateException("진행 중인 목표가 아니다: " + this.status);
        }
        this.status = GoalStatus.CANCELLED;
    }

    public boolean isActive() {
        return this.status == GoalStatus.ACTIVE;
    }
}
