package com.morak.match.repository;

import com.morak.match.entity.MatchRequest;
import com.morak.match.type.MatchRequestStatus;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MatchRequestRepository extends JpaRepository<MatchRequest, Long> {

    /**
     * 활성 요청 조회. status가 아니라 {@code activeMemberId}로 찾는다 — 이 컬럼에 걸린
     * {@code uk_mr_active}가 "회원당 활성 요청 1건"의 실제 방어선이므로, 조회도 같은 컬럼을
     * 봐야 판정과 제약이 어긋나지 않는다.
     */
    Optional<MatchRequest> findByActiveMemberId(Long memberId);

    /** MT-2. 활성 요청이 없으면 가장 최근에 종결된 요청을 돌려준다(성사·만료 안내). */
    Optional<MatchRequest> findFirstByMemberIdOrderByIdDesc(Long memberId);

    long countByStatusAndTargetMinutes(MatchRequestStatus status, int targetMinutes);

    /**
     * 대기열. 선착순이 매칭 순서의 전부라 {@code requestedAt} 오름차순이고, 같은 시각이
     * 겹칠 때를 대비해 id를 2차 정렬로 둔다 — 순서가 흔들리면 같은 입력에 다른 6인이 나온다.
     */
    List<MatchRequest> findByStatusAndTargetMinutesOrderByRequestedAtAscIdAsc(
            MatchRequestStatus status, int targetMinutes);

    List<MatchRequest> findByStatusAndTargetMinutesAndExpiresAtLessThan(
            MatchRequestStatus status, int targetMinutes, LocalDateTime now);

    /** B2가 처리할 조건만 추린다. 만료 대기가 없는 조건까지 잠글 이유가 없다. */
    @Query("""
            SELECT DISTINCT mr.targetMinutes
              FROM MatchRequest mr
             WHERE mr.status = :status
               AND mr.expiresAt < :now
            """)
    List<Integer> findTargetMinutesWithExpired(@Param("status") MatchRequestStatus status,
                                               @Param("now") LocalDateTime now);

    /**
     * 성사 확정(MT-1 9·12단계). {@code WHERE status = WAITING}이 경합 손실을 막고,
     * 반환된 영향 행 수가 6인지로 선착순 확정을 검증한다 — 미달이면 호출자가 예외를 던져
     * 세션·참가자까지 통째로 롤백시킨다.
     *
     * <p>상태 변경과 {@code activeMemberId} 해제를 같은 UPDATE에 넣은 이유는 3종 세트를
     * 물리적으로 분리 불가능하게 만들기 위해서다. 해제를 빠뜨리면 {@code uk_mr_active}가
     * 그 회원의 재요청을 영구히 막는다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE MatchRequest mr
               SET mr.status = :matched,
                   mr.matchedSessionId = :sessionId,
                   mr.activeMemberId = NULL
             WHERE mr.id IN :ids
               AND mr.status = :waiting
            """)
    int markMatched(@Param("ids") Collection<Long> ids,
                    @Param("sessionId") Long sessionId,
                    @Param("matched") MatchRequestStatus matched,
                    @Param("waiting") MatchRequestStatus waiting);

    /**
     * 대기 종료(MT-3 취소·B2 만료·AU-4 탈퇴). 성사와 같은 조건부 UPDATE에 세션 배정만 없다.
     * 이미 성사됐거나 종료된 요청은 조건에 걸려 0행이 되고, 호출자가 그 0을 보고 분기한다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE MatchRequest mr
               SET mr.status = :terminal,
                   mr.activeMemberId = NULL
             WHERE mr.id IN :ids
               AND mr.status = :waiting
            """)
    int releaseWaiting(@Param("ids") Collection<Long> ids,
                       @Param("terminal") MatchRequestStatus terminal,
                       @Param("waiting") MatchRequestStatus waiting);

    /**
     * 탈퇴 파기(B4)가 회원의 매칭 요청 이력을 지운다. 활성 요청은 AU-4가 이미 종료시킨 뒤라
     * 여기서 지우는 것은 종결된 행뿐이다.
     */
    void deleteByMemberId(Long memberId);
}
