package com.morak.match.type;

// 모든 이탈(MATCHED/CANCELLED/EXPIRED)에서 active_member_id를 NULL로 해제해야 한다.
public enum MatchRequestStatus {
    WAITING, MATCHED, CANCELLED, EXPIRED
}
