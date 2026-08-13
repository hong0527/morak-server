package com.morak.support;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;

/**
 * 등록만 받고 실행하지 않는 스케줄러. 테스트 컨텍스트의 {@code TaskScheduler} 자리를 이것으로
 * 채워 {@code @Scheduled}가 걸린 배치(B1·B2·B4)와 재접속 유예 스위퍼가 저절로 돌지 않게 한다.
 *
 * <p><b>배치가 테스트 도중에 스스로 돌면 판정이 흔들린다.</b> B1은 매분 0초에 종료 예정이 지난
 * 세션을 닫고 미지급 완주자를 흡수 지급하는데, "재실행해도 결과가 같다"를 확인하는 테스트가
 * 그 사이 실행에 끼면 무엇이 몇 번 돌았는지 알 수 없다. 시각을 {@link com.morak.dev.AdjustableClock}
 * 으로 밀고 다니는 테스트에서는 더 나쁘다 — 스케줄러가 옆 테스트가 밀어 둔 시각으로 남의 세션을
 * 닫는다.
 *
 * <p>배치를 실제로 확인하는 테스트는 배치 빈의 {@code run()}을 직접 부른다. DEV-4 수동 트리거가
 * 부르는 것과 같은 메서드라, 여기서 확인한 동작이 곧 스케줄 실행의 동작이다.
 */
public class NoopTaskScheduler implements TaskScheduler {

    @Override
    public ScheduledFuture<?> schedule(Runnable task, Trigger trigger) {
        return discarded();
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable task, Instant startTime) {
        return discarded();
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Instant startTime,
                                                  Duration period) {
        return discarded();
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Duration period) {
        return discarded();
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Instant startTime,
                                                     Duration delay) {
        return discarded();
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Duration delay) {
        return discarded();
    }

    private static ScheduledFuture<Object> discarded() {
        return new DiscardedFuture();
    }

    /** 등록 시점에 이미 취소된 것으로 보이는 핸들. 호출부가 null 검사를 하지 않아도 되게 한다. */
    private static final class DiscardedFuture implements ScheduledFuture<Object> {

        @Override
        public long getDelay(TimeUnit unit) {
            return 0;
        }

        @Override
        public int compareTo(java.util.concurrent.Delayed other) {
            return 0;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return true;
        }

        @Override
        public boolean isCancelled() {
            return true;
        }

        @Override
        public boolean isDone() {
            return true;
        }

        @Override
        public Object get() {
            return null;
        }

        @Override
        public Object get(long timeout, TimeUnit unit) {
            return null;
        }
    }
}
