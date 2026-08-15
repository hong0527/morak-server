package com.morak.point.service;

import com.morak.common.dto.PageParams;
import com.morak.common.dto.PageResponse;
import com.morak.common.error.BusinessException;
import com.morak.common.error.ErrorCode;
import com.morak.member.entity.Member;
import com.morak.member.repository.MemberRepository;
import com.morak.point.dto.response.PointBalanceResponse;
import com.morak.point.dto.response.PointLedgerItemResponse;
import com.morak.point.entity.PointLedger;
import com.morak.point.repository.PointLedgerRepository;
import com.morak.point.type.PointReason;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
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
 *
 * <p>조회(PT-1)도 여기 있다. 잔액 캐시와 원장을 함께 내리는 응답이라, 둘의 관계를 아는
 * 쪽이 만드는 편이 맞다.
 */
@Service
@Transactional
@RequiredArgsConstructor
public class PointService {

    private static final Logger log = LoggerFactory.getLogger(PointService.class);

    /**
     * 최신순 = 기록된 순서. <b>{@code created_at}이 아니라 id로 정렬한다</b> —
     * {@code balance_after}는 행이 쓰인 순서(id)로 연쇄하므로, 표시 순서를 다른 컬럼으로 잡으면
     * 목록에서 잔액이 오르내린다. 실제로 그랬다: 소급 지급이 과거 시각으로 들어간 회원의 PT-1이
     * 최신 행에 옛 잔액을 달고 나왔다.
     *
     * <p>{@link #now()}가 시각을 독점한 지금은 두 순서가 같지만, 정렬을 id에 두는 것은 그
     * 일치에 기대지 않기 위해서다. 같은 트랜잭션이 남기는 여러 줄(완주 지급 + 목표 달성)은
     * 어차피 시각이 같아 동률을 깰 컬럼이 필요하고, 그 컬럼이 곧 연쇄의 순서다.
     */
    private static final Sort LEDGER_SORT = Sort.by(Sort.Direction.DESC, "id");

    private final PointLedgerRepository pointLedgerRepository;
    private final MemberRepository memberRepository;
    private final Clock clock;

    /**
     * 원장에 한 줄 남기고 잔액 캐시를 같은 트랜잭션에서 갱신한다.
     *
     * <p><b>기록 시각은 호출부가 정하지 못한다</b>({@link #now()}). 원장의 {@code created_at}은
     * "이 줄을 쓴 시각"이고, 그 줄이 정산하는 사건이 언제 있었는지는 근거 행({@code ref_id})이
     * 이미 말한다. 호출부가 판정 시각을 그대로 넘길 수 있었을 때 실제로 어긋났다 — 이틀 전
     * 세션의 완주를 이의 인용으로 소급 지급하면서 세션 종료 시각을 실었고, 같은 트랜잭션의
     * 환급만 실제 시각이라 세 줄의 시각이 갈렸다.
     *
     * @param delta 부호 있는 증감. 지급은 +, 차감은 -
     * @param refId 사유별 ref 규약표가 정한 근거 행 id
     * @return 실제로 기록했으면 true, 이미 같은 근거로 처리돼 있으면 false
     */
    public boolean award(Long memberId, int delta, PointReason reason, Long refId) {
        if (pointLedgerRepository.existsByMemberIdAndReasonAndRefTypeAndRefId(
                memberId, reason, PointLedger.refTypeOf(reason), refId)) {
            return false;
        }
        // 패널티는 잔액 부족으로 회피할 수 없어야 하므로 음수를 허용한다(API명세서 SS-4 부수효과).
        // 잔액이 모자라면 막아야 하는 차감(주문)은 이 메서드가 아니라 spend가 맡는다.
        if (memberRepository.addPoint(memberId, delta) == 0) {
            // 원장은 회원 행을 FK로 참조한다. 없는 회원에게 지급이 시도됐다면 호출부의
            // 데이터가 이미 깨진 것이라 조용히 넘기지 않는다.
            throw new IllegalStateException("존재하지 않는 회원의 포인트 처리: " + memberId);
        }
        // 벌크 UPDATE가 영속성 컨텍스트를 비우고 갔으므로 여기서 읽는 잔액은 증감 후 값이다.
        int balanceAfter = getBalance(memberId);
        pointLedgerRepository.save(
                PointLedger.record(memberId, delta, reason, refId, balanceAfter, now()));
        log.info("포인트 {}: member={}, reason={}, ref={}, 잔액={}",
                delta > 0 ? "지급" : "차감", memberId, reason, refId, balanceAfter);
        return true;
    }

    /**
     * 잔액이 있을 때만 깎는다(SR-3 주문). {@link #award}와 나뉘어 있는 것은 실수가 아니라
     * 용도의 차이다 — <b>award는 잔액을 넘겨 깎을 수 있고(패널티는 잔액 부족으로 피할 수 없어야
     * 한다), 여기는 넘기면 거절한다</b>. 사용자가 쓰는 포인트를 award로 깎으면 잔액이 마이너스로
     * 내려간 채 주문이 성사된다.
     *
     * <p>검사와 차감을 한 UPDATE에 담아 동시 주문이 같은 잔액을 두 번 쓰지 못하게 한다
     * ({@link MemberRepository#deductPointIfEnough}). 여기서 예외를 던지면 호출부의
     * 트랜잭션이 통째로 롤백되므로 재고 차감·주문 행도 함께 사라진다.
     *
     * @param amount 깎을 금액(양수). 원장에는 부호를 뒤집어 음수로 남는다
     * @return 차감 후 잔액
     */
    public int spend(Long memberId, int amount, PointReason reason, Long refId) {
        if (amount <= 0) {
            throw new IllegalArgumentException("차감액은 양수여야 한다: " + amount);
        }
        if (memberRepository.deductPointIfEnough(memberId, amount) == 0) {
            throw new BusinessException(ErrorCode.INSUFFICIENT_POINT);
        }
        // 벌크 UPDATE가 영속성 컨텍스트를 비우고 갔으므로 여기서 읽는 잔액은 차감 후 값이다.
        int balanceAfter = memberRepository.findById(memberId)
                .orElseThrow(() -> new IllegalStateException("존재하지 않는 회원의 포인트 차감: " + memberId))
                .getPointBalance();
        pointLedgerRepository.save(
                PointLedger.record(memberId, -amount, reason, refId, balanceAfter, now()));
        log.info("포인트 사용: member={}, reason={}, ref={}, 금액={}, 잔액={}",
                memberId, reason, refId, amount, balanceAfter);
        return balanceAfter;
    }

    /**
     * 근거 행에 달린 원장 줄 번호. SR-5가 주문 상세에 {@code pointLedgerId}를 싣는다.
     * 원장 조회를 포인트 도메인 밖으로 내보내지 않기 위해 여기서 감싼다.
     */
    @Transactional(readOnly = true)
    public Long findLedgerId(Long memberId, PointReason reason, Long refId) {
        return pointLedgerRepository.findByMemberIdAndReasonAndRefTypeAndRefId(
                        memberId, reason, PointLedger.refTypeOf(reason), refId)
                .map(PointLedger::getId)
                .orElse(null);
    }

    /**
     * 잔액 캐시. 충전 승인(PY-2·PY-3)이 응답에 실을 값을 여기서 읽는다.
     *
     * <p>회원 행을 포인트 도메인 밖으로 내보내지 않기 위해 감싼다 — 잔액이 캐시라는 사실과
     * 어긋났을 때 원장이 옳다는 규칙을 아는 쪽이 하나여야 한다.
     *
     * <p>{@code readOnly}는 <b>밖에서 부를 때만</b> 걸린다. {@link #award}·{@link #spend}가
     * 부르는 것은 자기 호출이라 프록시를 타지 않고, 그때는 쓰기 트랜잭션 안에서 그냥 실행된다.
     * 노리는 동작이 그것이다 — 여기서 새 읽기 전용 트랜잭션이 열리면 아직 커밋되지 않은
     * 증감을 보지 못해 원장의 {@code balance_after}가 어긋난다.
     */
    @Transactional(readOnly = true)
    public int getBalance(Long memberId) {
        return memberRepository.findById(memberId)
                .orElseThrow(() -> new IllegalStateException("존재하지 않는 회원의 잔액 조회: " + memberId))
                .getPointBalance();
    }

    /** 원장 시각의 유일한 출처. private이라 이 클래스 밖에서 다른 시각을 실을 방법이 없다. */
    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    /**
     * PT-1. 잔액은 캐시에서, 내역은 원장에서 읽는다.
     */
    @Transactional(readOnly = true)
    public PointBalanceResponse getMyPoints(Long memberId, Integer page, Integer size) {
        Member member = memberRepository.findById(memberId)
                // 인터셉터가 이미 회원을 확인하고 들어오므로 여기서 없다면 그 사이에 파기된
                // 계정이다. 남은 토큰으로는 아무것도 볼 수 없어야 한다.
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
        PageParams params = PageParams.of(page, size);
        Page<PointLedger> ledger = pointLedgerRepository.findByMemberId(
                memberId, params.toPageable(LEDGER_SORT));
        return new PointBalanceResponse(member.getPointBalance(),
                PageResponse.of(ledger, PointLedgerItemResponse::from));
    }
}
