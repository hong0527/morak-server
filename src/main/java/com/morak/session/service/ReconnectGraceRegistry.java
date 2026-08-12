package com.morak.session.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 재접속 유예 창(D13). {@code participant_left}를 받은 참가자를 유예 시간 동안 붙잡아 두고,
 * 그 안에 {@code participant_joined}가 오면 없던 일로 되돌린다.
 *
 * <p><b>왜 DB가 아니라 메모리인가.</b> 스키마에 끊긴 시각을 담을 컬럼이 없고(db-schema §3),
 * 유예는 최대 90초짜리 임시 상태라 영속시킬 값이 아니다. 유예 창의 결과만 DB에 남는다 —
 * 돌아오면 아무 흔적도 남기지 않는 것이 D13의 요구다.
 *
 * <p>대가는 명확하다. <b>서버가 재기동하면 걸려 있던 유예 창이 사라진다.</b> 그 참가자는
 * 끊긴 채로 ACTIVE에 남았다가 세션 종료 시점에 완주로 계산된다. 재기동이 곧 유예 초기화라는
 * 뜻이며, 단일 인스턴스 전제이기도 하다(인스턴스가 둘이면 웹훅을 받은 쪽만 타이머를 쥔다).
 * 둘 중 하나라도 깨지면 이 상태는 {@code session_participant}로 내려가야 한다.
 */
@Component
public class ReconnectGraceRegistry {

    /** 세션·회원 쌍이 참가자 행을 유일하게 특정한다(uk_sp). */
    public record Key(Long sessionId, Long memberId) {
    }

    private final Map<Key, LocalDateTime> disconnectedAt = new ConcurrentHashMap<>();

    /**
     * 유예 창을 연다. 이미 열려 있으면 최초 시각을 유지한다 — 웹훅은 중복 수신되므로
     * 덮어쓰면 같은 끊김의 재전송이 유예를 계속 연장한다.
     */
    public void open(Long sessionId, Long memberId, LocalDateTime at) {
        disconnectedAt.putIfAbsent(new Key(sessionId, memberId), at);
    }

    public void close(Long sessionId, Long memberId) {
        disconnectedAt.remove(new Key(sessionId, memberId));
    }

    /** 세션이 끝나면 그 세션의 유예 창은 판정할 이유가 없다. */
    public void discardSession(Long sessionId) {
        disconnectedAt.keySet().removeIf(key -> key.sessionId().equals(sessionId));
    }

    /** 유예를 넘긴 창 목록. 판정 자체는 호출자(세션 서비스)가 한다. */
    public List<Key> expired(LocalDateTime now, int graceSeconds) {
        return disconnectedAt.entrySet().stream()
                .filter(entry -> !entry.getValue().plusSeconds(graceSeconds).isAfter(now))
                .map(Map.Entry::getKey)
                .toList();
    }

    public boolean isOpen(Long sessionId, Long memberId) {
        return disconnectedAt.containsKey(new Key(sessionId, memberId));
    }
}
