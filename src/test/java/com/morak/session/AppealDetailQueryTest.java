package com.morak.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.morak.auth.dto.request.AgreementItem;
import com.morak.auth.dto.request.LoginRequest;
import com.morak.auth.dto.response.LoginResponse;
import com.morak.auth.service.AuthService;
import com.morak.common.error.BusinessException;
import com.morak.common.error.ErrorCode;
import com.morak.member.type.AgreementType;
import com.morak.member.type.SocialProvider;
import com.morak.session.dto.request.AbsenceEventRequest;
import com.morak.session.dto.request.AppealCreateRequest;
import com.morak.session.dto.response.AppealDetailResponse;
import com.morak.session.service.AbsenceJudgeService;
import com.morak.session.service.AppealAdminService;
import com.morak.session.service.AppealService;
import com.morak.session.service.PauseService;
import com.morak.session.type.AbsenceEventType;
import com.morak.session.type.AppealStatus;
import com.morak.session.type.WarningBasis;
import com.morak.support.IntegrationTest;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 이의 심사 상세(AD-9)가 관리자에게 무엇을 보여 주는지 본다.
 *
 * <p>이의는 AI 오탐(NFR-301)의 유일한 구제 창구인데, 당사자 진술과 경고의 근거 구간이
 * 응답 어디에도 실리지 않으면 심사자는 큐의 메타데이터만 보고 인용·기각을 골라야 한다.
 * 여기서 확인하는 것은 둘이다 — 심사 재료가 판정과 같은 규칙으로 되짚어져 실리는가,
 * 그 과정에서 같은 세션 다른 참가자의 식별자가 새지 않는가.
 */
@DisplayName("이의 심사 상세")
class AppealDetailQueryTest extends IntegrationTest {

    private static final int TARGET_MINUTES = 60;
    private static final int PARTICIPANTS = 6;
    private static final String REASON = "자리에 있었습니다. 카메라 각도가 틀어져 있었습니다.";

    @Autowired
    private AbsenceJudgeService absenceJudgeService;

    @Autowired
    private PauseService pauseService;

    @Autowired
    private AppealService appealService;

    @Autowired
    private AppealAdminService appealAdminService;

    @Autowired
    private AuthService authService;

    @Autowired
    private MockMvc mockMvc;

    @Value("${morak.session.absence-threshold-seconds}")
    private int absenceThresholdSeconds;

    @Value("${morak.session.pause-limit-minutes}")
    private int pauseLimitMinutes;

    @Value("${morak.point.eviction-penalty}")
    private int evictionPenalty;

    @Test
    @DisplayName("경고의 근거 구간·보고 지연·동시 보고 집계가 판정과 같은 규칙으로 실린다")
    void 경고_근거가_판정_그대로_실린다() {
        // 이 테스트가 죽으면: 심사자가 보는 구간이 판정에 쓰인 구간과 다른 것이다. 오탐을
        // 다투는 자리에서 다른 증거를 보여 주면 인용도 기각도 근거가 없다.
        List<Long> memberIds = fixtures.joinMembers(PARTICIPANTS);
        Long sessionId = fixtures.openSession(TARGET_MINUTES, BASE_TIME, memberIds);
        Long memberId = memberIds.getFirst();
        Long witnessId = memberIds.get(1);

        // 경고 1: 65초 자리비움. END는 관측보다 2초 늦게 도착한다(전송 지연)
        LocalDateTime start1 = BASE_TIME.plusMinutes(1);
        LocalDateTime end1 = start1.plusSeconds(absenceThresholdSeconds + 5L);
        report(memberId, sessionId, AbsenceEventType.START, 0, start1, start1);
        // 같은 구간에 다른 참가자도 미검출을 보고한다 — 집계 신호의 재료
        report(witnessId, sessionId, AbsenceEventType.START, 0,
                start1.plusSeconds(10), start1.plusSeconds(10));
        report(witnessId, sessionId, AbsenceEventType.END, 1,
                start1.plusSeconds(40), start1.plusSeconds(40));
        report(memberId, sessionId, AbsenceEventType.END, 1, end1, end1.plusSeconds(2));

        // 경고 2: 지연 없는 65초 구간
        LocalDateTime start2 = BASE_TIME.plusMinutes(4);
        LocalDateTime end2 = start2.plusSeconds(absenceThresholdSeconds + 5L);
        report(memberId, sessionId, AbsenceEventType.START, 2, start2, start2);
        report(memberId, sessionId, AbsenceEventType.END, 3, end2, end2);

        // 경고 3: Pause 초과 복귀(D9). 근거 이벤트가 없는 경고이고, 3회째라 퇴출이다
        LocalDateTime pausedAt = BASE_TIME.plusMinutes(10);
        LocalDateTime resumedAt = pausedAt.plusMinutes(pauseLimitMinutes + 1L);
        clock.fixAt(pausedAt);
        pauseService.start(memberId, sessionId);
        clock.fixAt(resumedAt);
        pauseService.resume(memberId, sessionId);

        Long appealId = appealService.file(memberId, fixtures.evictionId(sessionId, memberId),
                new AppealCreateRequest(REASON)).appealId();

        AppealDetailResponse detail = appealAdminService.getAppeal(appealId);

        assertThat(detail.reasonText()).isEqualTo(REASON);
        assertThat(detail.memberId()).isEqualTo(memberId);
        assertThat(detail.status()).isEqualTo(AppealStatus.PENDING);
        assertThat(detail.nickname()).isNotBlank();
        assertThat(detail.eviction().sessionId()).isEqualTo(sessionId);
        assertThat(detail.eviction().evictedAt()).isEqualTo(resumedAt);
        assertThat(detail.eviction().warningCount()).isEqualTo(3);
        assertThat(detail.eviction().pointPenalty()).isEqualTo(evictionPenalty);
        assertThat(detail.eviction().revokedAt()).isNull();
        assertThat(detail.sessionParticipantCount()).isEqualTo(PARTICIPANTS);

        assertThat(detail.warnings()).hasSize(3);
        assertThat(detail.warnings()).extracting(AppealDetailResponse.WarningItem::seq)
                .containsExactly(1, 2, 3);

        AppealDetailResponse.WarningItem first = detail.warnings().getFirst();
        assertThat(first.basis()).isEqualTo(WarningBasis.ABSENCE);
        assertThat(first.absenceStartedAt()).isEqualTo(start1);
        assertThat(first.absenceEndedAt()).isEqualTo(end1);
        assertThat(first.absentSeconds()).isEqualTo(absenceThresholdSeconds + 5L);
        assertThat(first.reportSkewSeconds()).isEqualTo(2);
        assertThat(first.concurrentReporterCount()).isEqualTo(1);

        AppealDetailResponse.WarningItem second = detail.warnings().get(1);
        assertThat(second.absenceStartedAt()).isEqualTo(start2);
        assertThat(second.absenceEndedAt()).isEqualTo(end2);
        assertThat(second.reportSkewSeconds()).isZero();
        assertThat(second.concurrentReporterCount()).isZero();

        AppealDetailResponse.WarningItem third = detail.warnings().get(2);
        assertThat(third.basis()).isEqualTo(WarningBasis.PAUSE_OVERRUN);
        assertThat(third.issuedAt()).isEqualTo(resumedAt);
        assertThat(third.absenceStartedAt()).isNull();
        assertThat(third.absenceEndedAt()).isNull();
        assertThat(third.absentSeconds()).isNull();
        assertThat(third.reportSkewSeconds()).isNull();
        assertThat(third.concurrentReporterCount()).isNull();
    }

    @Test
    @DisplayName("없는 이의는 404다")
    void 없는_이의는_거절된다() {
        assertThatThrownBy(() -> appealAdminService.getAppeal(999_999L))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.APPEAL_NOT_FOUND);
    }

    @Test
    @DisplayName("관리자만 조회할 수 있고, 다른 참가자는 집계 수치로만 남는다")
    void 관리자_전용이고_타인_식별자는_새지_않는다() throws Exception {
        // 이 테스트가 죽으면: 이의 심사가 같은 세션 참가자들의 행동 기록을 열람하는 경로가
        // 됐거나, 역할 게이트가 이 경로만 비켜 간 것이다.
        List<Long> memberIds = fixtures.joinMembers(PARTICIPANTS);
        Long sessionId = fixtures.openSession(TARGET_MINUTES, BASE_TIME, memberIds);
        Long memberId = memberIds.getFirst();
        Long witnessId = memberIds.get(1);

        LocalDateTime start1 = BASE_TIME.plusMinutes(1);
        report(witnessId, sessionId, AbsenceEventType.START, 0,
                start1.plusSeconds(10), start1.plusSeconds(10));
        Long appealId = evictAndFileAppeal(sessionId, memberId);

        mockMvc.perform(get("/api/admin/appeals/{appealId}", appealId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + loginToken("ad9-user")))
                .andExpect(status().isForbidden());

        Long adminId = fixtures.joinMember("ad9-admin");
        fixtures.execute("UPDATE member SET role = 'ADMIN' WHERE id = ?", adminId);
        String body = mockMvc.perform(get("/api/admin/appeals/{appealId}", appealId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + loginToken("ad9-admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reasonText").value(REASON))
                .andExpect(jsonPath("$.warnings[0].concurrentReporterCount").value(1))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        // 동시 보고자는 수치로만 존재해야 한다. 당사자 닉네임은 실리고 목격자 것은 없다
        assertThat(body).contains(fixtures.member(memberId).getNickname());
        assertThat(body).doesNotContain(fixtures.member(witnessId).getNickname());
    }

    /** 자리비움 경고 3회로 퇴출시키고 그 퇴출에 이의를 접수한다. 첫 구간은 목격자 보고와 겹친다. */
    private Long evictAndFileAppeal(Long sessionId, Long memberId) {
        for (int round = 0; round < 3; round++) {
            LocalDateTime startedAt = BASE_TIME.plusMinutes(round * 3L + 1);
            LocalDateTime endedAt = startedAt.plusSeconds(absenceThresholdSeconds + 5L);
            report(memberId, sessionId, AbsenceEventType.START, round * 2L, startedAt, startedAt);
            report(memberId, sessionId, AbsenceEventType.END, round * 2L + 1, endedAt, endedAt);
        }
        return appealService.file(memberId, fixtures.evictionId(sessionId, memberId),
                new AppealCreateRequest(REASON)).appealId();
    }

    /** {@code reportedAt}을 관측 시각과 따로 받는다 — 보고 지연(skew)을 만들 수 있어야 한다. */
    private void report(Long memberId, Long sessionId, AbsenceEventType type, long clientSeq,
                        LocalDateTime occurredAt, LocalDateTime reportedAt) {
        clock.fixAt(reportedAt);
        absenceJudgeService.report(memberId, sessionId,
                new AbsenceEventRequest(type, clientSeq, offsetOf(occurredAt)));
    }

    /** 토큰만 필요한 재로그인. 같은 코드는 같은 소셜 계정이다(DevSocialClient 규약). */
    private String loginToken(String authorizationCode) {
        LoginResponse response = authService.login(new LoginRequest(
                SocialProvider.KAKAO, authorizationCode,
                List.of(new AgreementItem(AgreementType.TOS, true),
                        new AgreementItem(AgreementType.PRIVACY, true))));
        return response.accessToken();
    }

    private OffsetDateTime offsetOf(LocalDateTime at) {
        return at.atZone(clock.getZone()).toOffsetDateTime();
    }
}
