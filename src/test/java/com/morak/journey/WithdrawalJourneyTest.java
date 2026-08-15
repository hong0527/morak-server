package com.morak.journey;

import static org.assertj.core.api.Assertions.assertThat;

import com.morak.member.dto.response.MemberMeResponse;
import com.morak.member.dto.response.WithdrawalResponse;
import com.morak.member.service.MemberPurgeBatch;
import com.morak.member.type.MemberStatus;
import com.morak.session.service.SessionClosingBatch;
import com.morak.store.dto.request.OrderCreateRequest;
import com.morak.store.dto.response.OrderCreateResponse;
import com.morak.support.IntegrationTest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

/**
 * 여정 4 — 가입해서 활동하고, 탈퇴를 신청했다 무르고, 다시 신청해 끝내 파기된다.
 *
 * <p>탈퇴는 <b>되돌릴 수 있는 구간과 되돌릴 수 없는 구간</b>이 30일을 사이에 두고 붙어 있는
 * 유일한 흐름이다. 앞 구간에서 철회가 막히면 사용자는 실수 한 번으로 계정을 잃고, 뒤 구간에서
 * 파기가 새면 "지웠다"고 말한 개인정보가 남는다. 둘 다 사후에 고칠 수 없다.
 *
 * <p>파기의 계약은 "전부 지운다"가 아니라 <b>"개인 식별자만 지우고 거래 기록은 남긴다"</b>이다
 * (전자상거래법, db-schema 파기·보존 정책). 그래서 마지막 단계가 확인하는 것은 두 방향이다 —
 * 무엇이 사라졌는가와, 무엇이 남아 있는가.
 */
@DisplayName("여정 4 — 탈퇴와 파기")
class WithdrawalJourneyTest extends IntegrationTest {

    private static final int TARGET_MINUTES = 60;
    private static final int PRODUCT_PRICE = 500;

    @Autowired
    private SessionClosingBatch sessionClosingBatch;

    @Autowired
    private MemberPurgeBatch memberPurgeBatch;

    @Value("${morak.withdrawal.grace-days}")
    private int graceDays;

    @Value("${morak.point.welcome}")
    private int welcomePoint;

    @Value("${morak.point.session-complete-per-hour}")
    private int completePointPerHour;

    @Test
    @DisplayName("탈퇴 신청·철회·재신청·파기가 이어지고 파기 뒤에는 거래 기록만 남는다")
    void 탈퇴_여정() {
        // 이 테스트가 죽으면: 계정을 잃거나(철회가 막힘) 개인정보가 남는다(파기가 샘).
        // 어느 쪽도 사후에 고칠 수 없는 종류의 실패다.
        clock.fixAt(BASE_TIME);
        JourneySteps.Traveler me = JourneySteps.onboard(api, "journey4-leaver");
        Long memberId = me.memberId();
        Long productId = fixtures.createProduct(PRODUCT_PRICE, 10);

        // ── 1. 활동 ── 완주 2회와 주문 1건. 파기 때 남고 사라질 것들을 만들어 둔다
        completeSession(memberId, BASE_TIME);
        completeSession(memberId, BASE_TIME.plusDays(1));
        int sessionPoint = completePointPerHour * TARGET_MINUTES / 60;

        clock.fixAt(BASE_TIME.plusDays(1).plusHours(3));
        OrderCreateResponse order = api.post("/api/orders", token(memberId),
                new OrderCreateRequest(productId, 1, "journey4-order"), OrderCreateResponse.class);

        MemberMeResponse active = api.get("/api/members/me", token(memberId),
                MemberMeResponse.class);
        assertThat(active.memberStatus()).isEqualTo(MemberStatus.ACTIVE);
        assertThat(active.streak().current()).as("1단계: 이틀 연속 완주가 반영되지 않았다")
                .isEqualTo(2);
        assertThat(active.pointBalance())
                .isEqualTo(welcomePoint + sessionPoint * 2 - PRODUCT_PRICE);

        // ── 2. 탈퇴 신청 ── 30일 뒤 삭제 예정이 응답에 실린다
        WithdrawalResponse withdrawal = api.post("/api/members/me/withdrawal", token(memberId),
                null, WithdrawalResponse.class);
        assertThat(withdrawal.deleteScheduledAt())
                .as("2단계: 삭제 예정 시각이 유예 기간(%d일) 뒤가 아니다", graceDays)
                .isEqualTo(now().plusDays(graceDays));
        assertThat(fixtures.member(memberId).getStatus())
                .isEqualTo(MemberStatus.WITHDRAW_PENDING);

        // ── 3. 유예 중에는 조회 전용 API까지 닫힌다 ──
        // 유예는 "곧 사라질 계정"이라 참여와 소비를 가르지 않는다(§0-2 ② 게이트)
        assertThat(api.errorCodeOf("GET", "/api/members/me/points", token(memberId), null))
                .as("3단계: 유예 중인데 포인트 조회가 열려 있다").isEqualTo("WITHDRAWAL_PENDING");
        assertThat(api.errorCodeOf("GET", "/api/store/products", token(memberId), null))
                .as("3단계: 유예 중인데 스토어가 열려 있다").isEqualTo("WITHDRAWAL_PENDING");
        assertThat(api.errorCodeOf("POST", "/api/orders", token(memberId),
                new OrderCreateRequest(productId, 1, "journey4-blocked")))
                .as("3단계: 유예 중인데 주문이 된다 — 곧 사라질 계정이 포인트를 계속 쓴다")
                .isEqualTo("WITHDRAWAL_PENDING");
        // 자기 상태 확인만 남는다. 이것마저 막히면 무엇을 무를지도 볼 수 없다
        assertThat(api.get("/api/members/me", token(memberId), MemberMeResponse.class)
                .memberStatus()).as("3단계: 유예 중에 자기 상태도 볼 수 없다")
                .isEqualTo(MemberStatus.WITHDRAW_PENDING);

        // ── 4. 철회 ── 마음이 바뀌면 되돌릴 수 있다
        api.delete("/api/members/me/withdrawal", token(memberId));
        MemberMeResponse restored = api.get("/api/members/me", token(memberId),
                MemberMeResponse.class);
        assertThat(restored.memberStatus()).as("4단계: 철회했는데 계정이 살아나지 않았다")
                .isEqualTo(MemberStatus.ACTIVE);
        assertThat(restored.pointBalance())
                .as("4단계: 철회 뒤 포인트가 사라졌다")
                .isEqualTo(welcomePoint + sessionPoint * 2 - PRODUCT_PRICE);
        assertThat(restored.streak().current())
                .as("4단계: 철회 뒤 연속 기록이 사라졌다").isEqualTo(2);
        // 닫혔던 API가 다시 열린다
        api.getJson("/api/store/products", token(memberId));

        // ── 5. 다시 탈퇴 신청 ── 이번에는 무르지 않는다
        api.post("/api/members/me/withdrawal", token(memberId), null, WithdrawalResponse.class);
        LocalDateTime purgeAt = now().plusDays(graceDays).plusMinutes(1);

        // ── 6. 유예가 끝나기 전에는 파기되지 않는다 ──
        clock.fixAt(now().plusDays(graceDays).minusHours(1));
        memberPurgeBatch.run();
        assertThat(fixtures.member(memberId).getStatus())
                .as("6단계: 유예가 남았는데 파기됐다 — 되돌릴 수 있는 구간이 사라진다")
                .isEqualTo(MemberStatus.WITHDRAW_PENDING);

        // ── 7. 30일 경과 후 파기 ──
        clock.fixAt(purgeAt);
        memberPurgeBatch.run();

        // 사라진 것 — 개인 식별자와 활동 기록
        assertThat(fixtures.member(memberId).getStatus())
                .as("7단계: 유예가 지났는데 파기되지 않았다").isEqualTo(MemberStatus.DELETED);
        assertThat(fixtures.member(memberId).getNickname())
                .as("7단계: 닉네임이 익명화되지 않았다").isEqualTo("탈퇴회원");
        assertThat(fixtures.member(memberId).getBirthDate())
                .as("7단계: 생년월일이 남아 있다").isNull();
        assertThat(fixtures.member(memberId).getProviderUserId())
                .as("7단계: 소셜 식별자가 그대로 남아 있다 — 같은 계정을 되짚을 수 있다")
                .isEqualTo("deleted:" + memberId);
        assertThat(fixtures.count("streak_day", "member_id = ?", memberId))
                .as("7단계: 완주일 기록이 남아 있다 — 언제 공부했는지가 그대로다").isZero();
        assertThat(fixtures.count("member_agreement", "member_id = ?", memberId))
                .as("7단계: 약관 동의 기록이 남아 있다").isZero();
        assertThat(fixtures.count("media_consent", "member_id = ?", memberId))
                .as("7단계: 캠 분석 동의 기록이 남아 있다").isZero();

        // 남은 것 — 거래 기록. 전자상거래법상 보존 의무가 있다
        assertThat(fixtures.count("store_order", "id = ?", order.orderId()))
                .as("7단계: 주문 기록이 지워졌다 — 보존 의무가 있는 거래다").isEqualTo(1);
        assertThat(fixtures.ledgerSum(memberId))
                .as("7단계: 원장이 지워졌거나 잔액과 어긋난다 — 남은 줄의 balance_after가 무너진다")
                .isEqualTo(welcomePoint + sessionPoint * 2 - PRODUCT_PRICE);

        // ── 8. 파기된 계정으로는 다시 들어올 수 없다 ──
        // 토큰은 서명이 유효한 채로 최대 24시간 살아 있다. 상태로 막지 않으면 그동안 열려 있다
        assertThat(api.errorCodeOf("GET", "/api/members/me", token(memberId), null))
                .as("8단계: 파기된 계정의 토큰이 아직 통한다").isEqualTo("UNAUTHORIZED");

        // ── 9. 같은 소셜 계정으로 다시 가입하면 새 계정이다 ──
        // 영구 제재 이력자가 아니므로 재가입 자체는 막지 않는다. 다만 이전 계정과 이어지면
        // 파기가 무의미해지므로 완전히 새 회원이어야 한다
        JourneySteps.Traveler rejoined = JourneySteps.join(api, "journey4-leaver");
        assertThat(rejoined.memberId())
                .as("9단계: 재가입이 파기된 계정을 되살렸다").isNotEqualTo(memberId);
        assertThat(api.get("/api/members/me", rejoined.token(), MemberMeResponse.class)
                .pointBalance())
                .as("9단계: 새 계정이 이전 계정의 포인트를 물려받았다").isEqualTo(welcomePoint);
    }

    /** 하루치 세션을 열고 완주까지 닫는다. */
    private void completeSession(Long memberId, LocalDateTime startedAt) {
        clock.fixAt(startedAt);
        List<Long> seats = new ArrayList<>(fixtures.joinMembers(1));
        seats.addFirst(memberId);
        fixtures.openSession(TARGET_MINUTES, startedAt, seats);
        clock.fixAt(startedAt.plusMinutes(TARGET_MINUTES + 1));
        sessionClosingBatch.run();
    }

    /** 30일을 미는 여정이라 토큰은 그때그때 다시 받는다(유효 24시간). */
    private String token(Long memberId) {
        return fixtures.tokenOf(memberId);
    }
}
