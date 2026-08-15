package com.morak.session.dto.response;

import com.morak.common.type.BadgeCode;
import com.morak.member.service.StreakService;
import com.morak.session.entity.LiveSession;
import com.morak.session.entity.SessionParticipant;
import com.morak.session.service.WarningTraceService;
import com.morak.session.type.LeftReason;
import com.morak.session.type.ParticipantStatus;
import com.morak.session.type.SessionEndReason;
import com.morak.session.type.SessionStatus;
import com.morak.session.type.WarningBasis;
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
     * 본인 경고 1건과 그 근거 구간. 영상을 저장하지 않으므로(D17) 이 구간이 당사자가 이의
     * 사유(AP-1)를 쓸 유일한 재료다. 되짚기는 관리자 심사(AD-9)와 같은
     * {@link WarningTraceService}를 쓴다 — 관리자와 본인이 다른 구간을 보면 그 차이 자체가
     * 분쟁거리가 된다. Pause 초과 경고는 자리비움 구간이 없어 구간 세 필드가 null이다.
     */
    public record WarningItem(
            int seq,
            WarningBasis basis,
            LocalDateTime issuedAt,
            LocalDateTime absenceStartedAt,
            LocalDateTime absenceEndedAt,
            Long absentSeconds) {

        public static WarningItem from(WarningTraceService.WarningTrace trace) {
            // reportSkewSeconds는 싣지 않는다 — 전송 지연·시각 조작을 가리는 관리자용
            // 분석 신호라, 당사자에게 주면 다음 위조의 교본이 된다
            return new WarningItem(trace.seq(), trace.basis(), trace.issuedAt(),
                    trace.startedAt(), trace.endedAt(), trace.absentSeconds());
        }
    }

    /**
     * {@code evictionId}는 퇴출된 본인에게만 값이 있고 아니면 null이다. 이의 신청(AP-1)의
     * 진입 번호라 결과 화면에서 그대로 이의 버튼을 그릴 수 있어야 한다 — 퇴출 순간의 SS-4
     * 응답 한 번에만 실려 있으면 그 응답을 놓친 사용자는 3일짜리 기한을 흘려보낸다.
     *
     * <p>{@code warnings}도 같은 이유로 본인 정보에만 있다. 남의 행({@link Participant})에
     * 근거 구간이 실리면 같은 세션에 있었다는 이유로 타인의 자리비움 이력이 새어 나간다.
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
            Long evictionId,
            List<WarningItem> warnings) {

        static My of(SessionParticipant participant, StreakService.StreakSnapshot snapshot,
                     boolean countedToday, boolean goalAchieved, Long evictionId,
                     List<WarningItem> warnings) {
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
                    evictionId,
                    warnings);
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
                                           Long evictionId,
                                           List<WarningItem> myWarnings) {
        return new SessionResultResponse(
                session.getId(),
                session.getStatus(),
                session.getTargetMinutes(),
                session.getStartedAt(),
                session.getEndedAt(),
                session.getEndReason(),
                My.of(me, snapshot, countedToday, goalAchieved, evictionId, myWarnings),
                participants.stream()
                        .map(participant -> Participant.of(
                                participant,
                                nicknames.get(participant.getMemberId()),
                                me.getMemberId()))
                        .toList());
    }
}
