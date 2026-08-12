package com.morak.session.entity;

import com.morak.session.type.SessionEndReason;
import com.morak.session.type.SessionStatus;
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
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 라이브 캠 스터디 세션. 6인 확정 시점에만 생기고, 그 즉시 시작한다 — 대기 시간은
 * {@code startedAt}에 포함되지 않는다(D21).
 *
 * <p>영상은 어떤 형태로도 저장하지 않으므로 미디어 참조 컬럼이 없다(D17).
 *
 * <p>{@code status='ENDED'}는 {@code endedAt}·{@code endReason}이 둘 다 채워진 상태와 같다.
 * 두 종료 경로(B1 정시 종료, 잔여 2인 미만 조기 종료)가 모두 사유를 남겨야 운영 지표에서
 * 정상 종료와 조기 종료가 구분된다. CANCELLED는 enum에만 있고 v1에 전이 경로가 없다.
 */
@Entity
@Table(
        name = "live_session",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_ls_room",
                columnNames = "livekit_room_name"),
        indexes = @Index(
                name = "idx_ls_batch",
                columnList = "status, ends_at"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LiveSession {

    private static final String ROOM_NAME_PREFIX = "molock-";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 60 | 120 | 180 | 240 */
    @Column(name = "target_minutes", nullable = false)
    private int targetMinutes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SessionStatus status;

    @Column(name = "livekit_room_name", nullable = false, length = 100)
    private String livekitRoomName;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    /** 종료 예정 시각. B1이 이 시각을 지난 LIVE 세션을 종료 처리한다. */
    @Column(name = "ends_at", nullable = false)
    private LocalDateTime endsAt;

    @Column(name = "ended_at")
    private LocalDateTime endedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "end_reason", length = 20)
    private SessionEndReason endReason;

    private LiveSession(int targetMinutes, LocalDateTime startedAt, LocalDateTime endsAt) {
        this.targetMinutes = targetMinutes;
        this.status = SessionStatus.LIVE;
        // 방 이름은 "molock-{id}"인데 id는 INSERT가 끝나야 정해진다. 컬럼이 NOT NULL·UNIQUE라
        // 빈 값으로 넣을 수 없어 임시 유일값을 넣고 같은 트랜잭션에서 assignRoomName()이 덮는다.
        this.livekitRoomName = UUID.randomUUID().toString();
        this.startedAt = startedAt;
        this.endsAt = endsAt;
    }

    public static LiveSession open(int targetMinutes, LocalDateTime startedAt,
                                   LocalDateTime endsAt) {
        return new LiveSession(targetMinutes, startedAt, endsAt);
    }

    /** 저장 직후 같은 트랜잭션에서 호출한다. 방 이름 규칙은 이 클래스에만 둔다. */
    public void assignRoomName() {
        if (this.id == null) {
            throw new IllegalStateException("id가 정해진 뒤에 방 이름을 붙여야 한다");
        }
        this.livekitRoomName = roomNameOf(this.id);
    }

    /**
     * SS-10 웹훅이 방 이름으로 세션을 되짚을 때 쓰는 규칙. 토큰 발급과 웹훅 파싱이 같은
     * 규칙을 공유해야 하므로 문자열 조립을 흩어 두지 않는다.
     */
    public static String roomNameOf(Long sessionId) {
        return ROOM_NAME_PREFIX + sessionId;
    }

    /** 예정 시각 도달로 종료(B1). */
    public void endNormally(LocalDateTime endedAt) {
        end(endedAt, SessionEndReason.NORMAL);
    }

    /** 잔여 참가자가 최소 인원 미만이 되어 조기 종료(D12). */
    public void endUnderMinimum(LocalDateTime endedAt) {
        end(endedAt, SessionEndReason.EARLY_UNDER_MIN);
    }

    private void end(LocalDateTime endedAt, SessionEndReason reason) {
        if (this.status != SessionStatus.LIVE) {
            throw new IllegalStateException("진행 중인 세션이 아니다: " + this.status);
        }
        this.status = SessionStatus.ENDED;
        this.endedAt = endedAt;
        this.endReason = reason;
    }

    public boolean isLive() {
        return this.status == SessionStatus.LIVE;
    }
}
