package com.morak.session.dto.response;

import com.morak.session.entity.SessionParticipant;
import com.morak.session.type.ParticipantStatus;
import java.time.LocalDateTime;

/**
 * SS-5 화장실 모드 시작. 남은 시간을 클라이언트가 계산하도록 마감 시각을 함께 내린다 —
 * 상한값만 주면 단말 시계가 어긋난 만큼 카운트다운이 틀어진다.
 *
 * <p>경고 정보를 함께 싣는 이유는 시작 시점에 자리비움 구간이 마감되면서 경고가 붙을 수
 * 있기 때문이다(D9). 3회째는 화장실 모드 자체가 시작되지 않아 409로 드러나지만 1·2회째는
 * 정상 응답이라, 여기 싣지 않으면 사용자는 경고가 늘어난 것을 모르고 다음 경고에 퇴출된다.
 * 필드 이름은 SS-6과 같게 둔다 — 두 화면이 같은 값을 다르게 부르면 프론트가 분기한다.
 */
public record PauseStartResponse(ParticipantStatus status, LocalDateTime pausedAt,
                                 int pauseLimitSeconds, LocalDateTime resumeDueAt,
                                 boolean warningIssued, int warningCount,
                                 Long closedAbsenceSeconds) {

    /**
     * @param closedAbsenceSeconds 시작과 함께 마감된 자리비움 구간의 지속 초. 마감할 구간이
     *                             없었으면 {@code null}이다. 경고가 왜 붙었는지를 본인이
     *                             확인할 유일한 근거라 초를 그대로 내린다
     */
    public static PauseStartResponse of(SessionParticipant participant, LocalDateTime pausedAt,
                                        int pauseLimitSeconds, boolean warningIssued,
                                        Long closedAbsenceSeconds) {
        return new PauseStartResponse(ParticipantStatus.PAUSED, pausedAt, pauseLimitSeconds,
                pausedAt.plusSeconds(pauseLimitSeconds), warningIssued,
                participant.getWarningCount(), closedAbsenceSeconds);
    }
}
