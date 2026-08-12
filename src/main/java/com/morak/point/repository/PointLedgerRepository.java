package com.morak.point.repository;

import com.morak.point.entity.PointLedger;
import com.morak.point.type.PointReason;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 포인트 원장. 쓰기와 멱등 검사는 {@code PointService.award}가, 내역 조회는 PT-1이 쓴다.
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

    /** PT-1 내역. 정렬은 호출부가 {@code Pageable}에 실어 준다 — {@code idx_pl_member}가 받는다. */
    Page<PointLedger> findByMemberId(Long memberId, Pageable pageable);

    /**
     * 만 14세 미만 파기(AU-3·AU-1)가 웰컴 포인트 행을 함께 지운다. 원장을 지우는 유일한
     * 경로다 — 정정은 역분개이지 삭제가 아니고, 여기만 예외인 이유는 가입 자체가 성립하지
     * 않아 그 행이 거래 기록이 아니기 때문이다(api-spec AU-3 부수효과).
     */
    void deleteByMemberId(Long memberId);
}
