package com.morak.session.controller;

import com.morak.common.security.LoginMember;
import com.morak.session.dto.request.AppealCreateRequest;
import com.morak.session.dto.response.AppealCreateResponse;
import com.morak.session.service.AppealService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** AP-1 퇴출 이의 신청. 이의는 세션 도메인 소속이다(퇴출이 세션에서 일어난다). */
@RestController
@RequestMapping("/api/evictions/{evictionId}/appeals")
@RequiredArgsConstructor
public class AppealController {

    private final AppealService appealService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AppealCreateResponse file(@LoginMember Long memberId,
                                     @PathVariable Long evictionId,
                                     @Valid @RequestBody AppealCreateRequest request) {
        return appealService.file(memberId, evictionId, request);
    }
}
