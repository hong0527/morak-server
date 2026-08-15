package com.morak.session.dto.response;

import com.morak.session.entity.SessionParticipant;

/** SS-3 응답. 저장된 값을 그대로 돌려준다. */
public record SessionGoalResponse(String goalText) {

    public static SessionGoalResponse from(SessionParticipant participant) {
        return new SessionGoalResponse(participant.getGoalText());
    }
}
