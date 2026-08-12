package com.morak.session.repository;

import com.morak.session.entity.SessionParticipant;
import com.morak.session.type.ParticipantStatus;
import com.morak.session.type.SessionStatus;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SessionParticipantRepository extends JpaRepository<SessionParticipant, Long> {

    /** SS-1·SS-2·SS-3·SS-10이 참가자 한 명을 특정하는 유일한 경로. uk_sp가 유일성을 보장한다. */
    Optional<SessionParticipant> findBySessionIdAndMemberId(Long sessionId, Long memberId);

    /** SS-1 참가자 목록. 자리 순서가 매번 바뀌지 않도록 생성 순으로 고정한다. */
    List<SessionParticipant> findBySessionIdOrderByIdAsc(Long sessionId);

    /**
     * AD-7 모니터가 한 페이지분 세션의 참가자를 한 번에 읽는다. 세션마다 따로 조회하면
     * 목록 한 화면에 페이지 크기만큼의 쿼리가 나간다.
     */
    List<SessionParticipant> findBySessionIdInOrderByIdAsc(Collection<Long> sessionIds);

    /** 조기 종료 판정(D12)과 종료 시 완주 처리가 함께 쓰는 "지금 남아 있는 사람" 조회. */
    List<SessionParticipant> findBySessionIdAndStatusIn(Long sessionId,
                                                        Collection<ParticipantStatus> statuses);

    /**
     * 지금 참여 중인 세션. 탈퇴(AU-4)가 진행 중인 세션에서 빠져나올 때 쓴다.
     * 참가자 상태만 보면 끝난 세션의 ACTIVE 행까지 걸리므로 세션 상태를 함께 본다.
     */
    @Query("""
            SELECT sp
              FROM SessionParticipant sp
             WHERE sp.memberId = :memberId
               AND sp.status IN :participantStatuses
               AND sp.sessionId IN (
                     SELECT ls.id FROM LiveSession ls WHERE ls.status = :sessionStatus)
            """)
    List<SessionParticipant> findParticipating(@Param("memberId") Long memberId,
                                               @Param("participantStatuses")
                                               Collection<ParticipantStatus> participantStatuses,
                                               @Param("sessionStatus") SessionStatus sessionStatus);

    /**
     * SS-5 화장실 모드 시작. <b>조건부 UPDATE가 "세션당 1회"의 실제 방어선이다.</b> 서비스에서
     * {@code pauseUsed}를 읽고 if로 막으면 동시에 들어온 두 요청이 둘 다 통과한 뒤 나중 것이
     * 앞의 시작 시각을 덮어써, 10분 타이머가 계속 연장된다.
     *
     * <p>영속성 컨텍스트를 우회하는 벌크 연산이라 flush·clear를 함께 건다. 남겨 두면 같은
     * 트랜잭션에서 다시 읽은 참가자가 UPDATE 이전 상태로 보인다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE SessionParticipant sp
               SET sp.status = :paused, sp.pauseUsed = true, sp.pauseStartedAt = :now
             WHERE sp.sessionId = :sessionId
               AND sp.memberId = :memberId
               AND sp.status = :active
               AND sp.pauseUsed = false
            """)
    int startPause(@Param("sessionId") Long sessionId,
                   @Param("memberId") Long memberId,
                   @Param("now") LocalDateTime now,
                   @Param("active") ParticipantStatus active,
                   @Param("paused") ParticipantStatus paused);

    /**
     * B1의 두 번째 지급 대상 — <b>이미 끝난 세션의 미지급 완주자</b>. 조기 종료(D12)와
     * {@code room_finished} 웹훅은 종료·완주 마킹까지만 하고 포인트를 만들지 않으므로,
     * 이 흡수 경로가 없으면 그 완주자들이 영구 미지급으로 남는다.
     *
     * <p>{@code point_awarded = 0}이 미지급의 표식이 될 수 있는 것은 완주 지급액이 최소
     * 60분 세션에서도 100이라 0이 나올 수 없기 때문이다.
     */
    @Query("""
            SELECT sp.id
              FROM SessionParticipant sp
             WHERE sp.completed = true
               AND sp.pointAwarded = 0
               AND sp.sessionId IN (
                     SELECT ls.id FROM LiveSession ls WHERE ls.status = :sessionStatus)
             ORDER BY sp.id
            """)
    List<Long> findIdsAwaitingAward(@Param("sessionStatus") SessionStatus sessionStatus);

    /** SS-9 내 세션 이력. 필터 없는 경우. */
    Page<SessionParticipant> findByMemberId(Long memberId, Pageable pageable);

    /**
     * AD-2가 신고 대상자의 최근 참여를 훑는다. 관리자 상세는 페이지가 아니라 한 화면이라
     * 개수를 여기서 자른다 — 판단에 쓰는 것은 최근 이력이다.
     */
    List<SessionParticipant> findTop20ByMemberIdOrderByIdDesc(Long memberId);

    /**
     * SS-9 세션 상태 필터. {@code status IS NULL}을 한 쿼리에 섞지 않고 메서드를 나눈 이유는
     * 파라미터가 null인 enum 비교가 드라이버마다 다르게 풀려서다 — 조건이 조용히 빠지면
     * 필터가 걸린 줄 알고 전체를 받는다.
     */
    @Query("""
            SELECT sp
              FROM SessionParticipant sp
             WHERE sp.memberId = :memberId
               AND sp.sessionId IN (
                     SELECT ls.id FROM LiveSession ls WHERE ls.status = :sessionStatus)
            """)
    Page<SessionParticipant> findByMemberIdAndSessionStatus(
            @Param("memberId") Long memberId,
            @Param("sessionStatus") SessionStatus sessionStatus,
            Pageable pageable);

    /**
     * 지금 세션에 묶여 있는 회원을 골라낸다. MT-1의 ③ 활성 세션 검사와 ⑦ 후보 제외가
     * 같은 질문이라 쿼리를 하나로 둔다 — 둘이 갈라지면 "요청은 막히는데 남의 후보로는
     * 뽑히는" 상태가 생긴다.
     *
     * <p>참가자 상태만으로는 부족하다. 세션이 끝나도 참가자 행의 status는 ACTIVE로 남기
     * 때문에(완주 판정의 근거라 되돌리지 않는다) 세션이 LIVE인지를 함께 본다.
     */
    @Query("""
            SELECT sp.memberId
              FROM SessionParticipant sp
             WHERE sp.memberId IN :memberIds
               AND sp.status IN :participantStatuses
               AND sp.sessionId IN (
                     SELECT ls.id FROM LiveSession ls WHERE ls.status = :sessionStatus)
            """)
    List<Long> findMemberIdsInSession(@Param("memberIds") Collection<Long> memberIds,
                                      @Param("participantStatuses")
                                      Collection<ParticipantStatus> participantStatuses,
                                      @Param("sessionStatus") SessionStatus sessionStatus);
}
