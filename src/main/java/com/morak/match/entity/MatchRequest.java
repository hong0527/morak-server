package com.morak.match.entity;

import com.morak.common.type.GoalCategory;
import com.morak.match.type.MatchRequestStatus;
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
 * 매칭 대기 요청.
 *
 * <p>불변식: {@code activeMemberId}는 status가 WAITING일 때만 {@code memberId}와 같고,
 * 그 외 상태에서는 반드시 NULL이다. {@code uk_mr_active}가 이 컬럼에 걸려 있어
 * "회원당 활성 요청 1건"을 DB 레벨에서 강제하며, 이것이 이중 배정을 막는 마지막 방어선이다.
 *
 * <p>따라서 status를 바꾸는 모든 주체(MT-1·MT-3·B2·AD-4·AU-4)는 예외 없이
 * ① 조건 행 잠금 ② 조건부 UPDATE(WHERE status='WAITING') ③ activeMemberId=NULL 을 함께 수행한다.
 * 상태만 바꾸고 activeMemberId를 비우지 않으면 그 회원은 uk_mr_active에 걸려 다시는
 * 매칭을 요청할 수 없게 되고, 애플리케이션 재배포로도 풀리지 않는다. 이 클래스의
 * {@link #matched(Long)}·{@link #expire()}·{@link #cancel()}은 셋 다 두 변경을 함께 한다.
 */
@Entity
@Table(
        name = "match_request",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_mr_active",
                columnNames = "active_member_id"),
        indexes = {
                @Index(
                        name = "idx_mr_queue",
                        columnList = "status, category, daily_target_minutes, period_days, requested_at"),
                @Index(
                        name = "idx_mr_expire",
                        columnList = "status, expires_at")
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MatchRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private GoalCategory category;

    @Column(name = "daily_target_minutes", nullable = false)
    private int dailyTargetMinutes;

    @Column(name = "period_days", nullable = false)
    private int periodDays;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MatchRequestStatus status;

    /** WAITING일 때만 memberId, 그 외에는 NULL. uk_mr_active가 이 값으로 활성 요청 중복을 막는다. */
    @Column(name = "active_member_id")
    private Long activeMemberId;

    @Column(name = "requested_at", nullable = false)
    private LocalDateTime requestedAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "matched_group_id")
    private Long matchedGroupId;

    private MatchRequest(Long memberId, GoalCategory category, int dailyTargetMinutes, int periodDays,
                         LocalDateTime requestedAt, LocalDateTime expiresAt) {
        this.memberId = memberId;
        this.category = category;
        this.dailyTargetMinutes = dailyTargetMinutes;
        this.periodDays = periodDays;
        this.status = MatchRequestStatus.WAITING;
        this.activeMemberId = memberId;
        this.requestedAt = requestedAt;
        this.expiresAt = expiresAt;
    }

    public static MatchRequest request(Long memberId, GoalCategory category, int dailyTargetMinutes,
                                       int periodDays, LocalDateTime requestedAt, LocalDateTime expiresAt) {
        return new MatchRequest(memberId, category, dailyTargetMinutes, periodDays, requestedAt, expiresAt);
    }

    /** 매칭 성사(MT-1). 그룹 배정과 활성 해제를 함께 한다. */
    public void matched(Long groupId) {
        this.status = MatchRequestStatus.MATCHED;
        this.matchedGroupId = groupId;
        this.activeMemberId = null;
    }

    /** 대기 만료(B2). */
    public void expire() {
        this.status = MatchRequestStatus.EXPIRED;
        this.activeMemberId = null;
    }

    /** 대기 취소(MT-3·AD-4·AU-4). */
    public void cancel() {
        this.status = MatchRequestStatus.CANCELLED;
        this.activeMemberId = null;
    }

    public boolean isWaiting() {
        return this.status == MatchRequestStatus.WAITING;
    }

    public boolean isExpired(LocalDateTime now) {
        return !this.expiresAt.isAfter(now);
    }
}
