package com.morak.auth.client;

import java.time.LocalDate;

/**
 * 소셜 서비스에서 받아온 사용자 정보.
 *
 * @param providerUserId 그 소셜 서비스 안에서의 고유 번호
 * @param nickname       소셜 닉네임. 본인 확인용으로만 쓰고 타인에게 보이지 않는다
 * @param profileImageUrl 소셜 프로필 이미지. 같은 규칙
 * @param birthDate      생년월일. 주는 서비스도 있고 안 주는 서비스도 있어 없을 수 있다
 */
public record SocialUser(
        String providerUserId,
        String nickname,
        String profileImageUrl,
        LocalDate birthDate
) {
}
