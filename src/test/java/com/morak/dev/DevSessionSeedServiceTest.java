package com.morak.dev;

import static org.assertj.core.api.Assertions.assertThat;

import com.morak.dev.dto.request.DevSessionSeedRequest;
import com.morak.dev.dto.response.DevSessionSeedResponse;
import com.morak.member.entity.Member;
import com.morak.session.service.SessionClosingBatch;
import com.morak.support.IntegrationTest;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * DEV-3 시드의 Streak 캐시 정합. 시드는 게이트 실측의 재료를 만드는 도구라, 시드가 만든 상태가
 * 실제 완주 이력과 다르면 그 위의 모든 실측이 오염된다.
 */
@DisplayName("DEV-3 완주 이력 시드")
class DevSessionSeedServiceTest extends IntegrationTest {

    private static final int TARGET_MINUTES = 60;
    private static final LocalDate TODAY = BASE_TIME.toLocalDate();

    @Autowired
    private DevSessionSeedService seedService;

    @Autowired
    private SessionClosingBatch sessionClosingBatch;

    @Test
    @DisplayName("과거 일자 백필 후 캐시가 완주일 기록과 일치한다")
    void 백필_시드가_캐시를_재계산한다() {
        // 이 테스트가 죽으면: 시드가 증분 갱신에만 기대는 것이다. 증분 경로는 마지막 완주일보다
        // 과거인 날짜를 무시하므로, 오늘 완주가 이미 있는 회원에게 어제 이전을 백필하면
        // streak_day는 3연속인데 캐시는 1로 남는다 — 시드 위의 Streak 실측이 전부 틀어진다.
        Long memberId = fixtures.joinMember();
        completeToday(memberId);
        assertThat(fixtures.member(memberId).getCurrentStreak()).isEqualTo(1);

        DevSessionSeedResponse response = seedService.seed(new DevSessionSeedRequest(
                memberId, List.of(TODAY.minusDays(2), TODAY.minusDays(1)), null));

        Member member = fixtures.member(memberId);
        assertThat(response.currentStreak()).isEqualTo(3);
        assertThat(member.getCurrentStreak()).isEqualTo(3);
        assertThat(member.getLastCompletedOn()).isEqualTo(TODAY);
        assertThat(fixtures.count("streak_day", "member_id = ?", memberId)).isEqualTo(3);
    }

    @Test
    @DisplayName("끊긴 이력의 백필은 최근 연속만 센다")
    void 끊긴_이력은_최근_연속만_센다() {
        // 이 테스트가 죽으면: 재계산이 연속이 아니라 행 수를 세는 것이다. 중간에 끊긴 날이
        // 있어도 개수만 맞으면 연속으로 인정돼, 리셋(★D3)이 시드 데이터에서만 사라진다.
        Long memberId = fixtures.joinMember();
        completeToday(memberId);

        seedService.seed(new DevSessionSeedRequest(
                memberId, List.of(TODAY.minusDays(4), TODAY.minusDays(1)), null));

        Member member = fixtures.member(memberId);
        assertThat(member.getCurrentStreak()).isEqualTo(2);
        assertThat(member.getLastCompletedOn()).isEqualTo(TODAY);
        assertThat(fixtures.count("streak_day", "member_id = ?", memberId)).isEqualTo(3);
    }

    @Test
    @DisplayName("신규 회원의 오름차순 시드도 같은 값을 낸다")
    void 오름차순_시드는_연속을_그대로_센다() {
        // 이 테스트가 죽으면: 재계산이 정상 경로(증분이 이미 맞는 값을 낸 상태)를 덮으며
        // 값을 바꾼 것이다. 재계산은 증분의 결과를 보정하는 것이지 다른 답을 내면 안 된다.
        Long memberId = fixtures.joinMember();

        DevSessionSeedResponse response = seedService.seed(new DevSessionSeedRequest(
                memberId,
                List.of(TODAY.minusDays(2), TODAY.minusDays(1), TODAY), null));

        assertThat(response.currentStreak()).isEqualTo(3);
        assertThat(fixtures.member(memberId).getCurrentStreak()).isEqualTo(3);
        assertThat(fixtures.member(memberId).getLastCompletedOn()).isEqualTo(TODAY);
    }

    /** 오늘 완주를 B1과 같은 경로로 만든다. 백필 이전의 "이미 오늘 완주한 회원" 상태다. */
    private void completeToday(Long memberId) {
        fixtures.openSession(TARGET_MINUTES, TODAY.atTime(9, 0), List.of(memberId));
        clock.fixAt(TODAY.atTime(10, 30));
        sessionClosingBatch.run();
    }
}
