package com.morak.session.dto.response;

import com.morak.session.entity.LiveSession;
import com.morak.session.entity.SessionParticipant;
import com.morak.session.type.ParticipantStatus;
import com.morak.session.type.SessionStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * AD-7 진행 중 세션 모니터 항목.
 *
 * <p><b>영상은 관리자에게도 제공하지 않는다</b>(D17). 저장하지 않으므로 존재하지 않고,
 * 여기서 볼 수 있는 것은 참가자의 상태와 경고 현황뿐이다.
 *
 * <p>상태별 인원수는 저장 컬럼이 아니라 참가자 목록에서 센 값이다. 세션 행에 카운터를 두면
 * 상태가 바뀔 때마다 두 곳을 맞춰야 하고, 어긋나면 어느 쪽이 옳은지 알 수 없다.
 */
public record AdminSessionResponse(
        Long sessionId,
        SessionStatus status,
        int targetMinutes,
        LocalDateTime startedAt,
        LocalDateTime endsAt,
        int activeCount,
        int pausedCount,
        int leftCount,
        int evictedCount,
        List<Participant> participants) {

    /** {@code paused}는 상태에서 파생한 편의값이다 — 모니터가 화장실 모드를 따로 표시한다. */
    public record Participant(
            Long memberId,
            String nickname,
            ParticipantStatus status,
            int warningCount,
            boolean paused) {

        public static Participant of(SessionParticipant participant, String nickname) {
            return new Participant(
                    participant.getMemberId(),
                    nickname,
                    participant.getStatus(),
                    participant.getWarningCount(),
                    participant.getStatus() == ParticipantStatus.PAUSED);
        }
    }

    public static AdminSessionResponse of(LiveSession session,
                                          List<SessionParticipant> participants,
                                          Map<Long, String> nicknames) {
        return new AdminSessionResponse(
                session.getId(),
                session.getStatus(),
                session.getTargetMinutes(),
                session.getStartedAt(),
                session.getEndsAt(),
                count(participants, ParticipantStatus.ACTIVE),
                count(participants, ParticipantStatus.PAUSED),
                count(participants, ParticipantStatus.LEFT),
                count(participants, ParticipantStatus.EVICTED),
                participants.stream()
                        .map(participant -> Participant.of(participant,
                                nicknames.get(participant.getMemberId())))
                        .toList());
    }

    private static int count(List<SessionParticipant> participants, ParticipantStatus status) {
        return (int) participants.stream()
                .filter(participant -> participant.getStatus() == status)
                .count();
    }
}
