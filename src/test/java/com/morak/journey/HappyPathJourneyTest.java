package com.morak.journey;

import static org.assertj.core.api.Assertions.assertThat;

import com.morak.auth.dto.request.AgreementItem;
import com.morak.auth.dto.request.LoginRequest;
import com.morak.auth.dto.response.LoginResponse;
import com.morak.match.dto.request.MatchRequestCreateRequest;
import com.morak.match.dto.response.MatchRequestResponse;
import com.morak.match.service.MatchService;
import com.morak.match.type.MatchRequestStatus;
import com.morak.member.dto.request.BirthDateRequest;
import com.morak.member.dto.request.GoalRequest;
import com.morak.member.dto.request.MediaConsentRequest;
import com.morak.member.dto.response.AgeVerificationResponse;
import com.morak.member.dto.response.GoalResponse;
import com.morak.member.dto.response.MemberMeResponse;
import com.morak.member.type.AgeVerification;
import com.morak.member.type.AgreementType;
import com.morak.member.type.GoalStatus;
import com.morak.member.type.SocialProvider;
import com.morak.session.dto.request.AbsenceEventRequest;
import com.morak.session.dto.request.SessionGoalRequest;
import com.morak.session.dto.response.AbsenceEventResponse;
import com.morak.session.dto.response.LivekitTokenResponse;
import com.morak.session.dto.response.SessionGoalResponse;
import com.morak.session.dto.response.SessionResultResponse;
import com.morak.session.service.SessionClosingBatch;
import com.morak.session.type.AbsenceEventType;
import com.morak.session.type.ParticipantStatus;
import com.morak.session.type.SessionStatus;
import com.morak.store.dto.request.OrderCreateRequest;
import com.morak.store.dto.response.OrderCreateResponse;
import com.morak.store.dto.response.OrderDetailResponse;
import com.morak.store.type.OrderStatus;
import com.morak.support.IntegrationTest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import tools.jackson.databind.JsonNode;

/**
 * 여정 1 — 가입부터 주문까지 한 사람이 쭉 이어서 간다.
 *
 * <p>기능 단위 테스트는 마디를 하나씩 본다. <b>이 파일이 보는 것은 마디가 아니라 사슬이다</b> —
 * 앞 응답에서 꺼낸 값이 다음 호출의 입력이 되도록 이어서, 중간 어디도 테스트만 아는 지름길로
 * 건너뛰지 않는다. 세션 번호는 매칭 폴링 응답에서 꺼내고, 주문할 상품은 목록 응답에서 고르고,
 * 주문 번호는 주문 응답에서 꺼낸다.
 *
 * <p><b>HTTP로 부르는 것이 핵심이다.</b> 이 여정의 순서를 강제하는 것이 인터셉터 게이트이기
 * 때문이다 — 생년월일을 넣기 전에는 매칭이 열리지 않고, 캠 분석 동의 전에는 세션 토큰이
 * 나오지 않는다. 서비스를 직접 부르면 그 순서가 지켜지는지 확인할 수 없고, 여정은 "이렇게도
 * 되더라"를 증명하는 데 그친다.
 *
 * <p>주인공만 HTTP로 가고 나머지 5명은 서비스로 채운다. 그 5명은 다른 클라이언트라 여기서
 * 확인할 것이 없고, 배치도 마찬가지로 서버가 스스로 도는 것이라 빈을 직접 부른다.
 */
@DisplayName("여정 1 — 가입부터 주문까지")
class HappyPathJourneyTest extends IntegrationTest {

    private static final String SOCIAL_CODE = "journey1-protagonist";
    private static final int TARGET_MINUTES = 60;
    private static final int GOAL_PERIOD_DAYS = 7;
    private static final int PRODUCT_PRICE = 900;

    @Autowired
    private MatchService matchService;

    @Autowired
    private SessionClosingBatch sessionClosingBatch;

    @Value("${morak.session.required-participants}")
    private int requiredParticipants;

    @Value("${morak.point.welcome}")
    private int welcomePoint;

    @Value("${morak.point.session-complete-per-hour}")
    private int completePointPerHour;

    @Test
    @DisplayName("가입·연령·동의·목표·매칭·세션·완주·주문이 한 줄로 이어진다")
    void 정상_여정() {
        // 이 테스트가 죽으면: 개별 기능은 멀쩡한데 사슬이 끊긴 것이다. 실패 메시지의
        // "여정이 여기서 끊겼다 — {메서드} {경로}"가 어느 마디인지 알려 준다.
        clock.fixAt(BASE_TIME);
        Long productId = fixtures.createProduct(PRODUCT_PRICE, 10);

        // ── 1. 가입 ── 약관에 동의하고 계정을 얻는다. 이 응답의 토큰이 이후 전부의 입력이다
        LoginResponse joined = api.post("/api/auth/login", null,
                new LoginRequest(SocialProvider.KAKAO, SOCIAL_CODE, mandatoryAgreements()),
                LoginResponse.class);
        String token = joined.accessToken();
        assertThat(joined.isNewMember())
                .as("1단계: 처음 온 계정인데 기존 회원으로 처리됐다").isTrue();
        assertThat(joined.needsBirthdate())
                .as("1단계: 소셜이 생년월일을 주지 않았으므로 AU-3으로 보내야 한다").isTrue();

        // 아직 연령 미확인이라 매칭은 닫혀 있다. 이 거절이 다음 단계가 필요한 이유다
        assertThat(api.errorCodeOf("POST", "/api/match-requests", token,
                new MatchRequestCreateRequest(TARGET_MINUTES)))
                .as("1단계: 연령 확인 전인데 매칭이 열려 있다 — ⑤ 게이트가 빠졌다")
                .isEqualTo("AGE_NOT_VERIFIED");

        // ── 2. 생년월일 ── 게이트를 여는 유일한 문
        AgeVerificationResponse verified = api.post("/api/members/me/birthdate", token,
                new BirthDateRequest(LocalDate.of(2000, 5, 20)), AgeVerificationResponse.class);
        assertThat(verified.ageVerification())
                .as("2단계: 생년월일을 넣었는데 확정되지 않았다")
                .isEqualTo(AgeVerification.VERIFIED);

        // ── 3. 캠 분석 동의 ── 세션 토큰(SS-2)이 이것을 본다
        api.post("/api/members/me/media-consent", token, new MediaConsentRequest(true));

        // ── 4. 목표 7일 설정 ──
        GoalResponse goal = api.put("/api/members/me/goal", token,
                new GoalRequest(GOAL_PERIOD_DAYS), GoalResponse.class);
        assertThat(goal.status()).as("4단계: 건 목표가 진행 중이 아니다")
                .isEqualTo(GoalStatus.ACTIVE);
        assertThat(goal.progressDays()).as("4단계: 완주 이력이 없는데 진행도가 0이 아니다")
                .isZero();

        // ── 5. 매칭 요청 ── 혼자는 성사되지 않는다. 대기 상태와 정원을 응답으로 확인한다
        MatchRequestResponse waiting = api.post("/api/match-requests", token,
                new MatchRequestCreateRequest(TARGET_MINUTES), MatchRequestResponse.class);
        assertThat(waiting.status()).as("5단계: 혼자 요청했는데 성사됐다")
                .isEqualTo(MatchRequestStatus.WAITING);
        assertThat(waiting.requiredCount()).isEqualTo(requiredParticipants);

        // ── 6. 나머지 5명이 채워 6인이 확정된다 ──
        fillQueue(requiredParticipants - 1);

        // ── 7. 폴링으로 성사를 안다 ── 세션 번호는 여기서 처음 손에 들어온다(§0-7)
        MatchRequestResponse matched =
                api.get("/api/match-requests/me", token, MatchRequestResponse.class);
        assertThat(matched.status())
                .as("7단계: 6명이 찼는데 폴링이 성사를 알려주지 않는다")
                .isEqualTo(MatchRequestStatus.MATCHED);
        Long sessionId = matched.sessionId();
        assertThat(sessionId).as("7단계: 성사됐는데 세션 번호가 없다 — 들어갈 방이 없다")
                .isNotNull();

        // ── 8. 세션 접속 토큰 ── 앞에서 꺼낸 세션 번호를 그대로 쓴다
        LivekitTokenResponse livekit = api.post(
                "/api/sessions/" + sessionId + "/token", token, null, LivekitTokenResponse.class);
        assertThat(livekit.token()).as("8단계: 접속 토큰이 비어 있다").isNotBlank();
        assertThat(livekit.roomName()).as("8단계: 방 이름이 비어 있다 — 어디로 붙을지 알 수 없다")
                .isNotBlank();

        // ── 9. 오늘 할 일 등록 ──
        SessionGoalResponse sessionGoal = api.put("/api/sessions/" + sessionId + "/goal", token,
                new SessionGoalRequest("자료구조 2단원"), SessionGoalResponse.class);
        assertThat(sessionGoal.goalText()).isEqualTo("자료구조 2단원");

        // ── 10. 자리비움 두 번 — 임계 아래라 경고가 붙지 않는다 ──
        // 잠깐 자리를 뜨는 것은 정상적인 사용이다. 여기서 경고가 붙으면 60분 세션을
        // 완주할 수 있는 사람이 없다
        reportAbsence(token, sessionId, 0, BASE_TIME.plusMinutes(10), 30);
        reportAbsence(token, sessionId, 2, BASE_TIME.plusMinutes(25), 45);

        // ── 11. 정시 종료 ── 서버가 스스로 도는 자리라 배치를 직접 부른다
        clock.fixAt(BASE_TIME.plusMinutes(TARGET_MINUTES + 1));
        sessionClosingBatch.run();

        // ── 12. 세션 결과 ── 완주·지급·Streak가 한 화면에 실린다
        SessionResultResponse result = api.get(
                "/api/sessions/" + sessionId + "/result", token, SessionResultResponse.class);
        assertThat(result.status()).as("12단계: 배치가 돌았는데 세션이 아직 진행 중이다")
                .isEqualTo(SessionStatus.ENDED);
        assertThat(result.my().completed())
                .as("12단계: 끝까지 남아 있었는데 완주로 집계되지 않았다").isTrue();
        assertThat(result.my().participantStatus()).isEqualTo(ParticipantStatus.ACTIVE);
        assertThat(result.my().warningCount())
                .as("12단계: 임계 아래 자리비움에 경고가 붙었다").isZero();
        int sessionPoint = completePointPerHour * TARGET_MINUTES / 60;
        assertThat(result.my().pointAwarded())
                .as("12단계: 완주 포인트가 target_minutes 기준으로 지급되지 않았다")
                .isEqualTo(sessionPoint);
        assertThat(result.my().streak().after())
                .as("12단계: 첫 완주인데 연속이 1이 아니다").isEqualTo(1);
        assertThat(result.my().streak().countedToday()).isTrue();

        // ── 13. 내 정보 ── 결과 화면의 값이 홈 화면에도 같게 반영됐는가
        MemberMeResponse me = api.get("/api/members/me", token, MemberMeResponse.class);
        assertThat(me.pointBalance())
                .as("13단계: 잔액이 웰컴+완주 합과 다르다 — 결과 화면과 홈이 어긋난다")
                .isEqualTo(welcomePoint + sessionPoint);
        assertThat(me.streak().current()).as("13단계: 홈의 연속이 1이 아니다").isEqualTo(1);
        assertThat(me.goal().progressDays())
                .as("13단계: 완주가 목표 진행에 반영되지 않았다").isEqualTo(1);
        assertThat(me.mediaConsented()).isTrue();
        assertThat(me.activeSession())
                .as("13단계: 끝난 세션이 아직 진행 중으로 실린다").isNull();

        // ── 14. 상품 목록 ── 살 물건을 여기서 고른다
        JsonNode products = api.getJson("/api/store/products", token);
        assertThat(products.path("content").size())
                .as("14단계: 판매 중인 상품이 목록에 없다").isPositive();
        Long pickedProductId = products.path("content").get(0).path("productId").asLong();
        assertThat(pickedProductId)
                .as("14단계: 목록이 준비한 상품과 다른 것을 준다").isEqualTo(productId);

        // ── 15. 주문 ── 완주로 번 포인트로 결제한다
        int balanceBeforeOrder = me.pointBalance();
        OrderCreateResponse order = api.post("/api/orders", token,
                new OrderCreateRequest(pickedProductId, 1, "journey1-order-key"),
                OrderCreateResponse.class);
        assertThat(order.status()).isEqualTo(OrderStatus.ORDERED);
        assertThat(order.pointAmount()).isEqualTo(PRODUCT_PRICE);
        assertThat(order.pointBalance())
                .as("15단계: 주문 뒤 잔액이 결제액만큼 줄지 않았다")
                .isEqualTo(balanceBeforeOrder - PRODUCT_PRICE);

        // ── 16. 주문 내역 ── 주문 응답의 번호로 되짚는다
        OrderDetailResponse detail = api.get(
                "/api/orders/" + order.orderId(), token, OrderDetailResponse.class);
        assertThat(detail.orderId()).isEqualTo(order.orderId());
        assertThat(detail.productId()).isEqualTo(pickedProductId);
        assertThat(detail.pointLedgerId())
                .as("16단계: 주문에 결제 원장이 연결되지 않았다 — 무엇으로 샀는지 되짚을 수 없다")
                .isNotNull();

        JsonNode myOrders = api.getJson("/api/orders", token);
        assertThat(myOrders.path("totalElements").asInt())
                .as("16단계: 방금 한 주문이 내 주문 목록에 없다").isEqualTo(1);

        // ── 마무리 ── 원장이 여정 전체의 회계와 맞는가
        assertThat(fixtures.ledgerSum(joined.memberId()))
                .as("마무리: 원장 합(웰컴+완주-주문)이 잔액과 어긋난다")
                .isEqualTo(welcomePoint + sessionPoint - PRODUCT_PRICE);
    }

    /** 주인공을 뺀 나머지 대기자. 마지막 한 명이 들어오는 순간 6인이 확정된다. */
    private void fillQueue(int count) {
        for (Long memberId : fixtures.joinMembers(count)) {
            matchService.request(memberId, new MatchRequestCreateRequest(TARGET_MINUTES));
        }
    }

    /** 자리를 비웠다가 {@code seconds} 뒤에 돌아온다. 클라이언트가 START·END를 짝으로 보낸다. */
    private void reportAbsence(String token, Long sessionId, long firstSeq,
                               LocalDateTime startedAt, long seconds) {
        clock.fixAt(startedAt);
        api.post("/api/sessions/" + sessionId + "/absence-events", token,
                new AbsenceEventRequest(AbsenceEventType.START, firstSeq,
                        startedAt.atZone(clock.getZone()).toOffsetDateTime()),
                AbsenceEventResponse.class);

        LocalDateTime endedAt = startedAt.plusSeconds(seconds);
        clock.fixAt(endedAt);
        AbsenceEventResponse closed = api.post("/api/sessions/" + sessionId + "/absence-events",
                token, new AbsenceEventRequest(AbsenceEventType.END, firstSeq + 1,
                        endedAt.atZone(clock.getZone()).toOffsetDateTime()),
                AbsenceEventResponse.class);
        assertThat(closed.warningCount())
                .as("10단계: %d초 자리비움에 경고가 붙었다 — 임계는 60초다", seconds)
                .isZero();
        assertThat(closed.evicted()).isFalse();
    }

    private List<AgreementItem> mandatoryAgreements() {
        return List.of(new AgreementItem(AgreementType.TOS, true),
                new AgreementItem(AgreementType.PRIVACY, true));
    }
}
