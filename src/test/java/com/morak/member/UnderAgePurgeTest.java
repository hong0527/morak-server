package com.morak.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.morak.common.error.BusinessException;
import com.morak.common.error.ErrorCode;
import com.morak.match.entity.MatchLock;
import com.morak.member.dto.request.BirthDateRequest;
import com.morak.member.dto.request.MediaConsentRequest;
import com.morak.member.repository.MemberRepository;
import com.morak.member.service.MemberService;
import com.morak.support.IntegrationTest;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 만 14세 미만 판정 계정의 파기(★D7). 탈퇴의 30일 유예를 적용하지 않고 그 자리에서 지운다 —
 * 가입이 성립하지 않은 상태라 보관할 근거가 없다.
 *
 * <p>파기와 403 응답이 같은 트랜잭션에 있으면 예외의 롤백이 삭제까지 되돌려 계정이 그대로
 * 남는다. 그래서 파기는 별도 트랜잭션에서 먼저 커밋되어야 하고, 그 사실은 예외를 받은 뒤에
 * 남은 행을 세어야만 확인된다.
 */
@DisplayName("만 14세 미만 파기 원자성")
class UnderAgePurgeTest extends IntegrationTest {

    private static final int UNDER_AGE_YEARS = 13;

    @Autowired
    private MemberService memberService;

    @Autowired
    private MemberRepository memberRepository;

    @Test
    @DisplayName("미만 판정 뒤에는 회원·원장·동의·잠금 행이 하나도 남지 않는다")
    void 미만_판정은_계정_흔적을_남기지_않는다() {
        // 이 테스트가 죽으면: 파기가 판정 예외와 같은 트랜잭션에 묶여 롤백된 것이다. 계정은
        // 그대로 남는데 사용자는 403만 받아, 다시 로그인해도 같은 자리에서 막힌다.
        clock.fixAt(BASE_TIME);
        String authorizationCode = "under-age-" + BASE_TIME.toLocalDate();
        Long memberId = fixtures.joinMember(authorizationCode);
        memberService.agreeMediaConsent(memberId, new MediaConsentRequest(true));
        assertGateLeftTraces(memberId);

        LocalDate birthDate = LocalDate.now(clock).minusYears(UNDER_AGE_YEARS);
        assertThatThrownBy(() -> memberService.verifyAge(memberId, new BirthDateRequest(birthDate)))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.UNDER_AGE_SIGNUP_BLOCKED);

        assertThat(memberRepository.findById(memberId)).isEmpty();
        assertThat(fixtures.count("member_agreement", "member_id = ?", memberId)).isZero();
        assertThat(fixtures.count("media_consent", "member_id = ?", memberId)).isZero();
        assertThat(fixtures.count("streak_day", "member_id = ?", memberId)).isZero();
        assertThat(fixtures.count("point_ledger", "member_id = ?", memberId)).isZero();
        assertThat(fixtures.count("match_lock", "lock_key = ?", MatchLock.memberKey(memberId)))
                .isZero();
    }

    @Test
    @DisplayName("파기 뒤 같은 소셜 계정으로 다시 로그인하면 새 회원이 만들어진다")
    void 파기_뒤_재로그인은_새_계정이다() {
        // 이 테스트가 죽으면: 파기가 회원 행을 남겨 uk_member_provider에 걸리거나, 지워진 계정의
        // 잔해가 재로그인을 막는 것이다.
        clock.fixAt(BASE_TIME);
        String authorizationCode = "under-age-rejoin";
        Long purgedId = fixtures.joinMember(authorizationCode);
        LocalDate birthDate = LocalDate.now(clock).minusYears(UNDER_AGE_YEARS);
        assertThatThrownBy(() -> memberService.verifyAge(purgedId, new BirthDateRequest(birthDate)))
                .isInstanceOf(BusinessException.class);

        Long rejoinedId = fixtures.joinMember(authorizationCode);

        assertThat(rejoinedId).isNotEqualTo(purgedId);
        // 새 계정이므로 웰컴 포인트도 새로 하나다
        assertThat(fixtures.count("point_ledger", "member_id = ? AND reason = 'WELCOME'",
                rejoinedId)).isEqualTo(1);
        assertThat(fixtures.member(rejoinedId).getPointBalance())
                .isEqualTo(fixtures.ledgerSum(rejoinedId));
    }

    /** 지워질 것이 실제로 있었는지 먼저 확인한다. 없는 것을 지운 뒤 통과하는 테스트를 막는다. */
    private void assertGateLeftTraces(Long memberId) {
        assertThat(fixtures.count("member_agreement", "member_id = ?", memberId)).isEqualTo(2);
        assertThat(fixtures.count("media_consent", "member_id = ?", memberId)).isEqualTo(1);
        assertThat(fixtures.count("point_ledger", "member_id = ?", memberId)).isEqualTo(1);
        assertThat(fixtures.count("match_lock", "lock_key = ?", MatchLock.memberKey(memberId)))
                .isEqualTo(1);
    }
}
