package com.morak.member;

import static org.assertj.core.api.Assertions.assertThat;

import com.morak.match.entity.MatchLock;
import com.morak.member.dto.request.GoalRequest;
import com.morak.member.dto.request.MediaConsentRequest;
import com.morak.member.service.MemberPurgeBatch;
import com.morak.member.service.MemberService;
import com.morak.member.type.MemberStatus;
import com.morak.report.dto.request.ReportCreateRequest;
import com.morak.report.service.ReportService;
import com.morak.report.type.ReportReasonCode;
import com.morak.report.type.ReportTargetType;
import com.morak.session.dto.request.AbsenceEventRequest;
import com.morak.session.dto.request.SessionGoalRequest;
import com.morak.session.service.AbsenceJudgeService;
import com.morak.session.service.SessionClosingBatch;
import com.morak.session.service.SessionService;
import com.morak.session.type.AbsenceEventType;
import com.morak.store.dto.request.OrderCreateRequest;
import com.morak.store.service.StoreOrderService;
import com.morak.support.IntegrationTest;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

/**
 * 탈퇴 확정 파기(B4)의 경계. 지우는 것과 남기는 것이 뒤집히면 어느 쪽이든 사고다 — 남겨야 할
 * 거래 기록을 지우면 법정 보존과 다른 회원의 세션 이력이 함께 무너지고, 지워야 할 개인 기록을
 * 남기면 파기했다고 말한 것이 파기되지 않는다.
 */
@DisplayName("B4 보존 경계")
class WithdrawalPurgeBoundaryTest extends IntegrationTest {

    private static final int TARGET_MINUTES = 60;
    private static final int PRODUCT_PRICE = 500;

    @Autowired
    private MemberService memberService;

    @Autowired
    private MemberPurgeBatch memberPurgeBatch;

    @Autowired
    private SessionService sessionService;

    @Autowired
    private SessionClosingBatch sessionClosingBatch;

    @Autowired
    private AbsenceJudgeService absenceJudgeService;

    @Autowired
    private ReportService reportService;

    @Autowired
    private StoreOrderService storeOrderService;

    @Value("${morak.withdrawal.grace-days}")
    private int withdrawalGraceDays;

    @Test
    @DisplayName("파기 뒤에도 원장·주문·신고는 남고 개인 기록만 사라진다")
    void 탈퇴_파기는_거래_기록을_남기고_개인_기록만_지운다() {
        // 이 테스트가 죽으면: 파기 범위가 흔들린 것이다. 원장을 지우면 잔액과 원장 합이 갈라지고,
        // 세션 참가 행을 지우면 같은 세션에 있던 다른 참가자의 이력에서 인원이 어긋난다.
        clock.fixAt(BASE_TIME);
        Long memberId = fixtures.joinMember();
        Long otherId = fixtures.joinMember();
        Long sessionId = prepareEndedSessionWithHistory(memberId, otherId);
        fileReport(memberId, otherId, sessionId);
        placeOrder(memberId);

        int ledgerRowsBefore = fixtures.count("point_ledger", "member_id = ?", memberId);
        int balanceBefore = fixtures.member(memberId).getPointBalance();
        assertThat(ledgerRowsBefore).isEqualTo(3);   // 웰컴·완주 지급·주문 차감

        memberService.requestWithdrawal(memberId);
        clock.fixAt(BASE_TIME.plusDays(withdrawalGraceDays + 1L));
        int purged = memberPurgeBatch.run();

        assertThat(purged).isEqualTo(1);
        assertPreserved(memberId, sessionId, ledgerRowsBefore, balanceBefore);
        assertErased(memberId, otherId);
    }

    /** 남는 쪽 — 법적 보존 의무와 다른 회원의 이력 정합성이 근거다. */
    private void assertPreserved(Long memberId, Long sessionId, int ledgerRowsBefore,
                                 int balanceBefore) {
        assertThat(fixtures.count("point_ledger", "member_id = ?", memberId))
                .isEqualTo(ledgerRowsBefore);
        assertThat(fixtures.count("store_order", "member_id = ?", memberId)).isEqualTo(1);
        assertThat(fixtures.count("report", "reporter_id = ?", memberId)).isEqualTo(1);
        assertThat(fixtures.countAll("report_case")).isEqualTo(1);
        // 참가 행 자체는 남기고 본인이 쓴 '오늘 할 일'만 비운다
        assertThat(fixtures.count("session_participant",
                "session_id = ? AND member_id = ? AND goal_text IS NULL", sessionId, memberId))
                .isEqualTo(1);

        assertThat(fixtures.member(memberId).getStatus()).isEqualTo(MemberStatus.DELETED);
        assertThat(fixtures.member(memberId).getNickname()).isEqualTo("탈퇴회원");
        assertThat(fixtures.member(memberId).getProviderUserId()).isEqualTo("deleted:" + memberId);
        // 잔액은 개인 기록이 아니라 남은 원장의 파생값이라 0으로 덮지 않는다
        assertThat(fixtures.member(memberId).getPointBalance())
                .isEqualTo(balanceBefore)
                .isEqualTo(fixtures.ledgerSum(memberId));
    }

    /** 지우는 쪽 — 보존 근거가 없는 개인 기록이다. */
    private void assertErased(Long memberId, Long otherId) {
        assertThat(fixtures.count("member_agreement", "member_id = ?", memberId)).isZero();
        assertThat(fixtures.count("member_goal", "member_id = ?", memberId)).isZero();
        assertThat(fixtures.count("media_consent", "member_id = ?", memberId)).isZero();
        assertThat(fixtures.count("streak_day", "member_id = ?", memberId)).isZero();
        assertThat(fixtures.count("warning", "member_id = ?", memberId)).isZero();
        assertThat(fixtures.count("absence_event", "member_id = ?", memberId)).isZero();
        assertThat(fixtures.count("match_request", "member_id = ?", memberId)).isZero();
        assertThat(fixtures.count("match_lock", "lock_key = ?", MatchLock.memberKey(memberId)))
                .isZero();
        // 차단은 방향이 있는 2행이라 양쪽을 다 지운다. 한쪽만 남으면 상대의 매칭 후보를 계속 깎는다
        assertThat(fixtures.count("match_block", "member_id = ? OR blocked_member_id = ?",
                memberId, memberId)).isZero();
        assertThat(fixtures.count("match_block", "member_id = ?", otherId)).isZero();
        // Streak 캐시도 함께 비운다 — 진실인 streak_day를 지우고 캐시만 남기면 완주 기록이 회원
        // 행에 그대로 남는다
        assertThat(fixtures.member(memberId).getCurrentStreak()).isZero();
        assertThat(fixtures.member(memberId).getLastCompletedOn()).isNull();
    }

    /** 완주 이력·목표·동의·경고까지 갖춘 종료된 세션 하나를 만든다. */
    private Long prepareEndedSessionWithHistory(Long memberId, Long otherId) {
        memberService.agreeMediaConsent(memberId, new MediaConsentRequest(true));
        memberService.setGoal(memberId, new GoalRequest(7));
        Long sessionId = fixtures.openSession(TARGET_MINUTES, BASE_TIME,
                List.of(memberId, otherId));
        sessionService.updateGoal(memberId, sessionId, new SessionGoalRequest("알고리즘 3문제"));
        seedWarning(sessionId, memberId);

        clock.fixAt(BASE_TIME.plusMinutes(TARGET_MINUTES + 1));
        sessionClosingBatch.run();
        clock.fixAt(BASE_TIME);
        return sessionId;
    }

    /** 자리비움 한 구간을 판정 경로로 통과시켜 이벤트 2행과 경고 1행을 남긴다. */
    private void seedWarning(Long sessionId, Long memberId) {
        LocalDateTime startedAt = BASE_TIME.plusMinutes(5);
        LocalDateTime endedAt = startedAt.plusSeconds(65);
        clock.fixAt(startedAt);
        absenceJudgeService.report(memberId, sessionId, new AbsenceEventRequest(
                AbsenceEventType.START, 0L, startedAt.atZone(clock.getZone()).toOffsetDateTime()));
        clock.fixAt(endedAt);
        absenceJudgeService.report(memberId, sessionId, new AbsenceEventRequest(
                AbsenceEventType.END, 1L, endedAt.atZone(clock.getZone()).toOffsetDateTime()));
    }

    private void fileReport(Long reporterId, Long targetId, Long sessionId) {
        reportService.report(reporterId, new ReportCreateRequest(ReportTargetType.MEMBER,
                targetId, sessionId, ReportReasonCode.AD_SPAM, "광고 문구를 반복해서 띄웠습니다."));
    }

    private void placeOrder(Long memberId) {
        Long productId = fixtures.createProduct(PRODUCT_PRICE, 3);
        storeOrderService.order(memberId,
                new OrderCreateRequest(productId, 1, "order-" + memberId));
    }
}
