package com.morak.session.dto.response;

import com.morak.session.entity.LiveSession;
import com.morak.session.entity.SessionParticipant;
import com.morak.session.type.ParticipantStatus;
import java.time.LocalDateTime;

/**
 * AU-2의 {@code activeSession} — 진행 중 세션에 ACTIVE·PAUSED로 참가 중인 행.
 *
 * <p>앱을 재시작한 클라이언트가 재접속 유예(D13, 90초) 안에 SS-2를 다시 받으러 갈 진입점이다.
 * 이 필드가 없으면 세션 번호를 되찾을 계약상 경로가 없다 — MT-1은 409만 주고, SS-9 이력에는
 * 진행 중 세션의 번호가 실리지만 그것을 복귀 경로로 쓰라는 계약이 없다.
 */
public record ActiveSessionResponse(
        Long sessionId,
        ParticipantStatus participantStatus,
        int targetMinutes,
        LocalDateTime startedAt,
        LocalDateTime endsAt) {

    public static ActiveSessionResponse of(SessionParticipant participant, LiveSession session) {
        return new ActiveSessionResponse(
                session.getId(),
                participant.getStatus(),
                session.getTargetMinutes(),
                session.getStartedAt(),
                session.getEndsAt());
    }
}
