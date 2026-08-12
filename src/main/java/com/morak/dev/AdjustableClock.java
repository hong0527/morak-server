package com.morak.dev;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 개발 전용 조작 가능 시계. 매칭 만료(24시간)·자정 경계 같은 시각 판정을 실측하려면
 * 서버 시각을 움직여야 하는데, 모든 코드가 Clock 빈을 읽으므로 이 구현으로 갈아끼우면
 * 재시작 없이 시각을 고정하거나 밀 수 있다.
 *
 * <p>조작 전에는 시스템 시각과 동일하게 동작한다. 조작 API(DevClockController)가 빠진
 * 환경에서 이 빈이 뜨더라도 운영 시계와 구별되지 않는다.
 */
public final class AdjustableClock extends Clock {

    public enum Mode { SYSTEM, FIXED, OFFSET }

    // 모드·위임 시계·오프셋을 한 덩어리로 교체해 잠금 없이 스레드 안전을 확보한다.
    // 필드를 따로 두면 읽는 쪽이 조작 도중의 어긋난 조합을 볼 수 있다.
    private record State(Mode mode, Clock delegate, Long offsetMinutes) {}

    private final ZoneId zone;
    private final AtomicReference<State> state;

    public AdjustableClock(ZoneId zone) {
        this.zone = zone;
        this.state = new AtomicReference<>(new State(Mode.SYSTEM, Clock.system(zone), null));
    }

    public void fixAt(LocalDateTime fixedAt) {
        Instant instant = fixedAt.atZone(zone).toInstant();
        state.set(new State(Mode.FIXED, Clock.fixed(instant, zone), null));
    }

    /** 오프셋은 누적이 아니라 시스템 시각 기준 절대값이다. 재호출하면 이전 오프셋을 대체한다. */
    public void setOffsetMinutes(long minutes) {
        Clock shifted = Clock.offset(Clock.system(zone), Duration.ofMinutes(minutes));
        state.set(new State(Mode.OFFSET, shifted, minutes));
    }

    public void reset() {
        state.set(new State(Mode.SYSTEM, Clock.system(zone), null));
    }

    public Mode getMode() {
        return state.get().mode();
    }

    public Long getOffsetMinutes() {
        return state.get().offsetMinutes();
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId newZone) {
        // 현재 상태의 스냅샷을 반환한다. 이후 조작은 반환된 시계에 반영되지 않는다.
        return state.get().delegate().withZone(newZone);
    }

    @Override
    public Instant instant() {
        return state.get().delegate().instant();
    }
}
