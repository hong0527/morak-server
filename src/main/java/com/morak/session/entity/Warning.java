package com.morak.session.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 자리비움 경고. 만드는 주체는 서버뿐이고 클라이언트가 부르는 API가 없다.
 *
 * <p>{@code seq}는 1부터 빈틈없이 증가하며 3이 존재하면 반드시 {@code eviction} 행도 있다.
 * {@code uk_warning}이 없으면 동시 판정이 겹칠 때 2번 경고가 두 번 생겨 3회 퇴출이 앞당겨진다.
 *
 * <p>경고는 세션 스코프다(D11). 세션이 끝나면 계정에 남는 효과가 없다.
 */
@Entity
@Table(
        name = "warning",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_warning",
                columnNames = {"session_id", "member_id", "seq"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Warning {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    /** 세션 안에서의 경고 번호 1~3. session_participant.warning_count와 같은 값이다. */
    @Column(nullable = false)
    private int seq;

    /**
     * 판정을 유발한 END 이벤트. Pause 10분 초과 경고(D9)는 근거 이벤트가 없어 NULL이다 —
     * 그래서 이 컬럼은 NOT NULL이 될 수 없다.
     */
    @Column(name = "absence_event_id")
    private Long absenceEventId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    private Warning(Long sessionId, Long memberId, int seq, Long absenceEventId,
                    LocalDateTime createdAt) {
        this.sessionId = sessionId;
        this.memberId = memberId;
        this.seq = seq;
        this.absenceEventId = absenceEventId;
        this.createdAt = createdAt;
    }

    /** 자리비움 판정으로 부여하는 경고. */
    public static Warning fromAbsence(Long sessionId, Long memberId, int seq, Long absenceEventId,
                                      LocalDateTime createdAt) {
        return new Warning(sessionId, memberId, seq, absenceEventId, createdAt);
    }

    /**
     * 세션이 끝날 때까지 END가 오지 않은 자리비움을 정산해 부여하는 경고(B1). 근거는 짝을
     * 잃은 START 이벤트다 — 판정을 유발한 것이 END가 아니라 세션 종료라는 점만 다르고,
     * 어느 구간 때문에 경고가 붙었는지는 이 id로 되짚을 수 있다.
     */
    public static Warning fromUnclosedAbsence(Long sessionId, Long memberId, int seq,
                                              Long startEventId, LocalDateTime createdAt) {
        return new Warning(sessionId, memberId, seq, startEventId, createdAt);
    }

    /** Pause 제한 시간 초과로 부여하는 경고(D9). 근거 이벤트가 없다. */
    public static Warning fromPauseOverrun(Long sessionId, Long memberId, int seq,
                                           LocalDateTime createdAt) {
        return new Warning(sessionId, memberId, seq, null, createdAt);
    }
}
