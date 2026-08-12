package com.morak.member.repository;

import com.morak.member.entity.MemberGoal;
import com.morak.member.type.GoalStatus;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemberGoalRepository extends JpaRepository<MemberGoal, Long> {

    /** AU-7의 활성 1건 검사. 회원 행을 잠근 뒤에 불러야 동시 요청이 둘 다 통과하지 않는다. */
    boolean existsByMemberIdAndStatus(Long memberId, GoalStatus status);

    /**
     * AU-2가 내려주는 목표. 상태로 거르지 않고 가장 최근 행을 준다 — 달성(ACHIEVED)한 목표도
     * 달성 시각과 함께 화면에 남아야 하고, 명세의 {@code goal: null}은 "목표를 한 번도 설정하지
     * 않음"을 뜻하기 때문이다.
     */
    Optional<MemberGoal> findFirstByMemberIdOrderByIdDesc(Long memberId);

    /** B1 목표 달성 검사(★D3). 활성 목표는 최대 1건이지만 순서를 고정해 둔다. */
    Optional<MemberGoal> findFirstByMemberIdAndStatusOrderByIdDesc(Long memberId, GoalStatus status);

    /**
     * SS-8의 {@code goalAchieved}. 달성 시각을 세션 종료 시각으로 못 박아 기록하기 때문에
     * (B1이 {@code endedAt}을 그대로 넘긴다) 이 비교가 "이 세션이 목표를 채웠는가"와 같다.
     * 저장 컬럼을 새로 두는 대신 이미 있는 값으로 답한다.
     */
    boolean existsByMemberIdAndStatusAndAchievedAt(Long memberId, GoalStatus status,
                                                   LocalDateTime achievedAt);
}
