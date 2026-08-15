package com.morak.support;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.IntConsumer;

/**
 * 같은 순간에 같은 자리를 두드리는 요청을 만든다.
 *
 * <p><b>순차 호출로는 이 프로젝트의 방어선을 시험할 수 없다.</b> 멱등키 사전 조회·활성 요청
 * 검사·재고 확인은 전부 "먼저 보고 없으면 쓴다"라서 순차 재실행에서는 언제나 통과한다.
 * 뚫리는 자리는 둘이 함께 조회를 통과한 뒤이고, 그때 막는 것은 DB 제약과 행 잠금이다.
 *
 * <p>출발선을 {@link CyclicBarrier}로 맞추는 이유가 그것이다. 스레드를 띄우는 것만으로는
 * 먼저 뜬 쪽이 먼저 커밋해 경합이 성립하지 않는 일이 잦다.
 */
public final class Concurrently {

    private static final int TIMEOUT_SECONDS = 30;

    private Concurrently() {
    }

    /**
     * {@code threads}개의 스레드가 동시에 {@code task}를 한 번씩 부른다.
     *
     * @return 각 스레드에서 빠져나온 예외. 비어 있으면 전부 성공한 것이다
     */
    public static List<Throwable> run(int threads, IntConsumer task) {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier startLine = new CyclicBarrier(threads);
        CountDownLatch finished = new CountDownLatch(threads);
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
        try {
            for (int i = 0; i < threads; i++) {
                int index = i;
                pool.execute(() -> {
                    try {
                        startLine.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                        task.accept(index);
                    } catch (BrokenBarrierException | TimeoutException | RuntimeException
                             | Error e) {
                        failures.add(e);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        failures.add(e);
                    } finally {
                        finished.countDown();
                    }
                });
            }
            awaitCompletion(finished);
        } finally {
            pool.shutdownNow();
        }
        return List.copyOf(failures);
    }

    private static void awaitCompletion(CountDownLatch finished) {
        try {
            if (!finished.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("동시 요청이 제한 시간 안에 끝나지 않았다");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("동시 요청 대기가 중단됐다", e);
        }
    }
}
