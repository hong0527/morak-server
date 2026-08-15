package com.morak.report.service;

import com.morak.common.error.BusinessException;
import com.morak.common.error.ErrorCode;
import com.morak.match.entity.MatchBlock;
import com.morak.match.repository.MatchBlockRepository;
import com.morak.member.entity.Member;
import com.morak.member.repository.MemberRepository;
import com.morak.report.dto.request.ReportCreateRequest;
import com.morak.report.dto.response.ReportCreateResponse;
import com.morak.report.entity.Report;
import com.morak.report.entity.ReportCase;
import com.morak.report.repository.ReportCaseRepository;
import com.morak.report.repository.ReportRepository;
import com.morak.report.type.ReportSeverity;
import com.morak.report.type.ReportTargetType;
import com.morak.session.repository.LiveSessionRepository;
import com.morak.session.repository.SessionParticipantRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * RP-1 신고 접수 (API명세서 RP-1, FR-402·FR-701).
 *
 * <p><b>신고해도 아무도 세션에서 나가지 않는다</b>(★D6). 이 클래스가 세션 상태를 건드리는
 * 코드를 한 줄도 갖지 않는 것이 그 규약의 구현이다. 라이브 세션은 시간 단위이고 완주
 * 판정이 종료 시각 기준이라, 신고했다는 이유로 나가면 신고한 쪽이 그 세션 완주를 잃는다.
 * 반대로 신고만으로 상대를 내보내면 아무나 신고해 남을 쫓아내는 악용이 성립한다.
 * 피신고자에 대한 조치는 관리자가 AD-3에서 확정한 뒤에만 온다.
 *
 * <p>그래서 접수의 실제 부수효과는 {@code match_block} 2행뿐이다. 그 대신 처리 SLA
 * (고위험 24시간)가 피해자 보호 장치를 진다.
 */
@Service
public class ReportService {

    private static final Logger log = LoggerFactory.getLogger(ReportService.class);

    /**
     * SESSION 신고의 대상 표시명. 세션에는 닉네임이 없지만 스냅샷 컬럼이 NOT NULL이라
     * 목록에서 읽을 이름이 필요하다. 회원 닉네임과 섞이지 않는 형태로 만든다.
     */
    private static final String SESSION_TARGET_NAME = "세션 %d";

    private final ReportCaseRepository reportCaseRepository;
    private final ReportRepository reportRepository;
    private final MatchBlockRepository matchBlockRepository;
    private final MemberRepository memberRepository;
    private final LiveSessionRepository liveSessionRepository;
    private final SessionParticipantRepository sessionParticipantRepository;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;
    private final int highSlaHours;
    private final int normalSlaHours;

    public ReportService(ReportCaseRepository reportCaseRepository,
                         ReportRepository reportRepository,
                         MatchBlockRepository matchBlockRepository,
                         MemberRepository memberRepository,
                         LiveSessionRepository liveSessionRepository,
                         SessionParticipantRepository sessionParticipantRepository,
                         Clock clock,
                         PlatformTransactionManager transactionManager,
                         @Value("${morak.report.sla-hours.high}") int highSlaHours,
                         @Value("${morak.report.sla-hours.normal}") int normalSlaHours) {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.reportCaseRepository = reportCaseRepository;
        this.reportRepository = reportRepository;
        this.matchBlockRepository = matchBlockRepository;
        this.memberRepository = memberRepository;
        this.liveSessionRepository = liveSessionRepository;
        this.sessionParticipantRepository = sessionParticipantRepository;
        this.clock = clock;
        this.highSlaHours = highSlaHours;
        this.normalSlaHours = normalSlaHours;
    }

    /**
     * 접수 경계를 {@link TransactionTemplate}으로 직접 긋는다(PY-2·SR-3과 같은 이유).
     *
     * <p>이 경로가 걸릴 수 있는 제약이 셋이고 <b>셋 다 사전 조회를 함께 통과한 동시 요청에서만
     * 터진다</b> — 같은 대상의 첫 신고 두 건이 각각 케이스를 여는 {@code uk_rc_open},
     * 같은 사람이 같은 케이스에 두 번 접수하는 {@code uk_report}, 같은 쌍의 차단 행을 동시에
     * 넣는 {@code uk_mb}다. 제약 위반은 트랜잭션이 끝나야 손에 들어오므로 잡는 자리가
     * 트랜잭션 <b>밖</b>이어야 하고, 애너테이션만 붙이면 그 자리가 없어 500이 나간다.
     *
     * <p>대응은 재시도 한 번이다. 세 경우 모두 상대가 이미 커밋해 둔 행이 원인이라, 다시
     * 실행하면 그 행이 보이는 상태에서 정상 경로가 답을 낸다 — 케이스는 병합되고, 중복 접수는
     * 409 {@code DUPLICATE_REPORT}가 되며, 이미 있는 차단은 건너뛴다. 재시도에도 같은 예외가
     * 나면 우리가 모르는 제약이므로 그대로 올려 보낸다.
     */
    public ReportCreateResponse report(Long reporterId, ReportCreateRequest request) {
        try {
            return transactionTemplate.execute(status -> reportInternal(reporterId, request));
        } catch (DataIntegrityViolationException e) {
            log.info("신고 접수가 제약에 부딪혀 롤백됐다. 한 번 재시도한다: reporter={}", reporterId);
            return transactionTemplate.execute(status -> reportInternal(reporterId, request));
        }
    }

    private ReportCreateResponse reportInternal(Long reporterId, ReportCreateRequest request) {
        request.validateShape();
        LocalDateTime now = LocalDateTime.now(clock);

        String targetName = verifyEligibility(reporterId, request);
        Long targetId = targetIdOf(request);
        ReportSeverity severity = request.reasonCode().toSeverity();

        ReportCase reportCase = mergeOrOpen(request, targetId, targetName, severity, now);
        if (reportRepository.existsByCaseIdAndReporterId(reportCase.getId(), reporterId)) {
            throw new BusinessException(ErrorCode.DUPLICATE_REPORT);
        }
        reportRepository.save(Report.file(reportCase.getId(), reporterId, request.reasonCode(),
                request.detail(), now));

        Long blockedMemberId = blockRematch(reporterId, request, now);
        log.info("신고 접수: case={}, reporter={}, targetType={}, target={}, severity={}",
                reportCase.getId(), reporterId, request.targetType(), targetId,
                reportCase.getSeverity());
        return ReportCreateResponse.of(reportCase, now, blockedMemberId);
    }

    /**
     * 신고 자격: 요청자와 대상이 같은 세션에 있었어야 한다. 세션을 거치지 않은 신고를
     * 허용하면 회원 번호만 알면 누구든 케이스를 열 수 있고, 그 케이스가 상대의 재매칭을
     * 영구 차단한다.
     *
     * <p>없는 대상은 404, 있지만 그 세션과 무관하면 403이다. 대상이 존재하는지와 함께
     * 있었는지는 다른 질문이라 코드를 나눈다.
     *
     * @return 케이스에 스냅샷으로 박을 대상 표시명
     */
    private String verifyEligibility(Long reporterId, ReportCreateRequest request) {
        if (!liveSessionRepository.existsById(request.sessionId())) {
            throw new BusinessException(ErrorCode.TARGET_NOT_FOUND);
        }
        requireParticipant(request.sessionId(), reporterId);
        if (request.targetType() == ReportTargetType.SESSION) {
            return SESSION_TARGET_NAME.formatted(request.sessionId());
        }
        if (reporterId.equals(request.targetMemberId())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    Map.of("targetMemberId", "자기 자신은 신고할 수 없습니다."));
        }
        Member target = memberRepository.findById(request.targetMemberId())
                .orElseThrow(() -> new BusinessException(ErrorCode.TARGET_NOT_FOUND));
        requireParticipant(request.sessionId(), target.getId());
        return target.getNickname();
    }

    private void requireParticipant(Long sessionId, Long memberId) {
        if (sessionParticipantRepository.findBySessionIdAndMemberId(sessionId, memberId)
                .isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_SESSION_PARTICIPANT);
        }
    }

    /**
     * 같은 대상의 미처리 케이스가 있으면 거기 합치고 없으면 연다. 관리자가 같은 사람을
     * 두고 두 번 판단하지 않게 하는 것이 목적이다.
     *
     * <p>병합 시 더 높은 등급이 들어오면 케이스를 상향하고 기한을 다시 계산한다.
     * NORMAL 케이스에 성적 콘텐츠 신고가 합류했는데 72시간 기한이 그대로면 고위험 24시간
     * 약속이 깨진다. 재계산의 기준은 <b>케이스 접수 시각</b>이다 — 합류 시각으로 잡으면
     * 신고가 들어올 때마다 기한이 뒤로 밀려 오래된 케이스일수록 늦게 처리된다.
     */
    private ReportCase mergeOrOpen(ReportCreateRequest request, Long targetId, String targetName,
                                   ReportSeverity severity, LocalDateTime now) {
        ReportCase existing = reportCaseRepository
                .findByTargetTypeAndOpenTargetId(request.targetType(), targetId)
                .orElse(null);
        if (existing == null) {
            return reportCaseRepository.saveAndFlush(ReportCase.open(request.targetType(), targetId,
                    request.sessionId(), targetName, severity, slaDueAt(now, severity), now));
        }
        if (severity == ReportSeverity.HIGH && existing.getSeverity() == ReportSeverity.NORMAL) {
            existing.escalate(ReportSeverity.HIGH,
                    slaDueAt(existing.getReceivedAt(), ReportSeverity.HIGH));
        }
        return existing;
    }

    private LocalDateTime slaDueAt(LocalDateTime receivedAt, ReportSeverity severity) {
        return receivedAt.plusHours(severity == ReportSeverity.HIGH ? highSlaHours : normalSlaHours);
    }

    /**
     * 재매칭 영구 차단. <b>항상 2행이다</b> — 한 방향만 넣으면 대기열을 어느 쪽에서 훑느냐에
     * 따라 둘이 다시 같은 방에 들어간다. 해제 API는 없다.
     *
     * <p>SESSION 신고는 차단 대상 개인이 특정되지 않아 등재하지 않는다.
     *
     * @return 차단한 상대. SESSION 신고면 null
     */
    private Long blockRematch(Long reporterId, ReportCreateRequest request, LocalDateTime now) {
        if (request.targetType() == ReportTargetType.SESSION) {
            return null;
        }
        Long targetMemberId = request.targetMemberId();
        blockOneWay(reporterId, targetMemberId, now);
        blockOneWay(targetMemberId, reporterId, now);
        return targetMemberId;
    }

    private void blockOneWay(Long memberId, Long blockedMemberId, LocalDateTime now) {
        if (matchBlockRepository.existsByMemberIdAndBlockedMemberId(memberId, blockedMemberId)) {
            return;
        }
        matchBlockRepository.save(MatchBlock.byReport(memberId, blockedMemberId, now));
    }

    private Long targetIdOf(ReportCreateRequest request) {
        return request.targetType() == ReportTargetType.MEMBER
                ? request.targetMemberId()
                : request.sessionId();
    }
}
