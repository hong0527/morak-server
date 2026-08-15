package com.morak.session.service;

import com.morak.point.service.PointService;
import com.morak.point.type.PointReason;
import com.morak.session.entity.Eviction;
import com.morak.session.entity.SessionParticipant;
import com.morak.session.repository.EvictionRepository;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 3회 경고 퇴출의 단일 경로. 경고를 만드는 자리는 셋이지만(자리비움 판정 SS-4, Pause 초과
 * 복귀 SS-6, 종료 시 미결 정산) 퇴출 절차는 여기 하나뿐이다 — 나뉘면 {@code eviction} 행
 * 없이 상태만 EVICTED가 되거나 차감이 빠지는 경로가 생긴다.
 *
 * <p><b>임계 판정도 호출자가 아니라 이 클래스가 한다.</b> 호출자는 경고를 부여한 뒤 무조건
 * 물어보고, "3회인가"는 정책값을 쥔 여기서만 답한다.
 *
 * <p><b>잔여 인원 검사는 여기서 하지 않는다.</b> 퇴출이 사람을 줄이므로 이어서 해야 하는
 * 일이 맞지만, 그 검사는 세션 종료 루틴({@link SessionClosingService#closeIfUnderMinimum})을
 * 부르고 종료 루틴은 미결 정산에서 다시 경고를 만들어 이 클래스로 돌아온다 — 순환이다.
 * 그래서 {@code null}이 아닌 값을 받은 호출자가 이어서 종료 검사를 부른다. 호출자는 셋이고
 * 셋 다 그렇게 한다(종료 루틴 자신은 예외 — 이미 닫은 세션을 다시 닫을 이유가 없다).
 */
@Service
@Transactional
public class EvictionService {

    private static final Logger log = LoggerFactory.getLogger(EvictionService.class);

    private final EvictionRepository evictionRepository;
    private final PointService pointService;
    private final int evictWarningCount;
    private final int pointPenalty;

    public EvictionService(EvictionRepository evictionRepository,
                           PointService pointService,
                           @Value("${morak.session.evict-warning-count}") int evictWarningCount,
                           @Value("${morak.point.eviction-penalty}") int pointPenalty) {
        this.evictionRepository = evictionRepository;
        this.pointService = pointService;
        this.evictWarningCount = evictWarningCount;
        this.pointPenalty = pointPenalty;
    }

    /**
     * 경고 부여 직후 호출한다. 누적이 임계에 닿았으면 퇴출하고 만든 행을, 아니면 {@code null}을
     * 돌려준다.
     *
     * <p><b>차감은 퇴출과 같은 트랜잭션에서 즉시 한다.</b> SS-4 응답이 {@code pointDelta=-300}을
     * 싣는데 원장을 B1에 미루면 최대 1분 동안 그 값이 사실이 아니다 — 사용자는 화면에서 -300을
     * 보고 PT-1을 열면 아직 없는 상태를 만난다. 주체가 둘이 되는 문제는 멱등키
     * {@code (memberId, EVICTION_PENALTY, EVICTION, evictionId)}가 막는다. B1의
     * {@link SessionClosingService#settleEvictionPenalty}는 이 트랜잭션이 어떤 이유로 원장을
     * 남기지 못했을 때만 걸리는 안전망으로 남는다.
     *
     * <p>차감을 마지막에 두는 순서는 지켜야 한다. {@link PointService#award}가 잔액 캐시를
     * 벌크 UPDATE로 갱신하며 영속성 컨텍스트를 비우므로, 그 뒤에 엔티티에 쓴 값은 사라진다.
     * 퇴출 행 생성과 참가자 상태 전이가 먼저다.
     */
    public Eviction evictIfWarningLimitReached(Long sessionId, SessionParticipant participant,
                                               LocalDateTime now) {
        if (participant.getWarningCount() < evictWarningCount) {
            return null;
        }
        Eviction eviction = evictionRepository.save(Eviction.of(
                sessionId, participant.getMemberId(), participant.getWarningCount(),
                pointPenalty, now));
        // 상태 전이가 Pause 중에도 일어날 수 있어(D9) evict()가 pause_started_at을 함께 비운다
        participant.evict(now);
        log.info("경고 누적 퇴출: session={}, member={}, warningCount={}",
                sessionId, participant.getMemberId(), participant.getWarningCount());
        // TODO(12단계): LiveKit RemoveParticipant 호출. 3단계의 LEFT 경로도 같은 자리를 비워 뒀다
        pointService.award(eviction.getMemberId(), -pointPenalty, PointReason.EVICTION_PENALTY,
                eviction.getId());
        return eviction;
    }

    public int getPointPenalty() {
        return pointPenalty;
    }
}
