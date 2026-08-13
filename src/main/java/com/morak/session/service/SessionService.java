package com.morak.session.service;

import com.morak.common.dto.PageParams;
import com.morak.common.dto.PageResponse;
import com.morak.common.error.BusinessException;
import com.morak.common.error.ErrorCode;
import com.morak.member.repository.MediaConsentRepository;
import com.morak.member.repository.MemberGoalRepository;
import com.morak.member.repository.MemberNickname;
import com.morak.member.repository.MemberRepository;
import com.morak.member.repository.StreakDayRepository;
import com.morak.member.service.StreakService;
import com.morak.member.type.GoalStatus;
import com.morak.session.dto.request.SessionGoalRequest;
import com.morak.session.dto.response.LivekitTokenResponse;
import com.morak.session.dto.response.MySessionSummaryResponse;
import com.morak.session.dto.response.SessionDetailResponse;
import com.morak.session.dto.response.SessionGoalResponse;
import com.morak.session.dto.response.SessionResultResponse;
import com.morak.session.entity.Eviction;
import com.morak.session.entity.LiveSession;
import com.morak.session.entity.SessionParticipant;
import com.morak.session.repository.EvictionRepository;
import com.morak.session.repository.LiveSessionRepository;
import com.morak.session.repository.SessionParticipantRepository;
import com.morak.session.type.ParticipantStatus;
import com.morak.session.type.SessionStatus;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 라이브 세션 조회·참여 (API명세서 SS-1·SS-2·SS-3·SS-9).
 *
 * <p>세션 상태를 바꾸는 일은 이 클래스에 없다. 입장 기록은 웹훅이(SS-10), 퇴장·종료는
 * {@link SessionExitService}가 맡는다 — 조회 API가 상태를 건드리기 시작하면 화면을 한 번
 * 더 열었다는 이유로 세션이 끝나는 일이 생긴다.
 */
@Service
@Transactional(readOnly = true)
public class SessionService {

    private static final Set<ParticipantStatus> PRESENT =
            Set.of(ParticipantStatus.ACTIVE, ParticipantStatus.PAUSED);

    /** 최근 세션이 위로 오도록 고정한다. 세션 번호는 생성 순이라 시작 시각 순과 같다. */
    private static final Sort HISTORY_SORT = Sort.by(Sort.Direction.DESC, "sessionId");

    private final LiveSessionRepository liveSessionRepository;
    private final SessionParticipantRepository sessionParticipantRepository;
    private final MemberRepository memberRepository;
    private final MediaConsentRepository mediaConsentRepository;
    private final StreakDayRepository streakDayRepository;
    private final MemberGoalRepository memberGoalRepository;
    private final EvictionRepository evictionRepository;
    private final StreakService streakService;
    private final LiveKitTokenProvider liveKitTokenProvider;

    public SessionService(LiveSessionRepository liveSessionRepository,
                          SessionParticipantRepository sessionParticipantRepository,
                          MemberRepository memberRepository,
                          MediaConsentRepository mediaConsentRepository,
                          StreakDayRepository streakDayRepository,
                          MemberGoalRepository memberGoalRepository,
                          EvictionRepository evictionRepository,
                          StreakService streakService,
                          LiveKitTokenProvider liveKitTokenProvider) {
        this.liveSessionRepository = liveSessionRepository;
        this.sessionParticipantRepository = sessionParticipantRepository;
        this.memberRepository = memberRepository;
        this.mediaConsentRepository = mediaConsentRepository;
        this.streakDayRepository = streakDayRepository;
        this.memberGoalRepository = memberGoalRepository;
        this.evictionRepository = evictionRepository;
        this.streakService = streakService;
        this.liveKitTokenProvider = liveKitTokenProvider;
    }

    /** SS-1. 참가 이력이 있으면 상태와 무관하게 볼 수 있다 — 퇴장한 사람도 결과 화면을 연다. */
    public SessionDetailResponse getSession(Long memberId, Long sessionId) {
        LiveSession session = findSession(sessionId);
        List<SessionParticipant> participants =
                sessionParticipantRepository.findBySessionIdOrderByIdAsc(sessionId);
        SessionParticipant me = participants.stream()
                .filter(participant -> participant.getMemberId().equals(memberId))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_SESSION_PARTICIPANT));
        List<Long> memberIds = participants.stream().map(SessionParticipant::getMemberId).toList();
        // 익명 닉네임만 꺼낸다. 실명·SNS 값은 어떤 경우에도 내려가지 않는다.
        Map<Long, String> nicknames = memberRepository.findByIdIn(memberIds).stream()
                .collect(Collectors.toMap(MemberNickname::getId, MemberNickname::getNickname));
        return SessionDetailResponse.of(session, participants, nicknames, memberId,
                myEvictionId(me, sessionId, memberId));
    }

    /**
     * SS-8 세션 결과. 검사 순서가 곧 명세다 — 참가 이력이 먼저이고 종료 여부가 그다음이다.
     * 뒤집으면 남의 진행 중 세션을 찔러 본 사람이 403 대신 409를 받아 그 세션의 존재와
     * 진행 여부를 알게 된다.
     */
    public SessionResultResponse getResult(Long memberId, Long sessionId) {
        LiveSession session = findSession(sessionId);
        List<SessionParticipant> participants =
                sessionParticipantRepository.findBySessionIdOrderByIdAsc(sessionId);
        SessionParticipant me = participants.stream()
                .filter(participant -> participant.getMemberId().equals(memberId))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_SESSION_PARTICIPANT));
        if (session.getStatus() != SessionStatus.ENDED) {
            throw new BusinessException(ErrorCode.SESSION_NOT_ENDED);
        }
        Map<Long, String> nicknames = memberRepository
                .findByIdIn(participants.stream().map(SessionParticipant::getMemberId).toList())
                .stream()
                .collect(Collectors.toMap(MemberNickname::getId, MemberNickname::getNickname));
        LocalDate completedOn = session.getEndedAt().toLocalDate();
        // 그날의 완주가 이 세션으로 성립했는지. 다른 세션이 먼저 기록했으면 Streak는
        // 이미 올라 있고 이 세션은 중복 증가시키지 않았다는 뜻이다(★D2).
        boolean countedToday = streakDayRepository
                .findByMemberIdAndCompletedOn(memberId, completedOn)
                .map(streakDay -> streakDay.getSessionId().equals(sessionId))
                .orElse(false);
        // B1이 목표 달성 시각으로 세션 종료 시각을 그대로 쓴다. 저장 컬럼을 새로 두지 않고
        // 그 값으로 "이 세션이 목표를 채웠는가"를 답한다.
        boolean goalAchieved = memberGoalRepository.existsByMemberIdAndStatusAndAchievedAt(
                memberId, GoalStatus.ACHIEVED, session.getEndedAt());
        return SessionResultResponse.of(session, participants, nicknames, me,
                streakService.snapshotOn(memberId, completedOn), countedToday, goalAchieved,
                myEvictionId(me, sessionId, memberId));
    }

    /**
     * 이의 신청(AP-1)에 들어갈 퇴출 번호. 퇴출된 본인에게만 값이 있다.
     *
     * <p>퇴출이 아닌 참가자에게는 조회조차 하지 않는다. {@code uk_eviction} 덕에 결과가
     * 0행인 것이 확실하고, 화면 대부분은 퇴출과 무관하다.
     */
    private Long myEvictionId(SessionParticipant me, Long sessionId, Long memberId) {
        if (me.getStatus() != ParticipantStatus.EVICTED) {
            return null;
        }
        return evictionRepository.findBySessionIdAndMemberId(sessionId, memberId)
                .map(Eviction::getId)
                .orElse(null);
    }

    /**
     * SS-2. 토큰 발급은 상태를 바꾸지 않는다 — 실제 입장은 웹훅이 기록한다.
     *
     * <p>검사 순서가 곧 명세의 응답이다(§SS-2 절차 1~5). 동의 검사를 앞으로 당기면 종료된
     * 세션에 대해 CONSENT_REQUIRED가 나가서 클라이언트가 동의 화면으로 잘못 빠진다.
     */
    public LivekitTokenResponse issueToken(Long memberId, Long sessionId) {
        LiveSession session = findSession(sessionId);
        // 참가 자격이 세션 상태보다 먼저다(§0-3). 뒤집으면 참가자가 아닌 사람이 세션 번호를
        // 훑어 남의 세션이 끝났는지를 알 수 있다.
        SessionParticipant participant = findParticipant(sessionId, memberId);
        if (!session.isLive()) {
            throw new BusinessException(ErrorCode.SESSION_ENDED);
        }
        if (participant.getStatus() == ParticipantStatus.EVICTED) {
            throw new BusinessException(ErrorCode.ALREADY_EVICTED);
        }
        if (!PRESENT.contains(participant.getStatus())) {
            // LEFT는 재입장 불가다. 참가자가 아닌 것과 같은 응답으로 막는다.
            throw new BusinessException(ErrorCode.NOT_SESSION_PARTICIPANT);
        }
        if (!mediaConsentRepository.existsById(memberId)) {
            throw new BusinessException(ErrorCode.CONSENT_REQUIRED);
        }
        String roomName = session.getLivekitRoomName();
        return new LivekitTokenResponse(
                liveKitTokenProvider.getHost(),
                roomName,
                liveKitTokenProvider.issue(memberId, roomName),
                LiveKitTokenProvider.identityOf(memberId),
                // 오디오 publish 권한을 토큰에서 빼므로 항상 false다(D23)
                false,
                liveKitTokenProvider.getTokenTtlSeconds());
    }

    /** SS-3. 세션 중 몇 번이든 고칠 수 있고, 끝난 세션에는 쓰지 않는다. */
    @Transactional
    public SessionGoalResponse updateGoal(Long memberId, Long sessionId,
                                          SessionGoalRequest request) {
        LiveSession session = findSession(sessionId);
        SessionParticipant participant = findParticipant(sessionId, memberId);
        if (!session.isLive()) {
            throw new BusinessException(ErrorCode.SESSION_ENDED);
        }
        // 퇴출은 SS-2와 같은 응답으로 알린다. 같은 상태를 API마다 다른 코드로 내리면
        // 클라이언트가 한쪽에서는 이의 신청(AP-1)을, 다른 쪽에서는 "남의 세션"을 그린다.
        if (participant.getStatus() == ParticipantStatus.EVICTED) {
            throw new BusinessException(ErrorCode.ALREADY_EVICTED);
        }
        if (!PRESENT.contains(participant.getStatus())) {
            throw new BusinessException(ErrorCode.NOT_SESSION_PARTICIPANT);
        }
        participant.updateGoalText(request.goalText());
        return SessionGoalResponse.from(participant);
    }

    /** SS-9. 세션 상태 필터는 선택이고, 생략하면 전체다. */
    public PageResponse<MySessionSummaryResponse> getMySessions(Long memberId,
                                                                SessionStatus status,
                                                                Integer page, Integer size) {
        PageParams params = PageParams.of(page, size);
        Page<SessionParticipant> participants = status == null
                ? sessionParticipantRepository.findByMemberId(
                        memberId, params.toPageable(HISTORY_SORT))
                : sessionParticipantRepository.findByMemberIdAndSessionStatus(
                        memberId, status, params.toPageable(HISTORY_SORT));
        // 한 페이지 분량의 세션을 한 번에 읽는다. 행마다 세션을 조회하면 20건짜리 페이지가
        // 쿼리 21개가 된다.
        Map<Long, LiveSession> sessions = liveSessionRepository
                .findAllById(participants.getContent().stream()
                        .map(SessionParticipant::getSessionId)
                        .toList())
                .stream()
                .collect(Collectors.toMap(LiveSession::getId, Function.identity()));
        return PageResponse.of(participants, participant -> MySessionSummaryResponse.of(
                participant, sessions.get(participant.getSessionId())));
    }

    private LiveSession findSession(Long sessionId) {
        return liveSessionRepository.findById(sessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SESSION_NOT_FOUND));
    }

    private SessionParticipant findParticipant(Long sessionId, Long memberId) {
        return sessionParticipantRepository.findBySessionIdAndMemberId(sessionId, memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_SESSION_PARTICIPANT));
    }
}
