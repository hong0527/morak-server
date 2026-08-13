package com.morak.member;

import static org.assertj.core.api.Assertions.assertThat;

import com.morak.member.dto.request.GoalRequest;
import com.morak.member.entity.MemberGoal;
import com.morak.member.repository.MemberGoalRepository;
import com.morak.member.service.MemberService;
import com.morak.member.type.GoalStatus;
import com.morak.session.service.SessionClosingBatch;
import com.morak.support.IntegrationTest;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 목표 달성 판정(★D3). 판정 조건이 둘인 이유를 확인하는 자리다 — 연속 캐시만 보면 같은 연속을
 * 여러 번 팔 수 있고, 시작일 이후 완주일만 보면 중간에 끊긴 기간도 달성이 된다.
 */
@DisplayName("목표 달성 판정")
class GoalAchievementTest extends IntegrationTest {

    private static final int TARGET_MINUTES = 60;
    private static final int GOAL_PERIOD_DAYS = 7;
    private static final LocalDate DAY_ONE = BASE_TIME.toLocalDate();

    @Autowired
    private MemberService memberService;

    @Autowired
    private MemberGoalRepository memberGoalRepository;

    @Autowired
    private SessionClosingBatch sessionClosingBatch;

    @Test
    @DisplayName("7일 연속 완주로 목표를 달성한다")
    void 연속_7일_완주가_목표를_달성시킨다() {
        // 이 테스트가 죽으면: 완주가 목표 검사로 이어지지 않거나 달성 시점이 어긋난 것이다.
        Long memberId = fixtures.joinMember();
        Long goalId = startGoal(memberId, DAY_ONE);

        for (int day = 0; day < GOAL_PERIOD_DAYS - 1; day++) {
            completeDay(memberId, DAY_ONE.plusDays(day));
        }
        assertThat(goal(goalId).getStatus()).isEqualTo(GoalStatus.ACTIVE);
        assertThat(achievedCount(memberId)).isZero();

        completeDay(memberId, DAY_ONE.plusDays(GOAL_PERIOD_DAYS - 1L));

        assertThat(goal(goalId).getStatus()).isEqualTo(GoalStatus.ACHIEVED);
        assertThat(fixtures.member(memberId).getCurrentStreak()).isEqualTo(GOAL_PERIOD_DAYS);
        assertThat(fixtures.count("point_ledger",
                "member_id = ? AND reason = 'GOAL_ACHIEVED' AND ref_id = ?", memberId, goalId))
                .isEqualTo(1);
        assertThat(fixtures.member(memberId).getPointBalance())
                .isEqualTo(fixtures.ledgerSum(memberId));
    }

    @Test
    @DisplayName("달성 직후 다시 건 목표는 하루 완주로 달성되지 않는다")
    void 재설정한_목표는_하루_완주로_달성되지_않는다() {
        // 이 테스트가 죽으면: 판정이 연속 캐시 하나로 돌아간 것이다. 7일을 채운 회원이 목표를
        // 다시 걸고 다음 날 한 번만 완주해도 1,000p를 또 받는다 — 같은 연속을 여러 번 파는 길이
        // 열린다.
        Long memberId = fixtures.joinMember();
        Long firstGoalId = startGoal(memberId, DAY_ONE);
        for (int day = 0; day < GOAL_PERIOD_DAYS; day++) {
            completeDay(memberId, DAY_ONE.plusDays(day));
        }
        assertThat(goal(firstGoalId).getStatus()).isEqualTo(GoalStatus.ACHIEVED);

        LocalDate nextDay = DAY_ONE.plusDays(GOAL_PERIOD_DAYS);
        Long secondGoalId = startGoal(memberId, nextDay);
        completeDay(memberId, nextDay);

        // 연속은 8일이지만 새 목표 시작일 이후로 쌓인 완주일은 하루뿐이다
        assertThat(fixtures.member(memberId).getCurrentStreak()).isEqualTo(GOAL_PERIOD_DAYS + 1);
        assertThat(goal(secondGoalId).getStatus()).isEqualTo(GoalStatus.ACTIVE);
        assertThat(achievedCount(memberId)).isEqualTo(1);
    }

    @Test
    @DisplayName("하루에 두 세션을 완주해도 연속은 하루만 오른다")
    void 하루_두_세션은_연속을_하루만_올린다() {
        // 이 테스트가 죽으면: Streak 캐시를 올리는 경로가 streak_day INSERT와 갈라진 것이다.
        // 하루에 2씩 오르는 회원이 나오고 목표가 절반 기간에 달성된다(★D2).
        Long memberId = fixtures.joinMember();
        fixtures.openSession(TARGET_MINUTES, DAY_ONE.atTime(9, 0), List.of(memberId));
        fixtures.openSession(TARGET_MINUTES, DAY_ONE.atTime(13, 0), List.of(memberId));
        clock.fixAt(DAY_ONE.atTime(15, 0));

        int processed = sessionClosingBatch.run();

        assertThat(processed).isEqualTo(2);
        assertThat(fixtures.count("streak_day", "member_id = ?", memberId)).isEqualTo(1);
        assertThat(fixtures.member(memberId).getCurrentStreak()).isEqualTo(1);
        // 완주 지급은 세션마다 따로 남는다 — 하루 1행인 것은 완주일이지 지급이 아니다
        assertThat(fixtures.count("point_ledger",
                "member_id = ? AND reason = 'SESSION_COMPLETE'", memberId)).isEqualTo(2);
        assertThat(fixtures.member(memberId).getPointBalance())
                .isEqualTo(fixtures.ledgerSum(memberId));
    }

    /** 목표는 설정한 날짜부터 센다. 시작일을 정하려면 그날로 시계를 옮긴 뒤 걸어야 한다. */
    private Long startGoal(Long memberId, LocalDate startedOn) {
        clock.fixAt(startedOn.atTime(8, 0));
        memberService.setGoal(memberId, new GoalRequest(GOAL_PERIOD_DAYS));
        return memberGoalRepository.findFirstByMemberIdOrderByIdDesc(memberId)
                .orElseThrow()
                .getId();
    }

    /** 하루치 완주. 세션 생성부터 종료·지급까지 B1과 같은 경로를 지난다. */
    private void completeDay(Long memberId, LocalDate day) {
        fixtures.openSession(TARGET_MINUTES, day.atTime(9, 0), List.of(memberId));
        clock.fixAt(day.atTime(10, 30));
        sessionClosingBatch.run();
    }

    private MemberGoal goal(Long goalId) {
        return memberGoalRepository.findById(goalId).orElseThrow();
    }

    private int achievedCount(Long memberId) {
        return fixtures.count("point_ledger", "member_id = ? AND reason = 'GOAL_ACHIEVED'",
                memberId);
    }
}
