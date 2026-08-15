package com.morak.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.morak.common.error.BusinessException;
import com.morak.common.error.ErrorCode;
import com.morak.session.dto.request.AbsenceEventRequest;
import com.morak.session.dto.request.AppealCreateRequest;
import com.morak.session.dto.request.AppealProcessRequest;
import com.morak.session.dto.response.AppealProcessResponse;
import com.morak.session.service.AbsenceJudgeService;
import com.morak.session.service.AppealAdminService;
import com.morak.session.service.AppealService;
import com.morak.session.service.SessionClosingBatch;
import com.morak.session.type.AbsenceEventType;
import com.morak.session.type.AppealStatus;
import com.morak.support.IntegrationTest;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

/**
 * 진행 중인 세션의 퇴출을 인용하려 할 때 무슨 일이 일어나는지 본다(AD-6).
 *
 * <p>인용은 세 가지를 함께 한다 — 퇴출 취소, 포인트 역분개, 완주 소급. 그런데 <b>완주는 세션이
 * 끝나야 정해진다.</b> 진행 중에 인용하면 앞의 둘만 되고 완주 소급은 조용히 false가 되는데,
 * 이의는 그 순간 ACCEPTED로 종결돼 재처리 경로가 없다. 잘못된 퇴출로 잃은 완주가 영영
 * 돌아오지 않는 상태이고, 그것이 NFR-402가 막으려던 바로 그 결과다.
 */
@DisplayName("진행 중 세션의 이의 인용")
class AppealDuringLiveSessionTest extends IntegrationTest {

    private static final int TARGET_MINUTES = 60;
    private static final int PARTICIPANTS = 6;
    private static final Long ADMIN_ID = 1L;

    @Autowired
    private AbsenceJudgeService absenceJudgeService;

    @Autowired
    private AppealService appealService;

    @Autowired
    private AppealAdminService appealAdminService;

    @Autowired
    private SessionClosingBatch sessionClosingBatch;

    @Value("${morak.session.absence-threshold-seconds}")
    private int absenceThresholdSeconds;

    @Value("${morak.session.evict-warning-count}")
    private int evictWarningCount;

    @Value("${morak.point.eviction-penalty}")
    private int evictionPenalty;

    @Test
    @DisplayName("세션이 진행 중이면 인용을 409로 끊고 이의는 PENDING으로 남는다")
    void 진행_중_인용은_거절되고_이의는_살아_있다() {
        // 이 테스트가 죽으면: 완주 소급이 조용히 실패한 채 이의가 종결된다. 퇴출은 풀리는데
        // 완주는 돌아오지 않고, 이의가 이미 닫혀 다시 처리할 수도 없다.
        List<Long> memberIds = fixtures.joinMembers(PARTICIPANTS);
        Long sessionId = fixtures.openSession(TARGET_MINUTES, BASE_TIME, memberIds);
        Long memberId = memberIds.getFirst();
        Long appealId = evictAndFileAppeal(sessionId, memberId);

        assertThatThrownBy(() -> appealAdminService.process(ADMIN_ID, appealId,
                new AppealProcessRequest(AppealStatus.ACCEPTED, "진행 중 인용 시도")))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.SESSION_NOT_ENDED);

        assertThat(fixtures.count("appeal_case", "id = ? AND status = 'PENDING'", appealId))
                .isEqualTo(1);
        // 아무것도 되돌아가지 않았어야 한다
        assertThat(fixtures.count("eviction",
                "member_id = ? AND revoked_at IS NULL", memberId)).isEqualTo(1);
        assertThat(fixtures.count("point_ledger",
                "member_id = ? AND reason = 'APPEAL_REFUND'", memberId)).isZero();
    }

    @Test
    @DisplayName("세션이 끝난 뒤 같은 이의를 다시 처리하면 환급과 완주 소급이 함께 성립한다")
    void 종료_뒤에는_같은_이의가_그대로_처리된다() {
        // 이 테스트가 죽으면: 거절이 이의를 죽여 재처리 경로가 사라진 것이다. 진행 중 거절은
        // 판단을 미루는 것이지 기각이 아니다.
        List<Long> memberIds = fixtures.joinMembers(PARTICIPANTS);
        Long sessionId = fixtures.openSession(TARGET_MINUTES, BASE_TIME, memberIds);
        Long memberId = memberIds.getFirst();
        Long appealId = evictAndFileAppeal(sessionId, memberId);
        clock.fixAt(BASE_TIME.plusMinutes(TARGET_MINUTES + 1));
        sessionClosingBatch.run();

        AppealProcessResponse response = appealAdminService.process(ADMIN_ID, appealId,
                new AppealProcessRequest(AppealStatus.ACCEPTED, "종료 후 인용"));

        assertThat(response.pointRefunded()).isEqualTo(evictionPenalty);
        assertThat(response.sessionCompletedRestored()).isTrue();
        assertThat(fixtures.count("appeal_case", "id = ? AND status = 'ACCEPTED'", appealId))
                .isEqualTo(1);
        assertThat(fixtures.count("point_ledger",
                "member_id = ? AND reason = 'APPEAL_REFUND'", memberId)).isEqualTo(1);
        assertThat(fixtures.participant(sessionId, memberId).isCompleted()).isTrue();
        assertThat(fixtures.member(memberId).getPointBalance())
                .isEqualTo(fixtures.ledgerSum(memberId));
    }

    /** 자리비움 경고 3회로 퇴출시키고 그 퇴출에 이의를 접수한다. */
    private Long evictAndFileAppeal(Long sessionId, Long memberId) {
        for (int round = 0; round < evictWarningCount; round++) {
            LocalDateTime startedAt = BASE_TIME.plusMinutes(round * 3L + 1);
            LocalDateTime endedAt = startedAt.plusSeconds(absenceThresholdSeconds + 5L);
            report(memberId, sessionId, AbsenceEventType.START, round * 2L, startedAt);
            report(memberId, sessionId, AbsenceEventType.END, round * 2L + 1, endedAt);
        }
        Long evictionId = fixtures.evictionId(sessionId, memberId);
        return appealService.file(memberId, evictionId,
                new AppealCreateRequest("자리비움이 아니었습니다")).appealId();
    }

    private void report(Long memberId, Long sessionId, AbsenceEventType type, long clientSeq,
                        LocalDateTime at) {
        clock.fixAt(at);
        absenceJudgeService.report(memberId, sessionId,
                new AbsenceEventRequest(type, clientSeq,
                        at.atZone(clock.getZone()).toOffsetDateTime()));
    }
}
