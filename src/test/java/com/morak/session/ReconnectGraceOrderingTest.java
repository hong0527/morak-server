package com.morak.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.morak.session.entity.LiveSession;
import com.morak.session.service.ReconnectGraceSweeper;
import com.morak.session.type.LeftReason;
import com.morak.session.type.ParticipantStatus;
import com.morak.support.IntegrationTest;
import com.morak.support.LiveKitWebhookSigner;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import livekit.LivekitModels.ParticipantInfo;
import livekit.LivekitModels.Room;
import livekit.LivekitWebhook.WebhookEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * LiveKit 웹훅이 순서를 지키지 않고 도착할 때 재접속 유예 창이 어떻게 되는지 본다(D13).
 *
 * <p>웹훅은 순서를 보장하지 않아 <b>끊기기 전의 {@code participant_joined}가 {@code left}보다
 * 늦게 도착한다</b>. 그 이벤트로 창을 닫으면 실제로 끊긴 참가자의 유예가 사라지고, 90초 뒤에
 * 와야 할 퇴장 처리가 영영 오지 않는다. 그는 끊긴 채로 ACTIVE에 남아 세션 종료 시점에
 * 완주자로 집계된다 — 접속하지 않고 완주하는 경로다.
 *
 * <p>그래서 판단 기준이 <b>수신 시각이 아니라 이벤트 발생 시각</b>이어야 한다.
 */
@DisplayName("재접속 유예 창과 웹훅 순서")
class ReconnectGraceOrderingTest extends IntegrationTest {

    private static final int TARGET_MINUTES = 60;
    private static final int PARTICIPANTS = 6;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private LiveKitWebhookSigner webhookSigner;

    @Autowired
    private ReconnectGraceSweeper reconnectGraceSweeper;

    @Value("${morak.session.reconnect-grace-seconds}")
    private int graceSeconds;

    @Test
    @DisplayName("끊김보다 이른 입장 이벤트가 뒤늦게 와도 유예 창은 살아 있다")
    void 늦게_도착한_옛_입장은_유예를_지우지_못한다() throws Exception {
        // 이 테스트가 죽으면: 순서가 뒤바뀐 웹훅 하나로 유예 창이 사라진다. 끊긴 참가자가
        // ACTIVE로 남아 세션 종료 때 완주 지급까지 받는다.
        List<Long> memberIds = fixtures.joinMembers(PARTICIPANTS);
        Long sessionId = fixtures.openSession(TARGET_MINUTES, BASE_TIME, memberIds);
        Long memberId = memberIds.getFirst();
        LocalDateTime disconnectedAt = BASE_TIME.plusMinutes(10);

        clock.fixAt(disconnectedAt);
        deliver(participantEvent("participant_left", sessionId, memberId, disconnectedAt));
        // 끊기기 10초 전에 발생한 입장 이벤트가 이제야 도착한다
        deliver(participantEvent("participant_joined", sessionId, memberId,
                disconnectedAt.minusSeconds(10)));

        clock.fixAt(disconnectedAt.plusSeconds(graceSeconds + 1L));
        reconnectGraceSweeper.sweep();

        assertThat(fixtures.participant(sessionId, memberId).getStatus())
                .isEqualTo(ParticipantStatus.LEFT);
        assertThat(fixtures.participant(sessionId, memberId).getLeftReason())
                .isEqualTo(LeftReason.DEVICE_ISSUE);
    }

    @Test
    @DisplayName("끊김 이후에 발생한 입장 이벤트는 유예 창을 닫는다")
    void 실제_재접속은_유예를_없던_일로_만든다() throws Exception {
        // 이 테스트가 죽으면: 돌아온 사람이 그대로 퇴장 처리된다. 지하철에서 잠깐 끊긴 사람이
        // 미완주가 되는 것이 D13이 막으려던 결과다.
        List<Long> memberIds = fixtures.joinMembers(PARTICIPANTS);
        Long sessionId = fixtures.openSession(TARGET_MINUTES, BASE_TIME, memberIds);
        Long memberId = memberIds.getFirst();
        LocalDateTime disconnectedAt = BASE_TIME.plusMinutes(10);

        clock.fixAt(disconnectedAt);
        deliver(participantEvent("participant_left", sessionId, memberId, disconnectedAt));
        clock.fixAt(disconnectedAt.plusSeconds(10));
        deliver(participantEvent("participant_joined", sessionId, memberId,
                disconnectedAt.plusSeconds(10)));

        clock.fixAt(disconnectedAt.plusSeconds(graceSeconds + 1L));
        reconnectGraceSweeper.sweep();

        assertThat(fixtures.participant(sessionId, memberId).getStatus())
                .isEqualTo(ParticipantStatus.ACTIVE);
        // 돌아오면 아무 흔적도 남기지 않는 것이 D13의 요구다
        assertThat(fixtures.participant(sessionId, memberId).getWarningCount()).isZero();
    }

    private WebhookEvent participantEvent(String type, Long sessionId, Long memberId,
                                          LocalDateTime occurredAt) {
        return WebhookEvent.newBuilder()
                .setEvent(type)
                .setCreatedAt(occurredAt.toEpochSecond(offset()))
                .setRoom(Room.newBuilder().setName(LiveSession.roomNameOf(sessionId)))
                .setParticipant(ParticipantInfo.newBuilder().setIdentity(String.valueOf(memberId)))
                .build();
    }

    private ZoneOffset offset() {
        return clock.getZone().getRules().getOffset(LocalDateTime.now(clock));
    }

    private void deliver(WebhookEvent event) throws Exception {
        String body = webhookSigner.toJson(event);
        mockMvc.perform(post("/api/webhooks/livekit")
                        .header(HttpHeaders.AUTHORIZATION, webhookSigner.authorization(body))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }
}
