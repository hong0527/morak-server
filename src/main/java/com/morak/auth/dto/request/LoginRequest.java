package com.morak.auth.dto.request;

import com.morak.member.type.SocialProvider;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record LoginRequest(
        @NotNull SocialProvider provider,
        @NotBlank String authorizationCode,
        // 별도 가입 폼이 없어 약관 동의가 이 요청에 함께 실린다. 기존 회원 로그인은 내용을
        // 쓰지 않지만 필드 자체는 계약상 필수라 빈 배열이라도 보내야 한다.
        @NotNull @Valid List<AgreementItem> agreements
) {
}
