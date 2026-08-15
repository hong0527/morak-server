package com.morak.report.service;

import com.morak.common.dto.PageParams;
import com.morak.common.dto.PageResponse;
import com.morak.common.error.BusinessException;
import com.morak.common.error.ErrorCode;
import com.morak.common.sla.SlaOverdue;
import com.morak.member.repository.MemberRepository;
import com.morak.report.dto.request.ReportProcessRequest;
import com.morak.report.dto.request.SanctionCreateRequest;
import com.morak.report.dto.response.ReportCaseDetailResponse;
import com.morak.report.dto.response.ReportCaseSummaryResponse;
import com.morak.report.dto.response.ReportProcessResponse;
import com.morak.report.dto.response.SanctionCreateResponse;
import com.morak.report.entity.Report;
import com.morak.report.entity.ReportCase;
import com.morak.report.entity.ReportHistory;
import com.morak.report.entity.Sanction;
import com.morak.report.repository.ReportCaseRepository;
import com.morak.report.repository.ReportHistoryRepository;
import com.morak.report.repository.ReportRepository;
import com.morak.report.type.ReportSeverity;
import com.morak.report.type.ReportStatus;
import com.morak.report.type.ReportTargetType;
import com.morak.session.entity.LiveSession;
import com.morak.session.entity.SessionParticipant;
import com.morak.session.repository.LiveSessionRepository;
import com.morak.session.repository.SessionParticipantRepository;
import com.morak.session.repository.WarningRepository;
import jakarta.persistence.criteria.Predicate;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 신고 콘솔 (API명세서 AD-1·AD-2·AD-3·AD-4).
 *
 * <p>관리자 여부는 여기서 보지 않는다. {@code /api/admin/**} 경로 전체를 전역 인터셉터의
 * ③ 역할 검사가 막으므로, 서비스마다 다시 확인하면 검사가 두 곳이 되고 언젠가 한쪽이
 * 빠진다.
 *
 * <p>{@code overdue}는 이 클래스 어디에도 저장되지 않는다 — 목록 필터는
 * {@link SlaOverdue}가 만든 조회 조건이고 응답 값은 {@link ReportCase#isOverdue}의 계산이다.
 * 조회 시각이 곧 판정 시각이라 배치 주기만큼 실제와 어긋나는 구간이 없다.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class ReportAdminService {

    /** 기한이 임박한 케이스가 위로 온다. 큐의 정렬 키가 곧 SLA 판정 기준이다. */
    private static final Sort QUEUE_SORT =
            Sort.by(Sort.Order.asc("slaDueAt"), Sort.Order.asc("id"));

    private final ReportCaseRepository reportCaseRepository;
    private final ReportRepository reportRepository;
    private final ReportHistoryRepository reportHistoryRepository;
    private final SanctionService sanctionService;
    private final MemberRepository memberRepository;
    private final SessionParticipantRepository sessionParticipantRepository;
    private final LiveSessionRepository liveSessionRepository;
    private final WarningRepository warningRepository;
    private final Clock clock;

    /** AD-1 목록. 생략된 필터는 조건 자체를 만들지 않는다. */
    public PageResponse<ReportCaseSummaryResponse> getCases(ReportStatus status,
                                                            ReportSeverity severity,
                                                            Boolean overdue,
                                                            String q,
                                                            Integer page,
                                                            Integer size) {
        LocalDateTime now = LocalDateTime.now(clock);
        PageParams params = PageParams.of(page, size);
        Page<ReportCase> cases = reportCaseRepository.findAll(
                filter(status, severity, overdue, q, now), params.toPageable(QUEUE_SORT));

        Map<Long, List<Report>> reportsByCase = reportsOf(
                cases.getContent().stream().map(ReportCase::getId).toList());
        return PageResponse.of(cases, reportCase -> ReportCaseSummaryResponse.of(
                reportCase, reportsByCase.getOrDefault(reportCase.getId(), List.of()), now));
    }

    /** AD-2 상세. 판단 재료는 신고 사유·대상자 세션 이력·경고 로그·처리 이력이다. */
    public ReportCaseDetailResponse getCase(Long caseId) {
        LocalDateTime now = LocalDateTime.now(clock);
        ReportCase reportCase = findCase(caseId);
        List<Report> reports = reportRepository.findByCaseIdOrderByIdAsc(caseId);
        List<ReportHistory> history =
                reportHistoryRepository.findByCaseIdOrderByProcessedAtAscIdAsc(caseId);

        // SESSION 신고는 대상자가 특정되지 않아 개인 이력이 없다. 판단 근거는 신고 사유뿐이다.
        boolean memberTarget = reportCase.getTargetType() == ReportTargetType.MEMBER;
        List<SessionParticipant> participations = memberTarget
                ? sessionParticipantRepository
                        .findTop20ByMemberIdOrderByIdDesc(reportCase.getTargetId())
                : List.of();
        Map<Long, LiveSession> sessions = liveSessionRepository
                .findAllById(participations.stream().map(SessionParticipant::getSessionId).toList())
                .stream()
                .collect(Collectors.toMap(LiveSession::getId, Function.identity()));

        return ReportCaseDetailResponse.of(reportCase, reportCase.getTargetNickname(), reports,
                participations, sessions,
                memberTarget
                        ? warningRepository.findTop20ByMemberIdOrderByIdDesc(reportCase.getTargetId())
                        : List.of(),
                history, now);
    }

    /**
     * AD-3 처리. PENDING만 바꿀 수 있고 <b>종결된 케이스는 재오픈하지 않는다</b> —
     * 재오픈을 허용하면 같은 대상에 미처리 케이스가 둘 생기는 uk_rc_open 충돌 경로가
     * 되살아난다. 재검토는 새 케이스다.
     *
     * <p>{@code match_block}은 손대지 않는다. 차단은 RP-1 접수 시점에 이미 영구 등재됐고,
     * 기각됐다고 풀어 주지 않는다(★D6 — 해제 경로 없음).
     */
    @Transactional
    public ReportProcessResponse process(Long adminId, Long caseId, ReportProcessRequest request) {
        request.validateShape();
        LocalDateTime now = LocalDateTime.now(clock);
        ReportCase reportCase = findCase(caseId);
        if (reportCase.getStatus() != ReportStatus.PENDING) {
            throw new BusinessException(ErrorCode.ALREADY_PROCESSED);
        }
        if (request.status() == ReportStatus.SANCTIONED
                && reportCase.getTargetType() != ReportTargetType.MEMBER) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    Map.of("status", "세션 신고에는 제재 대상 회원이 없습니다."));
        }
        Long targetId = reportCase.getTargetId();

        // 제재보다 종결이 먼저다. 위의 상태 검사는 흔한 재요청을 걸러 주는 지름길일 뿐이고,
        // 동시에 들어온 두 처리를 갈라내는 것은 이 조건부 UPDATE다 — 순서를 뒤집으면 둘 다
        // 제재를 걸어 놓은 뒤에야 한쪽이 밀려나고, 그 제재는 이미 세션 퇴장까지 마친 뒤다.
        // 여기서 예외를 던지면 트랜잭션이 통째로 롤백되므로 종결만 남는 상태는 생기지 않는다.
        //
        // 이용 제한 검토 플래그를 같은 문장에 싣는 것은 반복 허위 신고자를 남기는 유일한
        // 경로가 기각이기 때문이다. SLA 초과를 근거로 자동 마킹하지는 않는다 — 처리가 늦은
        // 것은 운영 사정이지 신고자의 잘못이 아니다.
        int closed = reportCaseRepository.close(caseId, request.status(),
                request.status() == ReportStatus.REJECTED, ReportStatus.PENDING);
        if (closed == 0) {
            throw new BusinessException(ErrorCode.ALREADY_PROCESSED);
        }

        Sanction sanction = null;
        if (request.status() == ReportStatus.SANCTIONED) {
            sanction = sanctionService.apply(targetId, caseId, request.sanction(), adminId);
        }
        reportHistoryRepository.save(
                ReportHistory.process(caseId, adminId, request.status(), request.reviewNote(), now));
        // 조건부 UPDATE와 제재의 포인트 차감이 영속성 컨텍스트를 비웠다. 응답에 실을 케이스는
        // 여기서 다시 읽는다 — 위에서 잡아 둔 참조는 종결 이전 상태다.
        return ReportProcessResponse.of(findCase(caseId), sanction, now);
    }

    /** AD-4 제재 단독 적용. 절차는 AD-3의 제재 확정과 같은 메서드가 진다. */
    @Transactional
    public SanctionCreateResponse sanction(Long adminId, Long memberId,
                                           SanctionCreateRequest request) {
        if (!memberRepository.existsById(memberId)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    Map.of("memberId", "존재하지 않는 회원입니다."));
        }
        if (request.caseId() != null && !reportCaseRepository.existsById(request.caseId())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    Map.of("caseId", "존재하지 않는 신고 케이스입니다."));
        }
        return SanctionCreateResponse.from(
                sanctionService.apply(memberId, request.caseId(), request.toCommand(), adminId));
    }

    private ReportCase findCase(Long caseId) {
        return reportCaseRepository.findById(caseId)
                .orElseThrow(() -> new BusinessException(ErrorCode.REPORT_NOT_FOUND));
    }

    private Map<Long, List<Report>> reportsOf(List<Long> caseIds) {
        if (caseIds.isEmpty()) {
            return Map.of();
        }
        return reportRepository.findByCaseIdInOrderByIdAsc(caseIds).stream()
                .collect(Collectors.groupingBy(Report::getCaseId));
    }

    /**
     * AD-1의 필터 조립. {@code overdue}만 컬럼 비교가 아니라 두 컬럼과 현재 시각의 식이라
     * 공용 헬퍼를 거친다 — 이의 큐(AD-5)가 같은 식을 써야 하기 때문이다.
     */
    private Specification<ReportCase> filter(ReportStatus status, ReportSeverity severity,
                                             Boolean overdue, String q, LocalDateTime now) {
        return (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (status != null) {
                predicates.add(builder.equal(root.get("status"), status));
            }
            if (severity != null) {
                predicates.add(builder.equal(root.get("severity"), severity));
            }
            if (overdue != null) {
                predicates.add(SlaOverdue.predicate(builder, root.get("status"),
                        root.get("slaDueAt"), ReportStatus.PENDING, now, overdue));
            }
            if (q != null && !q.isBlank()) {
                predicates.add(builder.like(root.get("targetNickname"), "%" + q.trim() + "%"));
            }
            return builder.and(predicates.toArray(Predicate[]::new));
        };
    }
}
