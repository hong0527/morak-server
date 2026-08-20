package com.morak.auth.client;

import com.morak.common.error.BusinessException;
import com.morak.common.error.ErrorCode;
import com.morak.member.type.SocialProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * dev가 아닌 프로필의 기본 소셜 클라이언트. 어떤 인가 코드도 받아들이지 않고 401
 * {@code INVALID_SOCIAL_TOKEN}으로 끊는다(api-spec AU-1의 검증 실패 문면 그대로).
 *
 * <p><b>구현이 없어 기동조차 못 하는 상태를 대신하는 자리다.</b> {@link DevSocialClient}는
 * dev 프로필에만 있어서, 이 빈이 없으면 운영 프로필은 {@code SocialClient} 주입 실패로 뜨지
 * 않는다. 그러면 배포 파이프라인·헬스체크·나머지 API를 실서버에서 한 줄도 확인할 수 없다.
 *
 * <p>거절을 기본값으로 두는 것이 핵심이다. 통과시키는 스텁을 운영에 두면 인증 없는 로그인이
 * 열린다. 실제 카카오 구현은 12단계에서 이 빈을 대체한다 — 그때까지 운영 프로필의 AU-1은
 * "항상 401"이 정상 동작이다.
 */
@Component
@Profile("!dev & !demo")
public class RejectingSocialClient implements SocialClient {

    private static final Logger log = LoggerFactory.getLogger(RejectingSocialClient.class);

    @Override
    public SocialUser fetch(SocialProvider provider, String authorizationCode) {
        log.warn("소셜 로그인 구현이 아직 없어 거절한다: provider={}", provider);
        throw new BusinessException(ErrorCode.INVALID_SOCIAL_TOKEN);
    }
}
