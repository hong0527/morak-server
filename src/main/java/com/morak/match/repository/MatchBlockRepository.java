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
}
