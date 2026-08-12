package com.morak.point.service;

import com.morak.member.entity.Member;
import com.morak.member.repository.MemberRepository;
import com.morak.point.entity.PointLedger;
import com.morak.point.repository.PointLedgerRepository;
import com.morak.point.type.PointReason;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 포인트가 움직이는 유일한 경로. 지급도 차감도 여기를 지난다.
 *
 * <p><b>한 메서드로 모은 이유는 원장과 잔액 캐시가 갈라지지 않게 하기 위해서다.</b>
 * {@code point_ledger} INSERT와 {@code member.point_balance} 갱신은 같은 트랜잭션이어야
 * 하고, 한 곳이라도 캐시만 고치거나 원장만 남기면 "잔액은 원장 합과 같다"는 불변식이
 * 조용히 깨진다. 그 상태는 다음 지급의 {@code balance_after}까지 오염시킨다.
 *
 * <p>멱등은 두 겹이다. 순차 재실행은 {@link PointLedgerRepository#existsByMemberIdAndReasonAndRefTypeAndRefId}
 * 가 걸러 조용히 끝내고, 동시 실행은 {@code uk_pl_dedup}이 막는다 — 뒤엣것이 진짜
 * 방어선이고 이때는 트랜잭션이 통째로 롤백되므로 이중 지급이 남지 않는다.
 */
@Service
@Transactional
@RequiredArgsConstructor
public class PointService {

    private static final Logger log = LoggerFactory.getLogger(PointService.class);

    private final PointLedgerRepository pointLedgerRepository;
    private final MemberRepository memberRepository;

    /**
     * 원장에 한 줄 남기고 잔액 캐시를 같은 트랜잭션에서 갱신한다.
     *
     * @param delta 부호 있는 증감. 지급은 +, 차감은 -
     * @param refId 사유별 ref 규약표가 정한 근거 행 id
     * @return 실제로 기록했으면 true, 이미 같은 근거로 처리돼 있으면 false
     */
    public boolean award(Long memberId, int delta, PointReason reason, Long refId,
                         LocalDateTime now) {
        if (pointLedgerRepository.existsByMemberIdAndReasonAndRefTypeAndRefId(
                memberId, reason, PointLedger.refTypeOf(reason), refId)) {
            return false;
        }
        Member member = memberRepository.findById(memberId)
                // 원장은 회원 행을 FK로 참조한다. 없는 회원에게 지급이 시도됐다면 호출부의
                // 데이터가 이미 깨진 것이라 조용히 넘기지 않는다.
                .orElseThrow(() -> new IllegalStateException("존재하지 않는 회원의 포인트 처리: " + memberId));
        // 패널티는 잔액 부족으로 회피할 수 없어야 하므로 음수를 허용한다(API명세서 SS-4 부수효과).
        // 잔액이 모자라면 막아야 하는 차감(주문)은 7단계에서 조건부 UPDATE로 따로 만든다.
        int balanceAfter = member.applyPointDelta(delta);
        pointLedgerRepository.save(
                PointLedger.record(memberId, delta, reason, refId, balanceAfter, now));
        log.info("포인트 {}: member={}, reason={}, ref={}, 잔액={}",
                delta > 0 ? "지급" : "차감", memberId, reason, refId, balanceAfter);
        return true;
    }
}
