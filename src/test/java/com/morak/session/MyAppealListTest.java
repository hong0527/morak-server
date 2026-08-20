package com.morak.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.morak.auth.dto.request.AgreementItem;
import com.morak.auth.dto.request.LoginRequest;
import com.morak.auth.service.AuthService;
import com.morak.common.dto.PageResponse;
import com.morak.member.type.AgreementType;
import com.morak.member.type.SocialProvider;
import com.morak.report.dto.request.SanctionCommand;
import com.morak.report.service.SanctionService;
import com.morak.report.type.SanctionType;
import com.morak.session.dto.request.AbsenceEventRequest;
import com.morak.session.dto.request.AppealCreateRequest;
import com.morak.session.dto.request.AppealProcessRequest;
import com.morak.session.dto.response.MyAppealResponse;
import com.morak.session.service.AbsenceJudgeService;
import com.morak.session.service.AppealAdminService;
import com.morak.session.service.AppealService;
import com.morak.session.service.SessionClosingBatch;
import com.morak.session.type.AbsenceEventType;
import com.morak.session.type.AppealStatus;
import com.morak.support.IntegrationTest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

/**
 * AP-2 내 이의 목록. 인용·기각은 관리자 화면(AD-6)에서 일어나는데, 그 결과를 당사자가 볼
 * 유일한 자리가 이 목록이다 — 없으면 사용자는 포인트 내역의 환급 줄로 결과를 역추적해야 한다.
 *
 * <p>확인하는 것은 셋이다. 상태별로 결과 필드가 계약대로 채워지고 비는가, 남의 이의가 내
 * 목록에 섞이지 않는가, 제재 중에도 결과를 볼 수 있는가(신청 AP-1과 같은 게이트 예외).
 */
@DisplayName("내 이의 목록")
class MyAppealListTest extends IntegrationTest {

    private static final int TARGET_MINUTES = 60;
    private static final int PARTICIPANTS = 6;
    private static final Long ADMIN_ID = 1L;
    private static final String REASON = "자리에 있었습니다. 카메라 각도가 틀어져 있었습니다.";

    @Autowired
    private AbsenceJudgeService absenceJudgeService;

    @Autowired
    private AppealService appealService;

    @Autowired
    private AppealAdminService appealAdminService;

    @Autowired
    private SessionClosingBatch sessionClosingBatch;

    @Autowired
    private SanctionService sanctionService;

    @Autowired
    private AuthService authService;

    @Autowired
    private MockMvc mockMvc;

    @Value("${morak.session.absence-threshold-seconds}")
    private int absenceThresholdSeconds;

    @Value("${morak.session.evict-warning-count}")
    private int evictWarningCount;

    @Value("${morak.point.eviction-penalty}")
    private int evictionPenalty;

    @Test
    @DisplayName("심사 중·인용·기각이 각자의 결과 필드로 실린다")
    void 세_상태의_결과_필드가_계약대로_실린다() {
        // 이 테스트가 죽으면: 상태별 null 계약이 깨진 것이다. PENDING에 결과 필드가 채워지면
        // 프론트가 심사 중인 이의를 처리된 것으로 그리고, ACCEPTED에 원복 값이 비면 인용을
        // 받고도 무엇이 돌아왔는지 알 수 없다.
        Long memberId = fixtures.joinMember("ap2-me");

        Long pendingSession = evict(memberId, BASE_TIME);
        Long pendingAppealId = file(memberId, pendingSession, BASE_TIME.plusHours(1));

        Long acceptedSession = evict(memberId, BASE_TIME.plusHours(2));
        Long acceptedAppealId = file(memberId, acceptedSession, BASE_TIME.plusHours(3));
        clock.fixAt(BASE_TIME.plusHours(2).plusMinutes(TARGET_MINUTES + 1));
        sessionClosingBatch.run();
        appealAdminService.process(ADMIN_ID, acceptedAppealId,
                new AppealProcessRequest(AppealStatus.ACCEPTED, "각도 문제 확인"));

        Long rejectedSession = evict(memberId, BASE_TIME.plusHours(4));
        Long rejectedAppealId = file(memberId, rejectedSession, BASE_TIME.plusHours(5));
        appealAdminService.process(ADMIN_ID, rejectedAppealId,
                new AppealProcessRequest(AppealStatus.REJECTED, "구간 근거가 명확함"));

        PageResponse<MyAppealResponse> response =
                appealService.getMyAppeals(memberId, null, null);

        assertThat(response.totalElements()).isEqualTo(3);
        // 최근 접수 순 — 기각(마지막 접수)이 맨 위다
        assertThat(response.content())
                .extracting(MyAppealResponse::appealId)
                .containsExactly(rejectedAppealId, acceptedAppealId, pendingAppealId);

        MyAppealResponse rejected = response.content().get(0);
        assertThat(rejected.status()).isEqualTo(AppealStatus.REJECTED);
        assertThat(rejected.sessionId()).isEqualTo(rejectedSession);
        assertThat(rejected.decidedAt()).isNotNull();
        assertThat(rejected.pointRefunded()).isNull();
        assertThat(rejected.sessionCompletedRestored()).isNull();

        MyAppealResponse accepted = response.content().get(1);
        assertThat(accepted.status()).isEqualTo(AppealStatus.ACCEPTED);
        assertThat(accepted.sessionId()).isEqualTo(acceptedSession);
        assertThat(accepted.decidedAt()).isNotNull();
        assertThat(accepted.pointRefunded()).isEqualTo(evictionPenalty);
        assertThat(accepted.sessionCompletedRestored()).isTrue();

        MyAppealResponse pending = response.content().get(2);
        assertThat(pending.status()).isEqualTo(AppealStatus.PENDING);
        assertThat(pending.sessionId()).isEqualTo(pendingSession);
        assertThat(pending.evictedAt()).isNotNull();
        assertThat(pending.pointPenalty()).isEqualTo(evictionPenalty);
        assertThat(pending.slaDueAt()).isNotNull();
        assertThat(pending.decidedAt()).isNull();
        assertThat(pending.pointRefunded()).isNull();
        assertThat(pending.sessionCompletedRestored()).isNull();
    }

    @Test
    @DisplayName("같은 세션에서 함께 퇴출된 남의 이의는 내 목록에 없다")
    void 남의_이의는_섞이지_않는다() {
        // 이 테스트가 죽으면: 조회가 본인 스코프를 벗어난 것이다. 같은 세션에 있었다는 이유로
        // 남의 이의 번호·진술이 노출된다.
        List<Long> memberIds = fixtures.joinMembers(PARTICIPANTS);
        Long sessionId = fixtures.openSession(TARGET_MINUTES, BASE_TIME, memberIds);
        Long mine = memberIds.getFirst();
        Long other = memberIds.get(1);
        evictInSession(sessionId, mine, BASE_TIME.plusMinutes(1));
        evictInSession(sessionId, other, BASE_TIME.plusMinutes(20));
        Long myAppealId = file(mine, sessionId, BASE_TIME.plusHours(1));
        file(other, sessionId, BASE_TIME.plusHours(1).plusMinutes(1));

        PageResponse<MyAppealResponse> response = appealService.getMyAppeals(mine, null, null);

        assertThat(response.totalElements()).isEqualTo(1);
        assertThat(response.content().getFirst().appealId()).isEqualTo(myAppealId);
    }

    @Test
    @DisplayName("제재 중에도 이의 결과는 볼 수 있다")
    void 제재가_결과_확인을_막지_않는다() throws Exception {
        // 이 테스트가 죽으면: ④ 게이트 예외가 이 경로만 비켜 간 것이다. 신청(AP-1)은 열려
        // 있는데 결과 조회가 닫히면, 제재 중인 회원에게 이의는 넣고 답은 못 받는 반쪽 창구가 된다.
        Long memberId = fixtures.joinMember("ap2-sanctioned");
        // AP-2는 연령 게이트(⑤)를 통과해야 한다 — 여기서 확인하려는 예외는 ④ 제재뿐이다
        fixtures.execute("UPDATE member SET age_verification = 'VERIFIED' WHERE id = ?", memberId);
        Long sessionId = evict(memberId, BASE_TIME);
        file(memberId, sessionId, BASE_TIME.plusHours(1));
        sanctionService.apply(memberId, null, new SanctionCommand(SanctionType.TEMP, 3), ADMIN_ID);

        mockMvc.perform(get("/api/members/me/appeals")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + loginToken("ap2-sanctioned")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].reasonText").value(REASON));
    }

    /** 새 세션을 열고 자리비움 경고 3회로 퇴출시킨다. 반환은 세션 번호다. */
    private Long evict(Long memberId, LocalDateTime sessionStart) {
        List<Long> memberIds = new ArrayList<>(fixtures.joinMembers(PARTICIPANTS - 1));
        memberIds.addFirst(memberId);
        Long sessionId = fixtures.openSession(TARGET_MINUTES, sessionStart, memberIds);
        evictInSession(sessionId, memberId, sessionStart.plusMinutes(1));
        return sessionId;
    }

    private void evictInSession(Long sessionId, Long memberId, LocalDateTime firstStart) {
        for (int round = 0; round < evictWarningCount; round++) {
            LocalDateTime startedAt = firstStart.plusMinutes(round * 3L);
            LocalDateTime endedAt = startedAt.plusSeconds(absenceThresholdSeconds + 5L);
            report(memberId, sessionId, AbsenceEventType.START, round * 2L, startedAt);
            report(memberId, sessionId, AbsenceEventType.END, round * 2L + 1, endedAt);
        }
    }

    private Long file(Long memberId, Long sessionId, LocalDateTime at) {
        clock.fixAt(at);
        return appealService.file(memberId, fixtures.evictionId(sessionId, memberId),
                new AppealCreateRequest(REASON)).appealId();
    }

    private void report(Long memberId, Long sessionId, AbsenceEventType type, long clientSeq,
                        LocalDateTime occurredAt) {
        clock.fixAt(occurredAt);
        absenceJudgeService.report(memberId, sessionId, new AbsenceEventRequest(
                type, clientSeq, occurredAt.atZone(clock.getZone()).toOffsetDateTime()));
    }

    private String loginToken(String authorizationCode) {
        return authService.login(new LoginRequest(
                        SocialProvider.DEV, authorizationCode,
                        List.of(new AgreementItem(AgreementType.TOS, true),
                                new AgreementItem(AgreementType.PRIVACY, true))))
                .accessToken();
    }
}
