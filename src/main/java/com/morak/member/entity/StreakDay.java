package com.morak.member.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 완주한 날. 행의 존재가 곧 그날의 완주이고, 행이 없는 날짜는 Streak를 끊는다.
 *
 * <p>{@code uk_streak_day}가 B1 재실행 멱등의 근거다(★D2). 하루에 몇 세션을 완주하든 행은
 * 1개이며, 두 번째 INSERT는 제약 위반을 잡아 무시한다. "먼저 조회하고 없으면 INSERT"는
 * 동시 실행에서 둘 다 통과하므로 방어선이 되지 못한다.
 *
 * <p>날짜 경계는 {@code morak.timezone}(Asia/Seoul) 기준이다. 이의 인용(AD-6)으로 완주가
 * 소급되면 행을 INSERT하고 캐시를 재계산한다. 반대로 행을 지우는 경로는 없다.
 */
@Entity
@Table(
        name = "streak_day",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_streak_day",
                columnNames = {"member_id", "completed_on"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StreakDay {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    /** 시각이 아니라 날짜다. */
    @Column(name = "completed_on", nullable = false)
    private LocalDate completedOn;

    /** 그날 완주를 성립시킨 첫 세션. 여러 세션을 완주해도 첫 1건만 남는다. */
    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    private StreakDay(Long memberId, LocalDate completedOn, Long sessionId) {
        this.memberId = memberId;
        this.completedOn = completedOn;
        this.sessionId = sessionId;
    }

    public static StreakDay complete(Long memberId, LocalDate completedOn, Long sessionId) {
        return new StreakDay(memberId, completedOn, sessionId);
    }
}
