package com.morak.member.repository;

import com.morak.member.entity.StreakDay;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 완주일 기록. 쓰기는 5단계 B1(세션 종료 배치)이 하고, 읽기는 SS-8의 세션 시점 Streak가 쓴다.
 *
 * <p>AU-2의 Streak는 {@code member.current_streak} 캐시에서 읽는다. 이 테이블은 그 캐시의
 * 진실이지만 조회 때마다 역방향 연속 일수를 세는 것은 홈 화면 응답에 넣을 비용이 아니다.
 */
public interface StreakDayRepository extends JpaRepository<StreakDay, Long> {

    Optional<StreakDay> findByMemberIdAndCompletedOn(Long memberId, LocalDate completedOn);

    /**
     * 기준일 이하의 완주일을 최근 순으로. SS-8이 "그 세션 시점의 Streak"를 역방향으로 세는
     * 재료다 — 지금 값인 {@code member.current_streak}를 쓰면 과거 세션 결과를 다시 열었을 때
     * 그때가 아닌 오늘의 연속 일수가 나온다.
     */
    @Query("""
            SELECT sd.completedOn
              FROM StreakDay sd
             WHERE sd.memberId = :memberId
               AND sd.completedOn <= :date
             ORDER BY sd.completedOn DESC
            """)
    List<LocalDate> findCompletedOnUpTo(@Param("memberId") Long memberId,
                                        @Param("date") LocalDate date,
                                        Pageable pageable);

    /**
     * 완주일 전체를 최근 순으로. AD-6 인용이 소급 완주를 넣은 뒤 캐시를 다시 셀 때 쓴다.
     *
     * <p>{@link #findCompletedOnUpTo}와 달리 <b>상한을 두지 않는다.</b> 재계산에 상한을 걸면
     * 그 뒤의 완주일이 집계에서 빠져 {@code last_completed_on}이 과거로 되돌아간다 —
     * 되살린 하루가 오히려 연속을 깎는 결과가 된다.
     */
    @Query("""
            SELECT sd.completedOn
              FROM StreakDay sd
             WHERE sd.memberId = :memberId
             ORDER BY sd.completedOn DESC
            """)
    List<LocalDate> findAllCompletedOn(@Param("memberId") Long memberId, Pageable pageable);

    /** 만 14세 미만 파기(AU-3)와 탈퇴 파기(B4)가 회원의 완주 기록을 함께 지운다. */
    void deleteByMemberId(Long memberId);
}
