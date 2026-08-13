package com.morak.session.dto.response;

/**
 * SS-4 판정 결과. 클라이언트는 경고 횟수를 세지 않고 이 응답만 그린다 — 세는 주체가 둘이면
 * 화면과 서버 판정이 어긋난다(★D4).
 *
 * @param pointDelta 이 응답으로 확정된 포인트 증감. 퇴출이면 패널티만큼 음수이고 아니면 0이다.
 *                   원장은 퇴출 트랜잭션이 같은 자리에서 이미 남겼으므로 이 값은 예고가 아니라
 *                   결과다({@code EvictionService} 주석)
 */
public record AbsenceEventResponse(boolean accepted, int warningCount, boolean evicted,
                                   Long evictionId, int pointDelta) {

    public static AbsenceEventResponse of(int warningCount, Long evictionId, int pointPenalty) {
        boolean evicted = evictionId != null;
        return new AbsenceEventResponse(true, warningCount, evicted, evictionId,
                evicted ? -pointPenalty : 0);
    }
}
