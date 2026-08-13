package com.morak.session.controller;

import com.morak.common.dto.PageResponse;
import com.morak.common.security.LoginMember;
import com.morak.session.dto.response.MyAppealResponse;
import com.morak.session.service.AppealService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * AP-2 내 이의 목록. 경로는 회원 아래지만 이의는 세션 도메인 소속이라(SS-9와 같은 배치)
 * 이의 서비스가 소유한다.
 */
@RestController
@RequestMapping("/api/members/me/appeals")
@RequiredArgsConstructor
public class MyAppealController {

    private final AppealService appealService;

    @GetMapping
    public PageResponse<MyAppealResponse> getMyAppeals(
            @LoginMember Long memberId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return appealService.getMyAppeals(memberId, page, size);
    }
}
