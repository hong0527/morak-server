package com.morak.member.dto.response;

import com.morak.member.entity.Member;
import com.morak.member.type.MemberStatus;
import java.time.LocalDateTime;

/**
 * AD-8 탈퇴 처리 결과 항목 (NFR-202).
 *
 * <p>닉네임을 싣지 않는다. 파기된 계정은 닉네임이 이미 '탈퇴회원'으로 덮여 있어 식별에
 * 쓸 수 없고, 유예 중인 계정의 닉네임을 여기에 실으면 파기 전후로 응답이 갈린다. 이 화면이
 * 답해야 하는 것은 "누구인가"가 아니라 <b>예정대로 파기됐는가</b>이다.
 */
public record WithdrawalSummaryResponse(
        Long memberId,
        LocalDateTime requestedAt,
        LocalDateTime deleteScheduledAt,
        LocalDateTime deletedAt,
        MemberStatus status) {

    public static WithdrawalSummaryResponse from(Member member) {
        return new WithdrawalSummaryResponse(
                member.getId(),
                member.getWithdrawRequestedAt(),
                member.getDeleteScheduledAt(),
                member.getDeletedAt(),
                member.getStatus());
    }
}
