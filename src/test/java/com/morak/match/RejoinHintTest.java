package com.morak.match;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.morak.common.error.BusinessException;
import com.morak.common.error.ErrorCode;
import com.morak.match.dto.request.MatchRequestCreateRequest;
import com.morak.match.service.MatchService;
import com.morak.support.IntegrationTest;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * MT-1의 활성 세션 거절이 세션 번호를 함께 주는지 본다. 이 오류를 받는 대표 경우가 앱 재시작
 * 후의 재매칭 시도라, 번호가 없으면 클라이언트는 어느 방으로 돌아갈지 몰라 90초 재접속
 * 유예(D13)가 그냥 지나간다.
 */
@DisplayName("활성 세션 거절의 복귀 힌트")
class RejoinHintTest extends IntegrationTest {

    private static final int TARGET_MINUTES = 60;
    private static final int PARTICIPANTS = 6;

    @Autowired
    private MatchService matchService;

    @Test
    @DisplayName("활성 세션 참가 중의 매칭 요청은 세션 번호를 details로 준다")
    void 거절에_세션_번호가_실린다() {
        // 이 테스트가 죽으면: ALREADY_IN_ACTIVE_SESSION이 번호 없는 거절이 된 것이다.
        // REMATCH_COOLDOWN의 availableAt처럼, 오류가 다음 행동에 필요한 값을 실어야 한다.
        List<Long> memberIds = fixtures.joinMembers(PARTICIPANTS);
        Long sessionId = fixtures.openSession(TARGET_MINUTES, BASE_TIME, memberIds);
        clock.fixAt(BASE_TIME.plusMinutes(5));

        assertThatThrownBy(() -> matchService.request(memberIds.getFirst(),
                new MatchRequestCreateRequest(TARGET_MINUTES)))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> {
                    BusinessException business = (BusinessException) exception;
                    assertThat(business.getErrorCode())
                            .isEqualTo(ErrorCode.ALREADY_IN_ACTIVE_SESSION);
                    assertThat(business.getDetails()).containsEntry("sessionId", sessionId);
                });
    }
}
