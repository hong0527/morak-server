package com.morak.session.service;

import com.morak.common.error.BusinessException;
import com.morak.common.error.ErrorCode;
import com.morak.session.dto.request.AbsenceEventRequest;
import com.morak.session.dto.response.AbsenceEventResponse;
import com.morak.session.entity.AbsenceEvent;
import com.morak.session.entity.Eviction;
import com.morak.session.entity.LiveSession;
import com.morak.session.entity.SessionParticipant;
import com.morak.session.entity.Warning;
import com.morak.session.repository.AbsenceEventRepository;
import com.morak.session.repository.LiveSessionRepository;
import com.morak.session.repository.SessionParticipantRepository;
import com.morak.session.repository.WarningRepository;
import com.morak.session.type.AbsenceEventType;
import com.morak.session.type.ParticipantStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SS-4 자리비움 판정. <b>클라이언트는 자기 얼굴이 안 보이기 시작했다·다시 보인다만 말하고,
 * 그 구간이 몇 회의 경고인지와 3회째에 닿았는지는 서버가 계산한다</b>(★D4). 회수는
 * {@link AbsenceWarningPolicy}가 구간 길이로 정한다.
 *
 * <p>신뢰할 수 없는 입력을 다루는 방어선이 셋이고 하나만 빠져도 뚫린다.
 * <ul>
 *   <li>멱등키({@code clientSeq}) — 같은 이벤트를 재전송해 경고를 쌓지 못하게 한다
 *   <li>레이트리밋 — 짧은 간격의 START/END 폭주로 판정 경로를 두드리지 못하게 한다
 *   <li>시각 검증 — 세션 시작 이전이나 미래의 {@code occurredAt}으로 없던 구간을 만들지 못하게 한다
 * </ul>
 *
 * <p><b>미보고는 판정하지 않는다</b>(D13). 이벤트가 끊긴 것을 자리비움으로 추정하면 3단계의
 * 재접속 유예와 이중으로 걸려, 돌아온 사람이 경고를 안고 복귀한다. 연결이 끊긴 축은
 * {@link SessionExitService}가 {@code LEFT(DEVICE_ISSUE)}로만 정산한다.
 */
@Service
@Transactional
public class AbsenceJudgeService {

    private static final Logger log = LoggerFactory.getLogger(AbsenceJudgeService.class);

    /** 단말 시계가 조금 앞선 정도는 정상이다. 이 폭을 넘는 미래 시각만 조작으로 본다. */
    private static final int CLOCK_SKEW_TOLERANCE_SECONDS = 5;

    /**
     * Pause 시작이 자리비움 구간을 끊을 때 서버가 만드는 END의 {@code clientSeq}. 단말이 보내는
     * 값은 {@code @PositiveOrZero}라 음수와 겹칠 수 없고, Pause는 세션당 1회(SS-5)여서 이 값으로
     * {@code uk_ae}가 충돌하는 경로가 없다.
     */
    private static final long PAUSE_BOUNDARY_CLIENT_SEQ = -1L;

    /**
     * 복귀가 Pause 중에 열린 자리비움 구간을 버릴 때 쓰는 {@code clientSeq}. Pause도 복귀도
     * 세션당 1회여서 {@code -1}과 마찬가지로 {@code uk_ae}가 충돌할 경로가 없다.
     */
    private static final long RESUME_BOUNDARY_CLIENT_SEQ = -2L;

    private final LiveSessionRepository liveSessionRepository;
    private final SessionParticipantRepository sessionParticipantRepository;
    private final AbsenceEventRepository absenceEventRepository;
    private final WarningRepository warningRepository;
    private final EvictionService evictionService;
    private final SessionClosingService closingService;
    private final AbsenceWarningPolicy warningPolicy;
    private final Clock clock;
    private final int minIntervalSeconds;

    public AbsenceJudgeService(LiveSessionRepository liveSessionRepository,
                               SessionParticipantRepository sessionParticipantRepository,
                               AbsenceEventRepository absenceEventRepository,
                               WarningRepository warningRepository,
                               EvictionService evictionService,
                               SessionClosingService closingService,
                               AbsenceWarningPolicy warningPolicy,
                               Clock clock,
                               @Value("${morak.session.absence-min-interval-seconds}")
                               int minIntervalSeconds) {
        this.liveSessionRepository = liveSessionRepository;
        this.sessionParticipantRepository = sessionParticipantRepository;
        this.absenceEventRepository = absenceEventRepository;
        this.warningRepository = warningRepository;
        this.evictionService = evictionService;
        this.closingService = closingService;
        this.warningPolicy = warningPolicy;
        this.clock = clock;
        this.minIntervalSeconds = minIntervalSeconds;
    }

    /**
     * 검사 순서는 참가 자격이 먼저다(§0-3). 세션 상태를 먼저 보면 참가자가 아닌 사람이 세션
     * 번호를 훑어 남의 세션이 끝났는지를 알아낼 수 있다 — 실제로 비참가자가 종료된 세션에
     * 409 {@code SESSION_ENDED}를 받아 그 세션의 존재와 상태를 알 수 있었다.
     */
    public AbsenceEventResponse report(Long memberId, Long sessionId,
                                       AbsenceEventRequest request) {
        // 세션 행을 먼저 잡는다(SessionClosingService의 LOCK_ORDER). 이 경로는 경고·퇴출로
        // 참가자 행을 고친 뒤 종료 판정까지 이어지므로, 잠금을 뒤에서 잡으면 B1과 서로를 기다린다.
        LiveSession session = liveSessionRepository.findByIdForUpdate(sessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SESSION_NOT_FOUND));
        SessionParticipant participant = sessionParticipantRepository
                .findBySessionIdAndMemberId(sessionId, memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_SESSION_PARTICIPANT));
        if (!session.isLive()) {
            throw new BusinessException(ErrorCode.SESSION_ENDED);
        }
        rejectIfNotJudgeable(participant);

        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime occurredAt = toServerTime(request);
        validateOccurredAt(occurredAt, session, now);

        // 순서가 곧 명세다. 재전송(409)을 레이트리밋(429)보다 먼저 답해야 재시도한 클라이언트가
        // "이미 접수됨"을 보고 조용히 끝낼 수 있다.
        if (absenceEventRepository.existsBySessionIdAndMemberIdAndClientSeq(
                sessionId, memberId, request.clientSeq())) {
            throw new BusinessException(ErrorCode.DUPLICATE_ABSENCE_EVENT);
        }
        AbsenceEvent previous = absenceEventRepository
                .findFirstBySessionIdAndMemberIdOrderByIdDesc(sessionId, memberId)
                .orElse(null);
        rejectIfTooFrequent(previous, now);

        AbsenceEvent event = save(AbsenceEvent.report(sessionId, memberId, request.type(),
                request.clientSeq(), occurredAt, now));
        if (!isAbsenceClosed(request, previous, participant)) {
            return AbsenceEventResponse.of(participant.getWarningCount(), null,
                    evictionService.getPointPenalty(), null);
        }
        long absentSeconds = absentSeconds(previous.getOccurredAt(), occurredAt, session);
        int warnings = warningPolicy.warningCountFor(absentSeconds);
        if (warnings == 0) {
            // 경고가 없어도 닫힌 구간의 초는 내린다 — 임계에 얼마나 가까웠는지를 보여 줘야
            // 사용자가 다음 자리비움을 조절할 수 있다(SS-5의 closedAbsenceSeconds와 같은 계약)
            return AbsenceEventResponse.of(participant.getWarningCount(), null,
                    evictionService.getPointPenalty(), absentSeconds);
        }
        return warn(sessionId, participant, event, absentSeconds, warnings, now);
    }

    /**
     * SS-5 Pause 시작이 부르는 자리비움 구간 마감. <b>열려 있는 START를 Pause 시작 시각 기준으로
     * 정산하고 닫는다.</b>
     *
     * <p>닫지 않으면 PAUSED 구간이 자리비움에 섞인다. 10분을 화장실에 있다 돌아와 END를 보내면
     * 그 END는 ACTIVE 상태에서 도착하므로 판정 대상이 되고, 간격이 Pause 시작 전의 START부터
     * 계산돼 임계를 훌쩍 넘긴다. 세션 종료 정산도 같은 START를 집어 든다. 어느 쪽이든
     * "PAUSED 구간은 자리비움에 들어가지 않는다"(★D1·D9)가 성립하지 않는다.
     *
     * <p>정산은 END를 받은 것과 같은 규칙이다 — 구간 길이만큼 경고가 붙고({@link AbsenceWarningPolicy})
     * 그것이 3회째에 닿으면 퇴출이다. 길이를 여기서 따로 세지 않는 이유도 같다. Pause를 켜는 것으로
     * 이미 진행 중인 자리비움을 무르게 하지 않는 것이 D9의 요지다.
     *
     * <p>호출자는 Pause 상태 전이 <b>전에</b> 부른다. 뒤에 부르면 여기서 난 퇴출이 방금 세운
     * PAUSED를 덮어써, 응답은 화장실 모드 시작인데 참가자는 퇴출된 상태가 된다.
     */
    public PauseClosure closeAbsenceOnPause(LiveSession session, SessionParticipant participant,
                                            LocalDateTime at) {
        if (participant.getStatus() != ParticipantStatus.ACTIVE) {
            return null;
        }
        Long sessionId = session.getId();
        AbsenceEvent last = absenceEventRepository
                .findFirstBySessionIdAndMemberIdOrderByIdDesc(sessionId, participant.getMemberId())
                .orElse(null);
        if (last == null || last.getType() != AbsenceEventType.START) {
            return null;
        }
        AbsenceEvent closing = absenceEventRepository.saveAndFlush(AbsenceEvent.report(
                sessionId, participant.getMemberId(), AbsenceEventType.END,
                PAUSE_BOUNDARY_CLIENT_SEQ, at, at));
        long absentSeconds = absentSeconds(last.getOccurredAt(), at, session);
        int warnings = warningPolicy.warningCountFor(absentSeconds);
        if (warnings == 0) {
            return new PauseClosure(absentSeconds, false);
        }
        log.info("Pause 시작으로 자리비움 구간 마감: session={}, member={}, 지속 {}초",
                sessionId, participant.getMemberId(), absentSeconds);
        warn(sessionId, participant, closing, absentSeconds, warnings, at);
        return new PauseClosure(absentSeconds, true);
    }

    /**
     * SS-6 복귀가 부르는 마감. <b>Pause 중에 열린 자리비움 구간을 판정 없이 버린다.</b>
     *
     * <p>단말이 화장실 모드에서 감지를 멈추지 않으면(<b>카메라를 꺼도 검은 프레임이 계속
     * 흐르면 그렇게 된다</b>) PAUSED 구간에서 START가 올라온다. 그 START는 도착 시점에는
     * 판정되지 않지만, 복귀 뒤에 오는 END가 ACTIVE 상태에서 도착해 짝이 맞아 버린다 —
     * 화장실에 있던 시간이 그대로 자리비움으로 계산돼 경고가 붙는다. 실제로 재현됐다.
     *
     * <p>여기서 막는 근거는 {@link #closeAbsenceOnPause}가 세운 불변식이다. Pause 시작이
     * 열린 START를 반드시 닫으므로, <b>복귀 시점에 열려 있는 START는 Pause 중에 생긴 것밖에
     * 없다.</b> 시각을 비교할 필요가 없다({@code pauseStartedAt}은 복귀와 함께 비워진다).
     *
     * <p>경고를 주지 않는 것이 Pause 시작 쪽과 다른 점이다. 시작 쪽은 <b>이미 진행 중이던</b>
     * 자리비움이라 Pause로 무르게 하지 않는 것이 D9의 요지이지만, 여기서 버리는 것은
     * 화장실에 있는 동안 생긴 구간이라 애초에 자리비움이 아니다(★D1·D9).
     *
     * <p>호출자는 상태 전이 <b>전에</b> 부른다 — 뒤로 미룰 이유는 없고, Pause 시작 쪽과
     * 순서를 맞춰 두어야 두 경로를 같이 읽는 사람이 헷갈리지 않는다.
     */
    public void discardAbsenceOnResume(Long sessionId, SessionParticipant participant,
                                       LocalDateTime at) {
        AbsenceEvent last = absenceEventRepository
                .findFirstBySessionIdAndMemberIdOrderByIdDesc(sessionId, participant.getMemberId())
                .orElse(null);
        if (last == null || last.getType() != AbsenceEventType.START) {
            return;
        }
        absenceEventRepository.saveAndFlush(AbsenceEvent.report(sessionId,
                participant.getMemberId(), AbsenceEventType.END,
                RESUME_BOUNDARY_CLIENT_SEQ, at, at));
        log.info("복귀로 Pause 중 자리비움 구간 폐기: session={}, member={}",
                sessionId, participant.getMemberId());
    }

    /**
     * Pause 시작이 마감한 자리비움 구간. 마감할 구간이 없었으면 {@code null}이다.
     *
     * <p>이 값을 호출자에게 돌려주는 이유는 하나다 — <b>여기서 부여한 경고를 본인이 알아야
     * 한다.</b> 3회째는 조건부 UPDATE가 0행이 되어 409로 드러나지만, 1·2회째는 화장실 모드가
     * 정상 시작되므로 응답에 싣지 않으면 사용자는 경고가 늘어난 것을 모른 채 다음 경고에
     * 퇴출된다. 같은 이유로 SS-6은 처음부터 경고 여부를 응답에 싣고 있었다.
     */
    public record PauseClosure(long absentSeconds, boolean warningIssued) {
    }

    /**
     * 퇴출은 종점이고(409), 이미 나간 사람은 세션 밖이다(403). 어느 쪽이든 경고를 만들지
     * 않는 것이 핵심이다 — 유예 초과 판정과 이 요청의 도착 순서는 보장되지 않아, 끊겨서
     * LEFT된 사람의 이벤트가 뒤늦게 도착하는 일이 정상적으로 일어난다.
     */
    private void rejectIfNotJudgeable(SessionParticipant participant) {
        if (participant.getStatus() == ParticipantStatus.EVICTED) {
            throw new BusinessException(ErrorCode.ALREADY_EVICTED);
        }
        if (!participant.isPresent()) {
            throw new BusinessException(ErrorCode.NOT_SESSION_PARTICIPANT);
        }
    }

    /** 단말 타임존이 서버와 다를 수 있어 같은 순간을 서버 시각으로 환산해 저장한다. */
    private LocalDateTime toServerTime(AbsenceEventRequest request) {
        return request.occurredAt().atZoneSameInstant(clock.getZone()).toLocalDateTime();
    }

    /**
     * 세션 시작 이전이나 미래의 시각은 없던 자리비움을 만들 수 있어 거부한다. 판정을
     * {@code occurredAt} 간격으로 하는 이상 이 검증이 그 값의 유일한 울타리다.
     *
     * <p>양쪽 경계에 같은 허용폭을 준다. 단말은 초 단위로 끊은 시각을 보내는데 세션 시작
     * 시각에는 밀리초가 남아 있어, 입장 직후의 정상 보고가 시작 시각보다 몇백 밀리초
     * 이르게 찍히는 일이 실제로 생긴다.
     */
    private void validateOccurredAt(LocalDateTime occurredAt, LiveSession session,
                                    LocalDateTime now) {
        if (occurredAt.isBefore(session.getStartedAt().minusSeconds(CLOCK_SKEW_TOLERANCE_SECONDS))
                || occurredAt.isAfter(now.plusSeconds(CLOCK_SKEW_TOLERANCE_SECONDS))) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }

    /**
     * 판정할 자리비움 초. <b>구간을 세션 예정 종료 시각에서 끊는다 — 끝도 시작도.</b>
     *
     * <p>{@code ends_at}이 지났는데 B1이 아직 돌지 않은 최대 1분 동안 세션은 여전히 LIVE라
     * 이 경로가 열려 있고, 그 창에서 도착한 END는 종료 이후까지 늘어난 구간으로 판정됐다.
     * 같은 START를 B1이 집었다면 종료 시각까지만 세므로({@code settleUnclosedAbsence}),
     * 같은 자리비움이 <b>누가 먼저 닿았느냐에 따라</b> 경고가 되기도 안 되기도 했다 —
     * 55초 비운 사람이 종료 20초 뒤에 END를 보내면 75초로 계산돼 경고를 받았다.
     *
     * <p>끊어 두면 두 경로가 같은 값을 낸다. 세션이 끝난 뒤의 시간은 어차피 자리를 지킬
     * 의무가 없는 시간이라, 그 시간이 경고 판정에 실리는 것 자체가 틀린 것이기도 하다.
     *
     * <p>여기서 재는 것은 결국 <b>자리를 지킬 의무가 있던 시간과 구간이 겹친 만큼</b>이라,
     * 시작도 같은 자리에서 끊는다. 끝만 끊으면 그 창에서 <b>시작한</b> 자리비움이 뺄셈을
     * 거꾸로 돌려 음수를 낸다 — 종료 5초 뒤에 START, 20초 뒤에 END를 보내면 -5초가 나왔고
     * 그 값이 SS-4·SS-5 응답의 {@code closedAbsenceSeconds}로 그대로 나갔다. 임계 이하라
     * 경고는 붙지 않았지만, 이 필드는 영상이 없는 이 서비스에서 당사자가 이의(AP-1)를 쓸 때
     * 댈 유일한 근거라 음수로는 어느 쪽으로도 읽히지 않는다. 양끝을 끊으면 겹침이 없는
     * 구간은 자연히 0이 되고, 하한을 따로 두는 것과 달리 "겹친 만큼"이라는 정의가
     * 식에 그대로 남는다.
     */
    private static long absentSeconds(LocalDateTime startedAt, LocalDateTime endedAt,
                                      LiveSession session) {
        LocalDateTime endsAt = session.getEndsAt();
        LocalDateTime judgedStart = startedAt.isAfter(endsAt) ? endsAt : startedAt;
        LocalDateTime judgedEnd = endedAt.isAfter(endsAt) ? endsAt : endedAt;
        return Duration.between(judgedStart, judgedEnd).getSeconds();
    }

    /** 정상 클라이언트는 검출 상태가 바뀔 때만 보내므로 초 단위로 몰아치는 요청은 위조 신호다. */
    private void rejectIfTooFrequent(AbsenceEvent previous, LocalDateTime now) {
        if (previous != null
                && previous.getReportedAt().plusSeconds(minIntervalSeconds).isAfter(now)) {
            throw new BusinessException(ErrorCode.ABSENCE_RATE_LIMITED);
        }
    }

    private AbsenceEvent save(AbsenceEvent event) {
        try {
            // 멱등 검사를 통과한 동시 요청 둘이 여기서 만난다. uk_ae 위반은 서버 잘못이 아니라
            // 재전송이므로 500이 아니라 409로 답한다.
            return absenceEventRepository.saveAndFlush(event);
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.DUPLICATE_ABSENCE_EVENT);
        }
    }

    /**
     * 자리비움 구간이 닫혔는가. 짝이 없는 END(직전이 START가 아님)는 정상 입력이고 판정하지
     * 않는다. PAUSED 구간도 판정에서 빠진다 — 화장실 모드는 자리를 비우라고 만든 기능이라
     * 여기서 경고를 주면 SS-5가 함정이 된다.
     *
     * <p><b>여기서 막는 것은 PAUSED 중에 도착한 END뿐이다.</b> Pause 중에 열린 START를
     * 복귀 뒤의 END가 닫는 경로는 이 조건으로 걸리지 않는다 — 그 END는 ACTIVE 상태에서
     * 도착하기 때문이다. 그쪽은 {@link #discardAbsenceOnResume}이 복귀 시점에 끊는다.
     */
    private boolean isAbsenceClosed(AbsenceEventRequest request, AbsenceEvent previous,
                                    SessionParticipant participant) {
        return request.type() == AbsenceEventType.END
                && previous != null
                && previous.getType() == AbsenceEventType.START
                && participant.getStatus() == ParticipantStatus.ACTIVE;
    }

    /**
     * 퇴출이 났으면 잔여 인원 검사를 이어서 부른다. 퇴출도 사람이 세션에서 빠지는 경로라
     * 남은 인원이 최소 미만이면 그 자리에서 세션이 끝나야 한다(D12).
     */
    private AbsenceEventResponse warn(Long sessionId, SessionParticipant participant,
                                      AbsenceEvent event, long absentSeconds, int warnings,
                                      LocalDateTime now) {
        Eviction eviction = null;
        // 한 번에 여러 회가 붙을 수 있다(구간이 길수록 많다). 한 회씩 올리고 매번 물어보는
        // 것은 퇴출 판정이 EvictionService 하나뿐이기 때문이다 — 여기서 미리 세어 건너뛰면
        // 임계값을 아는 자리가 둘이 된다. 퇴출되면 그 자리에서 멈춰 상한을 넘겨 쌓지 않는다.
        for (int i = 0; i < warnings && eviction == null; i += 1) {
            int seq = participant.addWarning();
            warningRepository.save(Warning.fromAbsence(
                    sessionId, participant.getMemberId(), seq, event.getId(), now));
            log.info("자리비움 경고: session={}, member={}, seq={}, 지속 {}초",
                    sessionId, participant.getMemberId(), seq, absentSeconds);
            eviction = evictionService.evictIfWarningLimitReached(sessionId, participant, now);
        }
        if (eviction != null) {
            closingService.closeIfUnderMinimum(sessionId, now);
        }
        return AbsenceEventResponse.of(participant.getWarningCount(),
                eviction == null ? null : eviction.getId(), evictionService.getPointPenalty(),
                absentSeconds);
    }
}
