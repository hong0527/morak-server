package com.morak.match.controller;

import com.morak.common.security.LoginMember;
import com.morak.match.dto.request.MatchRequestCreateRequest;
import com.morak.match.dto.response.MatchRequestResponse;
import com.morak.match.service.MatchService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/match-requests")
@RequiredArgsConstructor
public class MatchController {

    private final MatchService matchService;

    /** MT-1. 대기든 성사든 요청 자체는 생성됐으므로 둘 다 201이다. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MatchRequestResponse request(@LoginMember Long memberId,
                                        @Valid @RequestBody MatchRequestCreateRequest request) {
        return matchService.request(memberId, request);
    }

    @GetMapping("/me")
    public MatchRequestResponse getMine(@LoginMember Long memberId) {
        return matchService.getMine(memberId);
    }

    @DeleteMapping("/{matchRequestId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(@LoginMember Long memberId, @PathVariable Long matchRequestId) {
        matchService.cancel(memberId, matchRequestId);
    }
}
