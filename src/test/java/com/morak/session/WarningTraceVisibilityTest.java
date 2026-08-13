package com.morak.session;

import static org.assertj.core.api.Assertions.assertThat;

import com.morak.session.dto.request.AbsenceEventRequest;
import com.morak.session.dto.request.AppealCreateRequest;
import com.morak.session.dto.response.AbsenceEventResponse;
import com.morak.session.dto.response.AppealDetailResponse;
import com.morak.session.dto.response.SessionResultResponse;
import com.morak.session.service.AbsenceJudgeService;
import com.morak.session.service.AppealAdminService;
import com.morak.session.service.AppealService;
import com.morak.session.service.PauseService;
import com.morak.session.service.SessionClosingBatch;
import com.morak.session.service.SessionService;
import com.morak.session.type.AbsenceEventType;
import com.morak.session.type.WarningBasis;
import com.morak.support.IntegrationTest;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import tools.jackson.databind.ObjectMapper;

/**
 * 경고의 근거 구간이 누구에게 보이는지 고정한다(SS-8·SS-4).
 *
 * <p>영상을 저장하지 않으므로(D17) 이 구간이 당사자가 이의 사유(AP-1)를 쓸 유일한 재료다.
 * 핵심 불변식은 둘이다 — <b>관리자(AD-9)와 본인(SS-8)이 같은 경고에 같은 구간을 본다</b>
 * (되짚기 두 벌이 생기면 그 차이 자체가 분쟁거리다), 그리고 <b>남의 행에는 어떤 경우에도
 * 구간이 실리지 않는다</b>(evictionId를 본인에게만 주는 것과 같은 원칙).
 */
@DisplayName("경고 근거 구간 노출")
class WarningTraceVisibilityTest extends IntegrationTest {

    private static final int TARGET_MINUTES = 60;
    private static final int PARTICIPANTS = 6;

    @Autowired
    private AbsenceJudgeService absenceJudgeService;

    @Autowired
    private AppealService appealService;

    @Autowired
    private AppealAdminService appealAdminService;

    @Autowired
    private SessionService sessionService;

    @Autowired
    private PauseService pauseService;

    @Autowired
    private SessionClosingBatch sessionClosingBatch;

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${morak.session.absence-threshold-seconds}")
    private int absenceThresholdSeconds;

    @Value("${morak.session.evict-warning-count}")
    private int evictWarningCount;

    @Test
    @DisplayName("관리자(AD-9)와 본인(SS-8)이 같은 경고에 동일한 구간 값을 본다")
    void 관리자와_본인이_같은_구간을_본다() {
        // 이 테스트가 죽으면: 되짚기가 두 벌로 갈라진 것이다. 관리자가 심사하는 구간과
        // 당사자가 이의에 쓴 구간이 어긋나, 판정이 아니라 데이터가 분쟁거리가 된다.
        List<Long> memberIds = fixtures.joinMembers(PARTICIPANTS);
        Long sessionId = fixtures.openSession(TARGET_MINUTES, BASE_TIME, memberIds);
        Long memberId = memberIds.getFirst();
        evictByAbsence(sessionId, memberId);
        closeSession();
        Long appealId = appealService.file(memberId, fixtures.evictionId(sessionId, memberId),
                new AppealCreateRequest("자리비움이 아니었습니다")).appealId();

        List<AppealDetailResponse.WarningItem> adminView =
                appealAdminService.getAppeal(appealId).warnings();
        List<SessionResultResponse.WarningItem> myView =
                sessionService.getResult(memberId, sessionId).my().warnings();

        assertThat(myView).hasSize(evictWarningCount);
        assertThat(adminView).hasSize(evictWarningCount);
        for (int i = 0; i < evictWarningCount; i++) {
            AppealDetailResponse.WarningItem admin = adminView.get(i);
            SessionResultResponse.WarningItem mine = myView.get(i);
            assertThat(mine.seq()).isEqualTo(admin.seq());
            assertThat(mine.basis()).isEqualTo(admin.basis());
            assertThat(mine.issuedAt()).isEqualTo(admin.issuedAt());
            assertThat(mine.absenceStartedAt()).isEqualTo(admin.absenceStartedAt());
            assertThat(mine.absenceEndedAt()).isEqualTo(admin.absenceEndedAt());
            assertThat(mine.absentSeconds()).isEqualTo(admin.absentSeconds());
        }
    }

    @Test
    @DisplayName("남의 행에는 구간이 실리지 않는다 — 직렬화 결과에서 my 밖에 구간 키가 없다")
    void 남의_행에는_구간이_없다() {
        // 이 테스트가 죽으면: 같은 세션에 있었다는 이유로 타인의 자리비움 이력이 새는
        // 것이다. 레코드 구조가 바뀌어 participants[]에 구간이 들어가도 여기서 잡혀야 한다.
        List<Long> memberIds = fixtures.joinMembers(PARTICIPANTS);
        Long sessionId = fixtures.openSession(TARGET_MINUTES, BASE_TIME, memberIds);
        Long evicted = memberIds.getFirst();
        Long viewer = memberIds.get(1);
        evictByAbsence(sessionId, evicted);
        closeSession();

        SessionResultResponse viewerResult = sessionService.getResult(viewer, sessionId);

        // 경고 없는 본인의 목록은 빈 리스트다 — null이면 프론트가 매번 분기한다
        assertThat(viewerResult.my().warnings()).isEmpty();
        // 퇴출자의 경고 수는 보이되(명세 SS-8) 구간은 응답 어디에도 없어야 한다
        String json = objectMapper.writeValueAsString(viewerResult);
        assertThat(json).doesNotContain("absenceStartedAt");
        String evictedOwnJson = objectMapper.writeValueAsString(
                sessionService.getResult(evicted, sessionId));
        assertThat(evictedOwnJson).contains("absenceStartedAt");
    }

    @Test
    @DisplayName("화장실 초과 경고는 종류만 있고 구간 세 값이 전부 null이다")
    void 화장실_초과_경고는_구간이_null이다() {
        // 이 테스트가 죽으면: 자리비움 구간이 없는 경고에 시각을 지어내 실은 것이다.
        // 되짚지 못하면 null이라는 원칙(WarningTraceService)이 본인 화면에서 깨진다.
        List<Long> memberIds = fixtures.joinMembers(PARTICIPANTS);
        Long sessionId = fixtures.openSession(TARGET_MINUTES, BASE_TIME, memberIds);
        Long memberId = memberIds.getFirst();
        clock.fixAt(BASE_TIME.plusMinutes(10));
        pauseService.start(memberId, sessionId);
        // 복귀하지 않고 세션이 끝난다. 경과 50분 > 상한 10분이라 종료 정산이 경고를 남긴다
        closeSession();

        List<SessionResultResponse.WarningItem> warnings =
                sessionService.getResult(memberId, sessionId).my().warnings();

        assertThat(warnings).hasSize(1);
        assertThat(warnings.getFirst().basis()).isEqualTo(WarningBasis.PAUSE_OVERRUN);
        assertThat(warnings.getFirst().absenceStartedAt()).isNull();
        assertThat(warnings.getFirst().absenceEndedAt()).isNull();
        assertThat(warnings.getFirst().absentSeconds()).isNull();
    }

    @Test
    @DisplayName("SS-4 응답은 닫힌 구간의 지속 초를 경고 여부와 무관하게 싣는다")
    void 자리비움_응답에_지속_초가_실린다() {
        // 이 테스트가 죽으면: 경고가 붙은 순간 몇 초로 판정됐는지 당사자가 알 수 없어,
        // 이의를 쓸 재료가 응답에서 사라진 것이다.
        List<Long> memberIds = fixtures.joinMembers(PARTICIPANTS);
        Long sessionId = fixtures.openSession(TARGET_MINUTES, BASE_TIME, memberIds);
        Long memberId = memberIds.getFirst();

        AbsenceEventResponse start = report(memberId, sessionId, AbsenceEventType.START, 0,
                BASE_TIME.plusMinutes(1));
        AbsenceEventResponse warned = report(memberId, sessionId, AbsenceEventType.END, 1,
                BASE_TIME.plusMinutes(1).plusSeconds(absenceThresholdSeconds + 10L));
        AbsenceEventResponse shortStart = report(memberId, sessionId, AbsenceEventType.START, 2,
                BASE_TIME.plusMinutes(5));
        AbsenceEventResponse closedShort = report(memberId, sessionId, AbsenceEventType.END, 3,
                BASE_TIME.plusMinutes(5).plusSeconds(30));

        assertThat(start.closedAbsenceSeconds()).isNull();
        assertThat(warned.closedAbsenceSeconds()).isEqualTo(absenceThresholdSeconds + 10L);
        assertThat(warned.warningCount()).isEqualTo(1);
        assertThat(shortStart.closedAbsenceSeconds()).isNull();
        // 임계 이하라 경고는 없지만 초는 내린다 — 임계에 얼마나 가까웠는지 보여 준다
        assertThat(closedShort.closedAbsenceSeconds()).isEqualTo(30L);
        assertThat(closedShort.warningCount()).isEqualTo(1);
    }

    /** 자리비움 경고를 임계까지 쌓아 퇴출시킨다. */
    private void evictByAbsence(Long sessionId, Long memberId) {
        for (int round = 0; round < evictWarningCount; round++) {
            LocalDateTime startedAt = BASE_TIME.plusMinutes(round * 3L + 1);
            LocalDateTime endedAt = startedAt.plusSeconds(absenceThresholdSeconds + 5L);
            report(memberId, sessionId, AbsenceEventType.START, round * 2L, startedAt);
            report(memberId, sessionId, AbsenceEventType.END, round * 2L + 1, endedAt);
        }
    }

    private void closeSession() {
        clock.fixAt(BASE_TIME.plusMinutes(TARGET_MINUTES + 1));
        sessionClosingBatch.run();
    }

    private AbsenceEventResponse report(Long memberId, Long sessionId, AbsenceEventType type,
                                        long clientSeq, LocalDateTime at) {
        clock.fixAt(at);
        return absenceJudgeService.report(memberId, sessionId,
                new AbsenceEventRequest(type, clientSeq,
                        at.atZone(clock.getZone()).toOffsetDateTime()));
    }
}
