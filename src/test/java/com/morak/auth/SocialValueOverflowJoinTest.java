package com.morak.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.morak.auth.client.SocialClient;
import com.morak.auth.client.SocialUser;
import com.morak.auth.dto.request.AgreementItem;
import com.morak.auth.dto.request.LoginRequest;
import com.morak.auth.service.AuthService;
import com.morak.common.error.BusinessException;
import com.morak.common.error.ErrorCode;
import com.morak.member.entity.Member;
import com.morak.member.repository.MemberRepository;
import com.morak.member.type.AgreementType;
import com.morak.member.type.SocialProvider;
import com.morak.support.IntegrationTest;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 소셜이 컬럼 길이를 넘는 값을 줬을 때 가입이 어떻게 되는지 본다(AU-1).
 *
 * <p>소셜 프로필의 길이는 우리가 정할 수 없다. 검증 없이 저장하면 INSERT가 제약에 걸려
 * 가입 전체가 500으로 죽는다 — 실측에서 60자 닉네임 하나로 가입이 실패했다.
 *
 * <p>처방은 값의 성격에 따라 갈린다. 닉네임은 본인 확인용 보조 정보라 잘라도 서비스가
 * 성립하고, URL은 잘리면 열리지 않는 주소라 버리는 쪽이 정직하다. <b>식별자만은 둘 다
 * 안 된다</b> — 잘라 저장하면 앞부분이 같은 다른 계정과 한 행으로 합쳐져 남의 계정으로
 * 로그인되므로 거절한다.
 */
@DisplayName("소셜 값 길이 초과 가입")
class SocialValueOverflowJoinTest extends IntegrationTest {

    private static final int NICKNAME_MAX = 50;

    private static final List<AgreementItem> MANDATORY_AGREEMENTS = List.of(
            new AgreementItem(AgreementType.TOS, true),
            new AgreementItem(AgreementType.PRIVACY, true));

    @MockitoBean
    private SocialClient socialClient;

    @Autowired
    private AuthService authService;

    @Autowired
    private MemberRepository memberRepository;

    @Test
    @DisplayName("긴 소셜 닉네임은 50자로 잘려 저장되고 가입은 성공한다")
    void 긴_닉네임은_잘리고_가입은_성공한다() {
        // 이 테스트가 죽으면: 닉네임 길이 하나로 가입 INSERT가 터져 실사용자가 가입 자체를
        // 못 하는 상태로 돌아간 것이다.
        clock.fixAt(BASE_TIME);
        String longNickname = "가".repeat(NICKNAME_MAX + 10);
        given(socialClient.fetch(any(), any()))
                .willReturn(new SocialUser("kakao-long-nickname", longNickname, null, null));

        authService.login(new LoginRequest(SocialProvider.KAKAO, "any", MANDATORY_AGREEMENTS));

        Member saved = memberRepository
                .findByProviderAndProviderUserId(SocialProvider.KAKAO, "kakao-long-nickname")
                .orElseThrow();
        assertThat(saved.getSnsNickname()).isEqualTo("가".repeat(NICKNAME_MAX));
    }

    @Test
    @DisplayName("절단 경계가 이모지 가운데면 그 문자를 통째로 버려 깨진 문자를 남기지 않는다")
    void 서로게이트_쌍_가운데는_자르지_않는다() {
        // 이 테스트가 죽으면: 상한 자리에서 서로게이트 반쪽이 잘려 나가, 유효하지 않은
        // 문자열이 저장되고 직렬화하는 자리마다 깨진 문자가 나간다.
        clock.fixAt(BASE_TIME);
        // 49자 + 이모지(UTF-16 2단위) = 51단위. 50에서 자르면 이모지의 앞 반쪽에서 끊긴다.
        String nickname = "가".repeat(NICKNAME_MAX - 1) + "🔥";
        given(socialClient.fetch(any(), any()))
                .willReturn(new SocialUser("kakao-emoji-nickname", nickname, null, null));

        authService.login(new LoginRequest(SocialProvider.KAKAO, "any", MANDATORY_AGREEMENTS));

        Member saved = memberRepository
                .findByProviderAndProviderUserId(SocialProvider.KAKAO, "kakao-emoji-nickname")
                .orElseThrow();
        assertThat(saved.getSnsNickname()).isEqualTo("가".repeat(NICKNAME_MAX - 1));
    }

    @Test
    @DisplayName("500자를 넘는 프로필 URL은 잘린 채 남지 않고 버려진다")
    void 긴_프로필_URL은_버려진다() {
        // 이 테스트가 죽으면: 열리지 않는 반토막 URL이 멀쩡한 값처럼 저장돼 쓰는 쪽을 속이거나,
        // 길이 초과로 가입이 다시 500으로 죽는 것이다.
        clock.fixAt(BASE_TIME);
        String longUrl = "https://img.example.com/" + "a".repeat(500);
        given(socialClient.fetch(any(), any()))
                .willReturn(new SocialUser("kakao-long-url", "닉네임", longUrl, null));

        authService.login(new LoginRequest(SocialProvider.KAKAO, "any", MANDATORY_AGREEMENTS));

        Member saved = memberRepository
                .findByProviderAndProviderUserId(SocialProvider.KAKAO, "kakao-long-url")
                .orElseThrow();
        assertThat(saved.getSnsProfileImageUrl()).isNull();
    }

    @Test
    @DisplayName("191자를 넘는 소셜 식별자는 절단하지 않고 인증 실패로 거절한다")
    void 긴_식별자는_거절되고_계정이_생기지_않는다() {
        // 이 테스트가 죽으면: 식별자가 잘려 저장되는 것이다. 앞 191자가 같은 두 소셜 계정이
        // 한 회원으로 합쳐져 서로의 계정으로 로그인된다.
        clock.fixAt(BASE_TIME);
        String longId = "u".repeat(Member.PROVIDER_USER_ID_MAX_LENGTH + 1);
        given(socialClient.fetch(any(), any()))
                .willReturn(new SocialUser(longId, "닉네임", null, null));

        assertThatThrownBy(() -> authService.login(
                new LoginRequest(SocialProvider.KAKAO, "any", MANDATORY_AGREEMENTS)))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_SOCIAL_TOKEN);

        assertThat(fixtures.countAll("member")).isZero();
    }
}
