package com.morak.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.morak.common.dto.PageResponse;
import com.morak.common.error.BusinessException;
import com.morak.common.error.ErrorCode;
import com.morak.member.service.MemberAdminService;
import com.morak.member.service.MemberPurgeBatch;
import com.morak.member.service.MemberService;
import com.morak.member.dto.response.WithdrawalSummaryResponse;
import com.morak.member.type.MemberStatus;
import com.morak.report.dto.request.ReportCreateRequest;
import com.morak.report.dto.request.ReportProcessRequest;
import com.morak.report.dto.request.SanctionCommand;
import com.morak.report.dto.request.SanctionCreateRequest;
import com.morak.report.dto.response.ReportCaseDetailResponse;
import com.morak.report.dto.response.ReportCaseSummaryResponse;
import com.morak.report.dto.response.ReportProcessResponse;
import com.morak.report.dto.response.SanctionCreateResponse;
import com.morak.report.service.ReportAdminService;
import com.morak.report.service.ReportService;
import com.morak.report.type.ReportReasonCode;
import com.morak.report.type.ReportSeverity;
import com.morak.report.type.ReportStatus;
import com.morak.report.type.ReportTargetType;
import com.morak.report.type.SanctionType;
import com.morak.session.dto.response.AdminSessionResponse;
import com.morak.session.service.SessionAdminService;
import com.morak.session.service.SessionClosingBatch;
import com.morak.session.service.SessionExitService;
import com.morak.session.type.LeftReason;
import com.morak.session.type.SessionStatus;
import com.morak.support.IntegrationTest;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

/**
 * AD-1~AD-4·AD-7·AD-8 운영 화면.
 *
 * <p>지금까지 관리자 API 중 테스트가 있던 것은 이의 처리(AD-6·AD-9)와 신고 제재 확정의
 * 동시성뿐이다. <b>운영자가 매일 여는 화면 — 신고 큐, 케이스 상세, 진행 중 세션 모니터,
 * 탈퇴 처리 결과 — 는 한 번도 열어 본 적이 없다.</b> 이것들이 깨지면 신고가 들어와도 처리할
 * 수 없고, 그건 서비스가 멈춘 것과 같다(SLA는 HIGH 24시간이다).
 *
 * <p>여기서는 게이트가 아니라 <b>내용</b>을 본다. 역할 검사는 인터셉터의 일이라
 * {@code GateMatrixTest}가 지고, 이 파일은 "목록이 제대로 걸러지고 상세가 판단 재료를
 * 싣는가"만 확인한다.
 */
@DisplayName("관리자 운영 API")
class AdminOperationsTest extends IntegrationTest {

    private static final Long ADMIN_ACTOR = 1L;
    private static final int TARGET_MINUTES = 60;

    @Autowired
    private ReportService reportService;

    @Autowired
    private ReportAdminService reportAdminService;

    @Autowired
    private SessionAdminService sessionAdminService;

    @Autowired
    private MemberAdminService memberAdminService;

    @Autowired
    private MemberService memberService;

    @Autowired
    private MemberPurgeBatch memberPurgeBatch;

    @Autowired
    private SessionExitService sessionExitService;

    @Autowired
    private SessionClosingBatch sessionClosingBatch;

    @Value("${morak.withdrawal.grace-days}")
    private int graceDays;

    // ── AD-1 신고 큐 ──

    @Test
    @DisplayName("신고 큐가 상태와 심각도로 걸러진다")
    void 신고_큐_필터() {
        // 이 테스트가 죽으면: 운영자가 미처리 건만 골라 볼 수 없다. 처리한 건이 큐에 계속
        // 남으면 무엇이 남았는지 알 수 없고, HIGH를 먼저 처리할 수도 없다.
        // 대상이 같으면 한 케이스로 병합되므로(ReportConcurrencyTest) 서로 다른 사람을 신고한다
        Long sessionId = openSessionWith(4);
        Long high = fileReport(ReportReasonCode.SEXUAL_CONTENT, sessionId, 0, 2);
        Long normal = fileReport(ReportReasonCode.AD_SPAM, sessionId, 1, 3);
        reportAdminService.process(ADMIN_ACTOR, normal,
                new ReportProcessRequest(ReportStatus.REJECTED, "근거 부족", null));

        assertThat(caseIds(reportAdminService.getCases(ReportStatus.PENDING, null, null, null,
                null, null))).containsExactly(high);
        assertThat(caseIds(reportAdminService.getCases(ReportStatus.REJECTED, null, null, null,
                null, null))).containsExactly(normal);
        // 심각도는 사유 코드가 정한다 — 성적 콘텐츠는 HIGH다
        assertThat(caseIds(reportAdminService.getCases(null, ReportSeverity.HIGH, null, null,
                null, null))).containsExactly(high);
        assertThat(caseIds(reportAdminService.getCases(null, null, null, null, null, null)))
                .containsExactlyInAnyOrder(high, normal);
    }

    @Test
    @DisplayName("SLA를 넘긴 미처리 건만 overdue로 걸린다")
    void 신고_큐_SLA_필터() {
        // 이 테스트가 죽으면: 기한을 넘긴 신고를 골라낼 수 없다. overdue는 저장 컬럼이 아니라
        // 조회 시점의 파생값이므로(구 B3 폐지), 이 계산이 틀리면 되돌릴 배치도 없다.
        Long sessionId = openSessionWith(4);
        Long overdue = fileReport(ReportReasonCode.AD_SPAM, sessionId, 0, 2);
        Long fresh = fileReport(ReportReasonCode.AD_SPAM, sessionId, 1, 3);
        reportAdminService.process(ADMIN_ACTOR, fresh,
                new ReportProcessRequest(ReportStatus.RESOLVED, "확인함", null));

        // NORMAL SLA는 72시간이다
        clock.fixAt(now().plusHours(73));

        assertThat(caseIds(reportAdminService.getCases(null, null, true, null, null, null)))
                .containsExactly(overdue);
        // 처리를 끝낸 건은 기한이 지나도 overdue가 아니다
        assertThat(caseIds(reportAdminService.getCases(null, null, true, null, null, null)))
                .doesNotContain(fresh);
    }

    // ── AD-2 케이스 상세 ──

    @Test
    @DisplayName("케이스 상세가 신고자·대상 세션·처리 이력을 함께 싣는다")
    void 케이스_상세가_판단_재료를_싣는다() {
        // 이 테스트가 죽으면: 운영자가 신고 하나를 놓고 제재를 결정할 근거를 볼 수 없다.
        // 신고자 목록이 비면 "몇 명이 같은 사람을 신고했는가"를 알 수 없어 판단이 불가능하다.
        Long sessionId = openSessionWith(3);
        List<Long> members = participantsOf(sessionId);
        Long target = members.get(0);
        Long caseId = report(members.get(1), target, sessionId, ReportReasonCode.AD_SPAM);
        report(members.get(2), target, sessionId, ReportReasonCode.AD_SPAM);

        ReportCaseDetailResponse detail = reportAdminService.getCase(caseId);

        assertThat(detail.caseId()).isEqualTo(caseId);
        assertThat(detail.targetType()).isEqualTo(ReportTargetType.MEMBER);
        assertThat(detail.status()).isEqualTo(ReportStatus.PENDING);
        assertThat(detail.slaDueAt()).isNotNull();
        // 같은 대상의 신고 둘이 한 케이스로 병합돼 신고자가 둘이다
        assertThat(detail.reporters()).hasSize(2);
        assertThat(detail.history()).isEmpty();

        reportAdminService.process(ADMIN_ACTOR, caseId,
                new ReportProcessRequest(ReportStatus.RESOLVED, "경고 안내함", null));
        assertThat(reportAdminService.getCase(caseId).history()).hasSize(1);
        assertThatThrownBy(() -> reportAdminService.getCase(999_999L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.REPORT_NOT_FOUND);
    }

    // ── AD-3 신고 처리 ──

    @Test
    @DisplayName("제재 없는 처리는 제재를 만들지 않고, 처리된 건은 다시 처리되지 않는다")
    void 신고_처리의_두_경로() {
        // 이 테스트가 죽으면: 기각·해결에도 제재가 붙거나(무고한 사용자가 제재를 받는다),
        // 이미 처리한 케이스가 두 번 처리돼 제재가 겹쳐 걸린다.
        Long sessionId = openSessionWith(3);
        Long caseId = fileReport(ReportReasonCode.AD_SPAM, sessionId, 0, 1);

        ReportProcessResponse processed = reportAdminService.process(ADMIN_ACTOR, caseId,
                new ReportProcessRequest(ReportStatus.RESOLVED, "안내로 종결", null));

        assertThat(processed.status()).isEqualTo(ReportStatus.RESOLVED);
        assertThat(processed.sanctionId()).isNull();
        assertThat(processed.processedAt()).isNotNull();
        assertThat(fixtures.countAll("sanction")).isZero();

        assertThatThrownBy(() -> reportAdminService.process(ADMIN_ACTOR, caseId,
                new ReportProcessRequest(ReportStatus.REJECTED, "번복", null)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.ALREADY_PROCESSED);
        assertThat(reportAdminService.getCase(caseId).status()).isEqualTo(ReportStatus.RESOLVED);
    }

    @Test
    @DisplayName("제재 확정은 케이스와 제재를 함께 남긴다")
    void 제재_확정이_케이스와_제재를_잇는다() {
        // 이 테스트가 죽으면: 제재가 걸렸는데 어느 신고 때문인지 되짚을 수 없다. 이의 심사와
        // 분쟁 대응이 전부 이 연결에 기댄다.
        Long sessionId = openSessionWith(3);
        List<Long> members = participantsOf(sessionId);
        Long target = members.get(0);
        Long caseId = report(members.get(1), target, sessionId, ReportReasonCode.VIOLENT_THREAT);

        ReportProcessResponse processed = reportAdminService.process(ADMIN_ACTOR, caseId,
                new ReportProcessRequest(ReportStatus.SANCTIONED, "위협 확인",
                        new SanctionCommand(SanctionType.TEMP, 7)));

        assertThat(processed.status()).isEqualTo(ReportStatus.SANCTIONED);
        assertThat(processed.sanctionId()).isNotNull();
        assertThat(fixtures.count("sanction", "member_id = ? AND case_id = ?", target, caseId))
                .isEqualTo(1);
    }

    // ── AD-4 제재 단독 적용 ──

    @Test
    @DisplayName("신고 없이도 제재를 걸 수 있고, 없는 회원에는 걸리지 않는다")
    void 제재_단독_적용() {
        // 이 테스트가 죽으면: 신고 접수 없이 확인된 위반(운영자 직접 목격 등)에 손쓸 방법이
        // 없어진다. 없는 회원에 제재가 걸리면 그 행은 영원히 주인이 없다.
        Long memberId = fixtures.joinVerifiedMember("ad4-target");

        SanctionCreateResponse sanction = reportAdminService.sanction(ADMIN_ACTOR, memberId,
                new SanctionCreateRequest(SanctionType.TEMP, 3, null));

        assertThat(sanction.memberId()).isEqualTo(memberId);
        assertThat(sanction.type()).isEqualTo(SanctionType.TEMP);
        assertThat(sanction.startsAt()).isNotNull();
        assertThat(sanction.endsAt()).isEqualTo(sanction.startsAt().plusDays(3));

        SanctionCreateResponse permanent = reportAdminService.sanction(ADMIN_ACTOR, memberId,
                new SanctionCreateRequest(SanctionType.PERMANENT, null, null));
        assertThat(permanent.endsAt()).isNull();

        assertThatThrownBy(() -> reportAdminService.sanction(ADMIN_ACTOR, 999_999L,
                new SanctionCreateRequest(SanctionType.TEMP, 3, null)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    // ── AD-7 진행 중 세션 모니터 ──

    @Test
    @DisplayName("세션 모니터가 상태별 인원을 세고 상태로 걸러진다")
    void 세션_모니터() {
        // 이 테스트가 죽으면: 운영자가 지금 무슨 일이 벌어지는지 볼 수 없다. 인원 집계가
        // 틀리면 신고가 들어온 세션에 몇 명이 남아 있는지 모른 채 판단해야 한다.
        Long live = openSessionWith(3);
        List<Long> members = participantsOf(live);
        sessionExitService.leaveOnRequest(members.get(2), live, LeftReason.PERSONAL);

        PageResponse<AdminSessionResponse> sessions =
                sessionAdminService.getSessions(SessionStatus.LIVE, null, null);

        assertThat(sessions.totalElements()).isEqualTo(1);
        AdminSessionResponse monitored = sessions.content().getFirst();
        assertThat(monitored.sessionId()).isEqualTo(live);
        assertThat(monitored.targetMinutes()).isEqualTo(TARGET_MINUTES);
        assertThat(monitored.activeCount()).isEqualTo(2);
        assertThat(monitored.leftCount()).isEqualTo(1);
        assertThat(monitored.participants()).hasSize(3);

        clock.fixAt(BASE_TIME.plusMinutes(TARGET_MINUTES + 1));
        sessionClosingBatch.run();

        assertThat(sessionAdminService.getSessions(SessionStatus.LIVE, null, null)
                .totalElements()).isZero();
        assertThat(sessionAdminService.getSessions(SessionStatus.ENDED, null, null)
                .content().getFirst().status()).isEqualTo(SessionStatus.ENDED);
        // 상태를 생략하면 조건 자체를 만들지 않는다
        assertThat(sessionAdminService.getSessions(null, null, null).totalElements()).isEqualTo(1);
    }

    // ── AD-8 탈퇴 처리 결과 ──

    @Test
    @DisplayName("탈퇴 목록이 유예 중과 파기 완료를 상태로 가른다")
    void 탈퇴_처리_결과() {
        // 이 테스트가 죽으면: 파기가 정말 실행됐는지 확인할 창구가 사라진다. 개인정보 파기는
        // "했다"를 증명해야 하는 일이라 결과 조회가 없으면 대응할 근거가 없다.
        Long pending = fixtures.joinVerifiedMember("ad8-pending");
        Long deleted = fixtures.joinVerifiedMember("ad8-deleted");
        fixtures.joinVerifiedMember("ad8-active");
        memberService.requestWithdrawal(pending);
        memberService.requestWithdrawal(deleted);

        // 한 명만 파기 대상이 되도록 예정 시각을 앞당긴다
        fixtures.execute("UPDATE member SET delete_scheduled_at = ? WHERE id = ?",
                BASE_TIME.minusDays(1), deleted);
        memberPurgeBatch.run();

        // 탈퇴하지 않은 회원은 어느 목록에도 없다
        assertThat(memberAdminService.getWithdrawals(null, null, null).totalElements())
                .isEqualTo(2);
        assertThat(memberAdminService.getWithdrawals(MemberStatus.WITHDRAW_PENDING, null, null)
                .content())
                .extracting(WithdrawalSummaryResponse::memberId)
                .containsExactly(pending);

        WithdrawalSummaryResponse purged =
                memberAdminService.getWithdrawals(MemberStatus.DELETED, null, null)
                        .content().getFirst();
        assertThat(purged.memberId()).isEqualTo(deleted);
        assertThat(purged.deletedAt()).isNotNull();
        assertThat(purged.requestedAt()).isNotNull();
    }

    @Test
    @DisplayName("탈퇴 목록에 조회할 수 없는 상태를 넘기면 400이다")
    void 탈퇴_목록의_상태값_검증() {
        // 이 테스트가 죽으면: ACTIVE를 넘겨 탈퇴하지 않은 회원 전체를 이 창구로 훑을 수 있다.
        assertThatThrownBy(() -> memberAdminService.getWithdrawals(MemberStatus.ACTIVE, null, null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    // ── 준비 ──

    private Long openSessionWith(int participants) {
        clock.fixAt(BASE_TIME);
        return fixtures.openSession(TARGET_MINUTES, BASE_TIME,
                fixtures.joinMembers(participants));
    }

    private List<Long> participantsOf(Long sessionId) {
        return fixtures.queryLongs(
                "SELECT member_id FROM session_participant WHERE session_id = ? ORDER BY id",
                sessionId);
    }

    /** 세션 참가자 중 {@code targetIndex}를 {@code reporterIndex}가 신고한다. */
    private Long fileReport(ReportReasonCode reasonCode, Long sessionId, int targetIndex,
                            int reporterIndex) {
        List<Long> members = participantsOf(sessionId);
        return report(members.get(reporterIndex), members.get(targetIndex), sessionId, reasonCode);
    }

    private Long report(Long reporterId, Long targetId, Long sessionId,
                        ReportReasonCode reasonCode) {
        return reportService.report(reporterId, new ReportCreateRequest(
                ReportTargetType.MEMBER, targetId, sessionId, reasonCode, "확인 요청")).caseId();
    }

    private List<Long> caseIds(PageResponse<ReportCaseSummaryResponse> page) {
        return page.content().stream().map(ReportCaseSummaryResponse::caseId).toList();
    }
}
