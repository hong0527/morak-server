package com.morak.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.morak.common.error.BusinessException;
import com.morak.common.error.ErrorCode;
import com.morak.member.dto.request.BirthDateRequest;
import com.morak.member.dto.request.GoalRequest;
import com.morak.member.dto.response.GoalResponse;
import com.morak.member.service.MemberService;
import com.morak.member.type.AgeVerification;
import com.morak.member.type.GoalStatus;
import com.morak.support.IntegrationTest;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * AU-3 연령 검증의 나이 경계와 AU-7 목표 설정의 거절 규칙.
 *
 * <p>둘 다 "정상 경로는 이미 다른 테스트가 지나가는데 <b>거절해야 할 자리</b>가 비어 있던"
 * 곳이다. 연령은 {@code UnderAgePurgeTest}가 파기의 원자성을, 목표는 {@code GoalProgressTest}가
 * 진행도 계산을 본다 — 그러나 <b>몇 살부터 미만인가</b>와 <b>언제 거절하는가</b>는 어느 쪽도
 * 확인하지 않았다.
 *
 * <p>연령 경계가 특히 위험한 이유는 판정이 되돌릴 수 없기 때문이다. 하루 어긋나면 자격이 있는
 * 사람의 계정이 그 자리에서 파기된다(★D7) — 사과할 상대의 계정이 이미 없다.
 */
@DisplayName("연령 검증과 목표 설정의 규칙")
class AgeAndGoalRulesTest extends IntegrationTest {

    private static final int MINIMUM_AGE = 14;

    @Autowired
    private MemberService memberService;

    // ── AU-3 연령 경계 ──

    @Test
    @DisplayName("만 14세 생일 당일은 가입 자격이 있다")
    void 생일_당일은_통과한다() {
        // 이 테스트가 죽으면: 자격이 있는 사람의 계정이 파기된다. 판정이 되돌릴 수 없어서
        // (★D7) 하루 어긋나는 것만으로 그날 생일인 가입자 전원을 잃는다.
        Long memberId = fixtures.joinMember("au3-exactly-14");
        LocalDate exactly14 = today().minusYears(MINIMUM_AGE);

        assertThat(memberService.verifyAge(memberId, new BirthDateRequest(exactly14))
                .ageVerification()).isEqualTo(AgeVerification.VERIFIED);
        assertThat(fixtures.member(memberId).getAgeVerification())
                .isEqualTo(AgeVerification.VERIFIED);
        assertThat(fixtures.member(memberId).getBirthDate()).isEqualTo(exactly14);
    }

    @Test
    @DisplayName("생일 하루 전이면 아직 만 13세라 계정이 파기된다")
    void 생일_하루_전은_차단된다() {
        // 이 테스트가 죽으면: 만 14세 미만이 통과한다. 이 한 줄이 ★D7의 전부다 — 미만은
        // 가입 자체를 막는 것이 정책이라, 여기가 뚫리면 저장된 미성년 계정을 걸러 낼 자리가
        // 이 서버 어디에도 없다(AgeVerification에 UNDER_AGE 값 자체가 없다).
        Long memberId = fixtures.joinMember("au3-one-day-short");
        LocalDate oneDayShort = today().minusYears(MINIMUM_AGE).plusDays(1);

        assertThatThrownBy(() ->
                memberService.verifyAge(memberId, new BirthDateRequest(oneDayShort)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.UNDER_AGE_SIGNUP_BLOCKED);

        // 판정과 파기는 한 몸이다. 거절만 하고 계정이 남으면 REQUIRED로 영원히 갇힌다
        assertThat(fixtures.countAll("member")).isZero();
    }

    @Test
    @DisplayName("확정된 연령은 재입력으로 뒤집을 수 없다")
    void 확정_후_재검증은_거절된다() {
        // 이 테스트가 죽으면: 성인으로 통과한 뒤 미성년 생년월일을 넣어 상태를 바꾸거나,
        // 반대로 파기를 피하려고 생년월일을 고쳐 쓸 수 있다. 검증은 한 번뿐이어야 한다.
        Long memberId = fixtures.joinMember("au3-already");
        LocalDate adult = today().minusYears(30);
        memberService.verifyAge(memberId, new BirthDateRequest(adult));

        assertThatThrownBy(() -> memberService.verifyAge(memberId,
                new BirthDateRequest(today().minusYears(MINIMUM_AGE).plusDays(1))))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.ALREADY_VERIFIED);

        assertThat(fixtures.member(memberId).getBirthDate()).isEqualTo(adult);
        assertThat(fixtures.member(memberId).getAgeVerification())
                .isEqualTo(AgeVerification.VERIFIED);
    }

    // ── AU-7 목표 설정 ──

    @Test
    @DisplayName("목표는 7·14·30일만 걸 수 있다")
    void 허용된_기간만_받는다() {
        // 이 테스트가 죽으면: 1일짜리 목표로 달성 포인트를 반복 수령하거나, 화면이 그리지
        // 못하는 기간이 저장된다. 허용값은 §0-4의 선택지 정의가 전부다.
        Long memberId = fixtures.joinVerifiedMember("au7-period");

        for (int allowed : new int[]{7, 14, 30}) {
            Long other = fixtures.joinVerifiedMember("au7-period-" + allowed);
            assertThat(memberService.setGoal(other, new GoalRequest(allowed)).periodDays())
                    .isEqualTo(allowed);
        }
        for (int rejected : new int[]{0, 1, 6, 15, 31, 365}) {
            assertThatThrownBy(() -> memberService.setGoal(memberId, new GoalRequest(rejected)))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.VALIDATION_FAILED);
        }
        assertThat(fixtures.count("member_goal", "member_id = ?", memberId)).isZero();
    }

    @Test
    @DisplayName("진행 중인 목표가 있으면 새 목표를 걸 수 없다")
    void 활성_목표는_하나뿐이다() {
        // 이 테스트가 죽으면: 목표를 여러 개 걸어 같은 완주로 달성 포인트를 여러 번 받는다.
        // 활성 1건 제약이 DB에 없어서(부분 인덱스를 못 쓴다) 이 검사가 유일한 방어선이다.
        Long memberId = fixtures.joinVerifiedMember("au7-duplicate");
        GoalResponse first = memberService.setGoal(memberId, new GoalRequest(7));

        assertThatThrownBy(() -> memberService.setGoal(memberId, new GoalRequest(14)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.GOAL_ALREADY_ACTIVE);

        assertThat(fixtures.count("member_goal", "member_id = ?", memberId)).isEqualTo(1);
        assertThat(fixtures.count("member_goal", "id = ? AND period_days = 7", first.goalId()))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("설정 직후의 목표는 진행 0이고 시작일이 오늘이다")
    void 새_목표의_초기값() {
        // 이 테스트가 죽으면: 완주 이력이 없는데 진행도가 0이 아니게 된다. 이전 연속이
        // 새 목표에 딸려 들어오면 하루 완주로 목표가 달성된다(§0-6의 실측 사고).
        Long memberId = fixtures.joinVerifiedMember("au7-fresh");
        clock.fixAt(BASE_TIME);

        GoalResponse goal = memberService.setGoal(memberId, new GoalRequest(30));

        assertThat(goal.periodDays()).isEqualTo(30);
        assertThat(goal.progressDays()).isZero();
        assertThat(goal.startedOn()).isEqualTo(BASE_TIME.toLocalDate());
        assertThat(goal.status()).isEqualTo(GoalStatus.ACTIVE);
        assertThat(goal.achievedAt()).isNull();
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }
}
