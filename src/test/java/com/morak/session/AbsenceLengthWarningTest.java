package com.morak.session;

import static org.assertj.core.api.Assertions.assertThat;

import com.morak.session.service.SessionClosingBatch;
import com.morak.session.dto.request.AbsenceEventRequest;
import com.morak.session.dto.response.AbsenceEventResponse;
import com.morak.session.dto.response.PauseStartResponse;
import com.morak.session.service.AbsenceJudgeService;
import com.morak.session.service.AbsenceWarningPolicy;
import com.morak.session.service.PauseService;
import com.morak.session.type.AbsenceEventType;
import com.morak.session.type.ParticipantStatus;
import com.morak.support.IntegrationTest;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

/**
 * 자리비움 <b>길이</b>가 판정에 들어가는지 본다.
 *
 * <p>구간 하나당 경고가 최대 1회이던 규칙에서는 결과가 뒤집혀 있었다 — 60분 세션에서 45분을
 * 비운 사람이 경고 1회로 완주해 포인트와 Streak을 받고, 61초짜리 물 마시기를 세 번 한 사람이
 * 퇴출에 −300P를 물었다. 자리비움 총량이 15배 차이 나는데 결과가 정반대다. 그 규칙은
 * <b>지속시간이 아니라 빈도를 처벌</b>하고 있었다.
 *
 * <p>경고를 만드는 자리가 셋이라(SS-4 판정, SS-5 Pause 시작 마감, B1 종료 정산) 계산이 갈리면
 * 같은 자리비움이 도착 경로에 따라 다르게 판정된다. 셋 다 같은 값을 내는지도 함께 본다.
 */
@DisplayName("자리비움 길이와 경고 수")
class AbsenceLengthWarningTest extends IntegrationTest {

    private static final int TARGET_MINUTES = 60;
    private static final int PARTICIPANTS = 6;

    @Autowired
    private AbsenceJudgeService absenceJudgeService;

    @Autowired
    private AbsenceWarningPolicy warningPolicy;

    @Autowired
    private PauseService pauseService;

    @Autowired
    private SessionClosingBatch sessionClosingBatch;

    @Value("${morak.session.absence-threshold-seconds}")
    private int thresholdSeconds;

    @Value("${morak.session.absence-warning-escalation-seconds}")
    private int escalationSeconds;

    @Value("${morak.session.evict-warning-count}")
    private int evictWarningCount;

    @Test
    @DisplayName("임계 이하는 여전히 경고가 없다")
    void 임계_이하는_경고가_없다() {
        // 이 테스트가 죽으면: "60초 초과면 경고"라는 기존 기준이 바뀐 것이다. 길이를 세는
        // 것과 첫 경고 기준을 낮추는 것은 다른 일이고, 후자는 이번 변경 범위가 아니다.
        assertThat(warningPolicy.warningCountFor(0)).isZero();
        assertThat(warningPolicy.warningCountFor(thresholdSeconds)).isZero();

        List<Long> memberIds = fixtures.joinMembers(PARTICIPANTS);
        Long sessionId = fixtures.openSession(TARGET_MINUTES, BASE_TIME, memberIds);
        Long memberId = memberIds.getFirst();

        LocalDateTime start = BASE_TIME.plusMinutes(1);
        report(memberId, sessionId, AbsenceEventType.START, 0, start);
        AbsenceEventResponse response = report(memberId, sessionId, AbsenceEventType.END, 1,
                start.plusSeconds(thresholdSeconds));

        assertThat(response.warningCount()).isZero();
        assertThat(response.closedAbsenceSeconds()).isEqualTo(thresholdSeconds);
    }

    @Test
    @DisplayName("임계 바로 위는 1회이고, 눈금을 넘길 때마다 하나씩 는다")
    void 눈금마다_한_회씩_는다() {
        // 계단이 어디에 있는지를 값으로 못 박는다. 눈금을 임계와 같게 되돌리면 여기가 깨진다.
        assertThat(warningPolicy.warningCountFor(thresholdSeconds + 1)).isEqualTo(1);
        assertThat(warningPolicy.warningCountFor(thresholdSeconds + escalationSeconds))
                .isEqualTo(1);
        assertThat(warningPolicy.warningCountFor(thresholdSeconds + escalationSeconds + 1))
                .isEqualTo(2);
        assertThat(warningPolicy.warningCountFor(thresholdSeconds + escalationSeconds * 2L))
                .isEqualTo(2);
        assertThat(warningPolicy.warningCountFor(thresholdSeconds + escalationSeconds * 2L + 1))
                .isEqualTo(3);
    }

    @Test
    @DisplayName("3분 남짓 한 번으로는 퇴출되지 않는다")
    void 짧은_이석_한_번으로는_퇴출되지_않는다() {
        // 이 테스트가 죽으면: 택배·전화처럼 흔하고 악의 없는 3분 이석이 첫 사건에 곧바로
        // −300P와 재매칭 금지가 된다. 눈금을 임계와 같게 두면 정확히 이 길이에서 그렇게 된다
        // — 임계 + 임계×(퇴출 회수−1) + 1초가 그 지점이라 그 값을 그대로 쓴다.
        List<Long> memberIds = fixtures.joinMembers(PARTICIPANTS);
        Long sessionId = fixtures.openSession(TARGET_MINUTES, BASE_TIME, memberIds);
        Long memberId = memberIds.getFirst();

        LocalDateTime start = BASE_TIME.plusMinutes(1);
        report(memberId, sessionId, AbsenceEventType.START, 0, start);
        long justOverIfEscalationWereThreshold =
                thresholdSeconds + (long) thresholdSeconds * (evictWarningCount - 1) + 1;
        AbsenceEventResponse response = report(memberId, sessionId, AbsenceEventType.END, 1,
                start.plusSeconds(justOverIfEscalationWereThreshold));

        assertThat(response.warningCount()).isEqualTo(1);
        assertThat(response.evicted()).isFalse();
        assertThat(fixtures.participant(sessionId, memberId).getStatus())
                .isEqualTo(ParticipantStatus.ACTIVE);
    }

    @Test
    @DisplayName("45분을 비우면 경고가 상한까지 쌓여 퇴출된다")
    void 오래_비우면_한_구간으로도_퇴출된다() {
        // W0의 사례 그대로다. 고치기 전에는 경고 1회로 완주해 포인트와 Streak을 받았다.
        List<Long> memberIds = fixtures.joinMembers(PARTICIPANTS);
        Long sessionId = fixtures.openSession(TARGET_MINUTES, BASE_TIME, memberIds);
        Long memberId = memberIds.getFirst();

        LocalDateTime start = BASE_TIME.plusMinutes(1);
        report(memberId, sessionId, AbsenceEventType.START, 0, start);
        AbsenceEventResponse response =
                report(memberId, sessionId, AbsenceEventType.END, 1, start.plusMinutes(45));

        assertThat(response.warningCount()).isEqualTo(evictWarningCount);
        assertThat(response.evicted()).isTrue();
        assertThat(response.closedAbsenceSeconds()).isEqualTo(45 * 60L);
        // 상한을 넘겨 쌓지 않는다 — 계산값은 3회를 크게 웃돈다
        assertThat(fixtures.count("warning", "session_id = ? AND member_id = ?",
                sessionId, memberId)).isEqualTo(evictWarningCount);
    }

    @Test
    @DisplayName("종료 정산의 미결 구간도 같은 규칙으로 판정한다")
    void 소급_정산도_같은_규칙이다() {
        // 이 테스트가 죽으면: 같은 자리비움이 END가 닿았는지 아닌지에 따라 다르게 판정된다.
        // START만 보내고 사라진 사람이 정직하게 END를 보낸 사람보다 유리해진다.
        List<Long> memberIds = fixtures.joinMembers(PARTICIPANTS);
        Long sessionId = fixtures.openSession(TARGET_MINUTES, BASE_TIME, memberIds);
        Long memberId = memberIds.getFirst();

        // 종료 45분 전에 START만 보내고 END를 보내지 않는다
        report(memberId, sessionId, AbsenceEventType.START, 0, BASE_TIME.plusMinutes(15));
        clock.fixAt(BASE_TIME.plusMinutes(TARGET_MINUTES).plusSeconds(1));
        sessionClosingBatch.run();

        assertThat(fixtures.participant(sessionId, memberId).getStatus())
                .isEqualTo(ParticipantStatus.EVICTED);
        assertThat(fixtures.count("warning", "session_id = ? AND member_id = ?",
                sessionId, memberId)).isEqualTo(evictWarningCount);
    }

    @Test
    @DisplayName("Pause 시작 마감도 같은 규칙으로 판정한다")
    void Pause_마감도_같은_규칙이다() {
        // 세 번째 경고 생성 경로. 여기만 옛 계산이면 자리비움이 길어도 화장실을 켜는 순간
        // 경고 1회로 깎인다 — 켜는 것이 회피책이 된다.
        List<Long> memberIds = fixtures.joinMembers(PARTICIPANTS);
        Long sessionId = fixtures.openSession(TARGET_MINUTES, BASE_TIME, memberIds);
        Long memberId = memberIds.getFirst();

        LocalDateTime start = BASE_TIME.plusMinutes(1);
        report(memberId, sessionId, AbsenceEventType.START, 0, start);
        // 임계 + 눈금 하나를 넘긴 시점에 화장실 모드를 켠다 → 2회여야 한다
        clock.fixAt(start.plusSeconds(thresholdSeconds + escalationSeconds + 1L));
        PauseStartResponse response = pauseService.start(memberId, sessionId);

        assertThat(response.warningIssued()).isTrue();
        assertThat(response.warningCount()).isEqualTo(2);
        assertThat(fixtures.count("warning", "session_id = ? AND member_id = ?",
                sessionId, memberId)).isEqualTo(2);
    }

    @Test
    @DisplayName("짧은 이석 세 번은 그대로 퇴출이다")
    void 짧은_이석_세_번은_변함없이_퇴출이다() {
        // 기존 동작이 유지되는지 본다. 길이를 세기 시작했다고 빈도 쪽이 느슨해지면 안 된다.
        List<Long> memberIds = fixtures.joinMembers(PARTICIPANTS);
        Long sessionId = fixtures.openSession(TARGET_MINUTES, BASE_TIME, memberIds);
        Long memberId = memberIds.getFirst();

        long seq = 0;
        AbsenceEventResponse last = null;
        for (int i = 0; i < evictWarningCount; i += 1) {
            LocalDateTime start = BASE_TIME.plusMinutes(1 + i * 5L);
            report(memberId, sessionId, AbsenceEventType.START, seq++, start);
            last = report(memberId, sessionId, AbsenceEventType.END, seq++,
                    start.plusSeconds(thresholdSeconds + 1L));
        }

        assertThat(last).isNotNull();
        assertThat(last.warningCount()).isEqualTo(evictWarningCount);
        assertThat(last.evicted()).isTrue();
    }

    private AbsenceEventResponse report(Long memberId, Long sessionId, AbsenceEventType type,
                                        long clientSeq, LocalDateTime at) {
        clock.fixAt(at);
        return absenceJudgeService.report(memberId, sessionId,
                new AbsenceEventRequest(type, clientSeq, at.atZone(clock.getZone())
                        .toOffsetDateTime()));
    }
}
