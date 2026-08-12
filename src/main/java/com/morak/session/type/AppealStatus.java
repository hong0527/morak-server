package com.morak.session.type;

// 퇴출 이의 처리 상태. ACCEPTED면 eviction.revoked_at을 채우고 차감 포인트를 원복한다.
public enum AppealStatus {
    PENDING, ACCEPTED, REJECTED
}
