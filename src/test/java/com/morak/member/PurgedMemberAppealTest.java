package com.morak.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.morak.common.error.BusinessException;
import com.morak.common.error.ErrorCode;
import com.morak.member.service.MemberPurgeBatch;
import com.morak.member.service.MemberService;
import com.morak.member.type.MemberStatus;
import com.morak.session.dto.request.AbsenceEventRequest;
import com.morak.session.dto.request.AppealCreateRequest;
import com.morak.session.dto.request.AppealProcessRequest;
import com.morak.session.dto.response.AppealSummaryResponse;
import com.morak.session.service.AbsenceJudgeService;
import com.morak.session.service.AppealAdminService;
import com.morak.session.service.AppealService;
import com.morak.session.service.SessionClosingBatch;
import com.morak.session.type.AbsenceEventType;
import com.morak.session.type.AppealStatus;
import com.morak.support.IntegrationTest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

/**
 * 파기(B4)와 이의 인용(AD-6)이 만나는 자리. <b>둘은 함께 성립할 수 없다</b> — 인용은 완주를
 * 소급하며 {@code streak_day}를 다시 세우는데, 그것이 방금 파기한 개인 기록이다.
 *
 * <p>실측에서 실제로 그랬다: 파기된 회원(DELETED)에게 완주일과 연속 일수가 되살아나
 * "파기했다"고 말한 기록이 파기되지 않은 상태로 남았다. 심사 근거인 경고·자리비움 이벤트는
 * 이미 지워진 뒤라 인용·기각 어느 쪽도 근거를 보고 내린 판단이 아니었다.
 */
@DisplayName("파기 회원의 이의")
class PurgedMemberAppealTest extends IntegrationTest {

    private static final int TARGET_MINUTES = 60;
    private static final int PARTICIPANTS = 6;
    private static final Long ADMIN_ID = 1L;
    private static final String REASON = "자리에 있었습니다. 카메라 각도가 틀어져 있었습니다.";

    @Autowired
    private MemberService memberService;

    @Autowired
    private MemberPurgeBatch memberPurgeBatch;

    @Autowired
    private AbsenceJudgeService absenceJudgeService;

    @Autowired
    private AppealService appealService;

    @Autowired
    private AppealAdminService appealAdminService;

    @Autowired
    private SessionClosingBatch sessionClosingBatch;

    @Value("${morak.session.absence-threshold-seconds}")
    private int absenceThresholdSeconds;

    @Value("${morak.session.evict-warning-count}")
    private int evictWarningCount;

    @Value("${morak.withdrawal.grace-days}")
    private int withdrawalGraceDays;

    @Test
    @DisplayName("파기된 회원의 이의는 인용되지 않고 개인 기록도 되살아나지 않는다")
    void 파기_회원의_이의는_개인_기록을_되살리지_않는다() {
        // 이 테스트가 죽으면: 파기 약속이 깨진 것이다. 인용이 완주를 소급하는 순간 지웠던
        // streak_day가 다시 생기고, 회원 행의 연속 일수·마지막 완주일까지 되돌아온다.
        Long memberId = fixtures.joinMember("purge-appeal");
        Long completedSession = completeSession(memberId, BASE_TIME);
        Long evictedSession = evict(memberId, BASE_TIME.plusDays(1));
        Long appealId = file(memberId, evictedSession, BASE_TIME.plusDays(1).plusHours(2));
        // 파기 전에는 완주 기록이 실제로 있었다. 없는 것을 되살리지 못하는 것은 시험이 아니다
        assertThat(fixtures.count("streak_day", "member_id = ?", memberId)).isEqualTo(1);

        purge(memberId);

        assertPurged(memberId, appealId);
        assertThatThrownBy(() -> appealAdminService.process(ADMIN_ID, appealId,
                new AppealProcessRequest(AppealStatus.ACCEPTED, "완주 소급 시도")))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                // 정상 순서라면 B4가 이미 CLOSED로 거둬 여기서 걸린다
                .isEqualTo(ErrorCode.ALREADY_PROCESSED);
        assertStillPurged(memberId, completedSession, evictedSession);
    }

    @Test
    @DisplayName("파기와 처리가 겹쳐 이의가 PENDING으로 남아 있어도 인용은 거절된다")
    void 파기된_회원의_PENDING_이의는_인용되지_않는다() {
        // 이 테스트가 죽으면: 방어가 B4의 자동 종결 하나뿐인 것이다. 관리자 트랜잭션이 이의를
        // PENDING으로 읽은 뒤 B4가 커밋하면 그 트랜잭션은 PENDING을 손에 들고 인용까지 간다.
        Long memberId = fixtures.joinMember("purge-appeal-race");
        Long evictedSession = evict(memberId, BASE_TIME);
        Long appealId = file(memberId, evictedSession, BASE_TIME.plusHours(2));
        purge(memberId);
        // 파기와 처리가 겹쳐 자동 종결이 아직 닿지 않은 상태를 만든다. 정상 경로로는 만들 수
        // 없는 중간 상태라 직접 되돌린다
        fixtures.execute("UPDATE appeal_case SET status = 'PENDING', decided_by = NULL,"
                + " decided_at = NULL, note = NULL WHERE id = ?", appealId);

        assertThatThrownBy(() -> appealAdminService.process(ADMIN_ID, appealId,
                new AppealProcessRequest(AppealStatus.ACCEPTED, "완주 소급 시도")))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.APPEAL_MEMBER_PURGED);
        assertThat(fixtures.count("streak_day", "member_id = ?", memberId)).isZero();
        // 기각도 막는다 — 근거가 사라진 뒤의 판단은 어느 쪽이든 심사가 아니다.
        // 에러 코드까지 보는 이유는 타입만 보면 ALREADY_PROCESSED로 막혀도 통과하기 때문이다
        assertThatThrownBy(() -> appealAdminService.process(ADMIN_ID, appealId,
                new AppealProcessRequest(AppealStatus.REJECTED, "근거 없음")))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.APPEAL_MEMBER_PURGED);
    }

    @Test
    @DisplayName("심사할 수 없게 된 이의는 큐에 남아 SLA를 넘기지 않는다")
    void 파기된_회원의_이의는_큐에서_내려간다() {
        // 이 테스트가 죽으면: 심사 근거가 통째로 사라진 이의가 PENDING으로 큐에 남아, 관리자가
        // 처리할 수 없는 건을 SLA 초과로 계속 보게 된다.
        Long memberId = fixtures.joinMember("purge-appeal-queue");
        Long evictedSession = evict(memberId, BASE_TIME);
        Long appealId = file(memberId, evictedSession, BASE_TIME.plusHours(2));

        purge(memberId);

        assertThat(pendingQueueIds()).doesNotContain(appealId);
        assertThat(overdueQueueIds()).doesNotContain(appealId);
        // 종결 사유는 남긴다. 관리자 화면이 "왜 심사되지 않았나"에 답할 유일한 재료다
        assertThat(fixtures.count("appeal_case",
                "id = ? AND status = 'CLOSED' AND decided_by = 'SYSTEM' AND note IS NOT NULL",
                appealId)).isEqualTo(1);
    }

    /** 파기 직후의 상태 — 지울 것은 지워졌고 이의는 심사 불가로 종결됐다. */
    private void assertPurged(Long memberId, Long appealId) {
        assertThat(fixtures.member(memberId).getStatus()).isEqualTo(MemberStatus.DELETED);
        assertThat(fixtures.count("streak_day", "member_id = ?", memberId)).isZero();
        assertThat(fixtures.count("warning", "member_id = ?", memberId)).isZero();
        assertThat(fixtures.count("absence_event", "member_id = ?", memberId)).isZero();
        assertThat(fixtures.count("appeal_case", "id = ? AND status = 'CLOSED'", appealId))
                .isEqualTo(1);
    }

    /** 인용 시도 뒤에도 파기 상태가 그대로다. 이 테스트의 본론이다. */
    private void assertStillPurged(Long memberId, Long completedSession, Long evictedSession) {
        assertThat(fixtures.count("streak_day", "member_id = ?", memberId)).isZero();
        assertThat(fixtures.member(memberId).getCurrentStreak()).isZero();
        assertThat(fixtures.member(memberId).getLastCompletedOn()).isNull();
        // 완주 표시와 환급 원장도 생기지 않는다 — 인용이 반쪽으로 성립하지 않았다는 뜻이다
        assertThat(fixtures.participant(evictedSession, memberId).isCompleted()).isFalse();
        assertThat(fixtures.count("point_ledger", "member_id = ? AND reason = 'APPEAL_REFUND'",
                memberId)).isZero();
        assertThat(fixtures.count("eviction", "member_id = ? AND revoked_at IS NOT NULL",
                memberId)).isZero();
        // 남기기로 한 것은 그대로다. 파기 범위가 넓어진 것도 사고다
        assertThat(fixtures.count("session_participant",
                "session_id = ? AND member_id = ?", completedSession, memberId)).isEqualTo(1);
        assertThat(fixtures.member(memberId).getPointBalance())
                .isEqualTo(fixtures.ledgerSum(memberId));
    }

    private List<Long> pendingQueueIds() {
        return appealAdminService.getAppeals(AppealStatus.PENDING, null, null, null).content()
                .stream()
                .map(AppealSummaryResponse::appealId)
                .toList();
    }

    private List<Long> overdueQueueIds() {
        return appealAdminService.getAppeals(null, true, null, null).content().stream()
                .map(AppealSummaryResponse::appealId)
                .toList();
    }

    private void purge(Long memberId) {
        clock.fixAt(BASE_TIME.plusDays(2));
        memberService.requestWithdrawal(memberId);
        clock.fixAt(BASE_TIME.plusDays(2L + withdrawalGraceDays + 1));
        assertThat(memberPurgeBatch.run()).isEqualTo(1);
    }

    /** 세션 하나를 열어 끝까지 남고 완주시킨다. 반환은 세션 번호다. */
    private Long completeSession(Long memberId, LocalDateTime sessionStart) {
        Long sessionId = openWith(memberId, sessionStart);
        clock.fixAt(sessionStart.plusMinutes(TARGET_MINUTES + 1L));
        sessionClosingBatch.run();
        return sessionId;
    }

    /** 새 세션을 열고 자리비움 경고 3회로 퇴출시킨 뒤 세션까지 끝낸다. */
    private Long evict(Long memberId, LocalDateTime sessionStart) {
        Long sessionId = openWith(memberId, sessionStart);
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

    private Long openWith(Long memberId, LocalDateTime sessionStart) {
        List<Long> memberIds = new ArrayList<>(fixtures.joinMembers(PARTICIPANTS - 1));
        memberIds.addFirst(memberId);
        clock.fixAt(sessionStart);
        return fixtures.openSession(TARGET_MINUTES, sessionStart, memberIds);
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
