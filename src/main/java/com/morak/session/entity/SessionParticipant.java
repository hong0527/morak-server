package com.morak.session.entity;

import com.morak.session.type.LeftReason;
import com.morak.session.type.ParticipantStatus;
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
 * 세션 참가자. 세션 도메인의 중심이고, 세션 결과(완주 여부·지급 포인트)도 별도 테이블 없이
 * 이 행의 {@code completed}·{@code pointAwarded}에서 파생한다.
 *
 * <p>행은 매칭 성사 시점에 생기고 {@code joinedAt}은 실제 입장 시점에 채워진다. 두 시각이
 * 다르기 때문에 NULL을 허용한다 — 매칭됐지만 끝내 입장하지 않은 사람은 NULL로 남는다.
 * 재접속은 새 행이 아니라 기존 행의 상태 복귀이고({@code uk_sp}), {@code joinedAt}은
 * 덮어쓰지 않는다.
 *
 * <p>완주 조건은 "세션 종료 시각까지 LEFT도 EVICTED도 아님"이다(★D1). Pause 10분은 재실로
 * 인정하며 재실 비율 기준은 두지 않는다. 자율 퇴장은 포인트 차감이 없고 그 세션은 미완주다.
 */
@Entity
@Table(
        name = "session_participant",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_sp",
                columnNames = {"session_id", "member_id"}),
        indexes = @Index(
                name = "idx_sp_member",
                columnList = "member_id, joined_at"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SessionParticipant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ParticipantStatus status;

    /** SS-10 웹훅이 최초 입장 시 1회만 기록한다. */
    @Column(name = "joined_at")
    private LocalDateTime joinedAt;

    @Column(name = "left_at")
    private LocalDateTime leftAt;

    /** LEFT일 때만 값이 있다. 퇴출은 사유가 아니라 status로 표현한다. */
    @Enumerated(EnumType.STRING)
    @Column(name = "left_reason", length = 30)
    private LeftReason leftReason;

    /** 세션 스코프 누적(D11). 세션이 끝나면 계정에 남는 효과가 없다. */
    @Column(name = "warning_count", nullable = false)
    private int warningCount;

    /** 세션당 1회. true가 되면 다시 false로 돌아가지 않는다. */
    @Column(name = "pause_used", nullable = false)
    private boolean pauseUsed;

    @Column(name = "pause_started_at")
    private LocalDateTime pauseStartedAt;

    /** '오늘 할 일' 한 줄. 같은 세션 참가자에게만 공개한다. */
    @Column(name = "goal_text", length = 50)
    private String goalText;

    @Column(nullable = false)
    private boolean completed;

    /** 지급 사실이 아니라 금액 스냅샷이다. 지급의 진실은 point_ledger에 있다. */
    @Column(name = "point_awarded", nullable = false)
    private int pointAwarded;

    private SessionParticipant(Long sessionId, Long memberId) {
        this.sessionId = sessionId;
        this.memberId = memberId;
        this.status = ParticipantStatus.ACTIVE;
        this.warningCount = 0;
        this.pauseUsed = false;
        this.completed = false;
        this.pointAwarded = 0;
    }

    public static SessionParticipant assign(Long sessionId, Long memberId) {
        return new SessionParticipant(sessionId, memberId);
    }

    /** 최초 입장만 기록한다. 재입장이 시각을 덮으면 참여 시작 시점이 사라진다. */
    public void markJoined(LocalDateTime joinedAt) {
        if (this.joinedAt == null) {
            this.joinedAt = joinedAt;
        }
    }

    public void updateGoalText(String goalText) {
        this.goalText = goalText;
    }

    /**
     * 자율 퇴장(SS-7). PAUSED 상태에서 나가는 경로가 있어 pauseStartedAt을 함께 비운다 —
     * 남겨 두면 "PAUSED가 아닌데 pause_started_at이 있는" 행이 생겨 불변식이 깨진다.
     * pauseUsed는 세션당 1회 소진 기록이라 되돌리지 않는다.
     */
    public void leave(LeftReason reason, LocalDateTime leftAt) {
        this.status = ParticipantStatus.LEFT;
        this.leftReason = reason;
        this.leftAt = leftAt;
        this.pauseStartedAt = null;
    }

    /**
     * 3회 경고 퇴출. 사유 컬럼은 건드리지 않는다 — 퇴출은 status로만 표현한다.
     * Pause 초과 경고로 퇴출되는 경로(D9)가 있어 PAUSED에서 곧바로 넘어올 수 있다.
     */
    public void evict(LocalDateTime evictedAt) {
        this.status = ParticipantStatus.EVICTED;
        this.leftAt = evictedAt;
        this.pauseStartedAt = null;
    }

    /** 경고 부여는 warning 행 생성과 같은 트랜잭션이다. 어긋나면 warning 테이블이 옳다. */
    public int addWarning() {
        this.warningCount += 1;
        return this.warningCount;
    }

    public void startPause(LocalDateTime startedAt) {
        this.status = ParticipantStatus.PAUSED;
        this.pauseUsed = true;
        this.pauseStartedAt = startedAt;
    }

    /** 복귀(SS-6). PAUSED와 pauseStartedAt은 항상 함께 켜지고 함께 꺼진다. */
    public void resume() {
        this.status = ParticipantStatus.ACTIVE;
        this.pauseStartedAt = null;
    }

    /** 완주 확정(B1). 금액은 지급 시점 정책값의 스냅샷이다. */
    public void complete(int pointAwarded) {
        this.completed = true;
        this.pointAwarded = pointAwarded;
    }

    /** 세션 종료 시점에 이탈 상태가 아니면 완주다(★D1). */
    public boolean isPresent() {
        return this.status == ParticipantStatus.ACTIVE || this.status == ParticipantStatus.PAUSED;
    }

    /** B4 탈퇴 파기. 행 자체는 다른 참가자의 세션 이력 정합성 때문에 남긴다. */
    public void clearPersonalText() {
        this.goalText = null;
    }
}
