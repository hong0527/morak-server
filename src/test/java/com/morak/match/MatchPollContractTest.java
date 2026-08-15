package com.morak.match;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.morak.common.error.BusinessException;
import com.morak.common.error.ErrorCode;
import com.morak.match.dto.request.MatchRequestCreateRequest;
import com.morak.match.dto.response.MatchRequestResponse;
import com.morak.match.service.MatchExpireBatch;
import com.morak.match.service.MatchService;
import com.morak.match.type.MatchRequestStatus;
import com.morak.support.IntegrationTest;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * MT-2 폴링의 종결 상태 계약. **가장 최근 요청을 상태와 무관하게 돌려주고, 404는 요청 이력이
 * 전무한 경우뿐이다.** 폴링하던 화면이 404라는 모호한 답 대신 CANCELLED·EXPIRED·MATCHED를
 * 그대로 받아야 무엇이 일어났는지 안다 — "최근 종결"이라는 모호어를 계약에서 없앤다.
 */
@DisplayName("매칭 폴링의 종결 상태")
class MatchPollContractTest extends IntegrationTest {

    private static final int TARGET_MINUTES = 60;
    private static final int REQUIRED = 6;

    @Autowired
    private MatchService matchService;

    @Autowired
    private MatchExpireBatch matchExpireBatch;

    @Test
    @DisplayName("취소 후 폴링은 404가 아니라 CANCELLED다")
    void 취소_후_폴링은_CANCELLED다() {
        // 이 테스트가 죽으면: 취소 직후의 폴링이 404를 받아 "요청한 적 없음"과 "방금 취소함"이
        // 화면에서 갈리지 않는 것이다.
        Long memberId = fixtures.joinMember();
        clock.fixAt(BASE_TIME);
        Long requestId = matchService
                .request(memberId, new MatchRequestCreateRequest(TARGET_MINUTES))
                .matchRequestId();
        matchService.cancel(memberId, requestId);

        MatchRequestResponse polled = matchService.getMine(memberId);

        assertThat(polled.matchRequestId()).isEqualTo(requestId);
        assertThat(polled.status()).isEqualTo(MatchRequestStatus.CANCELLED);
        assertThat(polled.waitingCount()).isZero();
        assertThat(polled.sessionId()).isNull();
    }

    @Test
    @DisplayName("만료 후 폴링은 EXPIRED다")
    void 만료_후_폴링은_EXPIRED다() {
        Long memberId = fixtures.joinMember();
        clock.fixAt(BASE_TIME);
        matchService.request(memberId, new MatchRequestCreateRequest(TARGET_MINUTES));
        clock.fixAt(BASE_TIME.plusMinutes(11));
        matchExpireBatch.run();

        MatchRequestResponse polled = matchService.getMine(memberId);

        assertThat(polled.status()).isEqualTo(MatchRequestStatus.EXPIRED);
        assertThat(polled.waitingCount()).isZero();
        assertThat(polled.sessionId()).isNull();
    }

    @Test
    @DisplayName("성사 후 폴링은 MATCHED와 세션 번호다")
    void 성사_후_폴링은_MATCHED다() {
        List<Long> memberIds = fixtures.joinMembers(REQUIRED);
        clock.fixAt(BASE_TIME);
        for (Long memberId : memberIds) {
            matchService.request(memberId, new MatchRequestCreateRequest(TARGET_MINUTES));
        }

        MatchRequestResponse polled = matchService.getMine(memberIds.getFirst());

        assertThat(polled.status()).isEqualTo(MatchRequestStatus.MATCHED);
        assertThat(polled.sessionId()).isNotNull();
    }

    @Test
    @DisplayName("요청 이력이 전무할 때만 404다")
    void 이력이_전무할_때만_404다() {
        Long memberId = fixtures.joinMember();

        assertThatThrownBy(() -> matchService.getMine(memberId))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.NO_ACTIVE_MATCH_REQUEST);
    }

    @Test
    @DisplayName("종결 뒤 새 요청을 걸면 폴링은 최신 요청을 돌려준다")
    void 폴링은_가장_최근_요청이다() {
        // 이 테스트가 죽으면: 폴링이 종결된 옛 요청을 계속 돌려줘, 재요청한 사용자의 대기
        // 화면이 이전 요청의 CANCELLED를 그리는 것이다.
        Long memberId = fixtures.joinMember();
        clock.fixAt(BASE_TIME);
        Long first = matchService
                .request(memberId, new MatchRequestCreateRequest(TARGET_MINUTES))
                .matchRequestId();
        matchService.cancel(memberId, first);
        Long second = matchService
                .request(memberId, new MatchRequestCreateRequest(TARGET_MINUTES))
                .matchRequestId();

        MatchRequestResponse polled = matchService.getMine(memberId);

        assertThat(polled.matchRequestId()).isEqualTo(second);
        assertThat(polled.status()).isEqualTo(MatchRequestStatus.WAITING);
    }
}
