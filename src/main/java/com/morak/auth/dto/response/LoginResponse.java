package com.morak.auth.dto.response;

import com.morak.member.entity.Member;
import com.morak.member.type.AgeVerification;

public record LoginResponse(
        String accessToken,
        boolean isNewMember,
        AgeVerification ageVerification,
        String loginResult
) {

    public static LoginResponse from(String accessToken, boolean isNewMember,
                                     Member member, String loginResult) {
        return new LoginResponse(accessToken, isNewMember, member.getAgeVerification(), loginResult);
    }
}
