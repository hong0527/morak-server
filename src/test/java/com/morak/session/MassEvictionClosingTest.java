package com.morak.session;

import static org.assertj.core.api.Assertions.assertThat;

import com.morak.common.error.BusinessException;
import com.morak.common.error.ErrorCode;
import com.morak.session.dto.request.AbsenceEventRequest;
import com.morak.session.service.AbsenceJudgeService;
import com.morak.session.type.AbsenceEventType;
import com.morak.session.type.ParticipantStatus;
import com.morak.session.type.SessionEndReason;
import com.morak.session.type.SessionStatus;
import com.morak.support.Concurrently;
import com.morak.support.IntegrationTest;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

/**
 * 여러 명이 같은 순간에 퇴출될 때의 조기 종료(D12).
 *
 * <p><b>잔여 인원 검사는 검사-후-행동이라 잠금 없이는 성립하지 않는다.</b> 여섯이 동시에
 * 퇴출되면 각 트랜잭션이 아직 커밋되지 않은 남들을 "남아 있는 사람"으로 세고, 전부 "아직
 * 2명 이상"이라 판단해 종료를 건너뛴다. 그러면 참가자가 0명인 세션이 {@code ends_at}까지
 * LIVE로 남는다 — 240분 조건이면 네 시간짜리 빈 방이고, 홀로 남은 사람은 그동안
 * {@code ALREADY_IN_ACTIVE_SESSION}에 걸려 재매칭도 못 한다.
 *
 * <p>순차로 퇴출시키면 같은 코드가 정상 동작하므로, 확인은 반드시 동시 실행이어야 한다.
 */
@DisplayName("동시 다중 퇴출과 조기 종료")
class MassEvictionClosingTest extends IntegrationTest {

    private static final int TARGET_MINUTES = 240;
    private static final int PARTICIPANTS = 6;

    @Autowired
    private AbsenceJudgeService absenceJudgeService;

    @Value("${morak.session.absence-threshold-seconds}")
    private int absenceThresholdSeconds;

    @Value("${morak.session.evict-warning-count}")
    private int evictWarningCount;

    @Value("${morak.session.min-participants}")
    private int minParticipants;

    @Test
    @DisplayName("전원이 동시에 3회째 경고에 닿으면 세션이 그 자리에서 조기 종료된다")
    void 동시_전원_퇴출은_세션을_닫는다() {
        // 이 테스트가 죽으면: 조기 종료 판정이 세션 행 잠금 없이 인원을 세는 것이다.
        // 아무도 남지 않은 세션이 LIVE로 남고, B1이 ends_at에 가서야 NORMAL로 닫는다.
        List<Long> memberIds = fixtures.joinMembers(PARTICIPANTS);
        Long sessionId = fixtures.openSession(TARGET_MINUTES, BASE_TIME, memberIds);
        accumulateWarningsBeforeLast(sessionId, memberIds);

        LocalDateTime evictedAt = raceLastWarning(sessionId, memberIds);

        assertThat(fixtures.session(sessionId).getStatus()).isEqualTo(SessionStatus.ENDED);
        assertThat(fixtures.session(sessionId).getEndReason())
                .isEqualTo(SessionEndReason.EARLY_UNDER_MIN);
        assertThat(fixtures.session(sessionId).getEndedAt()).isEqualTo(evictedAt);
        // 종료 정산이 남은 사람의 미종료 자리비움까지 마저 판정하므로 전원이 퇴출로 끝난다
        assertThat(fixtures.count("session_participant",
                "session_id = ? AND status = 'EVICTED'", sessionId)).isEqualTo(PARTICIPANTS);
        assertThat(fixtures.count("eviction", "session_id = ?", sessionId))
                .isEqualTo(PARTICIPANTS);
        assertNoDoubleSettlement(sessionId, memberIds);
    }

    @Test
    @DisplayName("동시 퇴출로 한 명만 남아도 세션이 닫히고 그 한 명이 완주로 정산된다")
    void 잔여_한_명이면_닫고_정산한다() {
        // 이 테스트가 죽으면: 홀로 남은 사람이 빈 방에 갇힌다. 세션이 안 닫히니 재매칭도
        // 막히고, 뒤늦게 B1이 닫을 때 사유가 EARLY_UNDER_MIN이 아니라 NORMAL이 된다.
        List<Long> memberIds = fixtures.joinMembers(PARTICIPANTS);
        Long sessionId = fixtures.openSession(TARGET_MINUTES, BASE_TIME, memberIds);
        List<Long> evicted = memberIds.subList(0, PARTICIPANTS - 1);
        Long stayed = memberIds.get(PARTICIPANTS - 1);
        accumulateWarningsBeforeLast(sessionId, evicted);

        raceLastWarning(sessionId, evicted);

        assertThat(fixtures.session(sessionId).getStatus()).isEqualTo(SessionStatus.ENDED);
        assertThat(fixtures.session(sessionId).getEndReason())
                .isEqualTo(SessionEndReason.EARLY_UNDER_MIN);
        assertThat(fixtures.count("session_participant",
                "session_id = ? AND status = 'EVICTED'", sessionId))
                .isEqualTo(PARTICIPANTS - minParticipants + 1);
        assertThat(fixtures.participant(sessionId, stayed).getStatus())
                .isEqualTo(ParticipantStatus.ACTIVE);
        assertThat(fixtures.participant(sessionId, stayed).isCompleted()).isTrue();
        assertThat(fixtures.participant(sessionId, stayed).getPointAwarded()).isPositive();
        assertNoDoubleSettlement(sessionId, memberIds);
    }

    /** 마지막 한 번을 남기고 경고를 쌓는다. 여기는 경합 대상이 아니라 순차로 부른다. */
    private void accumulateWarningsBeforeLast(Long sessionId, List<Long> memberIds) {
        for (int round = 0; round < evictWarningCount - 1; round++) {
            for (Long memberId : memberIds) {
                reportClosedAbsence(sessionId, memberId, round);
            }
        }
        for (Long memberId : memberIds) {
            assertThat(fixtures.participant(sessionId, memberId).getWarningCount())
                    .isEqualTo(evictWarningCount - 1);
        }
    }

    /**
     * 마지막 자리비움 구간을 열어 두고 <b>END만 동시에</b> 보낸다. 그 END가 3회째 경고이고
     * 곧 퇴출이라, 여섯 개의 퇴출 트랜잭션이 같은 순간에 잔여 인원 검사에 도달한다.
     *
     * @return 퇴출이 일어난 시각. 조기 종료의 {@code ended_at}이 이 값이어야 한다
     */
    private LocalDateTime raceLastWarning(Long sessionId, List<Long> memberIds) {
        int round = evictWarningCount - 1;
        LocalDateTime startedAt = BASE_TIME.plusMinutes(round * 3L + 1);
        LocalDateTime endedAt = startedAt.plusSeconds(absenceThresholdSeconds + 5L);
        long seq = round * 2L;

        clock.fixAt(startedAt);
        for (Long memberId : memberIds) {
            absenceJudgeService.report(memberId, sessionId, new AbsenceEventRequest(
                    AbsenceEventType.START, seq, offsetOf(startedAt)));
        }

        clock.fixAt(endedAt);
        List<Throwable> failures = Concurrently.run(memberIds.size(), index ->
                absenceJudgeService.report(memberIds.get(index), sessionId,
                        new AbsenceEventRequest(AbsenceEventType.END, seq + 1, offsetOf(endedAt))));

        // 세션이 닫힌 뒤에 도착한 보고는 409다. 그 참가자는 종료 정산이 같은 임계로 판정한다
        assertThat(failures).allSatisfy(failure -> {
            assertThat(failure).isInstanceOf(BusinessException.class);
            assertThat(((BusinessException) failure).getErrorCode())
                    .isEqualTo(ErrorCode.SESSION_ENDED);
        });
        return endedAt;
    }

    /** 임계를 넘긴 자리비움 한 구간(START·END). 보고 사이는 레이트리밋 간격보다 넉넉히 벌린다. */
    private void reportClosedAbsence(Long sessionId, Long memberId, int round) {
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

    /** 경합이 경고·퇴출·차감을 겹쳐 만들지 않았는지. 근거는 uk_warning과 uk_pl_dedup이다. */
    private void assertNoDoubleSettlement(Long sessionId, List<Long> memberIds) {
        for (Long memberId : memberIds) {
            assertThat(fixtures.count("warning", "session_id = ? AND member_id = ?",
                    sessionId, memberId))
                    .isEqualTo(fixtures.participant(sessionId, memberId).getWarningCount());
            assertThat(fixtures.count("eviction", "session_id = ? AND member_id = ?",
                    sessionId, memberId)).isLessThanOrEqualTo(1);
            assertThat(fixtures.member(memberId).getPointBalance())
                    .isEqualTo(fixtures.ledgerSum(memberId));
        }
    }

    private OffsetDateTime offsetOf(LocalDateTime at) {
        return at.atZone(clock.getZone()).toOffsetDateTime();
    }
}
