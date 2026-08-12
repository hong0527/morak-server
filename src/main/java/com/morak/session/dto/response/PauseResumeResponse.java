package com.morak.session.dto.response;

import com.morak.session.entity.SessionParticipant;
import com.morak.session.type.ParticipantStatus;

/**
 * SS-6 복귀 결과. 10분을 넘겼는지는 서버만 알 수 있으므로 경과 시간과 경고 부여 여부를
 * 함께 내린다(D9). 3회째 경고로 퇴출되면 {@code status}는 {@code EVICTED}다.
 */
public record PauseResumeResponse(ParticipantStatus status, long elapsedSeconds,
                                  boolean warningIssued, int warningCount, boolean evicted) {

    public static PauseResumeResponse of(SessionParticipant participant, long elapsedSeconds,
                                         boolean warningIssued) {
        return new PauseResumeResponse(
                participant.getStatus(),
                elapsedSeconds,
                warningIssued,
                participant.getWarningCount(),
                participant.getStatus() == ParticipantStatus.EVICTED);
    }
}
