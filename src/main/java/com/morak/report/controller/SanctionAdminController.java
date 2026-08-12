package com.morak.report.controller;

import com.morak.common.security.LoginMember;
import com.morak.report.dto.request.SanctionCreateRequest;
import com.morak.report.dto.response.SanctionCreateResponse;
import com.morak.report.service.ReportAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * AD-4 제재 단독 적용. 경로가 회원 밑({@code /api/admin/members/{id}/sanctions})이라
 * 신고 콘솔과 컨트롤러를 나눴을 뿐, 제재를 만드는 것은 report 도메인이므로 패키지는 같다.
 */
@RestController
@RequestMapping("/api/admin/members")
@RequiredArgsConstructor
public class SanctionAdminController {

    private final ReportAdminService reportAdminService;

    @PostMapping("/{memberId}/sanctions")
    @ResponseStatus(HttpStatus.CREATED)
    public SanctionCreateResponse sanction(@LoginMember Long adminId,
                                           @PathVariable Long memberId,
                                           @RequestBody SanctionCreateRequest request) {
        return reportAdminService.sanction(adminId, memberId, request);
    }
}
