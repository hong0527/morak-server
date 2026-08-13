package com.morak.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.morak.auth.client.SocialClient;
import com.morak.auth.client.SocialUser;
import com.morak.auth.dto.request.AgreementItem;
import com.morak.auth.dto.request.LoginRequest;
import com.morak.auth.dto.response.LoginResponse;
import com.morak.auth.service.AuthService;
import com.morak.member.entity.Member;
import com.morak.member.repository.MemberRepository;
import com.morak.member.type.AgeVerification;
import com.morak.member.type.AgreementType;
import com.morak.member.type.SocialProvider;
import com.morak.support.IntegrationTest;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 소셜이 생년월일을 준 가입에서 연령 확인이 DB까지 닿는지 본다(AU-1).
 *
 * <p><b>이 경로는 웰컴 포인트 지급을 사이에 두고 있다.</b> 지급은 잔액 캐시를 벌크 UPDATE로
 * 갱신하며 영속성 컨텍스트를 비우므로, 그 뒤에 회원 엔티티를 만지면 준영속 인스턴스에 쓰는
 * 셈이 되어 flush되지 않는다. 확인은 반드시 커밋된 행에서 해야 한다 — 같은 트랜잭션 안에서
 * 엔티티를 다시 읽어 보는 방식이면 사라진 변경도 그대로 보인다.
 *
 * <p>개발용 소셜 클라이언트는 생년월일을 주지 않으므로 여기서만 {@link SocialClient}를
 * 대역으로 바꾼다. 실제 카카오 구현이 들어오는 12단계에도 이 시험은 그대로 유효하다.
 */
@DisplayName("소셜 생년월일 가입")
class SocialBirthDateJoinTest extends IntegrationTest {

    private static final String PROVIDER_USER_ID = "kakao-with-birthdate";
    private static final int ADULT_YEARS = 30;

    private static final List<AgreementItem> MANDATORY_AGREEMENTS = List.of(
            new AgreementItem(AgreementType.TOS, true),
            new AgreementItem(AgreementType.PRIVACY, true));

    @MockitoBean
    private SocialClient socialClient;

    @Autowired
    private AuthService authService;

    @Autowired
    private MemberRepository memberRepository;

    @Value("${morak.point.welcome}")
    private int welcomePoint;

    @Test
    @DisplayName("소셜이 준 생년월일은 웰컴 지급 뒤에도 VERIFIED로 DB에 남는다")
    void 소셜_생년월일은_가입_트랜잭션에서_확정된다() {
        // 이 테스트가 죽으면: 연령 확정이 지급 뒤 준영속 인스턴스에 찍혀 사라진 것이다.
        // 사용자는 이미 나이를 준 채로 가입했는데 AU-3 생년월일 입력 화면에 다시 갇힌다.
        clock.fixAt(BASE_TIME);
        LocalDate birthDate = LocalDate.now(clock).minusYears(ADULT_YEARS);
        given(socialClient.fetch(any(), any()))
                .willReturn(new SocialUser(PROVIDER_USER_ID, "소셜닉네임", null, birthDate));

        LoginResponse response = authService.login(new LoginRequest(
                SocialProvider.KAKAO, "any-code", MANDATORY_AGREEMENTS));

        assertThat(response.isNewMember()).isTrue();
        assertThat(response.needsBirthdate()).isFalse();
        assertThat(response.ageVerification()).isEqualTo(AgeVerification.VERIFIED);
        // 응답이 아니라 커밋된 행이 판정 기준이다
        Member saved = memberRepository
                .findByProviderAndProviderUserId(SocialProvider.KAKAO, PROVIDER_USER_ID)
                .orElseThrow();
        assertThat(saved.getAgeVerification()).isEqualTo(AgeVerification.VERIFIED);
        assertThat(saved.getBirthDate()).isEqualTo(birthDate);
        assertThat(fixtures.count("member", "id = ? AND age_verification = 'VERIFIED'",
                saved.getId())).isEqualTo(1);
        // 지급이 함께 성립했는지도 본다 — 연령만 남고 웰컴이 빠지면 반대 방향의 같은 사고다
        assertThat(fixtures.count("point_ledger", "member_id = ? AND reason = 'WELCOME'",
                saved.getId())).isEqualTo(1);
        assertThat(saved.getPointBalance())
                .isEqualTo(welcomePoint)
                .isEqualTo(fixtures.ledgerSum(saved.getId()));
    }

    @Test
    @DisplayName("생년월일을 주지 않는 소셜은 REQUIRED로 남아 AU-3으로 간다")
    void 생년월일이_없으면_확정하지_않는다() {
        // 이 테스트가 죽으면: 없는 생년월일로 VERIFIED를 찍은 것이다. 연령 확인이 형식만
        // 남고 만 14세 미만 차단이 통째로 무의미해진다.
        clock.fixAt(BASE_TIME);
        given(socialClient.fetch(any(), any()))
                .willReturn(new SocialUser("kakao-no-birthdate", "소셜닉네임", null, null));

        LoginResponse response = authService.login(new LoginRequest(
                SocialProvider.KAKAO, "any-code", MANDATORY_AGREEMENTS));

        assertThat(response.needsBirthdate()).isTrue();
        assertThat(memberRepository.findById(response.memberId()).orElseThrow()
                .getAgeVerification()).isEqualTo(AgeVerification.REQUIRED);
    }
}
