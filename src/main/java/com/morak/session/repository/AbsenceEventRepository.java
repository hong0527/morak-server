package com.morak.session.repository;

import com.morak.session.entity.AbsenceEvent;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /** 탈퇴 파기(B4)가 회원의 자리비움 관측 기록을 지운다. */
    void deleteByMemberId(Long memberId);

    /**
     * AD-9가 경고의 근거 END에서 짝 START를 되짚는다. 판정(★D4)이 "END 직전 이벤트가
     * START인가"로 짝을 지었으므로, 같은 규칙(id가 바로 앞)으로 읽어야 판정과 같은 구간이
     * 나온다 — clientSeq 순으로 읽으면 순번을 어긴 단말에서 판정과 다른 구간을 보여 준다.
     */
    Optional<AbsenceEvent> findFirstBySessionIdAndMemberIdAndIdLessThanOrderByIdDesc(
            Long sessionId, Long memberId, Long id);

    /**
     * AD-9의 공통 원인 신호. 같은 구간에 미검출을 보고한 <b>다른</b> 참가자 수만 센다 —
     * 명단이 아니라 집계인 것이 계약이다. 누가 보고했는지가 응답에 실리면 이의 심사가
     * 같은 세션 참가자들의 행동 기록을 열람하는 경로가 된다.
     */
    @Query("""
            SELECT COUNT(DISTINCT e.memberId)
              FROM AbsenceEvent e
             WHERE e.sessionId = :sessionId
               AND e.memberId <> :memberId
               AND e.occurredAt BETWEEN :from AND :to
            """)
    long countOtherReporters(@Param("sessionId") Long sessionId,
                             @Param("memberId") Long memberId,
                             @Param("from") LocalDateTime from,
                             @Param("to") LocalDateTime to);
}
