package com.morak.member.repository;

import com.morak.member.entity.Member;
import com.morak.member.type.MemberStatus;
import com.morak.member.type.SocialProvider;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MemberRepository extends JpaRepository<Member, Long> {

    Optional<Member> findByProviderAndProviderUserId(SocialProvider provider, String providerUserId);

    /** 탈퇴 처리 배치가 삭제 예정 시각이 지난 회원을 찾는다. */
    List<Member> findByStatusAndDeleteScheduledAtBefore(MemberStatus status, LocalDateTime now);

    /**
     * AD-8 탈퇴 처리 결과. 유예 중과 파기 완료가 한 목록이라 상태를 집합으로 받는다 —
     * "신청은 됐는데 예정일이 지나도 파기되지 않은 건"을 찾는 것이 이 화면의 목적이라,
     * 두 상태를 나눠 보여주면 그 비교가 화면 밖에서 일어난다.
     */
    Page<Member> findByStatusIn(Collection<MemberStatus> statuses, Pageable pageable);

    /**
     * 표시용 닉네임만 필요한 화면(세션 상세·결과, AD-5·AD-7 콘솔)이 쓰는 일괄 조회.
     * 엔티티를 읽는 {@code findAllById}와 달리 개인정보 컬럼이 메모리에 올라오지 않는다.
     */
    List<MemberNickname> findByIdIn(Collection<Long> ids);

    /**
     * 회원 상태만 읽는다. <b>엔티티가 아니라 스칼라로 읽는 것이 요점이다</b> — 같은
     * 트랜잭션이 이미 읽은 회원은 영속성 컨텍스트에 남아 있어, {@code findById}로 다시 읽으면
     * 그때의 값이 그대로 돌아온다. 잠금을 얻은 뒤 "그 사이 상태가 바뀌었나"를 묻는 자리에서는
     * 그 캐시가 곧 오답이라, DB 값을 직접 받는 이 조회를 쓴다.
     */
    @Query("""
            SELECT m.status
              FROM Member m
             WHERE m.id = :memberId
            """)
    Optional<MemberStatus> findStatusById(@Param("memberId") Long memberId);

    /**
     * 대기열에서 뺄 회원. 탈퇴 신청(AU-4)과 매칭 요청(MT-1)이 동시에 들어오면 요청 쪽이 회원
     * 행 잠금을 늦게 얻어, AU-4가 이미 지나간 뒤에 대기 요청이 남을 수 있다. MT-1 게이트가
     * 그 창을 막지만 6인 확정 직전에 한 번 더 묻는다 — 들어오지 못하는 사람이 남의 세션에서
     * 자리를 차지하는 것이 이 도메인에서 가장 비싼 오염이라, 방어선을 하나만 두지 않는다.
     */
    @Query("""
            SELECT m.id
              FROM Member m
             WHERE m.id IN :memberIds
               AND m.status <> :status
            """)
    List<Long> findIdsWithStatusOtherThan(@Param("memberIds") Collection<Long> memberIds,
                                          @Param("status") MemberStatus status);

    /**
     * 잔액이 있을 때만 깎는다(SR-3). <b>잔액을 읽고 서비스에서 비교한 뒤 깎으면 동시 주문 두
     * 건이 같은 잔액을 보고 둘 다 통과해 마이너스가 된다.</b> 검사와 차감을 한 문장에 넣어야
     * DB가 행을 잠근 상태로 판정한다.
     *
     * <p>영향 행 0은 "잔액 부족"이다(존재하지 않는 회원도 0이지만, 인터셉터가 회원을 확인하고
     * 들어오므로 그 경로는 없다).
     *
     * <p>벌크 연산이라 영속성 컨텍스트를 우회한다. flush·clear를 함께 걸지 않으면 같은
     * 트랜잭션에서 다시 읽은 회원이 차감 이전 잔액으로 보이고, 그 값이 원장의
     * {@code balance_after}로 들어간다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Member m
               SET m.pointBalance = m.pointBalance - :amount
             WHERE m.id = :memberId
               AND m.pointBalance >= :amount
            """)
    int deductPointIfEnough(@Param("memberId") Long memberId, @Param("amount") int amount);

    /**
     * 잔액을 조건 없이 증감한다(지급·패널티). 잔액 부족을 막지 않는 것이
     * {@link #deductPointIfEnough}와의 차이다 — 퇴출 패널티는 잔액이 모자란다고 회피할 수
     * 있으면 안 된다.
     *
     * <p><b>엔티티를 읽어 더한 뒤 저장하면 안 된다.</b> 같은 회원에게 두 지급이 동시에 들어오면
     * 둘 다 같은 잔액을 읽고 각자 절대값을 써서 한쪽이 사라진다(원장은 두 줄 다 남으므로
     * "잔액 = 원장 합"이 깨진다). 실측: PY-2와 PY-3 20쌍을 동시에 쏘면 원장은 20줄인데 캐시는
     * 12건을 잃었다. 증감을 SQL 한 문장에 담아야 DB가 행을 잠근 상태로 더한다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Member m
               SET m.pointBalance = m.pointBalance + :delta
             WHERE m.id = :memberId
            """)
    int addPoint(@Param("memberId") Long memberId, @Param("delta") int delta);
}
