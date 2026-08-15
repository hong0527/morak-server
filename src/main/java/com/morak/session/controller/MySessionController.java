package com.morak.session.controller;

import com.morak.common.dto.PageResponse;
import com.morak.common.security.LoginMember;
import com.morak.session.dto.response.MySessionSummaryResponse;
import com.morak.session.service.SessionService;
import com.morak.session.type.SessionStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * SS-9 내 세션 이력. 경로는 회원 아래지만 내려주는 것은 세션 도메인의 데이터라
 * 세션 서비스가 소유한다.
 */
@RestController
@RequestMapping("/api/members/me/sessions")
@RequiredArgsConstructor
public class MySessionController {

    private final SessionService sessionService;

    @GetMapping
    public PageResponse<MySessionSummaryResponse> getMySessions(
            @LoginMember Long memberId,
            @RequestParam(required = false) SessionStatus status,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return sessionService.getMySessions(memberId, status, page, size);
    }
}
