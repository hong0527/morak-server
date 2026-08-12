package com.morak.member.dto.response;

import com.morak.member.entity.Member;
import com.morak.member.type.AgeVerification;

/** AU-3 연령 검증 응답. VERIFIED 또는 UNDER_AGE만 내려간다. */
public record AgeVerificationResponse(AgeVerification ageVerification) {

    public static AgeVerificationResponse from(Member member) {
        return new AgeVerificationResponse(member.getAgeVerification());
    }
}
