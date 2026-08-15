package com.morak.journey;

import static org.assertj.core.api.Assertions.assertThat;

import com.morak.match.dto.request.MatchRequestCreateRequest;
import com.morak.match.dto.response.MatchRequestResponse;
import com.morak.match.service.MatchService;
import com.morak.match.type.MatchRequestStatus;
import com.morak.member.dto.response.MemberMeResponse;
import com.morak.session.dto.request.AbsenceEventRequest;
import com.morak.session.dto.request.AppealCreateRequest;
import com.morak.session.dto.request.AppealProcessRequest;
import com.morak.session.dto.response.AppealCreateResponse;
import com.morak.session.dto.response.AppealProcessResponse;
import com.morak.session.dto.response.SessionResultResponse;
import com.morak.session.service.SessionClosingBatch;
import com.morak.session.type.AbsenceEventType;
import com.morak.session.type.AppealStatus;
import com.morak.session.type.ParticipantStatus;
import com.morak.support.IntegrationTest;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import tools.jackson.databind.JsonNode;

/**
 * 여정 2 — 퇴출당하고, 이의를 넣고, 인용받아 제자리로 돌아온다.
 *
 * <p>이 여정이 여정 1보다 중요하다. <b>여기가 잘못 돌면 사용자는 하지 않은 일로 벌을 받고 그
 * 상태에서 빠져나올 수 없다.</b> 이의 기한은 3일이고(AP-1), 그 사이 별개의 제재가 걸려도
 * 이의 창구는 열려 있어야 하며(§0-3 ④ 예외), 인용되면 차감·퇴출·미완주가 한꺼번에 되돌아가야
 * 한다. 마디 하나만 어긋나도 구제가 성립하지 않는다.
 *
 * <p>특히 확인하는 것 하나 — <b>인용은 재매칭 쿨다운까지 함께 푼다.</b> 퇴출이 취소됐는데
 * 30분 동안 매칭이 막혀 있으면, 잘못된 퇴출의 대가를 여전히 사용자가 치른다.
 */
@DisplayName("여정 2 — 퇴출과 구제")
class EvictionAppealJourneyTest extends IntegrationTest {

    private static final int TARGET_MINUTES = 60;
    private static final String APPEAL_REASON = "자리에 있었습니다. 카메라 각도가 틀어져 있었습니다.";

    @Autowired
    private MatchService matchService;

    @Autowired
    private SessionClosingBatch sessionClosingBatch;

    @Value("${morak.session.required-participants}")
    private int requiredParticipants;

    @Value("${morak.session.absence-threshold-seconds}")
    private int absenceThresholdSeconds;

    @Value("${morak.session.evict-warning-count}")
    private int evictWarningCount;

    @Value("${morak.point.eviction-penalty}")
    private int evictionPenalty;

    @Value("${morak.point.session-complete-per-hour}")
    private int completePointPerHour;

    @Test
    @DisplayName("퇴출·차감·쿨다운이 이의 인용으로 한꺼번에 되돌아간다")
    void 퇴출과_구제_여정() {
        // 이 테스트가 죽으면: 잘못된 퇴출을 되돌릴 길이 끊긴 것이다. 어느 마디가 끊겼는지는
        // 실패 메시지의 단계 번호가 알려 준다.
        clock.fixAt(BASE_TIME);
        JourneySteps.Traveler me = JourneySteps.onboard(api, "journey2-evicted");
        JourneySteps.Traveler admin = JourneySteps.onboard(api, "journey2-admin");
        fixtures.execute("UPDATE member SET role = 'ADMIN' WHERE id = ?", admin.memberId());

        int balanceBefore = api.get("/api/members/me", me.token(), MemberMeResponse.class)
                .pointBalance();

        // ── 1. 매칭·세션 진입 ──
        api.post("/api/match-requests", me.token(),
                new MatchRequestCreateRequest(TARGET_MINUTES), MatchRequestResponse.class);
        fillQueue(requiredParticipants - 1);
        Long sessionId = api.get("/api/match-requests/me", me.token(), MatchRequestResponse.class)
                .sessionId();
        assertThat(sessionId).as("1단계: 6명이 찼는데 세션이 열리지 않았다").isNotNull();
        api.post("/api/sessions/" + sessionId + "/token", me.token(), null, JsonNode.class);

        // ── 2. 자리비움 3회로 퇴출 ── 마지막 응답이 퇴출과 차감을 함께 알려 준다(§0-7)
        Long evictionId = null;
        for (int round = 0; round < evictWarningCount; round++) {
            JsonNode closed = absenceRound(me.token(), sessionId, round);
            int expectedWarnings = round + 1;
            assertThat(closed.path("warningCount").asInt())
                    .as("2단계: %d번째 임계 초과인데 경고가 %d회가 아니다", expectedWarnings,
                            expectedWarnings)
                    .isEqualTo(expectedWarnings);
            if (expectedWarnings == evictWarningCount) {
                assertThat(closed.path("evicted").asBoolean())
                        .as("2단계: 경고 %d회인데 퇴출되지 않았다", evictWarningCount).isTrue();
                assertThat(closed.path("pointDelta").asInt())
                        .as("2단계: 퇴출 응답이 차감액을 싣지 않는다 — 화면이 사실이 아닌 값을 그린다")
                        .isEqualTo(-evictionPenalty);
                evictionId = closed.path("evictionId").asLong();
            } else {
                assertThat(closed.path("evicted").asBoolean())
                        .as("2단계: 경고 %d회에 퇴출됐다 — 기준은 %d회다", expectedWarnings,
                                evictWarningCount).isFalse();
            }
        }
        assertThat(evictionId)
                .as("2단계: 퇴출 응답에 퇴출 번호가 없다 — 이의를 넣을 진입점이 사라진다")
                .isNotNull();
        assertThat(api.get("/api/members/me", me.token(), MemberMeResponse.class).pointBalance())
                .as("2단계: 차감이 원장까지 닿지 않았다 — 응답과 잔액이 어긋난다")
                .isEqualTo(balanceBefore - evictionPenalty);

        // ── 3. 곧바로 재매칭하면 쿨다운에 막힌다 ──
        assertThat(api.errorCodeOf("POST", "/api/match-requests", me.token(),
                new MatchRequestCreateRequest(TARGET_MINUTES)))
                .as("3단계: 퇴출 직후인데 재매칭이 열려 있다 — 쿨다운(D14)이 빠졌다")
                .isEqualTo("REMATCH_COOLDOWN");

        // ── 4. 이의 신청 ── 퇴출 응답에서 꺼낸 번호가 그대로 경로가 된다
        clock.fixAt(BASE_TIME.plusMinutes(30));
        AppealCreateResponse appeal = api.post("/api/evictions/" + evictionId + "/appeals",
                me.token(), new AppealCreateRequest(APPEAL_REASON), AppealCreateResponse.class);
        assertThat(appeal.status()).as("4단계: 접수된 이의가 심사 대기가 아니다")
                .isEqualTo(AppealStatus.PENDING);
        assertThat(appeal.evictionId()).isEqualTo(evictionId);

        // ── 5. 세션이 아직 진행 중이면 인용할 수 없다 ──
        // 완주 소급이 성립하지 않는 시점이라, 여기서 인용하면 퇴출만 풀리고 완주는 영영 안 돌아온다
        assertThat(api.errorCodeOf("PATCH", "/api/admin/appeals/" + appeal.appealId(),
                admin.token(), new AppealProcessRequest(AppealStatus.ACCEPTED, "각도 확인")))
                .as("5단계: 진행 중인 세션의 퇴출이 인용됐다 — 완주 소급이 빠진 채 종결된다")
                .isEqualTo("SESSION_NOT_ENDED");

        // ── 6. 세션 종료 ──
        clock.fixAt(BASE_TIME.plusMinutes(TARGET_MINUTES + 1));
        sessionClosingBatch.run();

        // ── 7. 관리자 인용 ── 환급과 완주 소급이 한 응답에 실린다
        AppealProcessResponse processed = api.patch(
                "/api/admin/appeals/" + appeal.appealId(), admin.token(),
                new AppealProcessRequest(AppealStatus.ACCEPTED, "각도 문제 확인"),
                AppealProcessResponse.class);
        assertThat(processed.status()).isEqualTo(AppealStatus.ACCEPTED);
        assertThat(processed.pointRefunded())
                .as("7단계: 인용했는데 차감이 환급되지 않았다").isEqualTo(evictionPenalty);
        assertThat(processed.sessionCompletedRestored())
                .as("7단계: 인용했는데 완주가 소급되지 않았다").isTrue();

        // ── 8. 잔액과 세션 결과가 제자리로 ──
        // 환급만으로 끝나지 않는다. 완주 소급이 그 세션의 완주 지급까지 함께 성립시키므로,
        // 잘못 퇴출당하지 않았다면 받았을 금액이 정확히 손에 남아야 한다 —
        // 웰컴 + 완주 지급이고, 차감 -300과 환급 +300은 원장에 흔적으로만 남는다
        int sessionPoint = completePointPerHour * TARGET_MINUTES / 60;
        MemberMeResponse restored =
                api.get("/api/members/me", me.token(), MemberMeResponse.class);
        assertThat(restored.pointBalance())
                .as("8단계: 인용 뒤 잔액이 '퇴출당하지 않았다면'의 값과 다르다")
                .isEqualTo(balanceBefore + sessionPoint);
        assertThat(fixtures.ledgerSum(me.memberId()))
                .as("8단계: 잔액 캐시와 원장 합이 어긋난다")
                .isEqualTo(balanceBefore + sessionPoint);
        assertThat(restored.streak().current())
                .as("8단계: 완주가 소급됐는데 연속에 반영되지 않았다").isEqualTo(1);

        SessionResultResponse result = api.get("/api/sessions/" + sessionId + "/result",
                me.token(), SessionResultResponse.class);
        assertThat(result.my().completed())
                .as("8단계: 완주가 소급됐는데 세션 결과는 여전히 미완주다").isTrue();
        assertThat(result.my().pointAwarded())
                .as("8단계: 완주로 소급됐는데 지급액이 결과에 실리지 않는다")
                .isEqualTo(sessionPoint);
        // 참가자 상태는 EVICTED로 남는 것이 의도다(명세 AD-6) — 취소된 사실은 상태가 아니라
        // eviction.revoked_at이 표현한다. 상태를 되돌리면 그 세션에 퇴출이 있었다는 감사
        // 기록이 참가자 행에서 사라지기 때문이다
        assertThat(result.my().participantStatus())
                .as("8단계: 퇴출 이력이 참가자 행에서 지워졌다 — 감사 기록이 사라진다")
                .isEqualTo(ParticipantStatus.EVICTED);
        assertThat(fixtures.count("eviction", "id = ? AND revoked_at IS NOT NULL", evictionId))
                .as("8단계: 인용했는데 퇴출이 취소로 기록되지 않았다").isEqualTo(1);

        // ── 9. 재매칭이 다시 열린다 ── 인용이 쿨다운까지 푼다
        MatchRequestResponse rematch = api.post("/api/match-requests", me.token(),
                new MatchRequestCreateRequest(TARGET_MINUTES), MatchRequestResponse.class);
        assertThat(rematch.status())
                .as("9단계: 인용됐는데 재매칭이 여전히 막혀 있다 — 잘못된 퇴출의 대가를 계속 치른다")
                .isEqualTo(MatchRequestStatus.WAITING);

        // ── 10. 내 이의 목록에서 결과를 확인한다 ── 당사자가 결과를 볼 유일한 창구
        JsonNode myAppeals = api.getJson("/api/members/me/appeals", me.token());
        assertThat(myAppeals.path("totalElements").asInt())
                .as("10단계: 넣은 이의가 내 목록에 없다").isEqualTo(1);
        JsonNode mine = myAppeals.path("content").get(0);
        assertThat(mine.path("status").asText()).isEqualTo("ACCEPTED");
        assertThat(mine.path("pointRefunded").asInt())
                .as("10단계: 결과 화면에 환급액이 실리지 않는다").isEqualTo(evictionPenalty);
        assertThat(mine.path("sessionCompletedRestored").asBoolean()).isTrue();
        assertThat(mine.path("reasonText").asText()).isEqualTo(APPEAL_REASON);
    }

    /** 임계를 넘긴 자리비움 한 번. 반환은 END 응답이다. */
    private JsonNode absenceRound(String token, Long sessionId, int round) {
        LocalDateTime startedAt = BASE_TIME.plusMinutes(5L + round * 3L);
        LocalDateTime endedAt = startedAt.plusSeconds(absenceThresholdSeconds + 5L);
        clock.fixAt(startedAt);
        api.post("/api/sessions/" + sessionId + "/absence-events", token,
                new AbsenceEventRequest(AbsenceEventType.START, round * 2L,
                        startedAt.atZone(clock.getZone()).toOffsetDateTime()), JsonNode.class);
        clock.fixAt(endedAt);
        return api.post("/api/sessions/" + sessionId + "/absence-events", token,
                new AbsenceEventRequest(AbsenceEventType.END, round * 2L + 1,
                        endedAt.atZone(clock.getZone()).toOffsetDateTime()), JsonNode.class);
    }

    private void fillQueue(int count) {
        for (Long memberId : fixtures.joinMembers(count)) {
            matchService.request(memberId, new MatchRequestCreateRequest(TARGET_MINUTES));
        }
    }

}
