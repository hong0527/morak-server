package com.morak.member.controller;

import com.morak.common.security.LoginMember;
import com.morak.member.dto.request.BirthDateRequest;
import com.morak.member.dto.request.GoalRequest;
import com.morak.member.dto.request.MediaConsentRequest;
import com.morak.member.dto.response.AgeVerificationResponse;
import com.morak.member.dto.response.GoalResponse;
import com.morak.member.dto.response.MemberMeResponse;
import com.morak.member.dto.response.WithdrawalResponse;
import com.morak.member.service.MemberService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/members/me")
@RequiredArgsConstructor
public class MemberController {

    private final MemberService memberService;

    @GetMapping
    public MemberMeResponse getMe(@LoginMember Long memberId) {
        return memberService.getMe(memberId);
    }

    @PostMapping("/birthdate")
    public AgeVerificationResponse verifyAge(@LoginMember Long memberId,
                                             @Valid @RequestBody BirthDateRequest request) {
        return memberService.verifyAge(memberId, request);
    }

    /** AU-6. 동의 문구는 클라이언트가 고정 문구로 보여주고, 서버는 동의 사실만 남긴다. */
    @PostMapping("/media-consent")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void agreeMediaConsent(@LoginMember Long memberId,
                                  @Valid @RequestBody MediaConsentRequest request) {
        memberService.agreeMediaConsent(memberId, request);
    }

    @PutMapping("/goal")
    public GoalResponse setGoal(@LoginMember Long memberId,
                                @Valid @RequestBody GoalRequest request) {
        return memberService.setGoal(memberId, request);
    }

    @PostMapping("/withdrawal")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public WithdrawalResponse withdraw(@LoginMember Long memberId) {
        return memberService.requestWithdrawal(memberId);
    }

    @DeleteMapping("/withdrawal")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancelWithdrawal(@LoginMember Long memberId) {
        memberService.cancelWithdrawal(memberId);
    }
}
