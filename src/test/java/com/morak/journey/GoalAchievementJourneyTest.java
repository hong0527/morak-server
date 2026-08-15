package com.morak.journey;

import static org.assertj.core.api.Assertions.assertThat;

import com.morak.member.dto.request.GoalRequest;
import com.morak.member.dto.response.GoalResponse;
import com.morak.member.dto.response.MemberMeResponse;
import com.morak.common.type.BadgeCode;
import com.morak.member.type.GoalStatus;
import com.morak.session.dto.response.SessionResultResponse;
import com.morak.session.service.SessionClosingBatch;
import com.morak.support.IntegrationTest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

/**
 * 여정 3 — 7일 목표를 걸고 7일을 채워 달성한다.
 *
 * <p>이 여정은 <b>하루가 아니라 일주일에 걸쳐 성립하는 계약</b>을 본다. 완주 하나하나는 다른
 * 테스트가 보지만, 그것이 이어져 연속이 되고 목표 진행이 되고 달성이 되는 사슬은 여기서만
 * 확인된다. 중간 하루가 엉뚱한 날짜로 기록되면 연속이 끊겨 달성이 영영 오지 않는데,
 * 그 사실은 7일째가 되어서야 드러난다.
 *
 * <p><b>자정을 넘기는 세션을 하루 섞는다.</b> 완주일의 귀속은 세션이 <b>시작한 날</b>이다
 * (§0-6) — 종료일로 적으면 23:30에 시작한 60분 세션이 시작한 날에 기록을 남기지 않아,
 * 심야에 매일 공부하는 사람의 연속이 매일 끊긴다.
 *
 * <p>세션에 앉히는 일은 픽스처로 한다. 매칭이 이어지는지는 여정 1이 이미 확인했고, 여기서
 * 7번 반복하면 확인하려는 사슬(완주 → 연속 → 목표)이 42명을 모으는 코드에 묻힌다.
 */
@DisplayName("여정 3 — 7일 목표 달성")
class GoalAchievementJourneyTest extends IntegrationTest {

    private static final int TARGET_MINUTES = 60;
    private static final int GOAL_PERIOD_DAYS = 7;
    /** 자정을 넘기는 날. 이 날의 세션만 23:30에 시작한다. */
    private static final int MIDNIGHT_DAY = 3;

    @Autowired
    private SessionClosingBatch sessionClosingBatch;

    @Value("${morak.point.welcome}")
    private int welcomePoint;

    @Value("${morak.point.session-complete-per-hour}")
    private int completePointPerHour;

    @Value("${morak.point.goal-achieved}")
    private int goalAchievedPoint;

    @Test
    @DisplayName("7일 연속 완주가 목표 달성·포인트·뱃지로 이어지고 새 목표를 걸 수 있다")
    void 목표_달성_여정() {
        // 이 테스트가 죽으면: 목표라는 기능 전체가 성립하지 않는다. 연속 계산·완주일 귀속·
        // 달성 판정 중 하나만 어긋나도 사용자는 7일을 채우고도 아무것도 받지 못한다.
        clock.fixAt(BASE_TIME);
        JourneySteps.Traveler me = JourneySteps.onboard(api, "journey3-runner");
        LocalDate startedOn = BASE_TIME.toLocalDate();

        // ── 1. 목표를 건다 ──
        GoalResponse goal = api.put("/api/members/me/goal", token(me),
                new GoalRequest(GOAL_PERIOD_DAYS), GoalResponse.class);
        assertThat(goal.status()).as("1단계: 건 목표가 진행 중이 아니다")
                .isEqualTo(GoalStatus.ACTIVE);
        assertThat(goal.startedOn()).as("1단계: 목표 시작일이 오늘이 아니다").isEqualTo(startedOn);

        // ── 2~8. 7일을 채운다 ── 하루가 끝날 때마다 연속과 진행도가 하루씩 오른다
        List<Long> sessionIds = new ArrayList<>();
        for (int day = 0; day < GOAL_PERIOD_DAYS; day++) {
            LocalDate date = startedOn.plusDays(day);
            Long sessionId = completeOneSession(me, date, day == MIDNIGHT_DAY);
            sessionIds.add(sessionId);

            int expected = day + 1;
            MemberMeResponse afterDay =
                    api.get("/api/members/me", token(me), MemberMeResponse.class);
            assertThat(afterDay.streak().current())
                    .as("%d일째(%s): 완주했는데 연속이 %d이 아니다", expected, date, expected)
                    .isEqualTo(expected);
            assertThat(afterDay.streak().lastCompletedOn())
                    .as("%d일째(%s): 마지막 완주일이 세션 시작일로 기록되지 않았다", expected, date)
                    .isEqualTo(date);

            // 완주일은 세션이 시작한 날에 붙는다. 자정을 넘긴 날에도 마찬가지다
            assertThat(fixtures.count("streak_day",
                    "member_id = ? AND completed_on = ?", me.memberId(), date))
                    .as("%d일째(%s): 완주일이 시작한 날에 기록되지 않았다%s", expected, date,
                            day == MIDNIGHT_DAY ? " — 자정을 넘긴 세션이 다음 날로 밀렸다" : "")
                    .isEqualTo(1);

            if (expected < GOAL_PERIOD_DAYS) {
                assertThat(afterDay.goal().status())
                        .as("%d일째: 아직 %d일인데 목표가 달성으로 닫혔다", expected, expected)
                        .isEqualTo(GoalStatus.ACTIVE);
                assertThat(afterDay.goal().progressDays())
                        .as("%d일째: 진행도가 완주일 수와 다르다", expected).isEqualTo(expected);
                assertThat(afterDay.badges())
                        .as("%d일째: 달성 전인데 뱃지가 나왔다", expected).isEmpty();
            }
        }

        // ── 9. 7일째의 세션 결과가 달성을 알린다 ──
        Long lastSessionId = sessionIds.get(GOAL_PERIOD_DAYS - 1);
        SessionResultResponse lastResult = api.get("/api/sessions/" + lastSessionId + "/result",
                token(me), SessionResultResponse.class);
        assertThat(lastResult.my().streak().after())
                .as("9단계: 7일째 결과의 연속이 7이 아니다").isEqualTo(GOAL_PERIOD_DAYS);
        assertThat(lastResult.my().goalAchieved())
                .as("9단계: 7일을 채운 세션이 달성을 알리지 않는다 — 사용자가 달성을 모른 채 지나간다")
                .isTrue();
        assertThat(lastResult.my().badgeCode())
                .as("9단계: 달성 세션의 결과에 뱃지가 실리지 않는다")
                .isEqualTo(BadgeCode.GOAL_ACHIEVED);

        // ── 10. 홈 화면의 목표·뱃지·포인트 ──
        MemberMeResponse achieved = api.get("/api/members/me", token(me), MemberMeResponse.class);
        assertThat(achieved.goal().status())
                .as("10단계: 7일을 채웠는데 목표가 달성으로 닫히지 않았다")
                .isEqualTo(GoalStatus.ACHIEVED);
        assertThat(achieved.goal().achievedAt()).as("10단계: 달성 시각이 없다").isNotNull();
        assertThat(achieved.goal().progressDays())
                .as("10단계: 달성한 목표의 진행이 기간값으로 고정되지 않았다")
                .isEqualTo(GOAL_PERIOD_DAYS);
        assertThat(achieved.badges())
                .as("10단계: 달성했는데 뱃지가 없다").hasSize(1);
        assertThat(achieved.badges().getFirst().code()).isEqualTo(BadgeCode.GOAL_ACHIEVED);

        int expectedBalance = welcomePoint
                + completePointPerHour * TARGET_MINUTES / 60 * GOAL_PERIOD_DAYS
                + goalAchievedPoint;
        assertThat(achieved.pointBalance())
                .as("10단계: 잔액이 웰컴+완주7회+목표달성 합과 다르다")
                .isEqualTo(expectedBalance);
        assertThat(fixtures.count("point_ledger",
                "member_id = ? AND reason = 'GOAL_ACHIEVED'", me.memberId()))
                .as("10단계: 목표 달성 지급이 한 번이 아니다 — 같은 연속을 다시 파는 경로가 열렸다")
                .isEqualTo(1);

        // ── 11. 달성 후에는 새 목표를 걸 수 있다 ──
        // 진행 중에는 못 걸지만(GOAL_ALREADY_ACTIVE) 닫힌 뒤에는 열려야 한다.
        // 여기가 막히면 목표를 한 번 달성한 사용자는 두 번 다시 목표를 세울 수 없다
        GoalResponse nextGoal = api.put("/api/members/me/goal", token(me),
                new GoalRequest(14), GoalResponse.class);
        assertThat(nextGoal.status()).as("11단계: 새로 건 목표가 진행 중이 아니다")
                .isEqualTo(GoalStatus.ACTIVE);
        assertThat(nextGoal.goalId()).as("11단계: 달성한 목표를 그대로 돌려준다")
                .isNotEqualTo(goal.goalId());
        // 이전 연속(7일)이 새 목표에 딸려 들어오면 하루만 더 완주해도 또 1,000p가 나간다
        // (§0-6 실측). 진행도가 1인 것은 옳다 — 오늘 완주를 마친 뒤 목표를 걸었으니 시작일
        // 오늘의 완주 하루가 세어진 것이고, 이전 6일은 시작일 이전이라 세어지지 않는다
        assertThat(nextGoal.progressDays())
                .as("11단계: 새 목표가 이전 연속 %d일을 이어받았다 — 같은 연속을 다시 팔 수 있다",
                        GOAL_PERIOD_DAYS)
                .isEqualTo(1);

        // 하루 더 완주해도 새 목표(14일)는 달성되지 않는다. 여기가 뚫리면 달성 포인트가
        // 이틀에 한 번씩 나가는 무한 지급이 된다
        completeOneSession(me, startedOn.plusDays(GOAL_PERIOD_DAYS), false);
        MemberMeResponse afterOneMore =
                api.get("/api/members/me", token(me), MemberMeResponse.class);
        assertThat(afterOneMore.goal().status())
                .as("11단계: 새 14일 목표가 하루 완주로 달성됐다")
                .isEqualTo(GoalStatus.ACTIVE);
        assertThat(fixtures.count("point_ledger",
                "member_id = ? AND reason = 'GOAL_ACHIEVED'", me.memberId()))
                .as("11단계: 목표 달성 지급이 두 번 나갔다")
                .isEqualTo(1);
    }

    /**
     * 하루치 세션을 열고 완주까지 닫는다. 반환은 세션 번호다.
     *
     * @param crossesMidnight 참이면 23:30에 시작해 다음 날 00:30에 끝난다
     */
    private Long completeOneSession(JourneySteps.Traveler me, LocalDate date,
                                    boolean crossesMidnight) {
        LocalDateTime startedAt = crossesMidnight
                ? date.atTime(23, 30)
                : date.atTime(10, 0);
        clock.fixAt(startedAt);
        List<Long> seats = new ArrayList<>(fixtures.joinMembers(1));
        seats.addFirst(me.memberId());
        Long sessionId = fixtures.openSession(TARGET_MINUTES, startedAt, seats);

        clock.fixAt(startedAt.plusMinutes(TARGET_MINUTES + 1));
        sessionClosingBatch.run();
        return sessionId;
    }

    /**
     * 토큰을 매번 다시 받는다. 여정이 7일짜리라 처음 받은 토큰은 24시간 뒤 만료되고,
     * 그때부터의 실패는 여정이 끊긴 것이 아니라 재로그인을 하지 않은 것이다 —
     * 실제 사용자도 그 사이 다시 로그인한다.
     */
    private String token(JourneySteps.Traveler me) {
        return fixtures.tokenOf(me.memberId());
    }
}
