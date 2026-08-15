package com.morak.session;

import static org.assertj.core.api.Assertions.assertThat;

import com.morak.session.dto.request.AbsenceEventRequest;
import com.morak.session.dto.response.AbsenceEventResponse;
import com.morak.session.dto.response.PauseStartResponse;
import com.morak.session.service.AbsenceJudgeService;
import com.morak.session.service.PauseService;
import com.morak.session.service.SessionClosingBatch;
import com.morak.session.type.AbsenceEventType;
import com.morak.support.IntegrationTest;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

/**
 * 예정 종료 시각({@code ends_at})이 지났는데 B1이 아직 돌지 않은 창. 세션은 그동안 여전히
 * LIVE라 SS-4 보고가 들어올 수 있고, <b>같은 자리비움을 두 경로가 볼 수 있다</b> — 진행 중
 * 판정과 종료 정산이다.
 *
 * <p>두 경로가 다른 값을 세면 결과가 배치 타이밍에 달린다. 55초를 비운 사람이 종료 20초 뒤에
 * END를 보내면 진행 중 판정은 75초로 세어 경고를 주고, 같은 사람의 END가 오지 않아 B1이
 * 집었다면 종료 시각까지 55초라 경고가 없다. 같은 행동에 다른 결과가 나오는 셈이다.
 */
@DisplayName("예정 종료 이후 자리비움 판정")
class AbsenceAfterScheduledEndTest extends IntegrationTest {

    private static final int TARGET_MINUTES = 60;
    private static final int PARTICIPANTS = 6;
    private static final LocalDateTime ENDS_AT = BASE_TIME.plusMinutes(TARGET_MINUTES);
    /** 종료 뒤에 END가 도착하는 폭. B1이 매분 도므로 실제로 생기는 창의 크기다. */
    private static final long LATE_END_SECONDS = 20;
    /** 종료 뒤에 비로소 START가 도착하는 폭. 같은 창 안이라 세션은 아직 LIVE다. */
    private static final long LATE_START_SECONDS = 5;

    @Autowired
    private AbsenceJudgeService absenceJudgeService;

    @Autowired
    private PauseService pauseService;

    @Autowired
    private SessionClosingBatch sessionClosingBatch;

    @Value("${morak.session.absence-threshold-seconds}")
    private int absenceThresholdSeconds;

    @Test
    @DisplayName("종료 뒤에 도착한 END는 종료 시각까지만 세어 임계 이내면 경고가 없다")
    void 종료_이후_구간은_판정에_실리지_않는다() {
        // 이 테스트가 죽으면: 세션이 끝난 뒤의 시간이 경고 판정에 다시 실린 것이다. 자리를
        // 지킬 의무가 없는 시간 때문에 경고가 붙고, B1이 먼저 돌았다면 없었을 경고가 생긴다.
        List<Long> memberIds = fixtures.joinMembers(PARTICIPANTS);
        Long sessionId = fixtures.openSession(TARGET_MINUTES, BASE_TIME, memberIds);
        Long lateEnd = memberIds.get(0);
        Long neverEnded = memberIds.get(1);
        long withinThreshold = absenceThresholdSeconds - 5L;

        // 같은 55초 자리비움을 한 명은 늦은 END로 닫고, 한 명은 열어 둔 채 B1에 맡긴다
        startAbsence(lateEnd, sessionId, withinThreshold, 0L);
        startAbsence(neverEnded, sessionId, withinThreshold, 10L);
        endAbsence(lateEnd, sessionId, 1L);
        closeSession();

        assertThat(warningCount(sessionId, lateEnd)).isZero();
        // 두 경로가 같은 답을 낸다는 것이 요지다. 값이 아니라 일치가 판정 기준이다
        assertThat(warningCount(sessionId, lateEnd))
                .isEqualTo(warningCount(sessionId, neverEnded));
    }

    @Test
    @DisplayName("임계를 넘긴 구간은 어느 경로가 집어도 똑같이 경고 1회다")
    void 임계를_넘긴_구간은_두_경로가_같은_경고를_준다() {
        // 이 테스트가 죽으면: 판정이 다시 두 벌이 된 것이다. 늦은 END 쪽만 종료 이후까지
        // 세면 같은 자리비움이 누가 먼저 닿았느냐에 따라 다르게 끝난다.
        List<Long> memberIds = fixtures.joinMembers(PARTICIPANTS);
        Long sessionId = fixtures.openSession(TARGET_MINUTES, BASE_TIME, memberIds);
        Long lateEnd = memberIds.get(0);
        Long neverEnded = memberIds.get(1);
        long overThreshold = absenceThresholdSeconds + 10L;

        startAbsence(lateEnd, sessionId, overThreshold, 0L);
        startAbsence(neverEnded, sessionId, overThreshold, 10L);
        endAbsence(lateEnd, sessionId, 1L);
        closeSession();

        assertThat(warningCount(sessionId, lateEnd)).isEqualTo(1);
        assertThat(warningCount(sessionId, neverEnded)).isEqualTo(1);
    }

    @Test
    @DisplayName("예정 종료 이후에 시작된 자리비움은 0초로 판정된다")
    void 종료_이후에_시작된_구간은_0초다() {
        // 이 테스트가 죽으면: 끊는 자리가 구간의 끝에만 남은 것이다. 시작이 종료 뒤면 뺄셈이
        // 거꾸로 돌아 closedAbsenceSeconds가 음수로 나가고, 영상이 없는 이상 그 값이 이의를
        // 쓸 때 댈 유일한 근거인데 음수로는 읽히지 않는다.
        List<Long> memberIds = fixtures.joinMembers(PARTICIPANTS);
        Long sessionId = fixtures.openSession(TARGET_MINUTES, BASE_TIME, memberIds);
        Long lateStart = memberIds.get(0);

        startAbsenceAfterEnd(lateStart, sessionId, 0L);
        AbsenceEventResponse closed = endAbsence(lateStart, sessionId, 1L);

        assertThat(closed.closedAbsenceSeconds()).isZero();
        assertThat(closed.warningCount()).isZero();
        assertThat(warningCount(sessionId, lateStart)).isZero();
    }

    @Test
    @DisplayName("종료 이후에 시작된 구간은 화장실 모드가 마감해도 0초다")
    void 종료_이후에_시작된_구간은_화장실_모드_마감에서도_0초다() {
        // SS-4와 SS-5가 같은 판정 헬퍼를 공유한다. 한쪽만 고치면 다른 쪽으로 음수가 계속 나간다.
        List<Long> memberIds = fixtures.joinMembers(PARTICIPANTS);
        Long sessionId = fixtures.openSession(TARGET_MINUTES, BASE_TIME, memberIds);
        Long lateStart = memberIds.get(0);

        startAbsenceAfterEnd(lateStart, sessionId, 0L);
        clock.fixAt(ENDS_AT.plusSeconds(LATE_END_SECONDS));
        PauseStartResponse paused = pauseService.start(lateStart, sessionId);

        assertThat(paused.closedAbsenceSeconds()).isZero();
        assertThat(paused.warningIssued()).isFalse();
        assertThat(warningCount(sessionId, lateStart)).isZero();
    }

    /** 예정 종료 시각 기준으로 {@code beforeEndSeconds}초 전에 자리를 비우기 시작한다. */
    private void startAbsence(Long memberId, Long sessionId, long beforeEndSeconds,
                              long clientSeq) {
        report(memberId, sessionId, AbsenceEventType.START, clientSeq,
                ENDS_AT.minusSeconds(beforeEndSeconds));
    }

    /** 예정 종료가 지난 뒤에 비로소 자리를 비우기 시작한다. B1 전이라 세션은 아직 LIVE다. */
    private void startAbsenceAfterEnd(Long memberId, Long sessionId, long clientSeq) {
        report(memberId, sessionId, AbsenceEventType.START, clientSeq,
                ENDS_AT.plusSeconds(LATE_START_SECONDS));
    }

    /** 예정 종료가 지난 뒤, B1이 아직 돌기 전에 END가 도착한다. */
    private AbsenceEventResponse endAbsence(Long memberId, Long sessionId, long clientSeq) {
        return report(memberId, sessionId, AbsenceEventType.END, clientSeq,
                ENDS_AT.plusSeconds(LATE_END_SECONDS));
    }

    private void closeSession() {
        clock.fixAt(ENDS_AT.plusMinutes(1));
        sessionClosingBatch.run();
    }

    private int warningCount(Long sessionId, Long memberId) {
        return fixtures.count("warning", "session_id = ? AND member_id = ?", sessionId, memberId);
    }

    private AbsenceEventResponse report(Long memberId, Long sessionId, AbsenceEventType type,
                                        long clientSeq, LocalDateTime occurredAt) {
        clock.fixAt(occurredAt);
        return absenceJudgeService.report(memberId, sessionId, new AbsenceEventRequest(
                type, clientSeq, occurredAt.atZone(clock.getZone()).toOffsetDateTime()));
    }
}
