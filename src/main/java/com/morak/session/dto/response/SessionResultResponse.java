package com.morak.session.dto.response;

import com.morak.common.type.BadgeCode;
import com.morak.member.service.StreakService;
import com.morak.session.entity.LiveSession;
import com.morak.session.entity.SessionParticipant;
import com.morak.session.type.LeftReason;
import com.morak.session.type.ParticipantStatus;
import com.morak.session.type.SessionEndReason;
import com.morak.session.type.SessionStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * SS-8 세션 결과. 읽기만 한다 — 지급은 B1이 이미 끝냈다.
 *
 * <p>{@code my.streak}는 {@code member.current_streak}가 아니라 그 세션 시점의 값이다.
 * 캐시를 쓰면 과거 세션 결과를 다시 열었을 때 그때가 아니라 지금의 연속 일수가 나온다.
 */
public record SessionResultResponse(
        Long sessionId,
        SessionStatus status,
        int targetMinutes,
        LocalDateTime startedAt,
        LocalDateTime endedAt,
        SessionEndReason endReason,
        My my,
        List<Participant> participants) {

    /**
     * {@code countedToday=false}는 같은 날 이미 다른 세션으로 완주가 기록되어 Streak가
     * 중복 증가하지 않았다는 뜻이다(★D2).
     */
    public record Streak(int before, int after, boolean countedToday) {}

    /**
     * {@code evictionId}는 퇴출된 본인에게만 값이 있고 아니면 null이다. 이의 신청(AP-1)의
     * 진입 번호라 결과 화면에서 그대로 이의 버튼을 그릴 수 있어야 한다 — 퇴출 순간의 SS-4
     * 응답 한 번에만 실려 있으면 그 응답을 놓친 사용자는 3일짜리 기한을 흘려보낸다.
     */
    public record My(
            boolean completed,
            ParticipantStatus participantStatus,
            LeftReason leftReason,
            int warningCount,
            int pointAwarded,
            Streak streak,
            boolean goalAchieved,
            BadgeCode badgeCode,
            Long evictionId) {

        static My of(SessionParticipant participant, StreakService.StreakSnapshot snapshot,
                     boolean countedToday, boolean goalAchieved, Long evictionId) {
            return new My(
                    participant.isCompleted(),
                    participant.getStatus(),
                    participant.getLeftReason(),
                    participant.getWarningCount(),
                    participant.getPointAwarded(),
                    new Streak(snapshot.before(), snapshot.after(), countedToday),
                    goalAchieved,
                    // 뱃지는 저장하지 않고 목표 달성 여부에서 파생한다(D3)
                    goalAchieved ? BadgeCode.GOAL_ACHIEVED : null,
                    evictionId);
        }
    }

    /** 남의 결과는 완주 여부와 경고 수까지만 보인다. 지급액은 본인 것만 내려간다. */
    public record Participant(
            Long memberId,
            String nickname,
            boolean isMe,
            ParticipantStatus participantStatus,
            boolean completed,
            int warningCount) {

        static Participant of(SessionParticipant participant, String nickname, Long viewerId) {
            return new Participant(
                    participant.getMemberId(),
                    nickname,
                    participant.getMemberId().equals(viewerId),
                    participant.getStatus(),
                    participant.isCompleted(),
                    participant.getWarningCount());
        }
    }

    public static SessionResultResponse of(LiveSession session,
                                           List<SessionParticipant> participants,
                                           Map<Long, String> nicknames,
                                           SessionParticipant me,
                                           StreakService.StreakSnapshot snapshot,
                                           boolean countedToday,
                                           boolean goalAchieved,
                                           Long evictionId) {
        return new SessionResultResponse(
                session.getId(),
                session.getStatus(),
                session.getTargetMinutes(),
                session.getStartedAt(),
                session.getEndedAt(),
                session.getEndReason(),
                My.of(me, snapshot, countedToday, goalAchieved, evictionId),
                participants.stream()
                        .map(participant -> Participant.of(
                                participant,
                                nicknames.get(participant.getMemberId()),
                                me.getMemberId()))
                        .toList());
    }
}
