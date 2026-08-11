package com.morak.member.type;

// 회원 생명주기. WITHDRAW_PENDING은 참여 API 차단, DELETED는 401.
public enum MemberStatus {
    ACTIVE, WITHDRAW_PENDING, DELETED
}
