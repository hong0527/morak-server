package com.morak.point.repository;

import com.morak.point.entity.PointCharge;
import com.morak.point.type.ChargeStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * PG 충전 건. PY-2는 충전 건 번호로, PY-3 웹훅은 주문번호로 같은 행을 찾는다 —
 * 웹훅은 우리 쪽 id를 모르고 자기가 받은 {@code orderId}만 안다.
 */
public interface PointChargeRepository extends JpaRepository<PointCharge, Long> {

    /** {@code uk_pc_order}가 받는다. 결과는 0행 아니면 1행이다. */
    Optional<PointCharge> findByPgOrderId(String pgOrderId);

    /**
     * 승인도 실패도 오지 않은 채 방치된 충전(B5). 결제창을 띄우고 닫아 버리면 PG가 아무것도
     * 통보하지 않아 READY가 영구히 남는다 — 대사할 때마다 "결제된 건가"를 사람이 확인해야
     * 하는 행이 매일 쌓인다.
     */
    @Query("""
            SELECT pc.id
              FROM PointCharge pc
             WHERE pc.status = :ready
               AND pc.createdAt < :cutoff
             ORDER BY pc.id
            """)
    List<Long> findIdsToExpire(@Param("ready") ChargeStatus ready,
                               @Param("cutoff") LocalDateTime cutoff);
}
