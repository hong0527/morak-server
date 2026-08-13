package com.morak.match.service;

import com.morak.common.error.BusinessException;
import com.morak.common.error.ErrorCode;
import com.morak.match.dto.request.MatchRequestCreateRequest;
import com.morak.match.dto.response.MatchRequestResponse;
import com.morak.match.entity.MatchBlock;
import com.morak.match.entity.MatchEvent;
import com.morak.match.entity.MatchLock;
import com.morak.match.entity.MatchRequest;
import com.morak.match.repository.MatchBlockRepository;
import com.morak.match.repository.MatchEventRepository;
import com.morak.match.repository.MatchLockRepository;
import com.morak.match.repository.MatchRequestRepository;
import com.morak.match.type.MatchEventType;
import com.morak.match.type.MatchRequestStatus;
import com.morak.member.repository.MemberRepository;
import com.morak.member.type.MemberStatus;
import com.morak.report.entity.Sanction;
import com.morak.report.repository.SanctionRepository;
import com.morak.session.entity.LiveSession;
import com.morak.session.entity.SessionParticipant;
import com.morak.session.repository.EvictionRepository;
import com.morak.session.repository.LiveSessionRepository;
import com.morak.session.repository.SessionParticipantRepository;
import com.morak.session.type.ParticipantStatus;
import com.morak.session.type.SessionStatus;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 매칭 대기열 (API명세서 MT-1·MT-2·MT-3, 배치 B2).
 *
 * <p><b>이 클래스의 규칙 하나: 잠금 획득 순서는 예외 없이 회원 행 → 조건 행이다.</b>
 * ({@link #LOCK_ORDER} 참조) 한 경로라도 역순으로 잡으면 두 트랜잭션이 서로가 쥔 행을
 * 기다리는 교착이 생기고, H2의 LOCK_TIMEOUT이 만료될 때까지 양쪽이 멈춘다.
 * 회원 행이 필요 없는 경로(MT-3·B2)는 조건 행만 잡는다 — 순서를 지키는 것이지 건너뛰는 게 아니다.
 *
 * <p>둘째 규칙: {@code match_request.status}를 바꾸는 모든 경로는 ① 조건 행 잠금
 * ② 조건부 UPDATE({@code WHERE status='WAITING'}) ③ {@code active_member_id=NULL} 셋을
 * 함께 한다. ①이 빠지면 6인 확정이 겹치고, ②가 빠지면 경합에서 남의 요청을 덮어쓰며,
 * ③이 빠지면 {@code uk_mr_active} 때문에 그 회원의 재요청이 영구 차단된다.
 * ②③은 {@link MatchRequestRepository#markMatched}·{@link MatchRequestRepository#releaseWaiting}
 * 안에서 한 문장으로 묶여 있어 따로 떼어낼 수 없다.
 */
@Service
@Transactional(readOnly = true)
public class MatchService {

    /**
     * 잠금 순서 상수. 값이 아니라 규약을 코드에 못 박기 위한 것이다 — 새 경로를 만드는
     * 사람이 이 상수를 보고 순서를 확인하도록 {@link #lockMemberRow}·{@link #lockConditionRow}
     * 주석이 이것을 가리킨다.
     */
    private static final String LOCK_ORDER = "member:{id} → match:{minutes}";

    private static final Set<ParticipantStatus> PARTICIPATING =
            Set.of(ParticipantStatus.ACTIVE, ParticipantStatus.PAUSED);

    private final MatchRequestRepository matchRequestRepository;
    private final MatchLockRepository matchLockRepository;
    private final MatchBlockRepository matchBlockRepository;
    private final MatchEventRepository matchEventRepository;
    private final LiveSessionRepository liveSessionRepository;
    private final SessionParticipantRepository sessionParticipantRepository;
    private final EvictionRepository evictionRepository;
    private final SanctionRepository sanctionRepository;
    private final MemberRepository memberRepository;
    private final Clock clock;
    private final List<Integer> targetMinutesOptions;
    private final int waitExpireMinutes;
    private final int rematchCooldownMinutes;
    private final int requiredParticipants;

    public MatchService(MatchRequestRepository matchRequestRepository,
                        MatchLockRepository matchLockRepository,
                        MatchBlockRepository matchBlockRepository,
                        MatchEventRepository matchEventRepository,
                        LiveSessionRepository liveSessionRepository,
                        SessionParticipantRepository sessionParticipantRepository,
                        EvictionRepository evictionRepository,
                        SanctionRepository sanctionRepository,
                        MemberRepository memberRepository,
                        Clock clock,
                        @Value("${morak.match.target-minutes-options}")
                        List<Integer> targetMinutesOptions,
                        @Value("${morak.match.wait-expire-minutes}") int waitExpireMinutes,
                        @Value("${morak.match.rematch-cooldown-minutes}")
                        int rematchCooldownMinutes,
                        @Value("${morak.session.required-participants}") int requiredParticipants) {
        this.matchRequestRepository = matchRequestRepository;
        this.matchLockRepository = matchLockRepository;
        this.matchBlockRepository = matchBlockRepository;
        this.matchEventRepository = matchEventRepository;
        this.liveSessionRepository = liveSessionRepository;
        this.sessionParticipantRepository = sessionParticipantRepository;
        this.evictionRepository = evictionRepository;
        this.sanctionRepository = sanctionRepository;
        this.memberRepository = memberRepository;
        this.clock = clock;
        this.targetMinutesOptions = targetMinutesOptions;
        this.waitExpireMinutes = waitExpireMinutes;
        this.rematchCooldownMinutes = rematchCooldownMinutes;
        this.requiredParticipants = requiredParticipants;
    }

    /**
     * MT-1 매칭 요청. 게이트 검사부터 세션 생성까지 전부 이 하나의 트랜잭션 안에서 끝난다.
     *
     * <p>경계를 여기 그은 이유는 6인 확정과 세션 생성이 나뉘면 "매칭은 됐는데 들어갈 방이
     * 없는 회원"이 생기기 때문이다. 잠금은 커밋과 함께 풀리므로 해제 코드를 따로 두지 않는다.
     */
    @Transactional
    public MatchRequestResponse request(Long memberId, MatchRequestCreateRequest request) {
        int targetMinutes = validateTargetMinutes(request.targetMinutes());
        LocalDateTime now = LocalDateTime.now(clock);

        lockMemberRow(memberId);
        rejectIfNotActive(memberId);
        rejectIfAlreadyWaiting(memberId);
        rejectIfInSession(memberId);
        rejectIfInRematchCooldown(memberId, now);
        lockConditionRow(targetMinutes);

        // 대기열 조회 전에 내 요청을 DB에 반영한다. "대기 5명 + 나 = 6"을 따로 계산하지 않고
        // 나를 포함한 대기열을 한 번만 읽기 위해서다 — 자기 제외로 세면 7명째에 성사된다.
        MatchRequest mine = matchRequestRepository.saveAndFlush(MatchRequest.request(
                memberId, targetMinutes, now, now.plusMinutes(waitExpireMinutes)));

        List<MatchRequest> group = selectGroup(mine, now);
        if (group == null) {
            return MatchRequestResponse.of(mine, countWaiting(targetMinutes), requiredParticipants);
        }
        Long sessionId = openSession(group, targetMinutes, now);
        return MatchRequestResponse.matched(mine, sessionId, requiredParticipants);
    }

    /** MT-2 폴링. 활성 요청이 없으면 마지막 요청의 종결 상태를 알려 준다(성사·만료·취소). */
    public MatchRequestResponse getMine(Long memberId) {
        MatchRequest request = matchRequestRepository.findFirstByMemberIdOrderByIdDesc(memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NO_ACTIVE_MATCH_REQUEST));
        return MatchRequestResponse.of(request, waitingCountOf(request), requiredParticipants);
    }

    /** MT-3 취소. 회원 행은 잡지 않는다 — 이 경로에 회원 단위 직렬화가 필요한 판정이 없다. */
    @Transactional
    public void cancel(Long memberId, Long matchRequestId) {
        MatchRequest request = matchRequestRepository.findById(matchRequestId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NO_ACTIVE_MATCH_REQUEST));
        if (!request.getMemberId().equals(memberId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        lockConditionRow(request.getTargetMinutes());

        int cancelled = matchRequestRepository.releaseWaiting(
                List.of(matchRequestId), MatchRequestStatus.CANCELLED, MatchRequestStatus.WAITING);
        if (cancelled == 0) {
            // 잠금을 기다리는 사이 상태가 바뀌었다. 위에서 읽은 엔티티는 벌크 UPDATE가
            // 컨텍스트를 비우기 전의 값이라 믿을 수 없으므로 다시 읽는다.
            MatchRequest current = matchRequestRepository.findById(matchRequestId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.NO_ACTIVE_MATCH_REQUEST));
            if (current.getStatus() == MatchRequestStatus.MATCHED) {
                throw new BusinessException(ErrorCode.ALREADY_MATCHED);
            }
            // 이미 취소·만료된 요청이다. 취소를 다시 요청한 것을 오류로 만들 이유가 없다.
            return;
        }
        matchEventRepository.save(MatchEvent.occurred(
                MatchEventType.WAIT_CANCELLED, memberId, null, LocalDateTime.now(clock)));
    }

    /**
     * AU-4 탈퇴 신청이 부르는 활성 요청 해제. 호출자가 회원 행을 이미 잡은 상태로 들어오고
     * 여기서 조건 행을 추가로 잡으므로 잠금 순서({@value #LOCK_ORDER})가 유지된다.
     */
    @Transactional
    public void cancelActiveRequest(Long memberId) {
        MatchRequest request = matchRequestRepository.findByActiveMemberId(memberId).orElse(null);
        if (request == null) {
            return;
        }
        lockConditionRow(request.getTargetMinutes());
        int cancelled = matchRequestRepository.releaseWaiting(
                List.of(request.getId()), MatchRequestStatus.CANCELLED, MatchRequestStatus.WAITING);
        if (cancelled == 0) {
            // 잠금을 기다리는 사이 성사되거나 만료됐다. 이미 해제된 요청이라 할 일이 없다.
            return;
        }
        matchEventRepository.save(MatchEvent.occurred(
                MatchEventType.WAIT_CANCELLED, memberId, null, LocalDateTime.now(clock)));
    }

    /**
     * B2 만료 처리. 조건 하나가 트랜잭션 하나다 — 4조건을 한 트랜잭션에 묶으면 잠금을 오래
     * 쥐고 있게 되고, 한 조건의 실패가 나머지 조건의 만료까지 되돌린다.
     */
    @Transactional
    public int expireWaiting(int targetMinutes, LocalDateTime now) {
        lockConditionRow(targetMinutes);
        List<MatchRequest> expired = matchRequestRepository
                .findByStatusAndTargetMinutesAndExpiresAtLessThan(
                        MatchRequestStatus.WAITING, targetMinutes, now);
        if (expired.isEmpty()) {
            return 0;
        }
        List<Long> ids = expired.stream().map(MatchRequest::getId).toList();
        int released = matchRequestRepository.releaseWaiting(
                ids, MatchRequestStatus.EXPIRED, MatchRequestStatus.WAITING);
        for (MatchRequest request : expired) {
            matchEventRepository.save(MatchEvent.occurred(
                    MatchEventType.WAIT_EXPIRED, request.getMemberId(), null, now));
        }
        return released;
    }

    /** 만료 대기가 실제로 있는 조건만 돌려준다. 없는 조건까지 잠글 이유가 없다. */
    public List<Integer> findConditionsWithExpired(LocalDateTime now) {
        return matchRequestRepository.findTargetMinutesWithExpired(
                MatchRequestStatus.WAITING, now);
    }

    // ── MT-1 게이트 ──

    private int validateTargetMinutes(Integer targetMinutes) {
        if (!targetMinutesOptions.contains(targetMinutes)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    Map.of("targetMinutes", "허용값은 " + targetMinutesOptions + "입니다."));
        }
        return targetMinutes;
    }

    /**
     * <b>회원 상태는 잠금을 얻은 뒤에 다시 읽는다.</b> 인터셉터(§0-2)가 본 값은 이 트랜잭션이
     * 회원 행을 잡기 전의 것이라, 그 뒤에 커밋된 탈퇴 신청을 보지 못한다. 실측에서 MT-1과
     * AU-4를 동시에 보내면 10건 중 7건이 탈퇴 대기 회원의 대기 요청으로 남았고, 그 요청이
     * 6인 확정에 뽑히면 입장할 수 없는 사람(SS-2가 403이다)이 남의 세션에서 자리를 차지한
     * 채 완주로 집계됐다.
     *
     * <p>회원 행을 잡은 뒤라는 것이 판정의 근거다 — AU-4도 같은 행을 잡고 커밋하므로,
     * 여기까지 왔다는 것은 상대 트랜잭션이 이미 끝났다는 뜻이다.
     */
    private void rejectIfNotActive(Long memberId) {
        MemberStatus status = memberRepository.findStatusById(memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
        // 코드는 인터셉터와 같은 것을 쓴다. 같은 상태를 API마다 다른 코드로 내리면
        // 클라이언트가 한쪽에서만 재로그인·탈퇴 안내 화면을 그린다.
        if (status == MemberStatus.WITHDRAW_PENDING) {
            throw new BusinessException(ErrorCode.WITHDRAWAL_PENDING);
        }
        if (status != MemberStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
    }

    private void rejectIfAlreadyWaiting(Long memberId) {
        if (matchRequestRepository.findByActiveMemberId(memberId).isPresent()) {
            throw new BusinessException(ErrorCode.DUPLICATE_MATCH_REQUEST);
        }
    }

    private void rejectIfInSession(Long memberId) {
        if (!findMembersInSession(List.of(memberId)).isEmpty()) {
            throw new BusinessException(ErrorCode.ALREADY_IN_ACTIVE_SESSION);
        }
    }

    private void rejectIfInRematchCooldown(Long memberId, LocalDateTime now) {
        evictionRepository.findFirstByMemberIdAndRevokedAtIsNullOrderByCreatedAtDesc(memberId)
                .map(eviction -> eviction.getCreatedAt().plusMinutes(rematchCooldownMinutes))
                .filter(availableAt -> availableAt.isAfter(now))
                .ifPresent(availableAt -> {
                    // 언제부터 되는지 알려 주지 않으면 클라이언트가 재시도 시점을 못 정한다
                    Map<String, Object> details = new HashMap<>();
                    details.put("availableAt", availableAt);
                    throw new BusinessException(ErrorCode.REMATCH_COOLDOWN, details);
                });
    }

    // ── 6인 확정 ──

    /**
     * 선착순으로 6인을 고른다. 채우지 못하면 {@code null}이고 요청자는 대기 상태로 남는다.
     *
     * <p>요청자는 항상 집합에 들어간다 — 성사 판정의 기준이 "내 요청으로 6이 찼는가"이기
     * 때문이다. 나머지는 대기가 오래된 순으로 채우되, 차단 관계가 걸린 후보는 건너뛰고
     * 다음 순번으로 넘어간다. 대기열에서 빼는 게 아니라 이 조합에서만 배제하는 것이라
     * 신고자와 대상은 각각 다른 사람과는 매칭될 수 있다(★D6).
     */
    private List<MatchRequest> selectGroup(MatchRequest mine, LocalDateTime now) {
        List<MatchRequest> queue = matchRequestRepository
                .findByStatusAndTargetMinutesOrderByRequestedAtAscIdAsc(
                        MatchRequestStatus.WAITING, mine.getTargetMinutes());
        List<Long> queuedMembers = queue.stream().map(MatchRequest::getMemberId).toList();

        Set<Long> excluded = new HashSet<>(findMembersInSession(queuedMembers));
        excluded.addAll(findSanctionedMembers(queuedMembers, now));
        excluded.addAll(findInactiveMembers(queuedMembers));
        Map<Long, Set<Long>> blocks = blocksAmong(queuedMembers);

        List<MatchRequest> picked = new ArrayList<>();
        picked.add(mine);
        for (MatchRequest candidate : queue) {
            if (picked.size() == requiredParticipants) {
                break;
            }
            if (candidate.getId().equals(mine.getId())
                    || excluded.contains(candidate.getMemberId())
                    || isBlockedWithAny(picked, candidate.getMemberId(), blocks)) {
                continue;
            }
            picked.add(candidate);
        }
        return picked.size() == requiredParticipants ? picked : null;
    }

    /**
     * 세션과 참가자를 만들고 요청 6건을 성사로 확정한다.
     *
     * <p>순서가 중요하다. 세션을 먼저 만들어야 요청에 적을 세션 번호가 생기고, 확정 UPDATE를
     * 마지막에 둬야 영향 행 수가 6이 아닐 때 세션·참가자까지 함께 롤백된다. 6인 확정 이후
     * 어느 한 조각만 남는 상태가 존재하면 안 된다.
     */
    private Long openSession(List<MatchRequest> group, int targetMinutes, LocalDateTime now) {
        LiveSession session = liveSessionRepository.saveAndFlush(
                LiveSession.open(targetMinutes, now, now.plusMinutes(targetMinutes)));
        // 방 이름은 id가 정해진 뒤에야 확정된다. 이 호출을 빠뜨리면 임시 UUID가 그대로 남는다.
        session.assignRoomName();
        liveSessionRepository.flush();

        for (MatchRequest request : group) {
            // joined_at은 채우지 않는다. 매칭 확정과 실제 입장은 다른 사건이고, 입장 시각은
            // SS-10 웹훅이 최초 1회만 기록한다. NULL은 "매칭됐지만 아직 안 들어옴"이다.
            sessionParticipantRepository.save(
                    SessionParticipant.assign(session.getId(), request.getMemberId()));
        }

        List<Long> ids = group.stream().map(MatchRequest::getId).toList();
        int matched = matchRequestRepository.markMatched(
                ids, session.getId(), MatchRequestStatus.MATCHED, MatchRequestStatus.WAITING);
        if (matched != requiredParticipants) {
            // 조건 행을 쥐고 있으므로 도달할 수 없는 분기다. 도달했다면 잠금을 우회한 경로가
            // 생겼다는 뜻이라, 인원이 어긋난 세션을 남기느니 전부 롤백시킨다.
            throw new IllegalStateException(
                    "성사 확정 행 수가 어긋났다. 기대 %d, 실제 %d, 조건 %d분"
                            .formatted(requiredParticipants, matched, targetMinutes));
        }
        for (MatchRequest request : group) {
            matchEventRepository.save(MatchEvent.occurred(
                    MatchEventType.MATCH_COMPLETED, request.getMemberId(), session.getId(), now));
        }
        return session.getId();
    }

    // ── 조회 보조 ──

    private List<Long> findMembersInSession(List<Long> memberIds) {
        if (memberIds.isEmpty()) {
            return List.of();
        }
        return sessionParticipantRepository.findMemberIdsInSession(
                memberIds, PARTICIPATING, SessionStatus.LIVE);
    }

    /**
     * 참여할 수 없는 상태의 회원. {@link #rejectIfNotActive}가 이미 막는 자리지만, 대기열에
     * 남은 요청은 그 게이트를 지난 지 오래된 것일 수 있다 — 요청 뒤에 탈퇴한 회원의 행이
     * 만료 전까지 대기열에 남는다(AU-4가 회수하지 못한 경우).
     */
    private Set<Long> findInactiveMembers(List<Long> memberIds) {
        if (memberIds.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(memberRepository.findIdsWithStatusOtherThan(
                memberIds, MemberStatus.ACTIVE));
    }

    private Set<Long> findSanctionedMembers(List<Long> memberIds, LocalDateTime now) {
        if (memberIds.isEmpty()) {
            return Set.of();
        }
        // 유효 판정은 인터셉터 ④와 같은 규칙이어야 해서 Sanction이 소유한다
        return sanctionRepository.findByMemberIdIn(memberIds).stream()
                .filter(sanction -> sanction.isEffectiveAt(now))
                .map(Sanction::getMemberId)
                .collect(Collectors.toSet());
    }

    /** 방향을 무시하고 대칭으로 다룬다. 한 방향만 남은 행이 있어도 배제가 성립해야 한다. */
    private Map<Long, Set<Long>> blocksAmong(List<Long> memberIds) {
        if (memberIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Set<Long>> blocks = new HashMap<>();
        for (MatchBlock block : matchBlockRepository.findWithin(memberIds)) {
            blocks.computeIfAbsent(block.getMemberId(), key -> new HashSet<>())
                    .add(block.getBlockedMemberId());
            blocks.computeIfAbsent(block.getBlockedMemberId(), key -> new HashSet<>())
                    .add(block.getMemberId());
        }
        return blocks;
    }

    private boolean isBlockedWithAny(List<MatchRequest> picked, Long memberId,
                                     Map<Long, Set<Long>> blocks) {
        Set<Long> blocked = blocks.get(memberId);
        if (blocked == null) {
            return false;
        }
        return picked.stream().anyMatch(request -> blocked.contains(request.getMemberId()));
    }

    private int countWaiting(int targetMinutes) {
        return Math.toIntExact(matchRequestRepository.countByStatusAndTargetMinutes(
                MatchRequestStatus.WAITING, targetMinutes));
    }

    /**
     * 대기 인원. WAITING이면 지금 그 조건에 몇 명이 모였는지고, 성사됐으면 정원이 다 찬
     * 것이므로 정원과 같다. 취소·만료된 요청은 대기열에 자리가 없어 0이다.
     */
    private int waitingCountOf(MatchRequest request) {
        return switch (request.getStatus()) {
            case WAITING -> countWaiting(request.getTargetMinutes());
            case MATCHED -> requiredParticipants;
            case CANCELLED, EXPIRED -> 0;
        };
    }

    // ── 잠금 ──

    /**
     * 회원 행 잠금. 잠금 순서는 {@value #LOCK_ORDER}이므로 이 호출은 항상
     * {@link #lockConditionRow}보다 먼저다.
     */
    private void lockMemberRow(Long memberId) {
        matchLockRepository.findByLockKey(MatchLock.memberKey(memberId))
                .orElseThrow(() -> new IllegalStateException(
                        "회원 잠금 행이 없다. 가입 트랜잭션이 깨진 계정이다: " + memberId));
    }

    /**
     * 조건 행 잠금. 잠금 순서는 {@value #LOCK_ORDER}이므로 이 호출 뒤에 회원 행을 잡으면
     * 안 된다 — 역순 획득 경로가 하나라도 생기면 교차 대기 데드락이다.
     *
     * <p>행이 없으면 만들지 않고 실패시킨다. 시더가 돌지 않은 상태에서 런타임에 만들기
     * 시작하면 동시 진입 시 INSERT 경합으로 복구가 불가능해진다(MatchLockSeeder 주석).
     */
    private void lockConditionRow(int targetMinutes) {
        matchLockRepository.findByLockKey(MatchLock.conditionKey(targetMinutes))
                .orElseThrow(() -> new IllegalStateException(
                        "조건 잠금 행이 없다. 시더가 돌지 않았다: " + targetMinutes));
    }
}
