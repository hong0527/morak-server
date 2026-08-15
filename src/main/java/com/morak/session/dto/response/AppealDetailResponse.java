package com.morak.session.dto.response;

import com.morak.session.entity.AppealCase;
import com.morak.session.entity.Eviction;
import com.morak.session.entity.Warning;
import com.morak.session.type.AppealStatus;
import com.morak.session.type.DecidedBy;
import com.morak.session.type.WarningBasis;
import java.time.LocalDateTime;
import java.util.List;

/**
 * AD-9 이의 심사 상세. 관리자가 인용·기각을 판단할 재료를 한 번에 모은다 — {@code reasonText}는
 * 유일한 당사자 진술인데 이 응답 전에는 저장만 되고 어느 응답에도 실리지 않았다.
 *
 * <p><b>다른 참가자의 식별자는 어디에도 없다.</b> {@code concurrentReporterCount}는 공통 원인
 * (조명·회선) 신호를 위한 집계 수치뿐이다 — 명단을 실으면 이의를 핑계로 같은 세션 사람들의
 * 행동 기록을 열람하는 경로가 된다.
 *
 * <p>영상은 저장하지 않으므로(D17) "실제로 자리에 있었는가"는 어떤 필드로도 확정되지 않는다.
 * 이 응답이 주는 것은 개연성 판단의 재료다 — 구간 길이가 임계에 얼마나 근접했는지,
 * 단말 시각과 수신 시각이 얼마나 벌어졌는지.
 */
public record AppealDetailResponse(
        Long appealId,
        Long evictionId,
        Long memberId,
        String nickname,
        AppealStatus status,
        boolean overdue,
        String reasonText,
        LocalDateTime createdAt,
        LocalDateTime slaDueAt,
        DecidedBy decidedBy,
        LocalDateTime decidedAt,
        String note,
        EvictionInfo eviction,
        int sessionParticipantCount,
        List<WarningItem> warnings) {

    public static AppealDetailResponse of(AppealCase appeal, Eviction eviction, String nickname,
                                          int sessionParticipantCount, List<WarningItem> warnings,
                                          LocalDateTime now) {
        return new AppealDetailResponse(
                appeal.getId(),
                appeal.getEvictionId(),
                appeal.getMemberId(),
                nickname,
                appeal.getStatus(),
                appeal.isOverdue(now),
                appeal.getReasonText(),
                appeal.getCreatedAt(),
                appeal.getSlaDueAt(),
                appeal.getDecidedBy(),
                appeal.getDecidedAt(),
                appeal.getNote(),
                EvictionInfo.from(eviction),
                sessionParticipantCount,
                warnings);
    }

    public record EvictionInfo(Long sessionId, LocalDateTime evictedAt, int warningCount,
                               int pointPenalty, LocalDateTime revokedAt) {

        static EvictionInfo from(Eviction eviction) {
            return new EvictionInfo(eviction.getSessionId(), eviction.getCreatedAt(),
                    eviction.getWarningCount(), eviction.getPointPenalty(),
                    eviction.getRevokedAt());
        }
    }

    /**
     * 경고 1건의 근거. 구간 계열 필드가 null인 것은 두 경우다 — Pause 초과 경고는 근거 이벤트
     * 자체가 없고(D9), 자리비움 경고라도 짝 이벤트를 되짚지 못하면 시각을 지어내지 않는다.
     *
     * @param reportSkewSeconds       근거 이벤트의 수신 시각 − 단말 관측 시각. 크게 양수면 전송
     *                                지연이나 시각 조작 신호라 인용 판단의 재료가 된다
     * @param concurrentReporterCount 같은 구간에 미검출을 보고한 <b>다른</b> 참가자 수. 여럿이
     *                                몰렸으면 개인의 이석이 아니라 공통 원인을 의심할 수 있다
     */
    public record WarningItem(int seq, WarningBasis basis, LocalDateTime issuedAt,
                              LocalDateTime absenceStartedAt, LocalDateTime absenceEndedAt,
                              Long absentSeconds, Long reportSkewSeconds,
                              Integer concurrentReporterCount) {

        public static WarningItem pauseOverrun(Warning warning) {
            return new WarningItem(warning.getSeq(), WarningBasis.PAUSE_OVERRUN,
                    warning.getCreatedAt(), null, null, null, null, null);
        }
    }
}
