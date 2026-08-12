package com.morak.session.type;

// 진행 중에는 NULL. EARLY_UNDER_MIN은 잔여 인원이 min-participants 미만이라 조기 종료한 경우다(D12).
public enum SessionEndReason {
    NORMAL, EARLY_UNDER_MIN
}
