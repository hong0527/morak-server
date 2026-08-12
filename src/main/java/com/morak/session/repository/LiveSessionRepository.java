package com.morak.session.repository;

import com.morak.session.entity.LiveSession;
import com.morak.session.type.SessionStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
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
