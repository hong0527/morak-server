package com.morak.member.type;

// TOS·PRIVACY는 필수, MARKETING은 선택. member에 boolean으로 두지 않는 이유는
// 개인정보 분쟁에서 필요한 것이 현재 상태가 아니라 동의 시점이기 때문이다.
public enum AgreementType {
    TOS, PRIVACY, MARKETING
}
