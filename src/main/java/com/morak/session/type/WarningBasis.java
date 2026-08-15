package com.morak.session.type;

/**
 * 경고의 근거 종류. 저장 컬럼이 아니라 AD-9 응답의 파생값이다 — {@code warning.absence_event_id}
 * 유무가 이미 이 구분을 담고 있어(NULL이면 Pause 초과, D9) 컬럼을 더하면 같은 사실이 두 곳이 된다.
 */
public enum WarningBasis {
    ABSENCE, PAUSE_OVERRUN
}
