package com.morak.member.type;

// 만 14세 검증 상태. UNDER_AGE가 없는 것은 의도다 — 미만 판정자는 계정 자체를 만들지 않으므로
// 그 상태의 회원이 존재할 수 없다(★D7). 인터셉터 ⑤는 != VERIFIED만 본다.
public enum AgeVerification {
    REQUIRED, VERIFIED
}
