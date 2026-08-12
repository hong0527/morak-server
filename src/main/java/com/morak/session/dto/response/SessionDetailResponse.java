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
     *
     * <p>{@code evictionId}는 <b>본인 행에만</b> 실린다. 이의 신청(AP-1)의 진입 번호이므로
     * 남의 행에 실으면 같은 세션에 있었다는 이유로 타인의 이의 경로를 열 수 있다.
     * 퇴출되지 않았거나 남의 행이면 null이다.
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
            String goalText,
            Long evictionId) {

        static Participant of(SessionParticipant participant, String nickname, Long viewerId,
                              Long viewerEvictionId) {
            boolean isMe = participant.getMemberId().equals(viewerId);
            return new Participant(
                    participant.getMemberId(),
                    nickname,
                    isMe,
                    participant.getStatus(),
                    participant.getWarningCount(),
                    participant.getStatus() == ParticipantStatus.PAUSED,
                    participant.isPauseUsed(),
                    participant.getJoinedAt(),
                    participant.getGoalText(),
                    isMe ? viewerEvictionId : null);
        }
    }

    public static SessionDetailResponse of(LiveSession session,
                                           List<SessionParticipant> participants,
                                           Map<Long, String> nicknames,
                                           Long viewerId,
                                           Long viewerEvictionId) {
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
                                viewerId,
                                viewerEvictionId))
                        .toList());
    }
}
