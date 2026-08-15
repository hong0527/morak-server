package com.morak.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.morak.common.error.BusinessException;
import com.morak.common.error.ErrorCode;
import com.morak.report.dto.request.ReportCreateRequest;
import com.morak.report.dto.request.ReportProcessRequest;
import com.morak.report.dto.request.SanctionCommand;
import com.morak.report.service.ReportAdminService;
import com.morak.report.service.ReportService;
import com.morak.report.type.ReportReasonCode;
import com.morak.report.type.ReportStatus;
import com.morak.report.type.ReportTargetType;
import com.morak.report.type.SanctionType;
import com.morak.support.Concurrently;
import com.morak.support.IntegrationTest;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 신고 접수와 처리가 동시에 들어왔을 때의 결과를 못 박는다.
 *
 * <p>이 도메인의 사전 조회는 전부 "먼저 보고 없으면 쓴다"라서 순차 재실행에서는 언제나
 * 통과한다. 실제로 갈라지는 자리는 둘이 함께 조회를 통과한 뒤이고, 그때 막는 것은
 * {@code uk_rc_open}·{@code uk_report}·{@code uk_mb} 세 제약과 조건부 UPDATE다.
 *
 * <p><b>제약이 막았다는 사실만으로는 부족하다.</b> 막힌 쪽이 500을 받으면 신고자는 안전
 * 도구가 고장 났다고 느끼고, 관리자는 같은 회원을 두 번 제재한다. 여기서 보는 것은 막혔는가가
 * 아니라 <b>막힌 쪽이 무엇을 받았는가</b>다.
 */
@DisplayName("신고 접수·처리 동시성")
class ReportConcurrencyTest extends IntegrationTest {

    private static final int TARGET_MINUTES = 60;
    private static final int PARTICIPANTS = 6;
    private static final Long ADMIN_ID = 1L;

    @Autowired
    private ReportService reportService;

    @Autowired
    private ReportAdminService reportAdminService;

    @Test
    @DisplayName("같은 대상에 동시 신고 2건은 케이스 하나로 병합된다")
    void 동시_신고는_한_케이스로_병합된다() {
        // 이 테스트가 죽으면: 케이스 생성이 uk_rc_open에 걸린 쪽이 500을 받는다. 신고는
        // 안전 도구라 상대가 먼저 열었다는 이유로 실패하면 안 된다.
        clock.fixAt(BASE_TIME);
        List<Long> memberIds = fixtures.joinMembers(PARTICIPANTS);
        Long sessionId = fixtures.openSession(TARGET_MINUTES, BASE_TIME, memberIds);
        Long targetId = memberIds.getLast();
        List<Long> reporters = memberIds.subList(0, 2);

        List<Throwable> failures = Concurrently.run(reporters.size(), index ->
                reportService.report(reporters.get(index), memberReport(sessionId, targetId)));

        assertThat(failures).isEmpty();
        assertThat(fixtures.count("report_case", "target_id = ? AND target_type = 'MEMBER'",
                targetId)).isEqualTo(1);
        assertThat(fixtures.countAll("report")).isEqualTo(2);
        // 접수 2건이 같은 케이스에 붙었는지까지 본다
        assertThat(fixtures.count("report",
                "case_id = (SELECT id FROM report_case WHERE target_id = ?)", targetId))
                .isEqualTo(2);
        // 차단은 신고 1건당 2행이고 쌍이 다르므로 4행이다
        assertThat(fixtures.countAll("match_block")).isEqualTo(4);
    }

    @Test
    @DisplayName("같은 사람의 동시 중복 신고는 한 건만 남고 나머지는 409다")
    void 동시_중복_신고는_409로_끊긴다() {
        // 이 테스트가 죽으면: uk_report에 걸린 쪽이 500을 받는다. 클라이언트가 재전송하면
        // 정상적으로 일어나는 상황이라 계약상의 409여야 한다.
        clock.fixAt(BASE_TIME);
        List<Long> memberIds = fixtures.joinMembers(PARTICIPANTS);
        Long sessionId = fixtures.openSession(TARGET_MINUTES, BASE_TIME, memberIds);
        Long targetId = memberIds.getLast();
        Long reporterId = memberIds.getFirst();

        List<Throwable> failures = Concurrently.run(2, index ->
                reportService.report(reporterId, memberReport(sessionId, targetId)));

        assertThat(fixtures.countAll("report")).isEqualTo(1);
        assertThat(fixtures.countAll("report_case")).isEqualTo(1);
        assertThat(failures).hasSize(1);
        assertThat(failures.getFirst()).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) failures.getFirst()).getErrorCode())
                .isEqualTo(ErrorCode.DUPLICATE_REPORT);
    }

    @Test
    @DisplayName("같은 케이스를 동시에 제재 확정하면 제재는 한 번만 걸린다")
    void 동시_제재_확정은_한_번만_적용된다() {
        // 이 테스트가 죽으면: 상태 검사와 종결 사이가 벌어져 같은 회원에게 제재가 두 번
        // 걸린다. 두 번째 제재는 첫 번째와 기간이 겹쳐 해제 시점을 사람이 계산할 수 없게 된다.
        clock.fixAt(BASE_TIME);
        List<Long> memberIds = fixtures.joinMembers(PARTICIPANTS);
        Long sessionId = fixtures.openSession(TARGET_MINUTES, BASE_TIME, memberIds);
        Long targetId = memberIds.getLast();
        Long caseId = reportService.report(memberIds.getFirst(), memberReport(sessionId, targetId))
                .caseId();

        List<Throwable> failures = Concurrently.run(2, index ->
                reportAdminService.process(ADMIN_ID, caseId, new ReportProcessRequest(
                        ReportStatus.SANCTIONED, "동시 처리",
                        new SanctionCommand(SanctionType.PERMANENT, null))));

        assertThat(fixtures.count("sanction", "member_id = ?", targetId)).isEqualTo(1);
        assertThat(fixtures.count("report_case",
                "id = ? AND status = 'SANCTIONED' AND open_target_id IS NULL", caseId))
                .isEqualTo(1);
        assertThat(fixtures.count("report_history", "case_id = ?", caseId)).isEqualTo(1);
        assertThat(failures).hasSize(1);
        assertThat(((BusinessException) failures.getFirst()).getErrorCode())
                .isEqualTo(ErrorCode.ALREADY_PROCESSED);
    }

    private ReportCreateRequest memberReport(Long sessionId, Long targetId) {
        return new ReportCreateRequest(ReportTargetType.MEMBER, targetId, sessionId,
                ReportReasonCode.AD_SPAM, "동시 접수 시험");
    }
}
