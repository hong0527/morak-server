package com.morak.session;

import static org.assertj.core.api.Assertions.assertThat;

import com.morak.session.repository.SessionParticipantRepository;
import com.morak.session.service.SessionClosingBatch;
import com.morak.session.type.SessionEndReason;
import com.morak.session.type.SessionStatus;
import com.morak.support.IntegrationTest;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

/**
 * 세션 종료가 남기는 흔적이 실제로 DB에 닿는지 본다.
 *
 * <p>지급이 잔액 캐시를 벌크 UPDATE로 갱신하며 영속성 컨텍스트를 비우기 때문에, 지급 이전에
 * 잡아 둔 참가자 참조에 완주를 표시하면 그 변경은 flush되지 않고 사라진다. 여기서 확인하는
 * 것은 서비스가 돌려주는 값이 아니라 <b>다시 읽은 행</b>이다.
 */
@DisplayName("완주 마킹 영속")
class SessionCompletionPersistenceTest extends IntegrationTest {

    private static final int TARGET_MINUTES = 60;
    private static final int PARTICIPANTS = 6;

    @Autowired
    private SessionClosingBatch sessionClosingBatch;

    @Autowired
    private SessionParticipantRepository sessionParticipantRepository;

    @Value("${morak.point.welcome}")
    private int welcomePoint;

    @Value("${morak.point.session-complete-per-hour}")
    private int completePointPerHour;

    @Test
    @DisplayName("B1 종료 뒤 완주 표시와 지급 금액이 DB에 남는다")
    void B1_종료_뒤_완주_표시가_DB에_남는다() {
        // 이 테스트가 죽으면: 지급이 영속성 컨텍스트를 비운 뒤의 complete()가 준영속 객체에
        // 쓰인 것이다. 원장과 완주일만 남고 완주 표시가 사라지면 그 참가자는 흡수 지급 대상
        // 조회에도 잡히지 않아 영구 미표시가 된다(8단계 실측 결함).
        List<Long> memberIds = fixtures.joinMembers(PARTICIPANTS);
        Long sessionId = fixtures.openSession(TARGET_MINUTES, BASE_TIME, memberIds);
        LocalDateTime endsAt = BASE_TIME.plusMinutes(TARGET_MINUTES);
        clock.fixAt(endsAt.plusMinutes(1));

        int processed = sessionClosingBatch.run();

        assertThat(processed).isEqualTo(1);
        assertThat(fixtures.session(sessionId).getStatus()).isEqualTo(SessionStatus.ENDED);
        assertThat(fixtures.session(sessionId).getEndReason()).isEqualTo(SessionEndReason.NORMAL);
        // 종료 시각은 배치가 돈 시각이 아니라 예정 시각이다. 배치가 늦게 돌았다는 이유로
        // 자리비움 구간이 길어져 경고가 붙으면 안 된다.
        assertThat(fixtures.session(sessionId).getEndedAt()).isEqualTo(endsAt);

        int expectedAward = completePointPerHour * TARGET_MINUTES / 60;
        assertThat(fixtures.count("session_participant",
                "session_id = ? AND completed = TRUE AND point_awarded = ?",
                sessionId, expectedAward))
                .isEqualTo(PARTICIPANTS);
        // 완주 표시가 남아 있다는 말의 다른 표현 — 흡수 지급 대상으로 다시 잡히지 않는다
        assertThat(sessionParticipantRepository.findIdsAwaitingAward(SessionStatus.ENDED))
                .isEmpty();

        for (Long memberId : memberIds) {
            assertThat(fixtures.member(memberId).getPointBalance())
                    .isEqualTo(welcomePoint + expectedAward)
                    .isEqualTo(fixtures.ledgerSum(memberId));
            assertThat(fixtures.count("streak_day", "member_id = ? AND completed_on = ?",
                    memberId, endsAt.toLocalDate())).isEqualTo(1);
            assertThat(fixtures.member(memberId).getCurrentStreak()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("완주 표시만 남고 지급이 빠진 참가자는 다음 B1이 흡수 지급한다")
    void 미지급_완주자는_다음_배치가_흡수_지급한다() {
        // 이 테스트가 죽으면: 종료 트랜잭션이 지급을 남기지 못하고 끊겼을 때 그 완주자가
        // 영구 미지급으로 남는다. 안전망을 지우지 않는 이유가 이 경로다.
        Long memberId = fixtures.joinMember();
        Long sessionId = fixtures.openSession(TARGET_MINUTES, BASE_TIME, List.of(memberId));
        LocalDateTime endsAt = BASE_TIME.plusMinutes(TARGET_MINUTES);
        clock.fixAt(endsAt.plusMinutes(1));
        // 종료는 됐지만 지급이 남지 않은 상태를 직접 만든다
        markEndedWithUnpaidCompletion(sessionId, memberId);

        int processed = sessionClosingBatch.run();

        assertThat(processed).isEqualTo(1);
        int expectedAward = completePointPerHour * TARGET_MINUTES / 60;
        assertThat(fixtures.participant(sessionId, memberId).getPointAwarded())
                .isEqualTo(expectedAward);
        assertThat(fixtures.member(memberId).getPointBalance())
                .isEqualTo(welcomePoint + expectedAward)
                .isEqualTo(fixtures.ledgerSum(memberId));
    }

    private void markEndedWithUnpaidCompletion(Long sessionId, Long memberId) {
        fixtures.execute("""
                UPDATE live_session
                   SET status = 'ENDED', ended_at = ?, end_reason = 'NORMAL'
                 WHERE id = ?
                """, BASE_TIME.plusMinutes(TARGET_MINUTES), sessionId);
        fixtures.execute("""
                UPDATE session_participant
                   SET completed = TRUE, point_awarded = 0
                 WHERE session_id = ? AND member_id = ?
                """, sessionId, memberId);
    }
}
