package com.morak.session;

import static org.assertj.core.api.Assertions.assertThat;

import com.morak.member.dto.request.GoalRequest;
import com.morak.member.service.MemberService;
import com.morak.point.dto.response.PointBalanceResponse;
import com.morak.point.dto.response.PointLedgerItemResponse;
import com.morak.point.service.PointService;
import com.morak.point.type.PointReason;
import com.morak.session.dto.request.AbsenceEventRequest;
import com.morak.session.dto.request.AppealCreateRequest;
import com.morak.session.dto.request.AppealProcessRequest;
import com.morak.session.dto.response.SessionResultResponse;
import com.morak.session.service.AbsenceJudgeService;
import com.morak.session.service.AppealAdminService;
import com.morak.session.service.AppealService;
import com.morak.session.service.SessionClosingBatch;
import com.morak.session.service.SessionService;
import com.morak.session.type.AbsenceEventType;
import com.morak.session.type.AppealStatus;
import com.morak.support.IntegrationTest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

/**
 * 이의 인용(AD-6)이 이틀 뒤에 오는 경우. 되돌리는 일이 <b>과거의 사건</b>을 다루기 때문에,
 * 무엇을 과거 시각으로 적고 무엇을 지금 시각으로 적는지가 갈린다.
 *
 * <p>완주가 어느 날로 집계되는지({@code completed_on})는 세션이 정하고 소급해도 바뀌지 않는다.
 * 반면 <b>원장에 몇 시에 적히는가는 그 줄을 쓴 시각</b>이다. 예전에는 소급 지급이 세션 종료
 * 시각으로 들어가 같은 트랜잭션의 환급과 시각이 갈렸고, PT-1이 시각순으로 읽는 바람에
 * {@code balance_after}가 목록에서 오르내렸다.
 */
@DisplayName("소급 인용")
class RetroactiveAcceptanceTest extends IntegrationTest {

    private static final int TARGET_MINUTES = 60;
    private static final int GOAL_PERIOD_DAYS = 7;
    private static final Long ADMIN_ID = 1L;
    private static final LocalDate DAY_ONE = BASE_TIME.toLocalDate();
    private static final String REASON = "자리에 있었습니다. 카메라 각도가 틀어져 있었습니다.";

    @Autowired
    private MemberService memberService;

    @Autowired
    private AbsenceJudgeService absenceJudgeService;

    @Autowired
    private AppealService appealService;

    @Autowired
    private AppealAdminService appealAdminService;

    @Autowired
    private SessionService sessionService;

    @Autowired
    private SessionClosingBatch sessionClosingBatch;

    @Autowired
    private PointService pointService;

    @Value("${morak.session.absence-threshold-seconds}")
    private int absenceThresholdSeconds;

    @Value("${morak.session.evict-warning-count}")
    private int evictWarningCount;

    @Test
    @DisplayName("이틀 뒤에 인용해도 PT-1의 잔액 연쇄가 목록 순서와 맞는다")
    void 소급_지급의_잔액_연쇄가_목록_순서와_맞는다() {
        // 이 테스트가 죽으면: 원장 시각을 호출부가 다시 정하게 된 것이다. 과거 시각으로 들어간
        // 줄이 뒤늦은 id를 달고 앉아, 사용자 화면에서 잔액이 오르내리고 최신 행의 잔액이
        // 실제 잔액과 달라진다.
        Long memberId = fixtures.joinMember("retro-ledger");
        Long sessionId = evictAndClose(memberId, BASE_TIME);
        Long appealId = file(memberId, sessionId, BASE_TIME.plusHours(2));

        LocalDateTime decidedAt = BASE_TIME.plusDays(2).withHour(15).withMinute(30);
        clock.fixAt(decidedAt);
        appealAdminService.process(ADMIN_ID, appealId,
                new AppealProcessRequest(AppealStatus.ACCEPTED, "각도 문제 확인"));

        PointBalanceResponse points = pointService.getMyPoints(memberId, null, null);
        List<PointLedgerItemResponse> rows = points.ledger().content();
        // 최신 행이 곧 현재 잔액이다. 화면 맨 위와 잔액 표시가 다르면 어느 쪽도 믿을 수 없다
        assertThat(rows.getFirst().balanceAfter()).isEqualTo(points.balance());
        assertChainedInListOrder(rows);
        // 한 트랜잭션이 남긴 줄은 전부 인용 시각이다. 완주 지급만 이틀 전으로 새지 않는다
        assertThat(createdAtOf(rows, PointReason.APPEAL_REFUND)).isEqualTo(decidedAt);
        assertThat(createdAtOf(rows, PointReason.SESSION_COMPLETE)).isEqualTo(decidedAt);
    }

    @Test
    @DisplayName("소급 인용으로 채워진 목표가 그 세션의 결과에서 달성으로 보인다")
    void 소급_인용이_채운_목표가_SS8에_달성으로_실린다() {
        // 이 테스트가 죽으면: 달성 판정(쓰기)과 결과 조회(읽기)가 다시 두 벌이 된 것이다.
        // 시각을 맞대는 방식은 달성 시각을 다르게 넣는 경로가 하나만 생겨도 조용히 false가 된다.
        Long memberId = fixtures.joinMember("retro-goal");
        startGoal(memberId);
        for (int day = 0; day < GOAL_PERIOD_DAYS - 1; day++) {
            completeDay(memberId, DAY_ONE.plusDays(day));
        }
        // 목표 마지막 날에 퇴출된다 — 이 하루가 소급으로 채워져야 7일이 된다
        LocalDate lastDay = DAY_ONE.plusDays(GOAL_PERIOD_DAYS - 1L);
        Long sessionId = evictAndClose(memberId, lastDay.atTime(9, 0));
        Long appealId = file(memberId, sessionId, lastDay.atTime(12, 0));
        assertThat(resultOf(memberId, sessionId).my().goalAchieved()).isFalse();

        clock.fixAt(lastDay.plusDays(2).atTime(15, 30));
        appealAdminService.process(ADMIN_ID, appealId,
                new AppealProcessRequest(AppealStatus.ACCEPTED, "각도 문제 확인"));

        SessionResultResponse.My me = resultOf(memberId, sessionId).my();
        assertThat(me.goalAchieved()).isTrue();
        assertThat(me.badgeCode()).isNotNull();
        assertThat(fixtures.member(memberId).getCurrentStreak()).isEqualTo(GOAL_PERIOD_DAYS);
        assertThat(fixtures.count("point_ledger",
                "member_id = ? AND reason = 'GOAL_ACHIEVED'", memberId)).isEqualTo(1);
        assertThat(fixtures.member(memberId).getPointBalance())
                .isEqualTo(fixtures.ledgerSum(memberId));
    }

    /**
     * 목록에 보이는 순서대로 잔액이 이어지는가. 한 행의 잔액에서 그 행의 증감을 빼면 바로
     * 아래 행의 잔액이어야 한다 — 이것이 성립하지 않으면 사용자는 근거 없이 오르내리는 숫자를
     * 본다.
     */
    private void assertChainedInListOrder(List<PointLedgerItemResponse> rows) {
        for (int index = 0; index < rows.size() - 1; index++) {
            PointLedgerItemResponse current = rows.get(index);
            assertThat(current.balanceAfter() - current.delta())
                    .as("원장 %d행의 이전 잔액", index)
                    .isEqualTo(rows.get(index + 1).balanceAfter());
        }
    }

    private LocalDateTime createdAtOf(List<PointLedgerItemResponse> rows, PointReason reason) {
        return rows.stream()
                .filter(row -> row.reason() == reason)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("원장에 없는 사유: " + reason))
                .createdAt();
    }

    private SessionResultResponse resultOf(Long memberId, Long sessionId) {
        return sessionService.getResult(memberId, sessionId);
    }

    private void startGoal(Long memberId) {
        clock.fixAt(DAY_ONE.atTime(8, 0));
        memberService.setGoal(memberId, new GoalRequest(GOAL_PERIOD_DAYS));
    }

    private void completeDay(Long memberId, LocalDate day) {
        fixtures.openSession(TARGET_MINUTES, day.atTime(9, 0), List.of(memberId));
        clock.fixAt(day.atTime(10, 30));
        sessionClosingBatch.run();
    }

    /** 세션을 열어 경고 3회로 퇴출시킨 뒤 세션까지 끝낸다. 인용은 끝난 세션에만 성립한다. */
    private Long evictAndClose(Long memberId, LocalDateTime sessionStart) {
        clock.fixAt(sessionStart);
        Long sessionId = fixtures.openSession(TARGET_MINUTES, sessionStart, List.of(memberId));
        for (int round = 0; round < evictWarningCount; round++) {
            LocalDateTime startedAt = sessionStart.plusMinutes(1L + round * 3L);
            LocalDateTime endedAt = startedAt.plusSeconds(absenceThresholdSeconds + 5L);
            report(memberId, sessionId, AbsenceEventType.START, round * 2L, startedAt);
            report(memberId, sessionId, AbsenceEventType.END, round * 2L + 1, endedAt);
        }
        clock.fixAt(sessionStart.plusMinutes(TARGET_MINUTES + 1L));
        sessionClosingBatch.run();
        return sessionId;
    }

    private Long file(Long memberId, Long sessionId, LocalDateTime at) {
        clock.fixAt(at);
        return appealService.file(memberId, fixtures.evictionId(sessionId, memberId),
                new AppealCreateRequest(REASON)).appealId();
    }

    private void report(Long memberId, Long sessionId, AbsenceEventType type, long clientSeq,
                        LocalDateTime occurredAt) {
        clock.fixAt(occurredAt);
        absenceJudgeService.report(memberId, sessionId, new AbsenceEventRequest(
                type, clientSeq, occurredAt.atZone(clock.getZone()).toOffsetDateTime()));
    }
}
