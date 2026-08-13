package com.morak.session;

import static org.assertj.core.api.Assertions.assertThat;

import com.morak.session.dto.request.AbsenceEventRequest;
import com.morak.session.service.AbsenceJudgeService;
import com.morak.session.service.ReconnectGraceRegistry;
import com.morak.session.service.ReconnectGraceSweeper;
import com.morak.session.type.AbsenceEventType;
import com.morak.session.type.LeftReason;
import com.morak.session.type.ParticipantStatus;
import com.morak.session.type.SessionStatus;
import com.morak.support.IntegrationTest;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

/**
 * 자리를 비우는 두 축이 서로 섞이지 않는지 본다.
 *
 * <p>얼굴이 안 보이는 것은 위반이라 경고가 쌓이고 3회면 퇴출·차감이다(★D4). 연결이 끊기는 것은
 * 사고라 유예를 주고, 넘기면 그 세션에서 나갈 뿐 경고도 차감도 없다(D13). 한 축의 판정이 다른
 * 축으로 새면 지하철에서 끊긴 사람이 경고를 안고 복귀하거나, 카메라를 가린 사람이 끊긴 척으로
 * 빠져나간다.
 */
@DisplayName("경고 두 축 분리")
class WarningAxisSeparationTest extends IntegrationTest {

    private static final int TARGET_MINUTES = 60;
    private static final int PARTICIPANTS = 6;

    @Autowired
    private AbsenceJudgeService absenceJudgeService;

    @Autowired
    private ReconnectGraceRegistry graceRegistry;

    @Autowired
    private ReconnectGraceSweeper reconnectGraceSweeper;

    @Value("${morak.session.absence-threshold-seconds}")
    private int absenceThresholdSeconds;

    @Value("${morak.session.reconnect-grace-seconds}")
    private int graceSeconds;

    @Value("${morak.session.evict-warning-count}")
    private int evictWarningCount;

    @Value("${morak.point.eviction-penalty}")
    private int evictionPenalty;

    @Value("${morak.point.welcome}")
    private int welcomePoint;

    @Test
    @DisplayName("경고 2회 상태에서 연결이 끊겨 유예를 넘기면 퇴장일 뿐 경고는 그대로다")
    void 연결_끊김은_경고를_늘리지_않는다() {
        // 이 테스트가 죽으면: 연결 끊김이 자리비움으로 추정되기 시작한 것이다. 미보고를 판정하면
        // 재접속 유예와 이중으로 걸려 돌아온 사람이 경고를 안고 복귀한다.
        List<Long> memberIds = fixtures.joinMembers(PARTICIPANTS);
        Long sessionId = fixtures.openSession(TARGET_MINUTES, BASE_TIME, memberIds);
        Long memberId = memberIds.getFirst();
        reportAbsence(sessionId, memberId, 0);
        reportAbsence(sessionId, memberId, 1);
        assertThat(fixtures.participant(sessionId, memberId).getWarningCount()).isEqualTo(2);

        LocalDateTime disconnectedAt = BASE_TIME.plusMinutes(20);
        clock.fixAt(disconnectedAt);
        graceRegistry.open(sessionId, memberId, disconnectedAt);
        clock.fixAt(disconnectedAt.plusSeconds(graceSeconds + 1L));

        reconnectGraceSweeper.sweep();

        assertThat(fixtures.participant(sessionId, memberId).getStatus())
                .isEqualTo(ParticipantStatus.LEFT);
        assertThat(fixtures.participant(sessionId, memberId).getLeftReason())
                .isEqualTo(LeftReason.DEVICE_ISSUE);
        assertThat(fixtures.participant(sessionId, memberId).getWarningCount()).isEqualTo(2);
        assertThat(fixtures.count("warning", "session_id = ? AND member_id = ?",
                sessionId, memberId)).isEqualTo(2);
        assertThat(fixtures.count("eviction", "member_id = ?", memberId)).isZero();
        assertThat(fixtures.count("point_ledger",
                "member_id = ? AND reason = 'EVICTION_PENALTY'", memberId)).isZero();
        assertThat(fixtures.member(memberId).getPointBalance()).isEqualTo(welcomePoint);
        // 다섯이 남았으므로 세션은 계속된다
        assertThat(fixtures.session(sessionId).getStatus()).isEqualTo(SessionStatus.LIVE);
    }

    @Test
    @DisplayName("얼굴 미검출 60초가 3회 쌓이면 퇴출되고 그 자리에서 차감된다")
    void 자리비움_3회는_퇴출과_즉시_차감이다() {
        // 이 테스트가 죽으면: 차감이 배치로 밀린 것이다. SS-4 응답은 -300을 싣는데 원장이 아직
        // 없으면 사용자가 화면에서 본 값이 최대 1분 동안 사실이 아니다.
        List<Long> memberIds = fixtures.joinMembers(PARTICIPANTS);
        Long sessionId = fixtures.openSession(TARGET_MINUTES, BASE_TIME, memberIds);
        Long memberId = memberIds.getFirst();

        for (int round = 0; round < evictWarningCount; round++) {
            reportAbsence(sessionId, memberId, round);
        }

        assertThat(fixtures.participant(sessionId, memberId).getStatus())
                .isEqualTo(ParticipantStatus.EVICTED);
        assertThat(fixtures.participant(sessionId, memberId).getWarningCount())
                .isEqualTo(evictWarningCount);
        assertThat(fixtures.count("eviction", "session_id = ? AND member_id = ?",
                sessionId, memberId)).isEqualTo(1);
        assertThat(fixtures.count("point_ledger",
                "member_id = ? AND reason = 'EVICTION_PENALTY' AND delta = ?",
                memberId, -evictionPenalty)).isEqualTo(1);
        assertThat(fixtures.member(memberId).getPointBalance())
                .isEqualTo(welcomePoint - evictionPenalty)
                .isEqualTo(fixtures.ledgerSum(memberId));
    }

    /**
     * 임계를 넘긴 자리비움 한 구간을 보고한다. START와 END 사이를 임계보다 길게 두고, 두 보고
     * 사이는 레이트리밋 간격보다 넉넉히 벌린다 — 시각을 밀지 않으면 두 번째 보고가 429다.
     */
    private void reportAbsence(Long sessionId, Long memberId, int round) {
        LocalDateTime startedAt = BASE_TIME.plusMinutes(round * 3L + 1);
        LocalDateTime endedAt = startedAt.plusSeconds(absenceThresholdSeconds + 5L);
        long seq = round * 2L;

        clock.fixAt(startedAt);
        absenceJudgeService.report(memberId, sessionId, new AbsenceEventRequest(
                AbsenceEventType.START, seq, offsetOf(startedAt)));
        clock.fixAt(endedAt);
        absenceJudgeService.report(memberId, sessionId, new AbsenceEventRequest(
                AbsenceEventType.END, seq + 1, offsetOf(endedAt)));
    }

    private OffsetDateTime offsetOf(LocalDateTime at) {
        return at.atZone(clock.getZone()).toOffsetDateTime();
    }
}
