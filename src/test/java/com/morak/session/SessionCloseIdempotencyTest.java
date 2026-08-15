package com.morak.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.morak.session.entity.LiveSession;
import com.morak.session.service.SessionClosingBatch;
import com.morak.session.service.SessionExitService;
import com.morak.session.type.LeftReason;
import com.morak.session.type.SessionEndReason;
import com.morak.session.type.SessionStatus;
import com.morak.support.IntegrationTest;
import com.morak.support.LiveKitWebhookSigner;
import java.time.LocalDateTime;
import java.util.List;
import livekit.LivekitModels.Room;
import livekit.LivekitWebhook.WebhookEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 세션이 끝나는 세 경로(B1 정시 종료·잔여 인원 미달 조기 종료·{@code room_finished} 웹훅)가
 * 같은 정산을 하고, 어느 쪽이 두 번 도착해도 결과가 달라지지 않는지 본다.
 *
 * <p>재실행 안전의 근거는 서비스 코드가 아니라 제약이다({@code uk_pl_dedup}·{@code uk_streak_day}).
 * 그래서 확인도 "두 번 불렀을 때 무엇이 늘었는가"로 한다.
 */
@DisplayName("세션 종료 3진입점 멱등")
class SessionCloseIdempotencyTest extends IntegrationTest {

    private static final int TARGET_MINUTES = 60;
    private static final int PARTICIPANTS = 6;

    @Autowired
    private SessionClosingBatch sessionClosingBatch;

    @Autowired
    private SessionExitService sessionExitService;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private LiveKitWebhookSigner webhookSigner;

    @Test
    @DisplayName("B1을 다시 돌려도 지급과 완주일이 늘지 않는다")
    void B1_재실행은_정산을_되풀이하지_않는다() {
        // 이 테스트가 죽으면: 배치 재실행이 이중 지급이 된다. 상태 검사는 지름길일 뿐이라
        // 실제 방어선인 멱등키가 빠지면 여기서 드러난다.
        List<Long> memberIds = fixtures.joinMembers(PARTICIPANTS);
        Long sessionId = fixtures.openSession(TARGET_MINUTES, BASE_TIME, memberIds);
        clock.fixAt(BASE_TIME.plusMinutes(TARGET_MINUTES + 1));
        sessionClosingBatch.run();
        Settlement afterFirstRun = settlementOf(sessionId, memberIds);

        int processed = sessionClosingBatch.run() + sessionClosingBatch.run();

        assertThat(processed).isZero();
        assertThat(settlementOf(sessionId, memberIds)).isEqualTo(afterFirstRun);
    }

    @Test
    @DisplayName("잔여 인원 미달 조기 종료도 그 자리에서 정산하고, 이어지는 B1은 아무것도 바꾸지 않는다")
    void 조기_종료도_같은_정산을_하고_재실행에_흔들리지_않는다() {
        // 이 테스트가 죽으면: 조기 종료가 완주 마킹까지만 하고 지급을 B1에 미루던 시절로
        // 돌아간 것이다. 그 사이 세션 결과 조회는 pointAwarded=0을 내린다.
        List<Long> memberIds = fixtures.joinMembers(PARTICIPANTS);
        Long sessionId = fixtures.openSession(TARGET_MINUTES, BASE_TIME, memberIds);
        LocalDateTime leftAt = BASE_TIME.plusMinutes(10);
        clock.fixAt(leftAt);

        // 남은 사람이 최소 인원 미만이 될 때까지 내보낸다
        for (Long memberId : memberIds.subList(0, PARTICIPANTS - 1)) {
            sessionExitService.leaveOnRequest(memberId, sessionId, LeftReason.PERSONAL);
        }

        Long stayed = memberIds.get(PARTICIPANTS - 1);
        assertThat(fixtures.session(sessionId).getStatus()).isEqualTo(SessionStatus.ENDED);
        assertThat(fixtures.session(sessionId).getEndReason())
                .isEqualTo(SessionEndReason.EARLY_UNDER_MIN);
        assertThat(fixtures.session(sessionId).getEndedAt()).isEqualTo(leftAt);
        assertThat(fixtures.participant(sessionId, stayed).isCompleted()).isTrue();
        assertThat(fixtures.participant(sessionId, stayed).getPointAwarded()).isPositive();
        // 먼저 나간 사람은 그 세션이 미완주다. 포인트 차감은 없다(D10)
        assertThat(fixtures.count("session_participant",
                "session_id = ? AND completed = TRUE", sessionId)).isEqualTo(1);
        Settlement afterEarlyClose = settlementOf(sessionId, memberIds);

        clock.fixAt(BASE_TIME.plusMinutes(TARGET_MINUTES + 1));
        int processed = sessionClosingBatch.run();

        assertThat(processed).isZero();
        assertThat(settlementOf(sessionId, memberIds)).isEqualTo(afterEarlyClose);
    }

    @Test
    @DisplayName("room_finished 웹훅이 두 번 와도 한 번만 정산한다")
    void room_finished_중복_수신은_한_번만_정산한다() throws Exception {
        // 이 테스트가 죽으면: 중복 수신되는 웹훅이 종료 루틴을 두 번 태운 것이다. 웹훅은
        // 재시도로 같은 이벤트가 여러 번 도착하는 것이 정상이다.
        List<Long> memberIds = fixtures.joinMembers(PARTICIPANTS);
        Long sessionId = fixtures.openSession(TARGET_MINUTES, BASE_TIME, memberIds);
        LocalDateTime finishedAt = BASE_TIME.plusMinutes(30);
        clock.fixAt(finishedAt);

        deliverRoomFinished(sessionId);
        Settlement afterFirstDelivery = settlementOf(sessionId, memberIds);

        deliverRoomFinished(sessionId);

        assertThat(fixtures.session(sessionId).getStatus()).isEqualTo(SessionStatus.ENDED);
        assertThat(fixtures.session(sessionId).getEndedAt()).isEqualTo(finishedAt);
        assertThat(fixtures.count("session_participant",
                "session_id = ? AND completed = TRUE", sessionId)).isEqualTo(PARTICIPANTS);
        assertThat(settlementOf(sessionId, memberIds)).isEqualTo(afterFirstDelivery);
    }

    @Test
    @DisplayName("서명이 맞지 않는 웹훅은 세션을 건드리지 못한다")
    void 서명이_틀린_웹훅은_처리되지_않는다() throws Exception {
        // 이 테스트가 죽으면: 누구나 남의 세션을 끝낼 수 있는 공개 엔드포인트가 된 것이다.
        // 이 경로는 JWT 게이트를 전부 건너뛰므로 서명이 유일한 신원 보장이다.
        List<Long> memberIds = fixtures.joinMembers(PARTICIPANTS);
        Long sessionId = fixtures.openSession(TARGET_MINUTES, BASE_TIME, memberIds);
        clock.fixAt(BASE_TIME.plusMinutes(30));
        String body = webhookSigner.toJson(roomFinished(sessionId));

        mockMvc.perform(post("/api/webhooks/livekit")
                        .header(HttpHeaders.AUTHORIZATION, webhookSigner.authorization("{}"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized());

        assertThat(fixtures.session(sessionId).getStatus()).isEqualTo(SessionStatus.LIVE);
        assertThat(fixtures.count("session_participant",
                "session_id = ? AND completed = TRUE", sessionId)).isZero();
    }

    private void deliverRoomFinished(Long sessionId) throws Exception {
        String body = webhookSigner.toJson(roomFinished(sessionId));
        mockMvc.perform(post("/api/webhooks/livekit")
                        .header(HttpHeaders.AUTHORIZATION, webhookSigner.authorization(body))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }

    private WebhookEvent roomFinished(Long sessionId) {
        return WebhookEvent.newBuilder()
                .setEvent("room_finished")
                .setRoom(Room.newBuilder().setName(LiveSession.roomNameOf(sessionId)))
                .build();
    }

    /** 종료가 남긴 것 전부. 두 번째 실행 뒤에도 같아야 한다. */
    private record Settlement(int ledgerRows, int ledgerSum, int streakDays, int completed,
                              int awarded) {
    }

    private Settlement settlementOf(Long sessionId, List<Long> memberIds) {
        int ledgerRows = 0;
        int ledgerSum = 0;
        int streakDays = 0;
        for (Long memberId : memberIds) {
            ledgerRows += fixtures.count("point_ledger", "member_id = ?", memberId);
            ledgerSum += fixtures.ledgerSum(memberId);
            streakDays += fixtures.count("streak_day", "member_id = ?", memberId);
            // 잔액 캐시가 원장과 갈라지지 않았는지도 이 자리에서 함께 본다
            assertThat(fixtures.member(memberId).getPointBalance())
                    .isEqualTo(fixtures.ledgerSum(memberId));
        }
        int completed = fixtures.count("session_participant",
                "session_id = ? AND completed = TRUE", sessionId);
        int awarded = fixtures.count("session_participant",
                "session_id = ? AND point_awarded > 0", sessionId);
        return new Settlement(ledgerRows, ledgerSum, streakDays, completed, awarded);
    }
}
