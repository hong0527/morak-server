package com.morak.point.service;

import com.morak.dev.DevBatch;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * B5 충전 승인 기한 만료. 승인도 실패도 오지 않은 채 {@code pg.ready-expire-minutes}를 넘긴
 * READY를 FAILED로 닫는다.
 *
 * <p><b>B2(매칭 대기 만료)에 얹지 않은 이유.</b> 두 배치는 주기만 같고 대상도 도메인도 다르다.
 * 매칭 만료 배치가 결제 건을 함께 훑기 시작하면 §5의 배치 표가 그 배치가 무엇을 하는지
 * 더 이상 설명하지 못하고, 한쪽 정책을 고칠 때 다른 쪽 대상까지 함께 읽어야 한다.
 * {@link DevBatch}는 도메인마다 배치를 따로 두고 트리거만 공유하라고 만든 자리다.
 *
 * <p>재실행해도 안전하다. 두 번째 실행의 대상 조회는 {@code status='READY'}에 걸려 비어 있고,
 * 조회와 전이 사이에 승인이 도착한 건은 전이 직전 상태 확인이 막는다.
 */
@Component
@RequiredArgsConstructor
public class PointChargeExpiryBatch implements DevBatch {

    private static final Logger log = LoggerFactory.getLogger(PointChargeExpiryBatch.class);

    private final PointChargeService pointChargeService;

    @Override
    public String name() {
        return "B5";
    }

    /** 매분 0초. 만료 판정이 분 단위라 이보다 촘촘할 이유가 없다. */
    @Scheduled(cron = "0 * * * * *")
    public void schedule() {
        run();
    }

    @Override
    public int run() {
        int expired = pointChargeService.expireStaleReady();
        if (expired > 0) {
            log.info("승인 기한 만료 충전 {}건", expired);
        }
        return expired;
    }
}
