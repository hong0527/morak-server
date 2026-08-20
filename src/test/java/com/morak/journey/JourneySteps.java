package com.morak.journey;

import com.morak.auth.dto.request.AgreementItem;
import com.morak.auth.dto.request.LoginRequest;
import com.morak.auth.dto.response.LoginResponse;
import com.morak.member.dto.request.BirthDateRequest;
import com.morak.member.dto.request.MediaConsentRequest;
import com.morak.member.type.AgreementType;
import com.morak.member.type.SocialProvider;
import com.morak.support.ApiClient;
import java.time.LocalDate;
import java.util.List;

/**
 * 여정마다 되풀이되는 앞부분 — 가입·연령 확인·캠 동의.
 *
 * <p><b>{@code TestFixtures}의 준비 메서드를 쓰지 않고 HTTP로 밟는다.</b> 픽스처는 컬럼을 직접
 * 써서 상태를 만드는데(연령 VERIFIED 등), 여정의 앞부분은 그 자체가 확인 대상이라 지름길로
 * 만들면 "이 순서로 실제 도달 가능한 상태인가"가 증명되지 않는다.
 *
 * <p>다만 이 앞부분을 <b>한 줄씩 확인하는 것은 여정 1의 일</b>이다. 나머지 여정은 여기까지를
 * 배경으로 두고 각자의 본론에서 시작한다 — 같은 단언을 네 번 반복하면 어느 여정이 무엇을
 * 지키는지 흐려진다.
 */
final class JourneySteps {

    private static final LocalDate ADULT_BIRTH_DATE = LocalDate.of(1998, 4, 11);

    private JourneySteps() {
    }

    /** 세션에 들어갈 수 있는 상태까지 밟은 회원. */
    static Traveler onboard(ApiClient api, String socialCode) {
        LoginResponse joined = api.post("/api/auth/login", null,
                new LoginRequest(SocialProvider.DEV, socialCode, List.of(
                        new AgreementItem(AgreementType.TOS, true),
                        new AgreementItem(AgreementType.PRIVACY, true))),
                LoginResponse.class);
        String token = joined.accessToken();
        api.post("/api/members/me/birthdate", token, new BirthDateRequest(ADULT_BIRTH_DATE),
                Object.class);
        api.post("/api/members/me/media-consent", token, new MediaConsentRequest(true));
        return new Traveler(joined.memberId(), token);
    }

    /** 가입만 한 상태(연령 미확인). 게이트가 걸린 자리를 확인하는 여정이 쓴다. */
    static Traveler join(ApiClient api, String socialCode) {
        LoginResponse joined = api.post("/api/auth/login", null,
                new LoginRequest(SocialProvider.DEV, socialCode, List.of(
                        new AgreementItem(AgreementType.TOS, true),
                        new AgreementItem(AgreementType.PRIVACY, true))),
                LoginResponse.class);
        return new Traveler(joined.memberId(), joined.accessToken());
    }

    /**
     * 여정을 걷는 사람. 토큰은 24시간이라 여정이 그보다 길어지면
     * {@code fixtures.tokenOf(memberId)}로 다시 받는다(실제 사용자도 재로그인한다).
     */
    record Traveler(Long memberId, String token) {
    }
}
