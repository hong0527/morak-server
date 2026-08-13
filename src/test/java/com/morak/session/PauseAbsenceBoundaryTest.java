package com.morak.session;

import static org.assertj.core.api.Assertions.assertThat;

import com.morak.session.dto.request.AbsenceEventRequest;
import com.morak.session.dto.response.PauseStartResponse;
import com.morak.session.service.AbsenceJudgeService;
import com.morak.session.service.PauseService;
import com.morak.session.type.AbsenceEventType;
import com.morak.support.IntegrationTest;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

/**
 * 화장실 모드(SS-5)가 자리비움 판정과 만나는 경계를 본다.
 *
 * <p>규약은 <b>PAUSED 구간은 자리비움에 들어가지 않는다</b>(★D1·D9)이다. 그런데 자리비움
 * START가 열린 채로 Pause가 시작되면 그 구간은 Pause를 가로지르게 되고, 복귀 후 도착한 END는
 * 화장실에 있던 시간까지 한 구간으로 계산한다. 10분을 쓰고 돌아온 사람이 60초 임계를 넘겨
 * 경고를 받는 셈이라, 약속이 정반대로 뒤집힌다.
 *
 * <p>그래서 Pause 시작이 열린 구간을 그 시각으로 끊는다. 끊을 때의 판정은 END를 받은 것과
 * 같아야 한다 — 이미 임계를 넘긴 자리비움은 Pause를 켜는 것으로 무를 수 없다(D9).
 */
@DisplayName("Pause와 자리비움 경계")
class PauseAbsenceBoundaryTest extends IntegrationTest {

    private static final int TARGET_MINUTES = 60;
    private static final int PARTICIPANTS = 6;

    @Autowired
    private AbsenceJudgeService absenceJudgeService;

    @Autowired
    private PauseService pauseService;

    @Value("${morak.session.absence-threshold-seconds}")
    private int absenceThresholdSeconds;

    @Value("${morak.session.pause-limit-minutes}")
    private int pauseLimitMinutes;

    @Test
    @DisplayName("START 직후 Pause를 켜고 돌아와 END를 보내면 경고가 붙지 않는다")
    void 화장실_구간은_자리비움에_들어가지_않는다() {
        // 이 테스트가 죽으면: Pause 구간이 자리비움 간격에 섞인 것이다. 화장실 모드를 쓴
        // 사람이 그 사용만으로 경고를 받게 되고, SS-5는 켜면 손해인 함정이 된다.
        List<Long> memberIds = fixtures.joinMembers(PARTICIPANTS);
        Long sessionId = fixtures.openSession(TARGET_MINUTES, BASE_TIME, memberIds);
        Long memberId = memberIds.getFirst();

        LocalDateTime absenceStart = BASE_TIME.plusMinutes(1);
        report(memberId, sessionId, AbsenceEventType.START, 0, absenceStart);
        // 임계 안쪽에서 화장실 모드로 들어간다
        clock.fixAt(absenceStart.plusSeconds(absenceThresholdSeconds - 10L));
        pauseService.start(memberId, sessionId);
        // 제한 시간 안에 돌아온다
        LocalDateTime resumedAt = absenceStart.plusMinutes(pauseLimitMinutes - 1L);
        clock.fixAt(resumedAt);
        pauseService.resume(memberId, sessionId);
        // 복귀 후 뒤늦게 도착한 END. 짝이 이미 닫혀 있어 판정 대상이 아니다
        report(memberId, sessionId, AbsenceEventType.END, 1, resumedAt.plusSeconds(30));

        assertThat(fixtures.participant(sessionId, memberId).getWarningCount()).isZero();
        assertThat(fixtures.count("warning", "session_id = ? AND member_id = ?",
                sessionId, memberId)).isZero();
        assertThat(fixtures.count("eviction", "member_id = ?", memberId)).isZero();
    }

    @Test
    @DisplayName("이미 임계를 넘긴 자리비움은 Pause를 켜도 그 자리에서 경고가 된다")
    void 임계를_넘긴_구간은_Pause로_무를_수_없다() {
        // 이 테스트가 죽으면: 경고가 쌓이기 시작할 때 Pause를 켜는 것이 최선의 회피책이 된다.
        // 마감이 판정 없이 구간만 지우면 그렇게 된다.
        List<Long> memberIds = fixtures.joinMembers(PARTICIPANTS);
        Long sessionId = fixtures.openSession(TARGET_MINUTES, BASE_TIME, memberIds);
        Long memberId = memberIds.getFirst();

        LocalDateTime absenceStart = BASE_TIME.plusMinutes(1);
        report(memberId, sessionId, AbsenceEventType.START, 0, absenceStart);
        clock.fixAt(absenceStart.plusSeconds(absenceThresholdSeconds + 30L));

        PauseStartResponse response = pauseService.start(memberId, sessionId);

        assertThat(fixtures.participant(sessionId, memberId).getWarningCount()).isEqualTo(1);
        assertThat(fixtures.count("warning", "session_id = ? AND member_id = ?",
                sessionId, memberId)).isEqualTo(1);
        // 여기서 붙은 경고를 본인이 알아야 한다. 응답에 없으면 화장실을 다녀온 사람은
        // 경고가 하나 는 것을 모른 채 다음 경고에 퇴출된다
        assertThat(response.warningIssued()).isTrue();
        assertThat(response.warningCount()).isEqualTo(1);
        assertThat(response.closedAbsenceSeconds())
                .isEqualTo(absenceThresholdSeconds + 30L);
    }

    @Test
    @DisplayName("마감할 구간이 없으면 응답의 경고 항목이 비어 있다")
    void 마감이_없으면_경고를_알리지_않는다() {
        // 이 테스트가 죽으면: 자리를 비운 적 없는 사람에게도 경고 표시가 뜬다.
        List<Long> memberIds = fixtures.joinMembers(PARTICIPANTS);
        Long sessionId = fixtures.openSession(TARGET_MINUTES, BASE_TIME, memberIds);
        Long memberId = memberIds.getFirst();

        clock.fixAt(BASE_TIME.plusMinutes(1));
        PauseStartResponse response = pauseService.start(memberId, sessionId);

        assertThat(response.warningIssued()).isFalse();
        assertThat(response.warningCount()).isZero();
        assertThat(response.closedAbsenceSeconds()).isNull();
    }

    @Test
    @DisplayName("임계 안쪽에서 마감되면 경고 없이 구간 길이만 알린다")
    void 임계_안쪽_마감은_길이만_알린다() {
        // 경고는 아니지만 "얼마나 자리를 비운 것으로 계산됐는지"는 본인이 볼 수 있어야
        // 판정을 납득하거나 다툴 수 있다.
        List<Long> memberIds = fixtures.joinMembers(PARTICIPANTS);
        Long sessionId = fixtures.openSession(TARGET_MINUTES, BASE_TIME, memberIds);
        Long memberId = memberIds.getFirst();

        LocalDateTime absenceStart = BASE_TIME.plusMinutes(1);
        report(memberId, sessionId, AbsenceEventType.START, 0, absenceStart);
        clock.fixAt(absenceStart.plusSeconds(absenceThresholdSeconds - 10L));

        PauseStartResponse response = pauseService.start(memberId, sessionId);

        assertThat(response.warningIssued()).isFalse();
        assertThat(response.warningCount()).isZero();
        assertThat(response.closedAbsenceSeconds())
                .isEqualTo(absenceThresholdSeconds - 10L);
    }

    private void report(Long memberId, Long sessionId, AbsenceEventType type, long clientSeq,
                        LocalDateTime at) {
        clock.fixAt(at);
        absenceJudgeService.report(memberId, sessionId,
                new AbsenceEventRequest(type, clientSeq, at.atZone(clock.getZone())
                        .toOffsetDateTime()));
    }
}
