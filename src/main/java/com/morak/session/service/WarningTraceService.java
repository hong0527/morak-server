package com.morak.session.service;

import com.morak.session.entity.AbsenceEvent;
import com.morak.session.entity.LiveSession;
import com.morak.session.entity.Warning;
import com.morak.session.repository.AbsenceEventRepository;
import com.morak.session.repository.WarningRepository;
import com.morak.session.type.AbsenceEventType;
import com.morak.session.type.WarningBasis;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 경고의 근거 구간 되짚기. 관리자 심사(AD-9)와 당사자 결과(SS-8)가 <b>이 하나를 함께 쓴다</b> —
 * 두 벌로 갈라지면 관리자가 보는 구간과 본인이 보는 구간이 어긋나고, 그 차이 자체가
 * 분쟁거리가 된다.
 *
 * <p>되짚기는 판정(★D4)과 같은 규칙이다. 근거가 END면 그 직전 이벤트가 START였던 것이고
 * (SS-4·SS-5), START면 END 없이 세션이 끝나 종료 시각으로 정산된 것이다(§5). 되짚지 못하는
 * 조합에서는 구간을 지어내지 않고 null로 둔다.
 */
@Service
@Transactional(readOnly = true)
public class WarningTraceService {

    private final WarningRepository warningRepository;
    private final AbsenceEventRepository absenceEventRepository;

    public WarningTraceService(WarningRepository warningRepository,
                               AbsenceEventRepository absenceEventRepository) {
        this.warningRepository = warningRepository;
        this.absenceEventRepository = absenceEventRepository;
    }

    /**
     * 근거 구간이 붙은 경고 1건.
     *
     * @param reportSkewSeconds 근거 이벤트의 서버 수신 시각 − 단말 관측 시각. 크게 양수면
     *                          전송 지연이나 시각 조작 신호다. Pause 초과 경고는 null
     */
    public record WarningTrace(int seq, WarningBasis basis, LocalDateTime issuedAt,
                               LocalDateTime startedAt, LocalDateTime endedAt,
                               Long absentSeconds, Long reportSkewSeconds) {
    }

    /**
     * 한 참가자의 경고 전량을 근거 구간과 함께 돌려준다. 세션 스코프라 임계(3)가 상한이다.
     *
     * <p><b>경고 1건마다 조회가 두 번 나간다</b>(근거 이벤트와 그 직전 START). 행마다 도는
     * 구조지만 고치지 않은 이유는 경고 상한이 3이라 데이터가 그 이상 커질 수 없어서다 —
     * 실측에서 세션 결과 응답 하나가 최대 16쿼리였고 전부 단건 조회다. 목록 API에 실리는
     * 구조가 아니라 페이지 크기에 비례하지도 않는다. 지금 배치 조회로 바꾸면 END와 START를
     * 짝짓는 되짚기를 메모리에서 다시 구현해야 해 복잡성만 얹는다.
     *
     * <p>고칠 시점은 경고 상한이 풀리거나 이 되짚기가 목록 응답에 실릴 때다. 그때는
     * {@code (sessionId, memberId)}의 이벤트를 한 번에 읽어 메모리에서 짝을 맞추면 된다.
     */
    public List<WarningTrace> traceAll(Long sessionId, Long memberId, LiveSession session) {
        return warningRepository.findBySessionIdAndMemberIdOrderBySeqAsc(sessionId, memberId)
                .stream()
                .map(warning -> trace(warning, session))
                .toList();
    }

    private WarningTrace trace(Warning warning, LiveSession session) {
        if (warning.getAbsenceEventId() == null) {
            return new WarningTrace(warning.getSeq(), WarningBasis.PAUSE_OVERRUN,
                    warning.getCreatedAt(), null, null, null, null);
        }
        AbsenceEvent basisEvent = absenceEventRepository.findById(warning.getAbsenceEventId())
                .orElseThrow(() -> new IllegalStateException(
                        "경고의 근거 이벤트가 없다: warning=" + warning.getId()));
        LocalDateTime startedAt;
        LocalDateTime endedAt;
        if (basisEvent.getType() == AbsenceEventType.END) {
            startedAt = absenceEventRepository
                    .findFirstBySessionIdAndMemberIdAndIdLessThanOrderByIdDesc(
                            warning.getSessionId(), warning.getMemberId(), basisEvent.getId())
                    .filter(previous -> previous.getType() == AbsenceEventType.START)
                    .map(AbsenceEvent::getOccurredAt)
                    .orElse(null);
            endedAt = basisEvent.getOccurredAt();
        } else {
            startedAt = basisEvent.getOccurredAt();
            endedAt = session.getEndedAt();
        }
        Long absentSeconds = startedAt == null || endedAt == null
                ? null
                : Duration.between(startedAt, endedAt).getSeconds();
        long reportSkewSeconds = Duration
                .between(basisEvent.getOccurredAt(), basisEvent.getReportedAt()).getSeconds();
        return new WarningTrace(warning.getSeq(), WarningBasis.ABSENCE, warning.getCreatedAt(),
                startedAt, endedAt, absentSeconds, reportSkewSeconds);
    }
}
