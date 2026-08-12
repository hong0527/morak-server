package com.morak.member.repository;

import com.morak.member.entity.StreakDay;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 완주일 기록. 쓰기 경로는 5단계 B1(세션 종료 배치)에서 생긴다 — 이 단계에는 조회만 있다.
 *
 * <p>AU-2의 Streak는 {@code member.current_streak} 캐시에서 읽는다. 이 테이블은 그 캐시의
 * 진실이지만 조회 때마다 역방향 연속 일수를 세는 것은 홈 화면 응답에 넣을 비용이 아니다.
 */
public interface StreakDayRepository extends JpaRepository<StreakDay, Long> {

    Optional<StreakDay> findByMemberIdAndCompletedOn(Long memberId, LocalDate completedOn);

    /** 만 14세 미만 파기(AU-3)와 탈퇴 파기(B4)가 회원의 완주 기록을 함께 지운다. */
    void deleteByMemberId(Long memberId);
}
