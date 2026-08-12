package com.morak.session.dto.response;

import com.morak.session.entity.LiveSession;
import com.morak.session.entity.SessionParticipant;
import com.morak.session.type.ParticipantStatus;
import com.morak.session.type.SessionStatus;
import java.time.LocalDateTime;

/**
 * SS-9 내 세션 이력 한 줄. {@code status}는 세션의 상태이고 {@code participantStatus}는
 * 그 세션에서의 내 상태다 — 진행 중인 세션에서 이미 퇴장한 경우가 있어 둘을 함께 내린다.
 */
public record MySessionSummaryResponse(
        Long sessionId,
        int targetMinutes,
        LocalDateTime startedAt,
        LocalDateTime endedAt,
        SessionStatus status,
        ParticipantStatus participantStatus,
        boolean completed,
        int pointAwarded,
        int warningCount) {

    public static MySessionSummaryResponse of(SessionParticipant participant,
                                              LiveSession session) {
        return new MySessionSummaryResponse(
                session.getId(),
                session.getTargetMinutes(),
                session.getStartedAt(),
                session.getEndedAt(),
                session.getStatus(),
                participant.getStatus(),
                participant.isCompleted(),
                participant.getPointAwarded(),
                participant.getWarningCount());
    }
}
