package com.morak.member.dto.response;

import com.morak.member.entity.Member;
import com.morak.member.type.AgeVerification;
import com.morak.member.type.MemberRole;
import com.morak.member.type.MemberStatus;
import com.morak.report.type.SanctionType;
import java.time.LocalDate;
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
        int pointBalance,
        GoalResponse goal,
        Streak streak,
        Sanction sanction) {

    /**
     * 연속 완주일. 한 번도 완주하지 않았으면 current=0, lastCompletedOn=null.
     *
     * <p>{@code current}는 캐시값을 그대로 내리지 않고 조회 시점 기준으로 판정한 값이다
     * ({@link Member#currentStreakOn}) — 연속이 끊긴 날 캐시를 0으로 쓰는 배치가 없어서,
     * 그대로 내리면 며칠 전에 끊긴 회원이 계속 옛 기록을 본다.
     */
    public record Streak(int current, LocalDate lastCompletedOn) {
    }

    /** 제재 정보. 제재가 없으면 sanction 자체가 null이다. endsAt은 영구 제재면 null. */
    public record Sanction(SanctionType type, LocalDateTime endsAt) {
    }

    public static MemberMeResponse from(Member member, LocalDate today, boolean mediaConsented,
                                        GoalResponse goal, Sanction sanction) {
        return new MemberMeResponse(
                member.getId(),
                member.getNickname(),
                member.getRole(),
                member.getAgeVerification(),
                member.getStatus(),
                mediaConsented,
                member.getPointBalance(),
                goal,
                new Streak(member.currentStreakOn(today), member.getLastCompletedOn()),
                sanction);
    }
}
