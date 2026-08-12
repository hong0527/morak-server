package com.morak.session.entity;

import com.morak.session.type.AbsenceEventType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * 단말이 보고하는 얼굴 미검출 이벤트. 보고자는 언제나 대상 본인이다 — 남을 신고할 수 없고,
 * 서버가 JWT와 대조해 강제한다(★D4).
 *
 * <p>경고 부여 여부는 이 테이블이 결정하지 않는다. 클라이언트는 관측만 보내고, START/END
 * 쌍의 간격이 임계(60초)를 넘는지는 서버가 계산한다. 짝이 안 맞는 이벤트는 정상 입력이다 —
 * START 없는 END는 무시하고, END 없이 세션이 끝나면 종료 시각을 END로 간주해 판정한다.
 *
 * <p>{@code uk_ae}가 네트워크 재전송 멱등의 근거다. 같은 {@code clientSeq}가 다시 와도
 * 새 행이 생기지 않으므로 재시도만으로 경고가 쌓이지 않는다.
 *
 * <p>{@code occurredAt}은 단말이 보낸 값이라 신뢰할 수 없는 입력이다. 레이트리밋과
 * {@code reportedAt} 대조가 유일한 방어선이다.
 */
@Entity
@Table(
        name = "absence_event",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_ae",
                columnNames = {"session_id", "member_id", "client_seq"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AbsenceEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    /** 항상 요청자 본인이다. */
    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private AbsenceEventType type;

    /** 세션·회원 스코프의 단조 증가 시퀀스. 멱등키의 재료다. */
    @Column(name = "client_seq", nullable = false)
    private long clientSeq;

    /** 단말이 관측한 시각. 경고 판정(60초 초과)의 계산 기준. */
    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    /** 서버 수신 시각. occurredAt과 크게 벌어지면 조작 신호다. */
    @Column(name = "reported_at", nullable = false)
    private LocalDateTime reportedAt;

    private AbsenceEvent(Long sessionId, Long memberId, AbsenceEventType type, long clientSeq,
                         LocalDateTime occurredAt, LocalDateTime reportedAt) {
        this.sessionId = sessionId;
        this.memberId = memberId;
        this.type = type;
        this.clientSeq = clientSeq;
        this.occurredAt = occurredAt;
        this.reportedAt = reportedAt;
    }

    public static AbsenceEvent report(Long sessionId, Long memberId, AbsenceEventType type,
                                      long clientSeq, LocalDateTime occurredAt,
                                      LocalDateTime reportedAt) {
        return new AbsenceEvent(sessionId, memberId, type, clientSeq, occurredAt, reportedAt);
    }
}
