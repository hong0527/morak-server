package com.morak.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.morak.session.dto.request.AbsenceEventRequest;
import com.morak.session.service.AbsenceJudgeService;
import com.morak.session.service.SessionClosingBatch;
import com.morak.session.type.AbsenceEventType;
import com.morak.session.type.SessionStatus;
import com.morak.support.IntegrationTest;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * B1이 한 세션에서 넘어져도 같은 실행의 나머지 세션을 처리하는지 본다.
 *
 * <p><b>대상 하나가 트랜잭션 하나라는 설계는 루프의 try/catch가 있어야 완성된다.</b> 없으면
 * 롤백된 건의 예외가 루프 밖으로 나가고, 아직 손대지 않은 세션들이 통째로 다음 회차로 밀린다.
 * 실측에서 종료 대상 2건 중 1건이 {@code room_finished} 웹훅과 부딪히자 나머지 1건이 22회 중
 * 14회 남았다.
 *
 * <p>경합을 시각에 맡기면 테스트가 흔들리므로 <b>같은 결과를 제약으로 만든다</b> — 종료 정산이
 * 쓸 경고 번호를 미리 채워 두면 {@code uk_warning}이 그 세션의 종료 트랜잭션을 되돌린다.
 * 다른 경로가 먼저 다녀간 상태와 DB가 보기에 같은 상황이다.
 */
@DisplayName("종료 배치의 대상 격리")
class ClosingBatchIsolationTest extends IntegrationTest {

    private static final int TARGET_MINUTES = 60;
    private static final int PARTICIPANTS = 6;

    @Autowired
    private SessionClosingBatch sessionClosingBatch;

    @Autowired
    private AbsenceJudgeService absenceJudgeService;

    @Test
    @DisplayName("한 세션이 제약에 부딪혀도 같은 실행의 다른 세션은 종료·지급된다")
    void 한_건의_실패가_나머지를_막지_않는다() {
        // 이 테스트가 죽으면: 예외가 배치 루프를 끊는 것이다. 종료 예정 세션이 몰린 분에
        // 한 건의 경합이 나머지 전부를 다음 회차로 미룬다.
        List<Long> blockedMembers = fixtures.joinMembers(PARTICIPANTS);
        Long blockedSession = fixtures.openSession(TARGET_MINUTES, BASE_TIME, blockedMembers);
        List<Long> healthyMembers = fixtures.joinMembers(PARTICIPANTS);
        Long healthySession = fixtures.openSession(TARGET_MINUTES, BASE_TIME, healthyMembers);
        // 배치는 id 순으로 도므로 넘어지는 세션이 먼저다
        assertThat(blockedSession).isLessThan(healthySession);
        blockSettlementWith(blockedSession, blockedMembers.getFirst());

        clock.fixAt(BASE_TIME.plusMinutes(TARGET_MINUTES + 1));
        int processed = sessionClosingBatch.run();

        assertThat(processed).isEqualTo(1);
        // 넘어진 세션은 통째로 롤백돼 손대기 전 그대로다
        assertThat(fixtures.session(blockedSession).getStatus()).isEqualTo(SessionStatus.LIVE);
        assertThat(fixtures.count("session_participant",
                "session_id = ? AND completed = TRUE", blockedSession)).isZero();
        assertThat(fixtures.count("warning", "session_id = ?", blockedSession)).isEqualTo(1);
        // 나머지 세션은 같은 실행에서 끝까지 정산됐다
        assertThat(fixtures.session(healthySession).getStatus()).isEqualTo(SessionStatus.ENDED);
        assertThat(fixtures.count("session_participant",
                "session_id = ? AND completed = TRUE", healthySession)).isEqualTo(PARTICIPANTS);
        for (Long memberId : healthyMembers) {
            assertThat(fixtures.count("point_ledger",
                    "member_id = ? AND reason = 'SESSION_COMPLETE'", memberId)).isEqualTo(1);
            assertThat(fixtures.member(memberId).getPointBalance())
                    .isEqualTo(fixtures.ledgerSum(memberId));
        }
    }

    @Test
    @DisplayName("막힌 세션은 다음 회차가 거둔다")
    void 밀린_세션은_다음_회차가_거둔다() {
        // 이 테스트가 죽으면: 경합으로 건너뛴 세션이 영영 미결로 남는다. 건너뛰기가 허용되는
        // 근거는 다음 실행이 같은 대상을 다시 집어 든다는 것뿐이다.
        List<Long> memberIds = fixtures.joinMembers(PARTICIPANTS);
        Long sessionId = fixtures.openSession(TARGET_MINUTES, BASE_TIME, memberIds);
        blockSettlementWith(sessionId, memberIds.getFirst());
        clock.fixAt(BASE_TIME.plusMinutes(TARGET_MINUTES + 1));
        assertThatCode(sessionClosingBatch::run).doesNotThrowAnyException();
        assertThat(fixtures.session(sessionId).getStatus()).isEqualTo(SessionStatus.LIVE);

        // 막아 둔 원인이 사라지면(다른 경로가 정리했거나 사람이 손봤다) 그대로 처리된다
        fixtures.execute("DELETE FROM warning WHERE session_id = ?", sessionId);
        int processed = sessionClosingBatch.run();

        assertThat(processed).isEqualTo(1);
        assertThat(fixtures.session(sessionId).getStatus()).isEqualTo(SessionStatus.ENDED);
        assertThat(fixtures.count("session_participant",
                "session_id = ? AND completed = TRUE", sessionId)).isEqualTo(PARTICIPANTS);
    }

    /**
     * 종료 정산이 반드시 경고를 쓰게 만들고(짝 없는 자리비움 START), 그 경고 번호를 미리
     * 채워 {@code uk_warning}에 부딪히게 한다. 종료 트랜잭션은 통째로 롤백된다.
     */
    private void blockSettlementWith(Long sessionId, Long memberId) {
        LocalDateTime startedAt = BASE_TIME.plusMinutes(1);
        clock.fixAt(startedAt);
        absenceJudgeService.report(memberId, sessionId, new AbsenceEventRequest(
                AbsenceEventType.START, 0L, startedAt.atZone(clock.getZone()).toOffsetDateTime()));
        fixtures.execute(
                "INSERT INTO warning (session_id, member_id, seq, created_at) VALUES (?, ?, 1, ?)",
                sessionId, memberId, startedAt);
    }
}
