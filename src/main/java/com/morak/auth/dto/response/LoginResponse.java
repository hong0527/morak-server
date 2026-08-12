package com.morak.auth.dto.response;

import com.morak.member.entity.Member;
import com.morak.member.type.AgeVerification;

public record LoginResponse(
        String accessToken,
        Long memberId,
        boolean isNewMember,
        // 생년월일 입력 화면(AU-3)으로 보낼지의 판단을 클라이언트가 enum 해석으로 하지 않도록
        // 서버가 계산해 내려준다
        boolean needsBirthdate,
        AgeVerification ageVerification,
        String loginResult
) {

    public static LoginResponse from(String accessToken, boolean isNewMember,
                                     Member member, String loginResult) {
        AgeVerification ageVerification = member.getAgeVerification();
        return new LoginResponse(
                accessToken,
                member.getId(),
                isNewMember,
                ageVerification == AgeVerification.REQUIRED,
                ageVerification,
                loginResult);
    }
}
