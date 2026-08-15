package com.morak.member.controller;

import com.morak.common.dto.PageResponse;
import com.morak.member.dto.response.WithdrawalSummaryResponse;
import com.morak.member.service.MemberAdminService;
import com.morak.member.type.MemberStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * AD-8 탈퇴 처리 결과. 관리자 역할 검사는 전역 인터셉터가 {@code /api/admin/**} 전체에
 * 걸므로 여기에는 없다(명세 §0-2 ③).
 */
@RestController
@RequestMapping("/api/admin/withdrawals")
@RequiredArgsConstructor
public class MemberAdminController {

    private final MemberAdminService memberAdminService;

    @GetMapping
    public PageResponse<WithdrawalSummaryResponse> getWithdrawals(
            @RequestParam(required = false) MemberStatus status,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return memberAdminService.getWithdrawals(status, page, size);
    }
}
