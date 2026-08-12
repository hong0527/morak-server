package com.morak.session.repository;

import com.morak.session.entity.AbsenceEvent;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AbsenceEventRepository extends JpaRepository<AbsenceEvent, Long> {

    /** SS-4 멱등 1차 방어. 동시 요청은 이 검사를 함께 통과할 수 있어 uk_ae가 최종 방어다. */
    boolean existsBySessionIdAndMemberIdAndClientSeq(Long sessionId, Long memberId, long clientSeq);

    /**
     * 같은 참가자의 직전 이벤트. 레이트리밋 기준(수신 시각)과 END의 짝(직전 미종료 START)을
     * 한 행이 함께 답한다 — 직전이 START면 열려 있는 자리비움이고, END면 짝이 없다.
     *
     * <p>clientSeq가 아니라 id 순인 것은 도착 순서가 판정 순서이기 때문이다. 단조 증가를
     * 어긴 시퀀스를 보내도 서버 판정이 뒤로 밀리지 않는다.
     */
    Optional<AbsenceEvent> findFirstBySessionIdAndMemberIdOrderByIdDesc(Long sessionId,
                                                                        Long memberId);
}
