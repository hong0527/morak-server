package com.morak.session.type;

// PERSONAL~ETC는 사용자 선택(SS-7 요청값). WITHDRAWAL·SANCTION은 서버 전용이라 요청으로 받지 않는다.
// EVICTED는 여기 없다 — 퇴출은 ParticipantStatus로 표현한다.
public enum LeftReason {
    PERSONAL, DEVICE_ISSUE, UNPLEASANT, ETC, WITHDRAWAL, SANCTION
}
