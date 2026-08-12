package com.morak.match.repository;

import com.morak.match.entity.MatchBlock;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MatchBlockRepository extends JpaRepository<MatchBlock, Long> {

    /**
     * 후보 집합 안에서만 차단 관계를 읽는다. 대기열에서 사람을 빼는 게 아니라 조합에서
     * 배제하는 방식이라(★D6), 필요한 것은 "이 사람들 사이에 차단 쌍이 있는가"뿐이다.
     *
     * <p>양방향을 따로 묻지 않는 이유는 RP-1이 신고 1건마다 2행을 넣기 때문이 아니다 —
     * 한 행만 남는 사고가 나더라도 배제가 성립하도록 호출자가 방향을 무시하고 대칭으로
     * 취급한다.
     */
    @Query("""
            SELECT mb
              FROM MatchBlock mb
             WHERE mb.memberId IN :memberIds
               AND mb.blockedMemberId IN :memberIds
            """)
    List<MatchBlock> findWithin(@Param("memberIds") Collection<Long> memberIds);

    /**
     * RP-1이 등재 전에 확인한다. 종결된 케이스의 상대를 다시 신고하는 경로가 있어
     * (재검토는 새 케이스다) 같은 쌍이 두 번 들어올 수 있기 때문이다.
     *
     * <p>이것은 편의 검사이지 방어선이 아니다 — 동시 신고 두 건이 함께 통과하면 uk_mb가
     * 뒤에 온 트랜잭션을 되돌린다. 차단이 한 행도 없이 끝나는 경우는 그래서 없다.
     */
    boolean existsByMemberIdAndBlockedMemberId(Long memberId, Long blockedMemberId);
}
