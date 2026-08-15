package com.morak.session.service;

import com.morak.common.error.BusinessException;
import com.morak.common.error.ErrorCode;
import com.morak.session.entity.LiveSession;
import com.morak.session.entity.SessionParticipant;
import com.morak.session.repository.LiveSessionRepository;
import com.morak.session.repository.SessionParticipantRepository;
import com.morak.session.type.ParticipantStatus;
import io.livekit.server.WebhookReceiver;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Set;
import livekit.LivekitWebhook.WebhookEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SS-10 LiveKit 웹훅. <b>누가 지금 방에 있는지의 진실 원천은 서버 DB가 아니라 이 웹훅이다.</b>
 * 서버는 LiveKit이 알려주는 것을 받아 적을 뿐이라, 중복 수신·순서 뒤바뀜을 전제로 짠다.
 *
 * <p>이 경로는 JWT 게이트를 전부 건너뛴다. 그래서 {@link #verify}가 유일한 신원 보장
 * 수단이고, 컨트롤러의 첫 줄이어야 한다. 검증을 빠뜨리면 누구나 남을 세션에서
 * 퇴장시킬 수 있는 공개 엔드포인트가 된다.
 *
 * <p>참가자 식별은 {@code participant.identity} = {@code member_id} 문자열 규약에 전적으로
 * 의존한다({@link LiveKitTokenProvider}와 같은 규약). 숫자가 아닌 identity는 위조 신호이므로
 * 처리하지 않고, 그래도 200으로 응답한다 — 4xx를 주면 LiveKit이 재시도를 반복한다.
 */
@Service
public class LiveKitWebhookService {

    private static final Logger log = LoggerFactory.getLogger(LiveKitWebhookService.class);

    private static final String EVENT_PARTICIPANT_JOINED = "participant_joined";
    private static final String EVENT_PARTICIPANT_LEFT = "participant_left";
    private static final String EVENT_ROOM_FINISHED = "room_finished";

    private static final Set<ParticipantStatus> PRESENT =
            Set.of(ParticipantStatus.ACTIVE, ParticipantStatus.PAUSED);

    private final WebhookReceiver webhookReceiver;
    private final LiveSessionRepository liveSessionRepository;
    private final SessionParticipantRepository sessionParticipantRepository;
    private final SessionExitService sessionExitService;
    private final ReconnectGraceRegistry graceRegistry;
    private final Clock clock;

    public LiveKitWebhookService(LiveSessionRepository liveSessionRepository,
                                 SessionParticipantRepository sessionParticipantRepository,
                                 SessionExitService sessionExitService,
                                 ReconnectGraceRegistry graceRegistry,
                                 Clock clock,
                                 @Value("${morak.livekit.api-key}") String apiKey,
                                 @Value("${morak.livekit.api-secret}") String apiSecret) {
        this.webhookReceiver = new WebhookReceiver(apiKey, apiSecret);
        this.liveSessionRepository = liveSessionRepository;
        this.sessionParticipantRepository = sessionParticipantRepository;
        this.sessionExitService = sessionExitService;
        this.graceRegistry = graceRegistry;
        this.clock = clock;
    }

    /**
     * 서명 검증. LiveKit은 본문의 SHA-256 해시를 claim에 넣고 API 시크릿으로 서명한 JWT를
     * {@code Authorization} 헤더로 보낸다. 서명과 본문 해시 둘 다 맞아야 통과한다.
     */
    public WebhookEvent verify(String authorization, String body) {
        if (authorization == null || authorization.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_WEBHOOK_SIGNATURE);
        }
        try {
            return webhookReceiver.receive(body, authorization);
        } catch (Exception e) {
            log.warn("웹훅 서명 검증 실패", e);
            throw new BusinessException(ErrorCode.INVALID_WEBHOOK_SIGNATURE);
        }
    }

    @Transactional
    public void handle(WebhookEvent event) {
        switch (event.getEvent()) {
            case EVENT_PARTICIPANT_JOINED -> onParticipantJoined(event);
            case EVENT_PARTICIPANT_LEFT -> onParticipantLeft(event);
            case EVENT_ROOM_FINISHED -> onRoomFinished(event);
            // 트랙 발행 등 우리가 쓰지 않는 이벤트가 같은 URL로 온다. 처리하지 않는 것이 정상이다.
            default -> log.debug("처리 대상이 아닌 이벤트: {}", event.getEvent());
        }
    }

    /**
     * 최초 입장 시각 기록과 유예 창 해제. {@code joined}가 {@code left}보다 늦게 도착하는
     * 경우가 있어 지금 상태를 보고 판단한다 — 이미 퇴장·퇴출된 사람은 되돌리지 않는다
     * (LEFT는 재입장 불가다).
     */
    private void onParticipantJoined(WebhookEvent event) {
        LiveSession session = findLiveSession(event);
        if (session == null) {
            return;
        }
        SessionParticipant participant = findParticipant(session, event);
        if (participant == null) {
            return;
        }
        // 이벤트 발생 시각으로 판단한다. 수신 시각을 쓰면 끊기기 전의 입장 이벤트가 뒤늦게
        // 도착했을 때 방금 열린 유예 창을 지운다.
        graceRegistry.closeIfNotBefore(session.getId(), participant.getMemberId(),
                occurredAt(event));
        if (!PRESENT.contains(participant.getStatus())) {
            log.info("참여 상태가 아닌 참가자의 입장 이벤트라 기록하지 않는다: session={}, member={}, status={}",
                    session.getId(), participant.getMemberId(), participant.getStatus());
            return;
        }
        // 최초 1회만 채운다. 재접속이 시각을 덮으면 참여 시작 시점이 사라진다.
        participant.markJoined(LocalDateTime.now(clock));
    }

    /**
     * 재접속 유예 창을 연다. 여기서 상태를 바꾸지 않는 것이 D13의 핵심이다 — 즉시
     * 퇴장 처리하면 지하철에서 잠깐 끊긴 사람이 그대로 미완주가 된다.
     */
    private void onParticipantLeft(WebhookEvent event) {
        LiveSession session = findLiveSession(event);
        if (session == null) {
            return;
        }
        SessionParticipant participant = findParticipant(session, event);
        if (participant == null) {
            return;
        }
        if (!PRESENT.contains(participant.getStatus())) {
            // 이미 퇴장했거나 퇴출된 참가자에게는 유예 타이머를 걸지 않는다
            return;
        }
        graceRegistry.open(session.getId(), participant.getMemberId(), occurredAt(event));
    }

    /**
     * 이벤트가 LiveKit에서 발생한 시각. 유예 창을 열고 닫는 판단이 <b>같은 시계</b>를 봐야
     * 순서 뒤바뀜을 가려낼 수 있어 수신 시각을 쓰지 않는다.
     *
     * <p>{@code createdAt}이 비어 있으면(구버전 서버·테스트 이벤트) 수신 시각으로 대신한다.
     * 그때는 순서 판정을 포기하는 것이지만, 값이 없다고 이벤트를 버리면 유예 창 자체가 서지 않는다.
     */
    private LocalDateTime occurredAt(WebhookEvent event) {
        if (event.getCreatedAt() <= 0) {
            return LocalDateTime.now(clock);
        }
        return LocalDateTime.ofInstant(Instant.ofEpochSecond(event.getCreatedAt()), clock.getZone());
    }

    private void onRoomFinished(WebhookEvent event) {
        LiveSession session = findLiveSession(event);
        if (session == null) {
            return;
        }
        sessionExitService.endOnRoomFinished(session);
    }

    /** 방 이름 → 세션. 끝난 세션의 이벤트는 늦게 도착한 것이므로 처리하지 않는다. */
    private LiveSession findLiveSession(WebhookEvent event) {
        if (!event.hasRoom() || event.getRoom().getName().isEmpty()) {
            log.warn("방 정보가 없는 웹훅이라 처리하지 않는다: event={}", event.getEvent());
            return null;
        }
        String roomName = event.getRoom().getName();
        LiveSession session = liveSessionRepository.findByLivekitRoomName(roomName).orElse(null);
        if (session == null) {
            log.warn("알 수 없는 방 이름이라 처리하지 않는다: room={}", roomName);
            return null;
        }
        if (!session.isLive()) {
            log.info("이미 종료된 세션의 이벤트라 처리하지 않는다: session={}, event={}",
                    session.getId(), event.getEvent());
            return null;
        }
        return session;
    }

    /** identity 파싱 실패와 비참가자는 같은 취급이다 — 둘 다 처리하지 않고 로그만 남긴다. */
    private SessionParticipant findParticipant(LiveSession session, WebhookEvent event) {
        if (!event.hasParticipant()) {
            log.warn("참가자 정보가 없는 웹훅이라 처리하지 않는다: event={}", event.getEvent());
            return null;
        }
        String identity = event.getParticipant().getIdentity();
        Long memberId;
        try {
            memberId = Long.valueOf(identity);
        } catch (NumberFormatException e) {
            // identity 규약(member_id 문자열)을 벗어난 값은 위조 신호다
            log.warn("숫자가 아닌 identity라 처리하지 않는다: identity={}", identity);
            return null;
        }
        SessionParticipant participant = sessionParticipantRepository
                .findBySessionIdAndMemberId(session.getId(), memberId)
                .orElse(null);
        if (participant == null) {
            log.warn("세션 참가자가 아니라 처리하지 않는다: session={}, member={}",
                    session.getId(), memberId);
        }
        return participant;
    }
}
