package com.morak.session.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * 3회 경고 퇴출. 퇴출 트랜잭션은 넷을 함께 한다 — 참가자 status=EVICTED, 포인트 원장에
 * -300 기록, 이 행 생성, LiveKit 강제 퇴장 요청.
 *
 * <p>{@code uk_eviction}이 이중 퇴출을 막는다. 자리비움 판정과 Pause 초과 판정이 동시에
 * 걸려도 -300이 두 번 빠지지 않는다.
 *
 * <p>이의 인용(AD-6)은 행을 지우지 않고 {@code revokedAt}만 채운다. 포인트도 삭제가 아니라
 * 역분개(APPEAL_REFUND +300)로 되돌린다. 취소된 퇴출은 재매칭 쿨다운 판정에서 제외한다.
 */
@Entity
@Table(
        name = "eviction",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_eviction",
                columnNames = {"session_id", "member_id"}),
        indexes = {
                @Index(name = "idx_ev_member", columnList = "member_id, created_at"),
                // B1 소급 차감 스캔의 선행 조건이 revoked_at IS NULL이다. 뒤따르는 NOT EXISTS는
                // 인덱스로 좁힐 수 없으므로, 여기서 후보를 줄이지 못하면 매분 퇴출 전체가 후보다.
                @Index(name = "idx_ev_revoked", columnList = "revoked_at")
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Eviction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    /** 퇴출 시점 경고 수 스냅샷. */
    @Column(name = "warning_count", nullable = false)
    private int warningCount;

    /** 차감액 절대값. 정책값 스냅샷이라 나중에 정책이 바뀌어도 과거 판정이 흔들리지 않는다. */
    @Column(name = "point_penalty", nullable = false)
    private int pointPenalty;

    /** 재매칭 쿨다운 30분(D14)의 기준 시각. */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    private Eviction(Long sessionId, Long memberId, int warningCount, int pointPenalty,
                     LocalDateTime createdAt) {
        this.sessionId = sessionId;
        this.memberId = memberId;
        this.warningCount = warningCount;
        this.pointPenalty = pointPenalty;
        this.createdAt = createdAt;
    }

    public static Eviction of(Long sessionId, Long memberId, int warningCount, int pointPenalty,
                              LocalDateTime createdAt) {
        return new Eviction(sessionId, memberId, warningCount, pointPenalty, createdAt);
    }

    /** 이의 인용(AD-6). 취소 표시일 뿐 행을 지우지 않는다. */
    public void revoke(LocalDateTime revokedAt) {
        this.revokedAt = revokedAt;
    }

    public boolean isRevoked() {
        return this.revokedAt != null;
    }
}
