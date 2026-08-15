package com.morak.member.repository;

import com.morak.member.entity.MemberGoal;
import com.morak.member.type.GoalStatus;
import java.util.List;
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

    /** AU-2 뱃지 파생. 뱃지는 별도 테이블 없이 ACHIEVED 목표 행에서 파생한다(★D3). */
    List<MemberGoal> findByMemberIdAndStatus(Long memberId, GoalStatus status);

    /**
     * SS-8의 {@code goalAchieved}. 달성을 성립시킨 세션을 목표 행이 직접 들고 있으므로
     * 이 조회가 곧 "이 세션이 목표를 채웠는가"다. 예전의 시각 동등 비교와 달리 쓰는 값과
     * 읽는 값이 같은 컬럼이라, 달성 시각을 어떻게 잡든 답이 흔들리지 않는다.
     */
    boolean existsByMemberIdAndAchievedSessionId(Long memberId, Long achievedSessionId);

    /** 탈퇴 파기(B4)가 회원의 목표 이력을 함께 지운다. */
    void deleteByMemberId(Long memberId);
}
