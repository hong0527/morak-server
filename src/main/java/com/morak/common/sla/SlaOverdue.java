package com.morak.common.sla;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import java.time.LocalDateTime;

/**
 * 처리 지연 판정 — <b>미종결 AND {@code sla_due_at < now}</b>.
 *
 * <p>저장 컬럼이 아니라 조회 시점의 파생값이다. 플래그를 미리 찍어 두는 배치는
 * 그 배치가 밀리거나 죽으면 기한을 넘긴 건이 큐에서 정상으로 보인다 — 안전 도구에서
 * 가장 위험한 실패 방식이라 마킹 배치(구 B3)를 폐지했다.
 *
 * <p><b>여기 모아 둔 이유는 이 식을 쓰는 큐가 둘이기 때문이다.</b> 신고 케이스(AD-1)와
 * 퇴출 이의(AD-5)가 각자 조건을 적으면 한쪽만 고친 날 두 콘솔의 SLA 판정이 갈린다.
 * 엔티티마다 status 타입과 컬럼 이름이 다르므로 경로를 인자로 받는다.
 */
public final class SlaOverdue {

    private SlaOverdue() {
    }

    /**
     * @param pendingStatus 그 도메인의 미종결 상태
     * @param overdue       true면 지연된 것만, false면 지연되지 않은 것만
     */
    public static <S> Predicate predicate(CriteriaBuilder builder,
                                          Path<S> statusPath,
                                          Path<LocalDateTime> slaDueAtPath,
                                          S pendingStatus,
                                          LocalDateTime now,
                                          boolean overdue) {
        Predicate delayed = builder.and(
                builder.equal(statusPath, pendingStatus),
                builder.lessThan(slaDueAtPath, now));
        // status·sla_due_at이 둘 다 NOT NULL이라 부정에 3값 논리 함정이 없다
        return overdue ? delayed : builder.not(delayed);
    }
}
