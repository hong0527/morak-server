package com.morak.member.dto.response;

import com.morak.member.entity.Member;
import java.time.LocalDateTime;

/** AU-4 탈퇴 신청 응답. deleteScheduledAt이 지나면 B4 배치가 계정을 익명화한다. */
public record WithdrawalResponse(LocalDateTime deleteScheduledAt) {

    public static WithdrawalResponse from(Member member) {
        return new WithdrawalResponse(member.getDeleteScheduledAt());
    }
}
