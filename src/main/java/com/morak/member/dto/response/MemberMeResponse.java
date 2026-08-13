package com.morak.member.dto.response;

import com.morak.common.type.BadgeCode;
import com.morak.member.entity.Member;
import com.morak.member.type.AgeVerification;
import com.morak.member.type.MemberRole;
import com.morak.member.type.MemberStatus;
import com.morak.report.type.SanctionType;
import com.morak.session.dto.response.ActiveSessionResponse;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

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
        ActiveSessionResponse activeSession,
        Sanction sanction,
        List<Badge> badges) {

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

    /**
     * 보유 뱃지. 별도 테이블 없이 ACHIEVED 목표 행에서 파생하며, 같은 코드는 첫 획득 시각으로
     * 한 번만 실린다 — "보유"의 답은 종류이지 횟수가 아니다. 없으면 빈 배열이다.
     */
    public record Badge(BadgeCode code, LocalDateTime earnedAt) {
    }

    public static MemberMeResponse from(Member member, LocalDate today, boolean mediaConsented,
                                        GoalResponse goal, ActiveSessionResponse activeSession,
                                        Sanction sanction, List<Badge> badges) {
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
                activeSession,
                sanction,
                badges);
    }
}
