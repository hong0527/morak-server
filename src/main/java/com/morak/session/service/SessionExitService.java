package com.morak.session.service;

import com.morak.common.error.BusinessException;
import com.morak.common.error.ErrorCode;
import com.morak.session.entity.LiveSession;
import com.morak.session.entity.SessionParticipant;
import com.morak.session.repository.LiveSessionRepository;
import com.morak.session.repository.SessionParticipantRepository;
import com.morak.session.type.LeftReason;
import com.morak.session.type.ParticipantStatus;
import com.morak.session.type.SessionEndReason;
import com.morak.session.type.SessionStatus;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 세션에서 사람이 빠지는 경로를 한 곳에 모은 서비스. 자율 퇴장(SS-7)·재접속 유예 초과
 * (SS-10)·탈퇴(AU-4)가 여기를 지난다.
 *
 * <p><b>모아 둔 이유는 퇴장이 혼자 끝나는 일이 아니기 때문이다.</b> 한 명이 빠질 때마다
 * 잔여 인원이 {@code session.min-participants} 미만인지 봐야 하고(D12), 미만이면 세션을
 * 조기 종료해야 한다. 이 두 단계를 호출부마다 따로 쓰면 어느 한 경로에서 빠뜨려
 * "혼자 남은 세션이 계속 LIVE"인 상태가 생긴다.
 *
 * <p><b>세션을 닫는 일 자체는 여기서 하지 않는다.</b> 종료는 사유가 무엇이든 같은 루틴이어야
 * 해서 {@link SessionClosingService#closeSession} 하나로 모았다 — 이 클래스가 따로 닫던 동안
 * 조기 종료와 {@code room_finished}는 미결 정산과 포인트 지급을 건너뛰었고, 그래서 남들이
 * 먼저 나가면 자기 경고가 사라지는 회피 경로가 있었다.
 *
 * <p><b>이 경로 자체는 경고를 만들지 않고 포인트를 건드리지 않는다</b>(D13). 연결 끊김은
 * 위반이 아니라 사고이므로 대가는 그 세션의 미완주뿐이다.
 */
@Service
@Transactional
public class SessionExitService {

    private static final Logger log = LoggerFactory.getLogger(SessionExitService.class);

    private static final Set<ParticipantStatus> PRESENT =
            Set.of(ParticipantStatus.ACTIVE, ParticipantStatus.PAUSED);

    private final LiveSessionRepository liveSessionRepository;
    private final SessionParticipantRepository sessionParticipantRepository;
    private final SessionClosingService closingService;
    private final ReconnectGraceRegistry graceRegistry;
    private final Clock clock;

    public SessionExitService(LiveSessionRepository liveSessionRepository,
                              SessionParticipantRepository sessionParticipantRepository,
                              SessionClosingService closingService,
                              ReconnectGraceRegistry graceRegistry,
                              Clock clock) {
        this.liveSessionRepository = liveSessionRepository;
        this.sessionParticipantRepository = sessionParticipantRepository;
        this.closingService = closingService;
        this.graceRegistry = graceRegistry;
        this.clock = clock;
    }

    /**
     * 재접속 유예 초과(SS-10). 유예 창이 열린 사이에 상태가 바뀌었을 수 있으므로 지금
     * 상태를 다시 확인한다 — 그 사이 퇴출됐거나 세션이 끝났으면 할 일이 없다.
     */
    public void leaveOnGraceExpired(Long sessionId, Long memberId) {
        LiveSession session = liveSessionRepository.findById(sessionId).orElse(null);
        if (session == null || !session.isLive()) {
            return;
        }
        SessionParticipant participant = sessionParticipantRepository
                .findBySessionIdAndMemberId(sessionId, memberId)
                .orElse(null);
        if (participant == null || !PRESENT.contains(participant.getStatus())) {
            return;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        participant.leave(LeftReason.DEVICE_ISSUE, now);
        log.info("재접속 유예 초과로 자동 퇴장 처리: session={}, member={}", sessionId, memberId);
        closingService.closeIfUnderMinimum(sessionId, now);
    }

    /**
     * SS-7 자율 퇴장. 사유를 남기는 것 말고는 유예 초과 경로와 같은 일을 한다 — 포인트
     * 차감이 없고 그 세션만 미완주다(D10).
     *
     * <p>검사 순서는 참가 자격이 먼저다(§0-3). 세션 상태를 먼저 보면 참가자가 아닌 사람이
     * 세션 번호를 훑어 남의 세션이 끝났는지를 알아낼 수 있다.
     *
     * <p>퇴출된 참가자를 {@code ALREADY_LEFT}가 아니라 {@code ALREADY_EVICTED}로 끊는 것은
     * SS-2·SS-3과 같은 응답을 주기 위해서다. 같은 상태를 API마다 다른 코드로 내리면
     * 클라이언트가 한쪽에서만 이의 신청(AP-1) 경로를 그린다.
     */
    public void leaveOnRequest(Long memberId, Long sessionId, LeftReason reason) {
        LiveSession session = liveSessionRepository.findById(sessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SESSION_NOT_FOUND));
        SessionParticipant participant = sessionParticipantRepository
                .findBySessionIdAndMemberId(sessionId, memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_SESSION_PARTICIPANT));
        if (!session.isLive()) {
            throw new BusinessException(ErrorCode.SESSION_ENDED);
        }
        if (participant.getStatus() == ParticipantStatus.EVICTED) {
            throw new BusinessException(ErrorCode.ALREADY_EVICTED);
        }
        if (participant.getStatus() == ParticipantStatus.LEFT) {
            throw new BusinessException(ErrorCode.ALREADY_LEFT);
        }
        LocalDateTime now = LocalDateTime.now(clock);
        // PAUSED에서 나가는 경로가 있어 leave()가 pause_started_at을 함께 비운다
        participant.leave(reason, now);
        // 끊긴 채로 나간 사람의 유예 창이 열려 있을 수 있다. 두면 스위퍼가 이미 나간 사람을
        // 한 번 더 집어 든다.
        graceRegistry.close(sessionId, memberId);
        log.info("자율 퇴장: session={}, member={}, reason={}", sessionId, memberId, reason);
        closingService.closeIfUnderMinimum(sessionId, now);
    }

    /**
     * AU-4 탈퇴. 대기 중인 매칭 요청만 정리하고 세션을 두면 탈퇴한 회원이 남의 세션에
     * 계속 남아 자리를 차지한다.
     *
     * <p>참가 행을 미리 읽어 두지 않고 id로 도는 것은 종료 루틴이 포인트를 지급하며 영속성
     * 컨텍스트를 비우기 때문이다. 목록으로 돌면 첫 종료 이후의 참가 행이 준영속이 되어
     * 그들의 퇴장이 조용히 사라진다.
     */
    public void leaveAll(Long memberId, LeftReason reason) {
        List<Long> participantIds = sessionParticipantRepository
                .findParticipating(memberId, PRESENT, SessionStatus.LIVE).stream()
                .map(SessionParticipant::getId)
                .toList();
        LocalDateTime now = LocalDateTime.now(clock);
        for (Long participantId : participantIds) {
            SessionParticipant participant = sessionParticipantRepository.findById(participantId)
                    .orElseThrow(() -> new IllegalStateException(
                            "퇴장 대상 참가자가 사라졌다: " + participantId));
            Long sessionId = participant.getSessionId();
            participant.leave(reason, now);
            graceRegistry.close(sessionId, memberId);
            closingService.closeIfUnderMinimum(sessionId, now);
        }
    }

    /**
     * {@code room_finished} 웹훅이 부르는 정시 종료. 종료 루틴은 세션이 이미 끝났으면
     * 아무것도 하지 않는다 — 웹훅은 중복 수신되고, 조기 종료가 먼저 닿았을 수도 있다.
     */
    public void endOnRoomFinished(LiveSession session) {
        closingService.closeSession(session.getId(), SessionEndReason.NORMAL,
                LocalDateTime.now(clock));
    }
}
