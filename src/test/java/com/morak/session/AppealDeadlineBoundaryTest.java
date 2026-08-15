package com.morak.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.morak.common.error.BusinessException;
import com.morak.common.error.ErrorCode;
import com.morak.session.dto.request.AbsenceEventRequest;
import com.morak.session.dto.request.AppealCreateRequest;
import com.morak.session.entity.Eviction;
import com.morak.session.repository.EvictionRepository;
import com.morak.session.service.AbsenceJudgeService;
import com.morak.session.service.AppealService;
import com.morak.session.type.AbsenceEventType;
import com.morak.support.IntegrationTest;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

/**
 * 퇴출 이의 신청 기한 3일의 경계를 고정한다(AP-1).
 *
 * <p>명세는 처음부터 "이의 기한은 3일"을 전제로 게이트를 설계했는데(제재 게이트 예외의 근거)
 * 코드에 검사가 없어 몇 달 지난 퇴출에도 접수됐다. 기산점은 세션 종료가 아니라 <b>퇴출
 * 시각</b>이다 — 당사자는 퇴출 응답(SS-4)으로 그 순간 통지받고, 세션 종료 기준이면 세션이
 * 길수록 기한이 늘어나는 우연이 생긴다.
 *
 * <p>경계는 정각 포함이다. 제재 종료·충전 만료·탈퇴 파기가 전부 "정각은 사용자에게 유리한
 * 쪽"으로 일관돼 있어 같은 방향을 따른다.
 */
@DisplayName("이의 신청 기한 경계")
class AppealDeadlineBoundaryTest extends IntegrationTest {

    private static final int TARGET_MINUTES = 60;
    private static final int PARTICIPANTS = 6;

    @Autowired
    private AbsenceJudgeService absenceJudgeService;

    @Autowired
    private AppealService appealService;

    @Autowired
    private EvictionRepository evictionRepository;

    @Value("${morak.session.absence-threshold-seconds}")
    private int absenceThresholdSeconds;

    @Value("${morak.session.evict-warning-count}")
    private int evictWarningCount;

    @Value("${morak.appeal.file-deadline-days}")
    private int fileDeadlineDays;

    @Test
    @DisplayName("퇴출 시각부터 정확히 3일까지는 접수된다")
    void 기한_정각까지는_접수된다() {
        // 이 테스트가 죽으면: 경계가 미포함으로 좁혀진 것이다. "3일 안에 냈는데 거절됐다"는
        // 문의가 생기고, 다른 기한 처리와 경계 방향이 갈라진다.
        List<Long> memberIds = fixtures.joinMembers(PARTICIPANTS);
        Long sessionId = fixtures.openSession(TARGET_MINUTES, BASE_TIME, memberIds);
        Long memberId = memberIds.getFirst();
        Eviction eviction = evict(sessionId, memberId);
        clock.fixAt(eviction.getCreatedAt().plusDays(fileDeadlineDays));

        Long appealId = appealService.file(memberId, eviction.getId(),
                new AppealCreateRequest("자리비움이 아니었습니다")).appealId();

        assertThat(fixtures.count("appeal_case", "id = ? AND status = 'PENDING'", appealId))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("3일에서 1초라도 지나면 409로 거절되고 이의는 만들어지지 않는다")
    void 기한이_지나면_거절된다() {
        // 이 테스트가 죽으면: 기한 검사가 사라져 몇 달 지난 퇴출에도 이의가 접수되는
        // 상태 — 이 검사가 생기기 전의 결함 — 로 돌아간 것이다.
        List<Long> memberIds = fixtures.joinMembers(PARTICIPANTS);
        Long sessionId = fixtures.openSession(TARGET_MINUTES, BASE_TIME, memberIds);
        Long memberId = memberIds.getFirst();
        Eviction eviction = evict(sessionId, memberId);
        clock.fixAt(eviction.getCreatedAt().plusDays(fileDeadlineDays).plusSeconds(1));

        assertThatThrownBy(() -> appealService.file(memberId, eviction.getId(),
                new AppealCreateRequest("자리비움이 아니었습니다")))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.APPEAL_DEADLINE_PASSED);

        assertThat(fixtures.count("appeal_case", "eviction_id = ?", eviction.getId())).isZero();
    }

    @Test
    @DisplayName("접수된 이의의 재전송은 기한이 지난 뒤에도 기한 초과가 아니라 중복으로 답한다")
    void 재전송은_기한_초과보다_중복이_먼저다() {
        // 이 테스트가 죽으면: 접수에 성공한 클라이언트의 재시도가 기한 초과로 읽혀,
        // 사용자는 "접수됐는데 기한이 지났다"는 모순된 화면을 본다.
        List<Long> memberIds = fixtures.joinMembers(PARTICIPANTS);
        Long sessionId = fixtures.openSession(TARGET_MINUTES, BASE_TIME, memberIds);
        Long memberId = memberIds.getFirst();
        Eviction eviction = evict(sessionId, memberId);
        clock.fixAt(eviction.getCreatedAt().plusHours(1));
        appealService.file(memberId, eviction.getId(),
                new AppealCreateRequest("자리비움이 아니었습니다"));
        clock.fixAt(eviction.getCreatedAt().plusDays(fileDeadlineDays).plusSeconds(1));

        assertThatThrownBy(() -> appealService.file(memberId, eviction.getId(),
                new AppealCreateRequest("자리비움이 아니었습니다")))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.APPEAL_ALREADY_FILED);
    }

    /** 자리비움 경고 3회로 퇴출시키고 퇴출 행을 돌려준다. 기한의 기산점이 이 행의 시각이다. */
    private Eviction evict(Long sessionId, Long memberId) {
        for (int round = 0; round < evictWarningCount; round++) {
            LocalDateTime startedAt = BASE_TIME.plusMinutes(round * 3L + 1);
            LocalDateTime endedAt = startedAt.plusSeconds(absenceThresholdSeconds + 5L);
            report(memberId, sessionId, AbsenceEventType.START, round * 2L, startedAt);
            report(memberId, sessionId, AbsenceEventType.END, round * 2L + 1, endedAt);
        }
        return evictionRepository.findBySessionIdAndMemberId(sessionId, memberId).orElseThrow();
    }

    private void report(Long memberId, Long sessionId, AbsenceEventType type, long clientSeq,
                        LocalDateTime at) {
        clock.fixAt(at);
        absenceJudgeService.report(memberId, sessionId,
                new AbsenceEventRequest(type, clientSeq,
                        at.atZone(clock.getZone()).toOffsetDateTime()));
    }
}
