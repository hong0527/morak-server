package com.morak.point.repository;

import com.morak.point.entity.PointLedger;
import com.morak.point.type.PointReason;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 포인트 원장. 5단계는 쓰기와 멱등 검사만 쓴다 — 잔액·내역 조회(PT-1)는 6단계다.
 */
public interface PointLedgerRepository extends JpaRepository<PointLedger, Long> {

    /**
     * 이미 기록된 지급인가. <b>이것은 방어선이 아니라 재실행의 지름길이다</b> — 동시 실행은
     * 이 검사를 함께 통과할 수 있고, 그때 막는 것은 {@code uk_pl_dedup}이다. 검사를 두는
     * 이유는 B1 재트리거처럼 순차적인 재실행에서 제약 위반으로 트랜잭션을 깨뜨리지 않기
     * 위해서다.
     */
    boolean existsByMemberIdAndReasonAndRefTypeAndRefId(Long memberId, PointReason reason,
                                                        String refType, Long refId);
}
