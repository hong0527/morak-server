package com.morak.session.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 재접속 유예 창(D13). {@code participant_left}를 받은 참가자를 유예 시간 동안 붙잡아 두고,
 * 그 안에 {@code participant_joined}가 오면 없던 일로 되돌린다.
 *
 * <p><b>왜 DB가 아니라 메모리인가.</b> 스키마에 끊긴 시각을 담을 컬럼이 없고(db-schema §3),
 * 유예는 최대 90초짜리 임시 상태라 영속시킬 값이 아니다. 유예 창의 결과만 DB에 남는다 —
 * 돌아오면 아무 흔적도 남기지 않는 것이 D13의 요구다.
 *
 * <p><b>변경은 전부 커밋 이후로 미룬다.</b> 이 맵은 트랜잭션 밖에 있어서 롤백이 되돌려 주지
 * 않는다. 트랜잭션 안에서 곧바로 지우면 그 트랜잭션이 실패했을 때 창만 사라지고 참가자는
 * 끊긴 채 ACTIVE로 남아, 스위퍼가 그를 다시는 집어 들지 않는다. 반대로 여는 쪽을 미리 하면
 * 롤백된 퇴장 이벤트가 없던 유예를 걸어 멀쩡한 참가자를 90초 뒤에 내보낸다. 그래서 호출부가
 * 아니라 이 클래스가 {@link TransactionSynchronization}을 걸고, 트랜잭션이 없는 호출
 * (스위퍼)은 그 자리에서 실행한다 — 호출 지점이 여섯 곳이라 한 곳이라도 빠뜨리면 그 경로만
 * 조용히 예전 동작으로 돌아간다.
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
        afterCommit(() -> disconnectedAt.putIfAbsent(new Key(sessionId, memberId), at));
    }

    public void close(Long sessionId, Long memberId) {
        afterCommit(() -> disconnectedAt.remove(new Key(sessionId, memberId)));
    }

    /**
     * 늦게 도착한 입장 이벤트로는 창을 닫지 않는다.
     *
     * <p>LiveKit 웹훅은 순서를 보장하지 않아 <b>끊기기 전의 {@code joined}가 {@code left}보다
     * 늦게 도착한다</b>. 그 이벤트로 창을 지우면 실제로 끊긴 참가자의 유예가 사라져 90초 뒤
     * 퇴장 처리가 영영 오지 않고, 그는 끊긴 채 완주자로 집계된다. 이벤트 발생 시각이 창을 연
     * 시각보다 이르면 무시하는 것이 그 방어다.
     *
     * @param at 이벤트가 LiveKit에서 발생한 시각. 서버 수신 시각이 아니다
     */
    public void closeIfNotBefore(Long sessionId, Long memberId, LocalDateTime at) {
        afterCommit(() -> disconnectedAt.computeIfPresent(new Key(sessionId, memberId),
                (key, openedAt) -> at.isBefore(openedAt) ? openedAt : null));
    }

    /** 세션이 끝나면 그 세션의 유예 창은 판정할 이유가 없다. */
    public void discardSession(Long sessionId) {
        afterCommit(() -> disconnectedAt.keySet()
                .removeIf(key -> key.sessionId().equals(sessionId)));
    }

    /** 유예를 넘긴 창 목록. 판정 자체는 호출자(세션 서비스)가 한다. */
    public List<Key> expired(LocalDateTime now, int graceSeconds) {
        return disconnectedAt.entrySet().stream()
                .filter(entry -> !entry.getValue().plusSeconds(graceSeconds).isAfter(now))
                .map(Map.Entry::getKey)
                .toList();
    }

    /**
     * 트랜잭션이 있으면 커밋 뒤에, 없으면 지금 실행한다. 클래스 주석의 "롤백이 되돌려 주지
     * 않는다"가 이 분기의 전부다.
     */
    private void afterCommit(Runnable change) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            change.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                change.run();
            }
        });
    }
}
