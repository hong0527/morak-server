package com.morak.match.entity;

import com.morak.match.type.MatchEventType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 매칭 지표의 원천 로그. 매칭 완료율·대기 이탈률·30일 재참여율을 이 테이블만으로 계산한다.
 *
 * <p>추가 전용이다. 기록된 행은 수정하지 않는다 — 집계 기준 시점이 바뀌면 과거 지표를
 * 재현할 수 없게 된다.
 */
@Entity
@Table(
        name = "match_event",
        indexes = @Index(
                name = "idx_me_type",
                columnList = "type, occurred_at"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MatchEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    /** 성사 이벤트에만 값이 있다. 대기 취소·만료는 배정된 세션이 없어 NULL이다. */
    @Column(name = "session_id")
    private Long sessionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private MatchEventType type;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    private MatchEvent(Long memberId, Long sessionId, MatchEventType type,
                       LocalDateTime occurredAt) {
        this.memberId = memberId;
        this.sessionId = sessionId;
        this.type = type;
        this.occurredAt = occurredAt;
    }

    public static MatchEvent occurred(MatchEventType type, Long memberId, Long sessionId,
                                      LocalDateTime occurredAt) {
        return new MatchEvent(memberId, sessionId, type, occurredAt);
    }
}
