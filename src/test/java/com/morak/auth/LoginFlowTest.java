package com.morak.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.morak.auth.dto.request.AgreementItem;
import com.morak.auth.dto.request.LoginRequest;
import com.morak.auth.dto.response.LoginResponse;
import com.morak.auth.service.AuthService;
import com.morak.common.error.BusinessException;
import com.morak.common.error.ErrorCode;
import com.morak.member.service.MemberPurgeBatch;
import com.morak.member.service.MemberService;
import com.morak.member.type.AgeVerification;
import com.morak.member.type.AgreementType;
import com.morak.member.type.MemberStatus;
import com.morak.member.type.SocialProvider;
import com.morak.report.dto.request.SanctionCommand;
import com.morak.report.service.SanctionService;
import com.morak.report.type.SanctionType;
import com.morak.support.IntegrationTest;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

/**
 * AU-1 로그인·가입의 정상 경로.
 *
 * <p>기존 테스트는 이 API의 <b>가장자리만</b> 덮고 있었다 — 소셜이 준 생년월일 처리, 값 길이
 * 초과, 미만 판정. 정작 "처음 들어온 사람이 계정을 얻고, 다시 오면 같은 계정으로 들어오고,
 * 탈퇴를 무르면 되살아나는가"는 아무 데서도 확인하지 않았다. 이 파일이 그 자리다.
 *
 * <p>가입은 한 트랜잭션에서 네 가지를 함께 한다 — 회원 행, 매칭 잠금 행, 동의 행, 웰컴 포인트.
 * 하나라도 빠지면 그 계정은 나중에 다른 API에서 500으로 터진다(잠금 행이 없으면 매칭도 목표
 * 설정도 못 한다). 그래서 응답만 보지 않고 네 자리를 전부 센다.
 */
@DisplayName("소셜 로그인·가입 흐름")
class LoginFlowTest extends IntegrationTest {

    private static final Long ADMIN_ACTOR = 1L;

    @Autowired
    private AuthService authService;

    @Autowired
    private MemberService memberService;

    @Autowired
    private SanctionService sanctionService;

    @Autowired
    private MemberPurgeBatch memberPurgeBatch;

    @Value("${morak.point.welcome}")
    private int welcomePoint;

    @Value("${morak.withdrawal.grace-days}")
    private int graceDays;

    @Test
    @DisplayName("처음 온 계정은 회원·잠금·동의·웰컴 포인트를 한 번에 얻는다")
    void 신규_가입이_네_자리를_모두_남긴다() {
        // 이 테스트가 죽으면: 가입이 반쪽만 성립한다. 잠금 행이 빠지면 그 계정은 매칭(MT-1)과
        // 목표 설정(AU-7)에서 500으로 터지고, 웰컴 지급이 빠지면 FR-103이 사라진다.
        LoginResponse response = login("flow-new", mandatory());

        assertThat(response.isNewMember()).isTrue();
        assertThat(response.loginResult()).isEqualTo("NORMAL");
        assertThat(response.accessToken()).isNotBlank();
        // 개발용 소셜은 생년월일을 주지 않으므로 AU-3으로 보내야 한다
        assertThat(response.ageVerification()).isEqualTo(AgeVerification.REQUIRED);
        assertThat(response.needsBirthdate()).isTrue();

        Long memberId = response.memberId();
        assertThat(fixtures.member(memberId).getStatus()).isEqualTo(MemberStatus.ACTIVE);
        assertThat(fixtures.count("match_lock", "lock_key = ?", "member:" + memberId))
                .isEqualTo(1);
        assertThat(fixtures.count("member_agreement", "member_id = ?", memberId)).isEqualTo(2);
        assertThat(fixtures.ledgerSum(memberId)).isEqualTo(welcomePoint);
        assertThat(fixtures.count("point_ledger", "member_id = ? AND reason = 'WELCOME'",
                memberId)).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 계정으로 다시 로그인하면 회원도 웰컴 포인트도 늘지 않는다")
    void 재로그인은_계정을_늘리지_않는다() {
        // 이 테스트가 죽으면: 로그인할 때마다 웰컴 포인트가 다시 나가거나 계정이 새로 생긴다.
        // 앞은 포인트 무한 발급이고, 뒤는 로그인할 때마다 이력이 사라지는 것으로 보인다.
        LoginResponse first = login("flow-repeat", mandatory());
        LoginResponse second = login("flow-repeat", mandatory());

        assertThat(second.isNewMember()).isFalse();
        assertThat(second.memberId()).isEqualTo(first.memberId());
        assertThat(second.loginResult()).isEqualTo("NORMAL");
        assertThat(fixtures.countAll("member")).isEqualTo(1);
        assertThat(fixtures.ledgerSum(first.memberId())).isEqualTo(welcomePoint);
    }

    @Test
    @DisplayName("탈퇴 유예 중 로그인하면 계정이 되살아나고 결과가 RESTORED다")
    void 유예_중_로그인이_계정을_복구한다() {
        // 이 테스트가 죽으면: 마음이 바뀐 사용자가 돌아올 길이 로그인 화면에서 막힌다.
        // loginResult가 NORMAL로 나가면 프론트가 "복구됐다"는 안내를 띄우지 못한다.
        LoginResponse joined = login("flow-restore", mandatory());
        memberService.requestWithdrawal(joined.memberId());
        assertThat(fixtures.member(joined.memberId()).getStatus())
                .isEqualTo(MemberStatus.WITHDRAW_PENDING);

        LoginResponse restored = login("flow-restore", mandatory());

        assertThat(restored.loginResult()).isEqualTo("RESTORED");
        assertThat(restored.isNewMember()).isFalse();
        assertThat(restored.memberId()).isEqualTo(joined.memberId());
        assertThat(fixtures.member(joined.memberId()).getStatus()).isEqualTo(MemberStatus.ACTIVE);
    }

    @Test
    @DisplayName("삭제 예정 시각이 지난 계정은 배치 전이라도 로그인으로 되살릴 수 없다")
    void 삭제_예정이_지나면_복구되지_않는다() {
        // 이 테스트가 죽으면: B4가 아직 돌지 않은 30일 경과 계정이 로그인 한 번으로 부활한다.
        // 파기 예정이 배치 실행 시각에 좌우되면 "언제 지워지는가"가 계약이 아니게 된다.
        LoginResponse joined = login("flow-too-late", mandatory());
        memberService.requestWithdrawal(joined.memberId());

        clock.fixAt(now().plusDays(graceDays).plusMinutes(1));

        assertThatThrownBy(() -> login("flow-too-late", mandatory()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.UNAUTHORIZED);
        assertThat(fixtures.member(joined.memberId()).getStatus())
                .isEqualTo(MemberStatus.WITHDRAW_PENDING);
    }

    @Test
    @DisplayName("필수 약관에 동의하지 않으면 계정이 만들어지지 않는다")
    void 필수_약관_미동의는_가입을_남기지_않는다() {
        // 이 테스트가 죽으면: 약관 동의 없이 계정이 생긴다. 400만 내려주고 회원 행이 남으면
        // 그 계정은 동의 기록이 없는 채로 영원히 남는다(D20 — 행의 유무가 곧 동의 여부다).
        List<AgreementItem> privacyOnly =
                List.of(new AgreementItem(AgreementType.PRIVACY, true));

        assertThatThrownBy(() -> login("flow-no-tos", privacyOnly))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.AGREEMENT_REQUIRED);

        assertThat(fixtures.countAll("member")).isZero();
        assertThat(fixtures.countAll("member_agreement")).isZero();
    }

    @Test
    @DisplayName("동의하지 않은 선택 약관은 행 자체를 남기지 않는다")
    void 선택_약관은_동의한_것만_행이_된다() {
        // 이 테스트가 죽으면: 미동의 마케팅이 false 행으로 저장돼 "동의함"으로 읽히거나,
        // 동의한 마케팅이 저장되지 않아 발송 근거가 사라진다. 둘 다 법적 근거의 문제다.
        LoginResponse declined = login("flow-marketing-no", List.of(
                new AgreementItem(AgreementType.TOS, true),
                new AgreementItem(AgreementType.PRIVACY, true),
                new AgreementItem(AgreementType.MARKETING, false)));
        assertThat(fixtures.count("member_agreement", "member_id = ?", declined.memberId()))
                .isEqualTo(2);
        assertThat(fixtures.count("member_agreement",
                "member_id = ? AND type = 'MARKETING'", declined.memberId())).isZero();

        LoginResponse agreed = login("flow-marketing-yes", List.of(
                new AgreementItem(AgreementType.TOS, true),
                new AgreementItem(AgreementType.PRIVACY, true),
                new AgreementItem(AgreementType.MARKETING, true)));
        assertThat(fixtures.count("member_agreement",
                "member_id = ? AND type = 'MARKETING'", agreed.memberId())).isEqualTo(1);
    }

    @Test
    @DisplayName("영구 제재 이력자가 탈퇴 후 파기되면 같은 소셜 계정으로 다시 가입할 수 없다")
    void 영구_제재_이력자의_재가입이_차단된다() {
        // 이 테스트가 죽으면: 영구 제재를 받은 사람이 탈퇴 후 재가입 한 번으로 깨끗한 계정을
        // 얻는다. 탈퇴 후에는 소셜 식별자 원문이 남지 않으므로 이 해시 등재가 유일한 방어선이다.
        LoginResponse joined = login("flow-rejoin", mandatory());
        sanctionService.apply(joined.memberId(), null,
                new SanctionCommand(SanctionType.PERMANENT, null), ADMIN_ACTOR);
        memberService.requestWithdrawal(joined.memberId());

        clock.fixAt(now().plusDays(graceDays).plusMinutes(1));
        memberPurgeBatch.run();

        assertThat(fixtures.member(joined.memberId()).getStatus())
                .isEqualTo(MemberStatus.DELETED);
        assertThat(fixtures.countAll("blocked_social_hash")).isEqualTo(1);
        assertThatThrownBy(() -> login("flow-rejoin", mandatory()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.REJOIN_BLOCKED);
    }

    @Test
    @DisplayName("기간 제재만 받은 이력자는 탈퇴 뒤에도 다시 가입할 수 있다")
    void 기간_제재는_재가입을_막지_않는다() {
        // 이 테스트가 죽으면: 3일 제재 한 번이 영구 재가입 금지가 된다. 차단 목록은 PERMANENT
        // 이력자만 올린다는 규칙(BlockedSocialHash)이 무너진 것이다.
        LoginResponse joined = login("flow-temp-rejoin", mandatory());
        sanctionService.apply(joined.memberId(), null,
                new SanctionCommand(SanctionType.TEMP, 3), ADMIN_ACTOR);
        memberService.requestWithdrawal(joined.memberId());

        clock.fixAt(now().plusDays(graceDays).plusMinutes(1));
        memberPurgeBatch.run();

        assertThat(fixtures.countAll("blocked_social_hash")).isZero();
        LoginResponse rejoined = login("flow-temp-rejoin", mandatory());
        assertThat(rejoined.isNewMember()).isTrue();
        assertThat(rejoined.memberId()).isNotEqualTo(joined.memberId());
    }

    @Test
    @DisplayName("빈 인가 코드는 소셜 인증 실패로 끊는다")
    void 빈_인가_코드는_401이다() {
        // 이 테스트가 죽으면: 소셜 검증 실패 경로가 사라져 인가 코드 없이도 계정이 생긴다.
        assertThatThrownBy(() -> login("", mandatory()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_SOCIAL_TOKEN);
        assertThat(fixtures.countAll("member")).isZero();
    }

    private LoginResponse login(String authorizationCode, List<AgreementItem> agreements) {
        return authService.login(
                new LoginRequest(SocialProvider.DEV, authorizationCode, agreements));
    }

    private List<AgreementItem> mandatory() {
        return List.of(new AgreementItem(AgreementType.TOS, true),
                new AgreementItem(AgreementType.PRIVACY, true));
    }
}
