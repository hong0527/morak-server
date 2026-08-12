package com.morak.point.repository;

import com.morak.point.entity.PointCharge;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * PG 충전 건. PY-2는 충전 건 번호로, PY-3 웹훅은 주문번호로 같은 행을 찾는다 —
 * 웹훅은 우리 쪽 id를 모르고 자기가 받은 {@code orderId}만 안다.
 */
public interface PointChargeRepository extends JpaRepository<PointCharge, Long> {

    /** {@code uk_pc_order}가 받는다. 결과는 0행 아니면 1행이다. */
    Optional<PointCharge> findByPgOrderId(String pgOrderId);
}
