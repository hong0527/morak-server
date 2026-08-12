package com.morak.auth.client;

import com.morak.member.type.SocialProvider;

/**
 * 소셜 서비스에서 사용자 정보를 받아온다.
 *
 * <p>구현이 둘이라 인터페이스를 둔다. 개발 중에는 앱 키가 없어도 로그인이 되어야
 * 뒤 단계를 만들 수 있고, 운영에서는 실제 소셜 서버를 호출해야 한다.
 * 프로필로 갈아끼운다.
 */
public interface SocialClient {

    /**
     * @param provider 어느 소셜 서비스인지
     * @param authorizationCode 소셜 로그인 후 앱이 받은 인가 코드
     * @throws com.morak.common.error.BusinessException 검증 실패 시 INVALID_SOCIAL_TOKEN
     */
    SocialUser fetch(SocialProvider provider, String authorizationCode);
}
