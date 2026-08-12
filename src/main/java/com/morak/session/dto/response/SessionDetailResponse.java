package com.morak.session.dto.response;

import com.morak.session.entity.LiveSession;
import com.morak.session.entity.SessionParticipant;
import com.morak.session.type.ParticipantStatus;
import com.morak.session.type.SessionEndReason;
import com.morak.session.type.SessionStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * SS-1 세션 상세. 참가자는 LEFT·EVICTED도 포함한다 — 상태를 병기하므로 빈자리가 화면에 드러난다.
 *
 * <p>내려보내는 신원 값은 익명 닉네임뿐이다. 실명·SNS 값은 어떤 경우에도 넣지 않는다.
 */
public record SessionDetailResponse(
        Long sessionId,
        SessionStatus status,
        int targetMinutes,
        LocalDateTime startedAt,
        LocalDateTime endsAt,
        LocalDateTime endedAt,
        SessionEndReason endReason,
        String roomName,
        List<Participant> participants) {

    /**
     * {@code joinedAt}이 null이면 매칭은 됐지만 아직 룸에 들어오지 않은 참가자다.
     * 값은 SS-10 {@code participant_joined} 웹훅이 최초 1회만 채운다.
     */
    public record Participant(
            Long memberId,
            String nickname,
            boolean isMe,
            ParticipantStatus status,
            int warningCount,
            boolean paused,
            boolean pauseUsed,
            LocalDateTime joinedAt,
            String goalText) {

        static Participant of(SessionParticipant participant, String nickname, Long viewerId) {
            return new Participant(
                    participant.getMemberId(),
                    nickname,
                    participant.getMemberId().equals(viewerId),
                    participant.getStatus(),
                    participant.getWarningCount(),
                    participant.getStatus() == ParticipantStatus.PAUSED,
                    participant.isPauseUsed(),
                    participant.getJoinedAt(),
                    participant.getGoalText());
        }
    }

    public static SessionDetailResponse of(LiveSession session,
                                           List<SessionParticipant> participants,
                                           Map<Long, String> nicknames,
                                           Long viewerId) {
        return new SessionDetailResponse(
                session.getId(),
                session.getStatus(),
                session.getTargetMinutes(),
                session.getStartedAt(),
                session.getEndsAt(),
                session.getEndedAt(),
                session.getEndReason(),
                session.getLivekitRoomName(),
                participants.stream()
                        .map(participant -> Participant.of(
                                participant,
                                nicknames.get(participant.getMemberId()),
                                viewerId))
                        .toList());
    }
}
