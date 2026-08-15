package com.morak.dev;

import com.morak.common.error.BusinessException;
import com.morak.common.error.ErrorCode;
import com.morak.dev.dto.request.DevSessionSeedRequest;
import com.morak.dev.dto.response.DevSessionSeedResponse;
import com.morak.member.entity.Member;
import com.morak.member.repository.MemberRepository;
import com.morak.member.service.StreakService;
import com.morak.session.entity.LiveSession;
import com.morak.session.entity.SessionParticipant;
import com.morak.session.repository.LiveSessionRepository;
import com.morak.session.repository.SessionParticipantRepository;
import com.morak.session.service.SessionClosingService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
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
public class DevSessionSeedService {

    /** 과거 세션의 시작 시각. 자정 경계에 걸리지 않는 값이면 무엇이든 된다. */
    private static final int SEED_START_HOUR = 9;

    private final MemberRepository memberRepository;
    private final LiveSessionRepository liveSessionRepository;
    private final SessionParticipantRepository sessionParticipantRepository;
    private final SessionClosingService sessionClosingService;
    private final StreakService streakService;
    private final List<Integer> targetMinutesOptions;

    public DevSessionSeedService(MemberRepository memberRepository,
                                 LiveSessionRepository liveSessionRepository,
                                 SessionParticipantRepository sessionParticipantRepository,
                                 SessionClosingService sessionClosingService,
                                 StreakService streakService,
                                 @Value("${morak.match.target-minutes-options}")
                                 List<Integer> targetMinutesOptions) {
        this.memberRepository = memberRepository;
        this.liveSessionRepository = liveSessionRepository;
        this.sessionParticipantRepository = sessionParticipantRepository;
        this.sessionClosingService = sessionClosingService;
        this.streakService = streakService;
        this.targetMinutesOptions = targetMinutesOptions;
    }

    public DevSessionSeedResponse seed(DevSessionSeedRequest request) {
        int targetMinutes = validateTargetMinutes(request.targetMinutesOrDefault());
        Long memberId = memberRepository.findById(request.memberId())
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED))
                .getId();
        List<Long> sessionIds = new ArrayList<>();
        // 오름차순으로 처리해야 연속 판정이 실제 시간 순서와 같아진다. 뒤섞인 날짜를 받으면
        // last_completed_on이 미래로 먼저 가 그 뒤의 과거 완주가 캐시를 흔들지 못한다.
        for (LocalDate date : request.dates().stream().distinct().sorted().toList()) {
            sessionIds.add(seedOneDay(memberId, date, targetMinutes));
        }
        // 시드가 부른 지급 경로가 영속성 컨텍스트를 비웠으므로 회원 행을 다시 읽는다.
        // 처음 읽은 참조를 그대로 쓰면 Streak도 잔액도 시드 이전 값이 응답에 실린다.
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new IllegalStateException("시드 대상 회원이 사라졌다: " + memberId));
        // 지급 경로의 증분 갱신(Member#recordCompletion)은 마지막 완주일보다 과거인 날짜를
        // 무시한다. 이미 오늘 완주한 회원에게 어제 이전을 백필하면 streak_day에는 행이
        // 쌓이는데 캐시는 그대로라, 시드 직후의 AU-2와 이 응답이 실제 연속과 다른 값을
        // 보인다. 재계산은 AD-6 소급 인용과 같은 StreakService 경로다 — 시드용 복제본을
        // 두면 언젠가 한쪽만 고쳐진다.
        streakService.recountStreak(memberId);
        return new DevSessionSeedResponse(memberId, sessionIds, member.getCurrentStreak(),
                member.getPointBalance());
    }

    /**
     * 시드가 만드는 세션도 매칭이 만드는 것과 같은 길이여야 한다. 목록에 없는 값을 허용하면
     * 시드로만 존재하는 세션 길이가 생기고, 그 길이에서만 나오는 지급액으로 게이트를 통과하게 된다.
     */
    private int validateTargetMinutes(int targetMinutes) {
        if (!targetMinutesOptions.contains(targetMinutes)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    Map.of("targetMinutes", "허용값은 " + targetMinutesOptions + "입니다."));
        }
        return targetMinutes;
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
                .save(SessionParticipant.assign(session.getId(), memberId));
        participant.complete(0);
        // 지급 경로는 영속성 컨텍스트를 비우므로(SessionClosingService 주석) 여기서 만든
        // 세션 종료·완주 마킹을 넘기기 전에 확정한다. 시드가 만드는 것은 "끝난 세션의
        // 미지급 완주자"라는 미결 상태뿐이고, 지급은 B1과 같은 경로가 한다.
        sessionParticipantRepository.flush();
        sessionClosingService.awardCompletion(participant.getId());
        return session.getId();
    }
}
