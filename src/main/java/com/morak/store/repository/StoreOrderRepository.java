package com.morak.store.repository;

import com.morak.store.entity.StoreOrder;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoreOrderRepository extends JpaRepository<StoreOrder, Long> {

    /**
     * 같은 멱등키로 이미 접수된 주문. <b>이것은 방어선이 아니라 재시도의 지름길이다</b> —
     * 동시에 들어온 두 요청은 이 조회를 함께 통과하고, 그때 막는 것은 {@code uk_so_idem}이다.
     * 조회를 두는 이유는 순차 재전송(네트워크 재시도)에서 제약 위반으로 트랜잭션을 깨뜨리지
     * 않고 기존 주문 번호를 돌려주기 위해서다.
     */
    Optional<StoreOrder> findByIdempotencyKey(String idempotencyKey);

    /** SR-4 내 주문 목록. 정렬은 호출부가 실어 준다 — {@code idx_so_member}가 받는다. */
    Page<StoreOrder> findByMemberId(Long memberId, Pageable pageable);
}
