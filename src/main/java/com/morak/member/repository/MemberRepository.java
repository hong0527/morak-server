package com.morak.member.repository;

import com.morak.member.entity.Member;
import com.morak.member.type.MemberStatus;
import com.morak.member.type.SocialProvider;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MemberRepository extends JpaRepository<Member, Long> {

    Optional<Member> findByProviderAndProviderUserId(SocialProvider provider, String providerUserId);

    /** 탈퇴 처리 배치가 삭제 예정 시각이 지난 회원을 찾는다. */
    List<Member> findByStatusAndDeleteScheduledAtBefore(MemberStatus status, LocalDateTime now);

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
}
