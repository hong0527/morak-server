package com.morak.session.type;

// 완주 판정은 세션 종료 시각의 상태가 ACTIVE·PAUSED인지로 갈린다(★D1).
// EVICTED는 사유가 아니라 상태다 — LeftReason에 두면 "자율 퇴장인데 사유가 퇴출"인 행이 생긴다.
public enum ParticipantStatus {
    ACTIVE, PAUSED, LEFT, EVICTED
}
