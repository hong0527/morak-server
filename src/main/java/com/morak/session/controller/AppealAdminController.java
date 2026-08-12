package com.morak.session.controller;

import com.morak.common.dto.PageResponse;
import com.morak.common.security.LoginMember;
import com.morak.session.dto.request.AppealProcessRequest;
import com.morak.session.dto.response.AppealProcessResponse;
import com.morak.session.dto.response.AppealSummaryResponse;
import com.morak.session.service.AppealAdminService;
import com.morak.session.type.AppealStatus;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * AD-5·AD-6 이의 콘솔. 관리자 역할 검사는 전역 인터셉터가 {@code /api/admin/**} 전체에
 * 걸므로 여기에는 없다(명세 §0-2 ③).
 */
@RestController
@RequestMapping("/api/admin/appeals")
@RequiredArgsConstructor
public class AppealAdminController {

    private final AppealAdminService appealAdminService;

    @GetMapping
    public PageResponse<AppealSummaryResponse> getAppeals(
            @RequestParam(required = false) AppealStatus status,
            @RequestParam(required = false) Boolean overdue,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return appealAdminService.getAppeals(status, overdue, page, size);
    }

    @PatchMapping("/{appealId}")
    public AppealProcessResponse process(@LoginMember Long adminId,
                                         @PathVariable Long appealId,
                                         @Valid @RequestBody AppealProcessRequest request) {
        return appealAdminService.process(adminId, appealId, request);
    }
}
