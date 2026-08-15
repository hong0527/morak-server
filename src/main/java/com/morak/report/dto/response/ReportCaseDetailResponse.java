package com.morak.report.dto.response;

import com.morak.report.entity.Report;
import com.morak.report.entity.ReportCase;
import com.morak.report.entity.ReportHistory;
import com.morak.report.type.ReportReasonCode;
import com.morak.report.type.ReportSeverity;
import com.morak.report.type.ReportStatus;
import com.morak.report.type.ReportTargetType;
import com.morak.session.entity.LiveSession;
import com.morak.session.entity.SessionParticipant;
import com.morak.session.entity.Warning;
import com.morak.session.type.ParticipantStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * AD-2 상세. 관리자가 판단할 수 있는 재료를 한 번에 모은다.
 *
 * <p><b>신고자 신원은 어디에도 없다.</b> {@code reporters}는 사유·서술·접수 시각만 담는다 —
 * 관리자가 신고자를 특정할 수 있으면 그 정보가 새는 경로가 생기고, 판단에는 필요하지 않다.
 *
 * <p>세션 영상은 저장하지 않으므로 열람 대상이 없다. 판단 근거는 대상자의 세션 이력
 * ({@code targetSessions})·경고 로그({@code targetWarnings})·신고 사유 셋뿐이다.
 */
public record ReportCaseDetailResponse(
        Long caseId,
        ReportTargetType targetType,
        TargetInfo target,
        ReportSeverity severity,
        ReportStatus status,
        boolean overdue,
        LocalDateTime receivedAt,
        LocalDateTime slaDueAt,
        List<ReporterItem> reporters,
        List<TargetSessionItem> targetSessions,
        List<TargetWarningItem> targetWarnings,
        List<HistoryItem> history) {

    /** MEMBER 신고면 {@code memberId}가 대상 회원, SESSION 신고면 null이다. */
    public record TargetInfo(Long memberId, String nickname, Long sessionId) {
    }

    public record ReporterItem(ReportReasonCode reasonCode, String detail,
                               LocalDateTime receivedAt) {

        static ReporterItem from(Report report) {
            return new ReporterItem(report.getReasonCode(), report.getDetail(),
                    report.getReceivedAt());
        }
    }

    public record TargetSessionItem(Long sessionId, LocalDateTime startedAt,
                                    ParticipantStatus participantStatus, int warningCount,
                                    boolean completed) {

        static TargetSessionItem of(SessionParticipant participant, LiveSession session) {
            return new TargetSessionItem(participant.getSessionId(),
                    session == null ? null : session.getStartedAt(),
                    participant.getStatus(), participant.getWarningCount(),
                    participant.isCompleted());
        }
    }

    public record TargetWarningItem(Long sessionId, int seq, LocalDateTime createdAt) {

        static TargetWarningItem from(Warning warning) {
            return new TargetWarningItem(warning.getSessionId(), warning.getSeq(),
                    warning.getCreatedAt());
        }
    }

    public record HistoryItem(Long adminId, ReportStatus status, String reviewNote,
                              LocalDateTime processedAt) {

        static HistoryItem from(ReportHistory history) {
            return new HistoryItem(history.getAdminId(), history.getStatus(),
                    history.getReviewNote(), history.getProcessedAt());
        }
    }

    public static ReportCaseDetailResponse of(ReportCase reportCase,
                                              String targetNickname,
                                              List<Report> reports,
                                              List<SessionParticipant> targetParticipations,
                                              Map<Long, LiveSession> sessionsById,
                                              List<Warning> targetWarnings,
                                              List<ReportHistory> history,
                                              LocalDateTime now) {
        boolean memberTarget = reportCase.getTargetType() == ReportTargetType.MEMBER;
        return new ReportCaseDetailResponse(
                reportCase.getId(),
                reportCase.getTargetType(),
                new TargetInfo(memberTarget ? reportCase.getTargetId() : null, targetNickname,
                        reportCase.getSessionId()),
                reportCase.getSeverity(),
                reportCase.getStatus(),
                reportCase.isOverdue(now),
                reportCase.getReceivedAt(),
                reportCase.getSlaDueAt(),
                reports.stream().map(ReporterItem::from).toList(),
                targetParticipations.stream()
                        .map(participant -> TargetSessionItem.of(participant,
                                sessionsById.get(participant.getSessionId())))
                        .toList(),
                targetWarnings.stream().map(TargetWarningItem::from).toList(),
                history.stream().map(HistoryItem::from).toList());
    }
}
