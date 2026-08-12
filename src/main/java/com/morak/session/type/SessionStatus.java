package com.morak.session.type;

// CANCELLED는 매칭 직후 세션이 성립하지 못한 경우다. 정상·조기 종료는 둘 다 ENDED이고
// 어느 쪽인지는 SessionEndReason이 구분한다.
public enum SessionStatus {
    LIVE, ENDED, CANCELLED
}
