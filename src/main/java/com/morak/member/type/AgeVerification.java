package com.morak.member.type;

// 만 14세 검증 상태. REQUIRED·UNDER_AGE는 매칭·인증 차단(전역 인터셉터 ⑤)
public enum AgeVerification {
    REQUIRED, VERIFIED, UNDER_AGE
}
