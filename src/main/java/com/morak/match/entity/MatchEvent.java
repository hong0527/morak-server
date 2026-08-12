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

    /** 대기 취소·만료 이벤트는 소속 그룹이 없어 NULL이다. */
    @Column(name = "group_id")
    private Long groupId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private MatchEventType type;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    private MatchEvent(Long memberId, Long groupId, MatchEventType type, LocalDateTime occurredAt) {
        this.memberId = memberId;
        this.groupId = groupId;
        this.type = type;
        this.occurredAt = occurredAt;
    }

    public static MatchEvent occurred(MatchEventType type, Long memberId, Long groupId,
                                      LocalDateTime occurredAt) {
        return new MatchEvent(memberId, groupId, type, occurredAt);
    }
}
