package com.morak.auth.dto.request;

import com.morak.member.type.SocialProvider;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record LoginRequest(
        @NotNull SocialProvider provider,
        @NotBlank String authorizationCode
) {
}
