package com.morak.match.type;

// 핵심 지표 원천: 매칭 완료율·대기 이탈률·30일 재참여율
public enum MatchEventType {
    MATCH_COMPLETED, REJOIN_ENTRY, WAIT_CANCELLED, WAIT_EXPIRED
}
