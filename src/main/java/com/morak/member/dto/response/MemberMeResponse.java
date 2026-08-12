package com.morak.member.dto.response;

import com.morak.member.entity.Member;
import com.morak.member.type.AgeVerification;
import com.morak.member.type.MemberRole;
import com.morak.member.type.MemberStatus;
import java.time.LocalDateTime;

/**
 * AU-2 내 정보 응답. openapi.yaml MemberMeResponse 스키마와 1:1 대응.
 *
 * <p>Member의 SNS 원본 값(providerUserId·snsNickname·snsProfileImageUrl·birthDate)은
 * 명세에 없으므로 본인 조회라도 내보내지 않는다.
 */
public record MemberMeResponse(
        Long memberId,
        String nickname,
        MemberRole role,
        AgeVerification ageVerification,
        MemberStatus memberStatus,
        boolean mediaConsented,
        Sanction sanction) {

    /** 제재 정보. 제재가 없으면 sanction 자체가 null이다. endsAt은 영구 제재면 null. */
    public record Sanction(boolean active, LocalDateTime endsAt) {
    }

    public static MemberMeResponse from(Member member, boolean mediaConsented, Sanction sanction) {
        return new MemberMeResponse(
                member.getId(),
                member.getNickname(),
                member.getRole(),
                member.getAgeVerification(),
                member.getStatus(),
                mediaConsented,
                sanction);
    }
}
