package com.morak.dev;

import com.morak.common.error.BusinessException;
import com.morak.common.error.ErrorCode;
import com.morak.dev.dto.request.DevSessionSeedRequest;
import com.morak.dev.dto.response.DevSessionSeedResponse;
import com.morak.member.entity.Member;
import com.morak.member.repository.MemberRepository;
import com.morak.session.entity.LiveSession;
import com.morak.session.entity.SessionParticipant;
import com.morak.session.repository.LiveSessionRepository;
import com.morak.session.repository.SessionParticipantRepository;
import com.morak.session.service.SessionClosingService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * DEV-3 과거 완주 이력 시드. Streak 연속 판정과 목표 달성(D3)은 며칠에 걸친 이력이 있어야
 * 재현되는데, 30일짜리 목표를 실시간으로 채울 수는 없다.
 *
 * <p><b>완주 처리는 직접 하지 않고 B1과 같은 경로를 부른다</b>
 * ({@link SessionClosingService#awardCompletion}). 시드가 {@code streak_day}를 직접 INSERT하면
 * 배치가 쓰는 규칙과 갈라져, 시드로 만든 상태에서만 통과하는 게이트가 생긴다. 여기서는
 * "끝난 세션에 완주 마킹만 된 참가자"라는 미결 상태까지만 만들고 지급은 정식 경로에 맡긴다.
 */
@Service
@Profile("dev")
@ConditionalOnProperty(name = "morak.dev.enabled", havingValue = "true")
@Transactional
@RequiredArgsConstructor
public class DevSessionSeedService {

    /** 과거 세션의 시작 시각. 자정 경계에 걸리지 않는 값이면 무엇이든 된다. */
    private static final int SEED_START_HOUR = 9;

    private final MemberRepository memberRepository;
    private final LiveSessionRepository liveSessionRepository;
    private final SessionParticipantRepository sessionParticipantRepository;
    private final SessionClosingService sessionClosingService;

    public DevSessionSeedResponse seed(DevSessionSeedRequest request) {
        Member member = memberRepository.findById(request.memberId())
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED));
        List<Long> sessionIds = new ArrayList<>();
        // 오름차순으로 처리해야 연속 판정이 실제 시간 순서와 같아진다. 뒤섞인 날짜를 받으면
        // last_completed_on이 미래로 먼저 가 그 뒤의 과거 완주가 캐시를 흔들지 못한다.
        for (LocalDate date : request.dates().stream().distinct().sorted().toList()) {
            sessionIds.add(seedOneDay(member.getId(), date, request.targetMinutesOrDefault()));
        }
        return new DevSessionSeedResponse(member.getId(), sessionIds, member.getCurrentStreak(),
                member.getPointBalance());
    }

    private Long seedOneDay(Long memberId, LocalDate date, int targetMinutes) {
        LocalDateTime startedAt = date.atTime(SEED_START_HOUR, 0);
        LocalDateTime endsAt = startedAt.plusMinutes(targetMinutes);
        LiveSession session =
                liveSessionRepository.save(LiveSession.open(targetMinutes, startedAt, endsAt));
        session.assignRoomName();
        session.endNormally(endsAt);
        // 참가자 id가 완주 지급의 멱등키(ref)라 지급 전에 확정돼 있어야 한다.
        SessionParticipant participant = sessionParticipantRepository
                .saveAndFlush(SessionParticipant.assign(session.getId(), memberId));
        participant.complete(0);
        sessionClosingService.awardCompletion(participant.getId());
        return session.getId();
    }
}
