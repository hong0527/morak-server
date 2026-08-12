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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 세션에서 사람이 빠지는 경로를 한 곳에 모은 서비스. 자율 퇴장(SS-7)·재접속 유예 초과
 * (SS-10)·탈퇴(AU-4)가 여기를 지나고, 퇴출(SS-4·SS-6)은 {@link EvictionService}가 상태를
 * 바꾼 뒤 조기 종료 검사만 이쪽에 맡긴다.
 *
 * <p><b>모아 둔 이유는 퇴장이 혼자 끝나는 일이 아니기 때문이다.</b> 한 명이 빠질 때마다
 * 잔여 인원이 {@code session.min-participants} 미만인지 봐야 하고(D12), 미만이면 세션을
 * 조기 종료하면서 남은 사람을 완주 처리해야 한다. 이 두 단계를 호출부마다 따로 쓰면
 * 어느 한 경로에서 빠뜨려 "혼자 남은 세션이 계속 LIVE"인 상태가 생긴다.
 *
 * <p><b>이 경로는 경고를 만들지 않고 포인트를 건드리지 않는다</b>(D13). 연결 끊김은 위반이
 * 아니라 사고이므로 대가는 그 세션의 미완주뿐이다. 완주자 포인트 지급은 5단계 B1 소관이라
 * 여기서는 {@code completed}만 세우고 금액은 0으로 둔다.
 */
@Service
@Transactional
public class SessionExitService {

    private static final Logger log = LoggerFactory.getLogger(SessionExitService.class);

    private static final Set<ParticipantStatus> PRESENT =
            Set.of(ParticipantStatus.ACTIVE, ParticipantStatus.PAUSED);

    /** 완주 지급액은 5단계 B1이 정한다. 여기서 임의의 금액을 적으면 지급 근거가 둘이 된다. */
    private static final int POINT_UNSETTLED = 0;

    private final LiveSessionRepository liveSessionRepository;
    private final SessionParticipantRepository sessionParticipantRepository;
    private final ReconnectGraceRegistry graceRegistry;
    private final Clock clock;
    private final int minParticipants;

    public SessionExitService(LiveSessionRepository liveSessionRepository,
                              SessionParticipantRepository sessionParticipantRepository,
                              ReconnectGraceRegistry graceRegistry,
                              Clock clock,
                              @Value("${morak.session.min-participants}") int minParticipants) {
        this.liveSessionRepository = liveSessionRepository;
        this.sessionParticipantRepository = sessionParticipantRepository;
        this.graceRegistry = graceRegistry;
        this.clock = clock;
        this.minParticipants = minParticipants;
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
        endIfUnderMinimum(session, now);
    }

    /**
     * SS-7 자율 퇴장. 사유를 남기는 것 말고는 유예 초과 경로와 같은 일을 한다 — 포인트
     * 차감이 없고 그 세션만 미완주다(D10).
     *
     * <p>퇴출된 참가자를 {@code ALREADY_LEFT}가 아니라 {@code ALREADY_EVICTED}로 끊는 것은
     * SS-2·SS-3과 같은 응답을 주기 위해서다. 같은 상태를 API마다 다른 코드로 내리면
     * 클라이언트가 한쪽에서만 이의 신청(AP-1) 경로를 그린다.
     */
    public void leaveOnRequest(Long memberId, Long sessionId, LeftReason reason) {
        LiveSession session = liveSessionRepository.findById(sessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SESSION_NOT_FOUND));
        if (!session.isLive()) {
            throw new BusinessException(ErrorCode.SESSION_ENDED);
        }
        SessionParticipant participant = sessionParticipantRepository
                .findBySessionIdAndMemberId(sessionId, memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_SESSION_PARTICIPANT));
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
        endIfUnderMinimum(session, now);
    }

    /**
     * AU-4 탈퇴. 대기 중인 매칭 요청만 정리하고 세션을 두면 탈퇴한 회원이 남의 세션에
     * 계속 남아 자리를 차지한다.
     */
    public void leaveAll(Long memberId, LeftReason reason) {
        List<SessionParticipant> participating = sessionParticipantRepository
                .findParticipating(memberId, PRESENT, SessionStatus.LIVE);
        LocalDateTime now = LocalDateTime.now(clock);
        for (SessionParticipant participant : participating) {
            participant.leave(reason, now);
            graceRegistry.close(participant.getSessionId(), memberId);
            liveSessionRepository.findById(participant.getSessionId())
                    .ifPresent(session -> endIfUnderMinimum(session, now));
        }
    }

    /**
     * 잔여 ACTIVE+PAUSED가 최소 인원 미만이면 조기 종료한다(D12). PAUSED를 세는 것은
     * Pause가 재실로 인정되기 때문이다(★D1) — 빼면 화장실 간 사람 때문에 세션이 끝난다.
     *
     * <p>사람이 빠지는 경로마다 이 검사를 붙여야 하므로 4단계의 퇴출({@link EvictionService})도
     * 여기를 부른다. 경로별로 따로 세면 한 곳만 빠뜨려도 "혼자 남은 세션이 계속 LIVE"가 된다.
     */
    public void endIfUnderMinimum(LiveSession session, LocalDateTime now) {
        List<SessionParticipant> remaining = sessionParticipantRepository
                .findBySessionIdAndStatusIn(session.getId(), PRESENT);
        if (remaining.size() >= minParticipants) {
            return;
        }
        end(session, remaining, SessionEndReason.EARLY_UNDER_MIN, now);
    }

    /**
     * {@code room_finished} 웹훅이 부르는 정시 종료. 세션이 이미 끝났으면 아무것도 하지
     * 않는다 — 웹훅은 중복 수신되고, 조기 종료가 먼저 닿았을 수도 있다.
     */
    public void endOnRoomFinished(LiveSession session) {
        if (!session.isLive()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        end(session, sessionParticipantRepository.findBySessionIdAndStatusIn(session.getId(), PRESENT),
                SessionEndReason.NORMAL, now);
    }

    private void end(LiveSession session, List<SessionParticipant> present,
                     SessionEndReason reason, LocalDateTime now) {
        if (reason == SessionEndReason.NORMAL) {
            session.endNormally(now);
        } else {
            session.endUnderMinimum(now);
        }
        // 종료 시각에 이탈 상태가 아니면 완주다(★D1). 조기 종료로 짧게 끝났어도 남은 사람의
        // 책임이 아니므로 완주에서 빼지 않는다(D15 보충).
        for (SessionParticipant participant : present) {
            participant.complete(POINT_UNSETTLED);
        }
        graceRegistry.discardSession(session.getId());
        log.info("세션 종료: session={}, reason={}, 완주 {}명", session.getId(), reason, present.size());
    }
}
