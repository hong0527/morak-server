package com.morak.member.type;

// 관리자 API(/api/admin/**) 접근 분기. ADMIN은 DB 수동 UPDATE로만 부여한다.
public enum MemberRole {
    PARTICIPANT, ADMIN
}
