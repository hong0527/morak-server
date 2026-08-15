package com.morak.match;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.morak.match.dto.request.MatchRequestCreateRequest;
import com.morak.match.service.MatchExpireBatch;
import com.morak.match.service.MatchService;
import com.morak.member.service.MemberService;
import com.morak.report.dto.request.SanctionCommand;
import com.morak.report.service.SanctionService;
import com.morak.report.type.SanctionType;
import com.morak.support.Concurrently;
import com.morak.support.IntegrationTest;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

/**
 * 매칭의 두 불변식. 6인 확정은 겹치지 않고, 대기가 끝나는 모든 경로는 활성 표식을 반드시
 * 놓아 준다.
 *
 * <p>{@code uk_mr_active}는 "회원당 활성 요청 1건"을 DB에서 강제하는 제약이라, 상태만 바꾸고
 * {@code active_member_id}를 비우지 않은 경로가 하나라도 있으면 그 회원은 다시는 매칭을
 * 요청할 수 없게 된다. 재배포로도 풀리지 않으므로 해제 경로를 하나씩 확인한다.
 */
@DisplayName("매칭 동시성과 활성 표식 해제")
class MatchConcurrencyTest extends IntegrationTest {

    private static final int TARGET_MINUTES = 60;

    @Autowired
    private MatchService matchService;

    @Autowired
    private MatchExpireBatch matchExpireBatch;

    @Autowired
    private MemberService memberService;

    @Autowired
    private SanctionService sanctionService;

    @Value("${morak.session.required-participants}")
    private int requiredParticipants;

    @Value("${morak.match.wait-expire-minutes}")
    private int waitExpireMinutes;

    @Test
    @DisplayName("같은 조건 동시 7요청은 정확히 6명만 성사시킨다")
    void 동시_7요청은_6명만_성사시킨다() {
        // 이 테스트가 죽으면: 조건 행 잠금이나 조건부 UPDATE가 빠져 6인 확정이 겹친 것이다.
        // 7인 세션이 생기거나, 정원 미달 세션이 둘 생긴다.
        clock.fixAt(BASE_TIME);
        List<Long> memberIds = fixtures.joinMembers(requiredParticipants + 1);

        List<Throwable> failures = Concurrently.run(memberIds.size(), index ->
                matchService.request(memberIds.get(index),
                        new MatchRequestCreateRequest(TARGET_MINUTES)));

        assertThat(failures).isEmpty();
        assertThat(fixtures.count("match_request", "status = 'MATCHED'"))
                .isEqualTo(requiredParticipants);
        assertThat(fixtures.count("match_request", "status = 'WAITING'")).isEqualTo(1);
        assertThat(fixtures.countAll("live_session")).isEqualTo(1);
        assertThat(fixtures.countAll("session_participant")).isEqualTo(requiredParticipants);
        // 성사된 6건은 활성 표식을 놓아 준 상태여야 한다
        assertThat(fixtures.count("match_request",
                "status = 'MATCHED' AND active_member_id IS NULL"))
                .isEqualTo(requiredParticipants);
    }

    @Test
    @DisplayName("취소한 회원은 곧바로 다시 요청할 수 있다")
    void 취소_뒤_재요청할_수_있다() {
        // 이 테스트가 죽으면: MT-3이 상태만 바꾸고 active_member_id를 남긴 것이다.
        clock.fixAt(BASE_TIME);
        Long memberId = fixtures.joinMember();
        Long requestId = matchService.request(memberId,
                new MatchRequestCreateRequest(TARGET_MINUTES)).matchRequestId();

        matchService.cancel(memberId, requestId);

        assertThat(fixtures.count("match_request", "id = ? AND status = 'CANCELLED'", requestId))
                .isEqualTo(1);
        assertRequestableAgain(memberId);
    }

    @Test
    @DisplayName("만료된 회원은 곧바로 다시 요청할 수 있다")
    void 만료_뒤_재요청할_수_있다() {
        // 이 테스트가 죽으면: B2가 조건부 UPDATE를 벗어나 활성 표식을 남긴 것이다.
        clock.fixAt(BASE_TIME);
        Long memberId = fixtures.joinMember();
        Long requestId = matchService.request(memberId,
                new MatchRequestCreateRequest(TARGET_MINUTES)).matchRequestId();
        clock.fixAt(BASE_TIME.plusMinutes(waitExpireMinutes + 1));

        int expired = matchExpireBatch.run();

        assertThat(expired).isEqualTo(1);
        assertThat(fixtures.count("match_request", "id = ? AND status = 'EXPIRED'", requestId))
                .isEqualTo(1);
        assertRequestableAgain(memberId);
    }

    @Test
    @DisplayName("탈퇴 신청이 대기 요청을 놓아 주고, 철회하면 다시 요청할 수 있다")
    void 탈퇴_신청_뒤_철회하면_재요청할_수_있다() {
        // 이 테스트가 죽으면: AU-4가 대기 요청을 남겨 탈퇴한 회원이 남의 세션에 6번째로
        // 들어가거나, 철회한 회원이 활성 표식에 걸려 영영 매칭하지 못한다.
        clock.fixAt(BASE_TIME);
        Long memberId = fixtures.joinMember();
        Long requestId = matchService.request(memberId,
                new MatchRequestCreateRequest(TARGET_MINUTES)).matchRequestId();

        memberService.requestWithdrawal(memberId);

        assertThat(fixtures.count("match_request", "id = ? AND status = 'CANCELLED'", requestId))
                .isEqualTo(1);
        memberService.cancelWithdrawal(memberId);
        assertRequestableAgain(memberId);
    }

    @Test
    @DisplayName("제재가 대기 요청을 놓아 준다")
    void 제재_뒤_활성_표식이_풀린다() {
        // 이 테스트가 죽으면: 제재당한 회원이 대기열에 이름만 남은 유령이 된다. 제재가 끝나도
        // uk_mr_active가 재요청을 막는다.
        clock.fixAt(BASE_TIME);
        Long memberId = fixtures.joinMember();
        Long adminId = fixtures.joinMember();
        Long requestId = matchService.request(memberId,
                new MatchRequestCreateRequest(TARGET_MINUTES)).matchRequestId();

        sanctionService.apply(memberId, null, new SanctionCommand(SanctionType.TEMP, 3), adminId);

        assertThat(fixtures.count("match_request", "id = ? AND status = 'CANCELLED'", requestId))
                .isEqualTo(1);
        assertRequestableAgain(memberId);
    }

    /** 활성 표식이 풀렸다는 말의 실제 의미 — 같은 회원의 다음 요청이 제약에 걸리지 않는다. */
    private void assertRequestableAgain(Long memberId) {
        assertThat(fixtures.count("match_request", "active_member_id = ?", memberId)).isZero();
        assertThatCode(() -> matchService.request(memberId,
                new MatchRequestCreateRequest(TARGET_MINUTES))).doesNotThrowAnyException();
        assertThat(fixtures.count("match_request",
                "active_member_id = ? AND status = 'WAITING'", memberId)).isEqualTo(1);
    }
}
