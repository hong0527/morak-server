package com.morak.member;

import static org.assertj.core.api.Assertions.assertThat;

import com.morak.common.type.BadgeCode;
import com.morak.member.dto.request.GoalRequest;
import com.morak.member.dto.response.GoalResponse;
import com.morak.member.dto.response.MemberMeResponse;
import com.morak.member.service.MemberService;
import com.morak.session.service.SessionClosingBatch;
import com.morak.support.IntegrationTest;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 목표 진행도(progressDays)와 보유 뱃지. 달성 판정(§0-6)은 연속 캐시 AND 시작일 이후 완주일
 * 수의 두 조건인데 화면이 연속만 알면, 달성 직후 새로 건 목표가 이전 연속 때문에 "기간/기간
 * 직전"으로 그려진다 — 서버가 이중 지급을 막으려 도입한 바로 그 조건을 화면이 모르는 상태다.
 */
@DisplayName("목표 진행도와 뱃지")
class GoalProgressTest extends IntegrationTest {

    private static final int TARGET_MINUTES = 60;
    private static final int GOAL_PERIOD_DAYS = 7;
    private static final LocalDate DAY_ONE = BASE_TIME.toLocalDate();

    @Autowired
    private MemberService memberService;

    @Autowired
    private SessionClosingBatch sessionClosingBatch;

    @Test
    @DisplayName("이전 연속은 진행에 잡히지 않는다")
    void 이전_연속은_진행이_아니다() {
        // 이 테스트가 죽으면: 진행률이 연속 캐시 하나로 그려지는 것이다. 7일을 채워 달성한
        // 회원이 새 목표를 걸면 화면이 곧바로 "7/7 직전"을 보여 주고, 다음 날 완주해도
        // 달성이 나오지 않아 서버가 고장난 것으로 읽힌다.
        Long memberId = fixtures.joinMember();
        for (int day = 0; day < GOAL_PERIOD_DAYS; day++) {
            completeDay(memberId, DAY_ONE.plusDays(day));
        }
        LocalDate nextDay = DAY_ONE.plusDays(GOAL_PERIOD_DAYS);
        clock.fixAt(nextDay.atTime(8, 0));
        GoalResponse newGoal = memberService.setGoal(memberId, new GoalRequest(GOAL_PERIOD_DAYS));

        // 연속은 7일이지만 새 목표 시작일 이후의 완주는 아직 없다
        assertThat(newGoal.progressDays()).isZero();

        completeDay(memberId, nextDay);

        GoalResponse afterOneDay = memberService.getMe(memberId).goal();
        assertThat(afterOneDay.progressDays()).isEqualTo(1);
        assertThat(memberService.getMe(memberId).streak().current())
                .isEqualTo(GOAL_PERIOD_DAYS + 1);
    }

    @Test
    @DisplayName("오늘 완주한 뒤 건 목표는 설정 직후 진행이 1이다")
    void 완주_후_설정한_목표는_그날부터_센다() {
        // 이 테스트가 죽으면: 응답과 달성 판정이 다른 식을 쓰는 것이다. 판정은 시작일을
        // 포함해 세므로(>= started_on) 응답도 그 하루를 보여야 판정과 화면이 같은 날 달성한다.
        Long memberId = fixtures.joinMember();
        completeDay(memberId, DAY_ONE);

        GoalResponse goal = memberService.setGoal(memberId, new GoalRequest(GOAL_PERIOD_DAYS));

        assertThat(goal.progressDays()).isEqualTo(1);
    }

    @Test
    @DisplayName("달성한 목표의 진행은 기간값으로 고정되고 뱃지가 실린다")
    void 달성_목표는_기간값이고_뱃지가_파생된다() {
        // 이 테스트가 죽으면: 달성 뒤에도 완주가 쌓일 때마다 닫힌 목표의 진행이 계속 자라거나,
        // 뱃지를 지급 순간(SS-8)에만 볼 수 있고 마이페이지에서 볼 수 없는 것이다.
        Long memberId = fixtures.joinMember();
        clock.fixAt(DAY_ONE.atTime(8, 0));
        memberService.setGoal(memberId, new GoalRequest(GOAL_PERIOD_DAYS));
        for (int day = 0; day <= GOAL_PERIOD_DAYS; day++) {
            completeDay(memberId, DAY_ONE.plusDays(day));
        }

        MemberMeResponse me = memberService.getMe(memberId);

        assertThat(me.goal().progressDays()).isEqualTo(GOAL_PERIOD_DAYS);
        assertThat(me.badges()).hasSize(1);
        assertThat(me.badges().getFirst().code()).isEqualTo(BadgeCode.GOAL_ACHIEVED);
        assertThat(me.badges().getFirst().earnedAt()).isNotNull();
    }

    @Test
    @DisplayName("달성이 없으면 뱃지는 빈 배열이다")
    void 미달성은_빈_배열이다() {
        Long memberId = fixtures.joinMember();

        assertThat(memberService.getMe(memberId).badges()).isEmpty();
    }

    /** 하루치 완주. 세션 생성부터 종료·지급까지 B1과 같은 경로를 지난다. */
    private void completeDay(Long memberId, LocalDate day) {
        fixtures.openSession(TARGET_MINUTES, day.atTime(9, 0), List.of(memberId));
        clock.fixAt(day.atTime(10, 30));
        sessionClosingBatch.run();
    }
}
