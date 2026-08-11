package com.morak.ai.type;

// 관리자 검토 큐 유형. CENSORSHIP이 없으면 자동 차단 건의 관리자 도달 경로가 0이 된다.
public enum AiReviewType {
    AUTHENTICITY, CENSORSHIP, COMPLETION
}
