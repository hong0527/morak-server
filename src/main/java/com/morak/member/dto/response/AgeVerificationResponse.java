package com.morak.member.dto.response;

import com.morak.member.entity.Member;
import com.morak.member.type.AgeVerification;

/** AU-3 연령 검증 응답. 성공 응답은 VERIFIED뿐이다 — 미만 판정은 계정이 파기되고 403이 나간다. */
public record AgeVerificationResponse(AgeVerification ageVerification) {

    public static AgeVerificationResponse from(Member member) {
        return new AgeVerificationResponse(member.getAgeVerification());
    }
}
