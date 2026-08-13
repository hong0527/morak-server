package com.morak.match;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.morak.common.error.BusinessException;
import com.morak.common.error.ErrorCode;
import com.morak.match.dto.request.MatchRequestCreateRequest;
import com.morak.match.service.MatchService;
import com.morak.member.service.MemberService;
import com.morak.member.type.MemberStatus;
import com.morak.support.Concurrently;
import com.morak.support.IntegrationTest;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

/**
 * 매칭 요청(MT-1)과 탈퇴 신청(AU-4)이 겹칠 때 대기열·세션에 남는 것.
 *
 * <p><b>인터셉터(§0-2)의 회원 상태 검사만으로는 이 경합을 막지 못한다.</b> 그 검사는 요청이
 * 회원 행 잠금을 얻기 전에 끝나므로, 그 사이에 커밋된 탈퇴를 보지 못한다. 실측에서 두 요청을
 * 동시에 보내면 10건 중 7건이 탈퇴 대기 회원의 대기 요청으로 남았다.
 *
 * <p>남은 요청은 그 자리에서 끝나지 않는다. 6인 확정이 그 행을 집어 들면 <b>입장할 수 없는
 * 사람</b>(SS-2가 403이다)이 남의 세션에서 자리를 차지한 채 완주로 집계되고 포인트까지 받는다.
 *
 * <p>그래서 방어선이 둘이고 여기서 따로 확인한다 — 잠금 뒤의 상태 재확인(MT-1 게이트)과
 * 6인 확정 직전의 배제다. 앞의 둘은 스레드 순서에 기대지 않으려고 <b>탈퇴가 이미 커밋된
 * 상태</b>에서 서비스를 직접 부른다. 경합이 만들어 내는 상태가 정확히 그것이기 때문이다.
 */
@DisplayName("탈퇴와 매칭 요청의 경합")
class WithdrawalMatchRaceTest extends IntegrationTest {

    private static final int TARGET_MINUTES = 60;

    @Autowired
    private MatchService matchService;

    @Autowired
    private MemberService memberService;

    @Value("${morak.session.required-participants}")
    private int requiredParticipants;

    @Test
    @DisplayName("탈퇴가 커밋된 뒤 도착한 매칭 요청은 대기열에 들어가지 못한다")
    void 탈퇴_뒤_도착한_요청은_거절된다() {
        // 이 테스트가 죽으면: MT-1이 회원 행을 잡은 뒤 상태를 다시 읽지 않는 것이다.
        // 인터셉터가 통과시킨 요청은 그 뒤에 커밋된 탈퇴를 모른 채 대기열에 들어간다.
        clock.fixAt(BASE_TIME);
        Long memberId = fixtures.joinMember();
        memberService.requestWithdrawal(memberId);

        assertThatThrownBy(() -> matchService.request(memberId,
                new MatchRequestCreateRequest(TARGET_MINUTES)))
                .isInstanceOf(BusinessException.class)
                .extracting(failure -> ((BusinessException) failure).getErrorCode())
                .isEqualTo(ErrorCode.WITHDRAWAL_PENDING);
        assertThat(fixtures.count("match_request", "member_id = ?", memberId)).isZero();
    }

    @Test
    @DisplayName("대기열에 남은 탈퇴 회원의 요청은 6인 확정에 뽑히지 않는다")
    void 탈퇴_회원의_대기_요청은_뽑히지_않는다() {
        // 이 테스트가 죽으면: 6인 확정의 배제 집합에서 회원 상태가 빠진 것이다. 게이트를
        // 지난 지 오래된 대기 행이 남아 있으면 그대로 남의 세션에 뽑힌다.
        clock.fixAt(BASE_TIME);
        Long withdrawn = fixtures.joinMember();
        matchService.request(withdrawn, new MatchRequestCreateRequest(TARGET_MINUTES));
        // AU-4가 회수하지 못한 대기 행. 경합이 남기는 상태를 상태만으로 재현한다
        fixtures.execute("UPDATE member SET status = 'WITHDRAW_PENDING' WHERE id = ?", withdrawn);

        List<Long> others = fixtures.joinMembers(requiredParticipants);
        for (Long memberId : others) {
            matchService.request(memberId, new MatchRequestCreateRequest(TARGET_MINUTES));
        }

        // 정원은 남은 6명으로 채워졌고 탈퇴 회원은 대기열에 남아 있다
        assertThat(fixtures.countAll("live_session")).isEqualTo(1);
        assertThat(fixtures.count("session_participant", "member_id = ?", withdrawn)).isZero();
        assertThat(fixtures.count("match_request",
                "member_id = ? AND status = 'WAITING'", withdrawn)).isEqualTo(1);
    }

    @Test
    @DisplayName("두 요청이 동시에 와도 어느 순서든 탈퇴 회원이 대기열·세션에 남지 않는다")
    void 동시_요청은_어느_순서든_회원을_남기지_않는다() {
        // 이 테스트가 죽으면: 두 경로 중 하나가 상대의 결과를 보지 못하는 것이다. 어느 쪽이
        // 먼저 커밋하든 남는 상태는 같아야 한다.
        clock.fixAt(BASE_TIME);
        List<Long> waiting = fixtures.joinMembers(requiredParticipants - 1);
        for (Long waitingId : waiting) {
            matchService.request(waitingId, new MatchRequestCreateRequest(TARGET_MINUTES));
        }
        Long memberId = fixtures.joinMember();

        List<Throwable> failures = Concurrently.run(2, index -> {
            if (index == 0) {
                matchService.request(memberId, new MatchRequestCreateRequest(TARGET_MINUTES));
            } else {
                memberService.requestWithdrawal(memberId);
            }
        });

        // 늦게 도착한 MT-1이 403으로 끊기는 것은 정상이다. 그 밖의 실패는 이 경합이 만들면 안 된다
        assertThat(failures).allSatisfy(failure -> {
            assertThat(failure).isInstanceOf(BusinessException.class);
            assertThat(((BusinessException) failure).getErrorCode())
                    .isEqualTo(ErrorCode.WITHDRAWAL_PENDING);
        });
        assertThat(fixtures.member(memberId).getStatus()).isEqualTo(MemberStatus.WITHDRAW_PENDING);
        assertThat(fixtures.count("match_request", "active_member_id = ?", memberId)).isZero();
        assertThat(fixtures.count("session_participant",
                "member_id = ? AND status IN ('ACTIVE', 'PAUSED')", memberId)).isZero();
    }
}
