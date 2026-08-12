package com.morak.match.type;

// 핵심 지표 원천: 매칭 완료율·대기 이탈률·30일 재참여율
// 공석 충원 입장(구 REJOIN_ENTRY)은 v1 보류라 이벤트가 발생할 경로 자체가 없다(FR-306).
public enum MatchEventType {
    MATCH_COMPLETED, WAIT_CANCELLED, WAIT_EXPIRED
}
