package com.morak.common.batch;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntSupplier;
import org.slf4j.Logger;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * 배치가 대상 하나의 실패로 나머지를 버리지 않게 한다.
 *
 * <p><b>대상 하나가 트랜잭션 하나라는 설계는 이 감싸기가 있어야 완성된다.</b> 없으면 롤백된
 * 건의 예외가 루프 밖으로 나가, 같은 실행에서 아직 손대지 않은 대상들이 통째로 다음 회차로
 * 밀린다. 세션 종료에서 실측한 적이 있다 — 종료 대상 2건 중 1건이 웹훅과 부딪히자 나머지
 * 1건이 22회 중 14회 남았다.
 *
 * <p>실패를 두 갈래로 나눈다. 데이터 접근 예외는 경합이라 경고에 그친다 — 다른 경로가 같은
 * 일을 먼저 끝냈다는 뜻이고, 남은 미결은 다음 회차가 거둔다. 오류로 올리면 정상 동작이 매분
 * 알람을 울린다. 그 밖의 예외는 경합이 아니므로 스택과 함께 오류로 남긴다.
 *
 * <p><b>예외의 종류만으로는 경합과 고장을 끝까지 가르지 못한다.</b> 탈퇴 파기에서 회원의
 * 식별자가 이미 선점돼 있으면 무결성 위반이 나는데, 이것도 데이터 접근 예외라 경합과 같은
 * 계열이다. 다른 경로와 부딪힌 것이 아니라 그 대상은 몇 번을 다시 해도 실패한다. 그래서
 * 시간 축으로 한 번 더 가른다 — 같은 대상이 연달아 실패하면 그것은 경합이 아니라 고장이다.
 * 이 승격이 없으면 데이터가 결손된 회원의 파기가 무기한 밀리는데 로그에는 경고 한 줄만
 * 남아 아무도 모른다.
 *
 * <p>배치마다 이 코드를 복사하지 않는 이유는 하나다 — 네 배치가 각자 갖고 있으면 실패 분류를
 * 바꾸는 날 한 곳이 빠진다. 로거를 받는 것은 로그가 배치 자신의 이름으로 남아야 하기 때문이다.
 */
@Component
public class BatchGuard {

    /**
     * 연속 실패가 이 횟수에 이르면 경고를 오류로 올린다.
     *
     * <p>3인 것은 배치 주기가 서로 다르기 때문이다. 매분 도는 세션 종료·매칭 만료에서는 3분
     * 안에 드러나고, 하루 한 번인 탈퇴 파기에서는 사흘이 걸린다. 회수가 아니라 시간으로 세면
     * 주기가 다른 배치를 한 기준으로 다룰 수 없고, 반대로 1로 낮추면 흔한 경합이 매번 오류가
     * 된다. 경합은 대개 다음 회차에 풀리므로 두 번까지는 기다려 준다.
     */
    private static final int ESCALATE_AFTER = 3;

    /**
     * 이 기간 동안 다시 실패하지 않은 기록은 잊는다. 성공하면 그 자리에서 지우지만, 대상이
     * 다른 경로에서 처리돼 배치 목록에서 사라지면 성공 신호가 오지 않아 기록만 남는다.
     * 잊는 창이 없으면 오래 살아 있는 프로세스에서 기록이 계속 쌓인다.
     */
    private static final Duration FORGET_AFTER = Duration.ofDays(2);

    private final Map<String, Failure> failures = new ConcurrentHashMap<>();
    private final Clock clock;

    public BatchGuard(Clock clock) {
        this.clock = clock;
    }

    public int guarded(Logger log, String step, Object targetId, IntSupplier work) {
        String key = step + ":" + targetId;
        try {
            int result = work.getAsInt();
            // 한 번 성공하면 앞선 실패는 지나간 일이다. 지우지 않으면 드문드문 난 실패가
            // 쌓여 멀쩡한 경합이 고장으로 승격된다.
            failures.remove(key);
            return result;
        } catch (DataAccessException e) {
            int streak = recordFailure(key);
            if (streak >= ESCALATE_AFTER) {
                log.error("{} {}회 연속 실패 — 경합이 아니라 고장이다: target={}, 원인={}",
                        step, streak, targetId, e.getClass().getSimpleName(), e);
            } else {
                log.warn("{} 건너뜀 — 다른 경로와 경합했다: target={}, 원인={}",
                        step, targetId, e.getClass().getSimpleName());
            }
            return 0;
        } catch (Exception e) {
            // 경합이 아닌 것까지 같은 문장으로 삼키면 진짜 결함이 정상 상황으로 위장된다.
            // 건너뛰는 동작은 같지만(나머지 대상은 처리해야 한다) 스택을 남겨 눈에 띄게 한다.
            recordFailure(key);
            log.error("{} 건너뜀 — 예상하지 못한 실패: target={}", step, targetId, e);
            return 0;
        }
    }

    private int recordFailure(String key) {
        LocalDateTime now = LocalDateTime.now(clock);
        forgetStale(now);
        return failures.compute(key, (ignored, previous) -> previous == null
                ? new Failure(1, now)
                : new Failure(previous.streak() + 1, now)).streak();
    }

    private void forgetStale(LocalDateTime now) {
        failures.values().removeIf(failure ->
                failure.lastFailedAt().isBefore(now.minus(FORGET_AFTER)));
    }

    private record Failure(int streak, LocalDateTime lastFailedAt) {
    }
}
