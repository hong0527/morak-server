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
     * 한 번 더 한다 — 그날의 첫 세션이 끝난 뒤에 목표를 설정한 회원은 두 번째 세션의 검사에서
     * 그날을 처음 인정받는다. 검사를 건너뛰면 그 하루가 영영 세어지지 않는다.
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
     * 완주 하루를 소급 기록한다(AD-6 이의 인용). 하는 일은 {@link #recordCompletion}과 같지만
     * <b>캐시를 증분이 아니라 재계산으로 갱신한다</b>.
     *
     * <p>되살린 날이 마지막 완주일보다 과거일 수 있기 때문이다. 그 경우 증분 갱신
     * ({@link Member#recordCompletion})은 날짜를 무시하고 끝나, {@code streak_day}에는 행이
     * 생겼는데 회원의 연속 일수는 끊긴 채로 남는다. 되살린 하루가 앞뒤 날짜를 잇는 경우가
     * 바로 이의를 인용하는 이유이므로, 여기서는 완주일 전체를 다시 세어 덮어쓴다.
     */
    public CompletionRecord recordBackfilledCompletion(Long memberId, LocalDate completedOn,
                                                       Long sessionId, LocalDateTime now) {
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new IllegalStateException("존재하지 않는 회원의 완주 기록: " + memberId));
        boolean countedToday = false;
        if (streakDayRepository.findByMemberIdAndCompletedOn(memberId, completedOn).isEmpty()) {
            streakDayRepository.save(StreakDay.complete(memberId, completedOn, sessionId));
            // 아래 재계산이 방금 넣은 행까지 읽어야 한다. INSERT가 아직 영속성 컨텍스트에만
            // 있으면 재계산은 소급 이전의 연속 일수를 그대로 돌려준다.
            streakDayRepository.flush();
            recountStreak(member);
            countedToday = true;
        }
        return new CompletionRecord(countedToday, member.getCurrentStreak(),
                achieveGoalIfReached(member, now));
    }

    /**
     * 완주일 기록을 근거로 캐시를 다시 센다.
     *
     * <p>기준점은 오늘이 아니라 <b>가장 최근 완주일</b>이다. 오늘부터 세면 어제 완주하고 오늘
     * 아직 안 한 회원의 연속이 0으로 굳어, 캐시를 읽는 자리의 판정
     * ({@link Member#currentStreakOn})과 이중으로 끊긴다 — 그쪽은 어제 완주를 살아 있는
     * 연속으로 본다.
     *
     * <p>읽는 범위에도 상한을 두지 않는다. 소급한 날 이후의 완주일을 빼고 세면
     * {@code last_completed_on}이 과거로 밀려, 되살린 하루가 오히려 연속을 깎는다.
     */
    private void recountStreak(Member member) {
        List<LocalDate> completedDays = streakDayRepository.findAllCompletedOn(
                member.getId(), PageRequest.ofSize(MAX_LOOKBACK_DAYS));
        if (completedDays.isEmpty()) {
            return;
        }
        LocalDate latest = completedDays.getFirst();
        member.applyRecountedStreak(latest, countBackFrom(new HashSet<>(completedDays), latest));
    }

    /**
     * 목표 달성 검사(★D3). 달성한 목표는 ACHIEVED로 닫는다 — ACTIVE로 두면 다음 완주 때마다
     * 같은 목표에 1,000p가 다시 지급된다. 재도전은 새 행이라 목표를 다시 설정할 수 있다.
     *
     * <p><b>조건이 둘인 이유는 같은 연속을 여러 번 팔 수 없게 하기 위해서다.</b> 연속 캐시만
     * 보면 7일 목표를 달성한 회원이 곧바로 7일 목표를 다시 걸고 다음 날 한 번 완주하는 것으로
     * 또 1,000p를 받는다({@code current_streak}가 이미 8이므로). 그래서 목표 시작일 이후에
     * 실제로 쌓인 완주일이 기간만큼 되는지를 함께 본다 — 목표는 "지금까지 며칠 했는가"가
     * 아니라 "여기서부터 며칠 더 하는가"이기 때문이다.
     *
     * <p>연속 캐시 조건도 남긴다. 시작일 이후 완주일이 7일이어도 중간에 하루 끊겼다면
     * 연속이 아니므로 달성이 아니다. 두 조건은 서로를 대체하지 못한다.
     */
    private Long achieveGoalIfReached(Member member, LocalDateTime now) {
        MemberGoal goal = memberGoalRepository
                .findFirstByMemberIdAndStatusOrderByIdDesc(member.getId(), GoalStatus.ACTIVE)
                .orElse(null);
        if (goal == null || member.getCurrentStreak() < goal.getPeriodDays()) {
            return null;
        }
        // 방금 넣은 완주일이 아직 영속성 컨텍스트에만 있으면 아래 집계가 그 하루를 빼고 센다.
        streakDayRepository.flush();
        if (streakDayRepository.countByMemberIdAndCompletedOnGreaterThanEqual(
                member.getId(), goal.getStartedOn()) < goal.getPeriodDays()) {
            return null;
        }
        goal.achieve(now);
        // 지급은 잔액 캐시를 벌크 UPDATE로 갱신하며 영속성 컨텍스트를 비운다. 여기까지 만든
        // 변경(streak_day INSERT·Streak 캐시·목표 ACHIEVED)을 먼저 내보내지 않으면, 지급
        // 시점의 clear가 그것들을 그대로 버려 원장만 남는다. 지금은 addPoint의
        // flushAutomatically가 같은 일을 해 주지만, 정합성을 남의 도메인 애너테이션에
        // 맡기지 않는다.
        memberRepository.flush();
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
