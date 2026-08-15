package com.morak.session.type;

/**
 * 퇴출 이의 처리 상태. ACCEPTED면 eviction.revoked_at을 채우고 차감 포인트를 원복한다.
 *
 * <p>{@code CLOSED}는 판단이 아니라 <b>심사가 성립하지 않아 종결된 것</b>이다. 지금 이 상태가
 * 되는 경로는 신청자의 계정이 파기된 경우 하나뿐이다(B4) — 파기가 경고·자리비움 이벤트를 지워
 * 심사 근거가 사라지고, 인용이 하는 완주 소급은 방금 파기한 개인 기록을 되살리는 일이 된다.
 * REJECTED로 적지 않는 이유는 그것이 "이의에 이유가 없다"는 판단이어서다. 근거를 보지 못한 채
 * 그렇게 적으면 남는 기록이 사실과 다르다.
 */
public enum AppealStatus {
    PENDING, ACCEPTED, REJECTED, CLOSED
}
