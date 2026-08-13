package com.morak.common.batch;

import java.util.function.IntSupplier;
import org.slf4j.Logger;
import org.springframework.dao.DataAccessException;

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
 * 알람을 울린다. 그 밖의 예외는 경합이 아니므로 스택과 함께 오류로 남긴다. 전부 한 문장으로
 * 삼키면 진짜 결함이 정상 상황으로 위장돼, 매분 조용히 건너뛰는 대상이 생겨도 아무도 모른다.
 *
 * <p>배치마다 이 코드를 복사하지 않는 이유는 하나다 — 네 배치가 각자 갖고 있으면 실패 분류를
 * 바꾸는 날 한 곳이 빠진다. 로거를 받는 것은 로그가 배치 자신의 이름으로 남아야 하기 때문이다.
 */
public final class BatchGuard {

    private BatchGuard() {
    }

    public static int guarded(Logger log, String step, Object targetId, IntSupplier work) {
        try {
            return work.getAsInt();
        } catch (DataAccessException e) {
            log.warn("{} 건너뜀 — 다른 경로와 경합했다: target={}, 원인={}",
                    step, targetId, e.getClass().getSimpleName());
            return 0;
        } catch (Exception e) {
            log.error("{} 건너뜀 — 예상하지 못한 실패: target={}", step, targetId, e);
            return 0;
        }
    }
}
