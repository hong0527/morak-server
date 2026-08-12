package com.morak.member.service;

import com.morak.member.entity.Member;
import com.morak.member.entity.MemberGoal;
import com.morak.member.entity.StreakDay;
import com.morak.member.repository.MemberGoalRepository;
import com.morak.member.repository.MemberRepository;
import com.morak.member.repository.StreakDayRepository;
import com.morak.member.type.GoalStatus;
import com.morak.point.service.PointService;
import com.morak.point.type.PointReason;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 완주일 기록과 목표 달성 판정. 세션 도메인이 "누가 완주했는가"를 정하면 그 결과가 회원에게
 * 남기는 흔적은 전부 여기서 만든다.
 *
 * <p><b>Streak가 오르는 곳을 한 군데로 모은 이유는 하루 다회 완주 때문이다</b>(★D2).
 * {@code streak_day}의 UNIQUE가 그날의 두 번째 INSERT를 막고, 캐시는 그 결과를 따라간다 —
 * 캐시를 먼저 올리는 경로가 하나라도 생기면 하루에 2씩 오르는 회원이 나온다.
 *
 * <p>미완주일의 리셋을 미리 써 두는 배치는 없다. 연속이 끊겼는지는 다음 완주 시점에
 * {@code last_completed_on}과의 거리로 판정한다({@link Member#recordCompletion}).
 */
@Service
@Transactional
public class StreakService {

    private static final Logger log = LoggerFactory.getLogger(StreakService.class);

    /**
     * 역방향 연속 일수를 셀 때 한 번에 읽는 최대 행 수. 목표 기간이 최장 30일이라 이보다
     * 긴 연속을 화면에서 구분할 이유가 없고, 상한이 없으면 오래된 회원의 SS-8 한 번이
     * 완주 기록 전체를 읽는다.
     */
    private static final int MAX_LOOKBACK_DAYS = 400;

    private final StreakDayRepository streakDayRepository;
    private final MemberRepository memberRepository;
    private final MemberGoalRepository memberGoalRepository;
    private final PointService pointService;
    private final int goalAchievedPoint;

    public StreakService(StreakDayRepository streakDayRepository,
                         MemberRepository memberRepository,
                         MemberGoalRepository memberGoalRepository,
                         PointService pointService,
                         @Value("${morak.point.goal-achieved}") int goalAchievedPoint) {
        this.streakDayRepository = streakDayRepository;
        this.memberRepository = memberRepository;
        this.memberGoalRepository = memberGoalRepository;
        this.pointService = pointService;
        this.goalAchievedPoint = goalAchievedPoint;
    }

    /**
     * @param countedToday 이 세션이 그날의 완주를 처음 성립시켰는가. false는 같은 날 다른
     *                     세션이 이미 기록해 Streak가 중복으로 오르지 않았다는 뜻이다(★D2)
     */
    public record CompletionRecord(boolean countedToday, int currentStreak, Long achievedGoalId) {}

    /** 기준일과 그 전날의 연속 완주 일수. SS-8의 {@code before}·{@code after}가 그대로 쓴다. */
    public record StreakSnapshot(int before, int after) {}

    /**
     * 완주 하루를 기록한다. 같은 날이 이미 기록돼 있으면 캐시를 건드리지 않고 목표 검사만
     * 한 번 더 한다 — 목표를 늦게 설정한 회원이 이미 채운 연속을 인정받지 못하면 안 된다.
     */
    public CompletionRecord recordCompletion(Long memberId, LocalDate completedOn, Long sessionId,
                                             LocalDateTime now) {
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new IllegalStateException("존재하지 않는 회원의 완주 기록: " + memberId));
        boolean countedToday = false;
        if (streakDayRepository.findByMemberIdAndCompletedOn(memberId, completedOn).isEmpty()) {
            streakDayRepository.save(StreakDay.complete(memberId, completedOn, sessionId));
            member.recordCompletion(completedOn);
            countedToday = true;
        }
        return new CompletionRecord(countedToday, member.getCurrentStreak(),
                achieveGoalIfReached(member, now));
    }

    /**
     * 목표 달성 검사(★D3). 달성한 목표는 ACHIEVED로 닫는다 — ACTIVE로 두면 다음 완주 때마다
     * 같은 목표에 1,000p가 다시 지급된다. 재도전은 새 행이라 목표를 다시 설정할 수 있다.
     */
    private Long achieveGoalIfReached(Member member, LocalDateTime now) {
        MemberGoal goal = memberGoalRepository
                .findFirstByMemberIdAndStatusOrderByIdDesc(member.getId(), GoalStatus.ACTIVE)
                .orElse(null);
        if (goal == null || member.getCurrentStreak() < goal.getPeriodDays()) {
            return null;
        }
        goal.achieve(now);
        pointService.award(member.getId(), goalAchievedPoint, PointReason.GOAL_ACHIEVED,
                goal.getId(), now);
        log.info("목표 달성: member={}, goal={}, {}일 연속",
                member.getId(), goal.getId(), member.getCurrentStreak());
        return goal.getId();
    }

    /**
     * 그 세션 시점의 Streak. {@code after}는 기준일까지, {@code before}는 그 전날까지의
     * 역방향 연속 일수다. 기준일에 완주 기록이 없으면 {@code after}는 0이다 — 그날 연속이
     * 끊겼다는 뜻이고, 이것이 미완주일 리셋(★D3)이 실제로 드러나는 자리다.
     */
    @Transactional(readOnly = true)
    public StreakSnapshot snapshotOn(Long memberId, LocalDate date) {
        List<LocalDate> completedDays = streakDayRepository.findCompletedOnUpTo(
                memberId, date, PageRequest.ofSize(MAX_LOOKBACK_DAYS));
        Set<LocalDate> days = new HashSet<>(completedDays);
        return new StreakSnapshot(countBackFrom(days, date.minusDays(1)),
                countBackFrom(days, date));
    }

    private static int countBackFrom(Set<LocalDate> days, LocalDate from) {
        int count = 0;
        for (LocalDate day = from; days.contains(day); day = day.minusDays(1)) {
            count++;
        }
        return count;
    }
}
