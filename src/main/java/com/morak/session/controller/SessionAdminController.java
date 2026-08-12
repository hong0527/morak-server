package com.morak.session.controller;

import com.morak.common.dto.PageResponse;
import com.morak.session.dto.response.AdminSessionResponse;
import com.morak.session.service.SessionAdminService;
import com.morak.session.type.SessionStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * AD-7 진행 중 세션 모니터. 관리자 역할 검사는 전역 인터셉터가 {@code /api/admin/**}
 * 전체에 걸므로 여기에는 없다(명세 §0-2 ③).
 */
@RestController
@RequestMapping("/api/admin/sessions")
@RequiredArgsConstructor
public class SessionAdminController {

    private final SessionAdminService sessionAdminService;

    @GetMapping
    public PageResponse<AdminSessionResponse> getSessions(
            @RequestParam(required = false) SessionStatus status,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return sessionAdminService.getSessions(status, page, size);
    }
}
