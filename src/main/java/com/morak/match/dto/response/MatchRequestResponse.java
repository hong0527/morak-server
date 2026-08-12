package com.morak.match.dto.response;

import com.morak.match.entity.MatchRequest;
import com.morak.match.type.MatchRequestStatus;
import java.time.LocalDateTime;

/** MT-1·MT-2 공통 응답. openapi.yaml MatchRequestResponse 스키마와 1:1 대응. */
public record MatchRequestResponse(
        Long matchRequestId,
        MatchRequestStatus status,
        int targetMinutes,
        LocalDateTime requestedAt,
        LocalDateTime expiresAt,
        int waitingCount,
        int requiredCount,
        Long sessionId) {

    public static MatchRequestResponse of(MatchRequest request, int waitingCount,
                                          int requiredCount) {
        return new MatchRequestResponse(
                request.getId(),
                request.getStatus(),
                request.getTargetMinutes(),
                request.getRequestedAt(),
                request.getExpiresAt(),
                waitingCount,
                requiredCount,
                request.getMatchedSessionId());
    }

    /**
     * 성사 직후 전용. 성사는 벌크 UPDATE로 확정하는데 그 UPDATE가 영속성 컨텍스트를 비우므로
     * 엔티티는 아직 WAITING 시절의 값을 들고 있다. 확정된 status와 세션 번호는 인자로 받는다.
     */
    public static MatchRequestResponse matched(MatchRequest request, Long sessionId,
                                               int requiredCount) {
        return new MatchRequestResponse(
                request.getId(),
                MatchRequestStatus.MATCHED,
                request.getTargetMinutes(),
                request.getRequestedAt(),
                request.getExpiresAt(),
                requiredCount,
                requiredCount,
                sessionId);
    }
}
