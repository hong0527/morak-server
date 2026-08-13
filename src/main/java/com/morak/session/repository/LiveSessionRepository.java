package com.morak.session.repository;

import com.morak.session.entity.LiveSession;
import com.morak.session.type.SessionStatus;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 2단계는 생성만 썼다. 조회(SS-1)·웹훅 역참조(SS-10)가 3단계에서 붙었고, 종료 배치(B1)가
 * 5단계에서 붙었다.
 */
public interface LiveSessionRepository extends JpaRepository<LiveSession, Long> {

    /** SS-10이 방 이름으로 세션을 되짚는 경로. uk_ls_room이 유일성을 보장한다. */
    Optional<LiveSession> findByLivekitRoomName(String livekitRoomName);

    /**
     * 세션 행을 FOR UPDATE로 잡는다. <b>참가자 상태를 바꾸면서 세션 종료 판정까지 하는 경로는
     * 예외 없이 이 조회로 시작한다</b>({@link com.morak.session.service.SessionClosingService}
     * 잠금 순서 주석).
     *
     * <p>잠그지 않으면 잔여 인원 판정이 성립하지 않는다. 여섯 명이 동시에 퇴출되면 각
     * 트랜잭션이 아직 커밋되지 않은 남들을 "남아 있는 사람"으로 세어 전부 종료를 건너뛰고,
     * 참가자가 0명인 세션이 LIVE로 남는다. 이 행을 잡아 직렬화해야 마지막 한 명이 앞선
     * 커밋들을 보고 닫는다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT ls
              FROM LiveSession ls
             WHERE ls.id = :sessionId
            """)
    Optional<LiveSession> findByIdForUpdate(@Param("sessionId") Long sessionId);

    /**
     * AD-7 세션 모니터의 상태 필터. {@code status IS NULL}을 한 쿼리에 섞지 않고 메서드를
     * 나눈 이유는 파라미터가 null인 enum 비교가 드라이버마다 다르게 풀려서다 — 조건이 조용히
     * 빠지면 필터가 걸린 줄 알고 전체를 받는다(SessionParticipantRepository의 SS-9와 같은 이유).
     * 필터가 없을 때는 {@code findAll(Pageable)}을 그대로 쓴다.
     */
    Page<LiveSession> findByStatus(SessionStatus status, Pageable pageable);

    /**
     * B1이 종료할 세션. 엔티티가 아니라 id만 읽는 이유는 세션 하나가 트랜잭션 하나이기
     * 때문이다 — 목록을 읽은 트랜잭션 안에서 전부 처리하면 한 세션의 정산 실패가 그날의
     * 나머지 세션까지 되돌린다. {@code idx_ls_batch(status, ends_at)}가 이 조회를 받친다.
     */
    @Query("""
            SELECT ls.id
              FROM LiveSession ls
             WHERE ls.status = :status
               AND ls.endsAt <= :now
             ORDER BY ls.id
            """)
    List<Long> findIdsToClose(@Param("status") SessionStatus status,
                              @Param("now") LocalDateTime now);
}
