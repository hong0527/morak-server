package com.morak.session.dto.response;

/**
 * SS-4 판정 결과. 클라이언트는 경고 횟수를 세지 않고 이 응답만 그린다 — 세는 주체가 둘이면
 * 화면과 서버 판정이 어긋난다(★D4).
 *
 * @param pointDelta 퇴출 패널티 금액. 원장 기록은 6단계 소관이라 지금은 {@code eviction}
 *                   행의 {@code point_penalty}가 근거로 남는다
 */
public record AbsenceEventResponse(boolean accepted, int warningCount, boolean evicted,
                                   Long evictionId, int pointDelta) {

    public static AbsenceEventResponse of(int warningCount, Long evictionId, int pointPenalty) {
        boolean evicted = evictionId != null;
        return new AbsenceEventResponse(true, warningCount, evicted, evictionId,
                evicted ? -pointPenalty : 0);
    }
}
