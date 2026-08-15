package com.morak.match;

import static org.assertj.core.api.Assertions.assertThat;

import com.morak.match.dto.request.MatchRequestCreateRequest;
import com.morak.match.dto.response.MatchRequestResponse;
import com.morak.match.service.MatchService;
import com.morak.match.type.MatchRequestStatus;
import com.morak.support.IntegrationTest;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 만료 시각이 지났지만 B2가 아직 마킹하지 않은 요청(최대 1분 창)의 성사. **이 창의 성사는
 * 계약이다**(§0-7) — 후보 조회(MT-1 7단계)는 상태만 보고 `expires_at`을 보지 않는다.
 *
 * <p>그 창에서 성사되는 것은 사용자에게 손해가 아니라 이득이고(기다린 보람이 1분 늦게 온
 * 것뿐이다), 후보에서 빼면 매칭 확률만 낮아진다. 그래서 프론트 규약도 "카운트다운 0에서도
 * `EXPIRED`를 받을 때까지 폴링을 유지한다"로 맞춰져 있다.
 */
@DisplayName("만료 창의 매칭 성사")
class MatchExpiryWindowTest extends IntegrationTest {

    private static final int TARGET_MINUTES = 60;
    private static final int REQUIRED = 6;

    @Autowired
    private MatchService matchService;

    @Test
    @DisplayName("만료 시각이 지난 대기 요청도 B2 마킹 전에는 성사된다")
    void 만료_창의_요청이_성사된다() {
        // 이 테스트가 죽으면: 후보 조회가 expires_at을 걸러 계약(§0-7)과 갈라진 것이다.
        // 프론트는 "만료 표시 후에도 EXPIRED 수신까지 폴링"을 계약으로 믿는데, 서버가 그 창의
        // 성사를 배제하면 그 폴링이 헛돌고 매칭 확률만 낮아진다.
        List<Long> waiters = fixtures.joinMembers(REQUIRED - 1);
        clock.fixAt(BASE_TIME);
        for (Long memberId : waiters) {
            matchService.request(memberId, new MatchRequestCreateRequest(TARGET_MINUTES));
        }
        // 만료(+10분)를 넘겼지만 B2는 돌지 않았다 — 마킹 전 창
        clock.fixAt(BASE_TIME.plusMinutes(11));
        Long sixth = fixtures.joinMember();

        MatchRequestResponse response =
                matchService.request(sixth, new MatchRequestCreateRequest(TARGET_MINUTES));

        assertThat(response.status()).isEqualTo(MatchRequestStatus.MATCHED);
        assertThat(response.sessionId()).isNotNull();
        // 만료 시각이 지나 있던 다섯 명도 함께 성사됐다
        for (Long memberId : waiters) {
            MatchRequestResponse polled = matchService.getMine(memberId);
            assertThat(polled.status()).isEqualTo(MatchRequestStatus.MATCHED);
            assertThat(polled.sessionId()).isEqualTo(response.sessionId());
        }
    }
}
