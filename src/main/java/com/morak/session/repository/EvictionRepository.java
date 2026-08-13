package com.morak.session.repository;

import com.morak.point.type.PointReason;
import com.morak.session.entity.Eviction;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EvictionRepository extends JpaRepository<Eviction, Long> {

    /**
     * B1이 소급 차감할 퇴출. 차감은 퇴출 트랜잭션이 즉시 하므로 여기 걸리는 것은 그 트랜잭션이
     * 원장을 남기지 못하고 끊긴 건뿐이다 — 안전망이라 대상이 비어 있는 것이 정상이다.
     *
     * <p><b>미차감 조건을 쿼리가 진다.</b> 예전에는 취소되지 않은 퇴출을 전부 읽어 멱등 검사에
     * 맡겼는데, 대상이 언제나 비어 있는 안전망이 매분 퇴출 전체를 훑는 구조라 서비스가 오래
     * 살수록 배치 한 번의 비용이 계속 자란다. 시간 창으로 자르는 방법도 있지만 그 창보다 오래
     * 배치가 멈추면 그 사이 끊긴 건이 영구 미차감으로 남아, 안전망이 안전망이 아니게 된다.
     *
     * <p>대신 point 도메인의 규약이 이 쿼리에 복제되지 않도록 멱등키의 두 축
     * ({@code reason}·{@code refType})을 호출부에서 받는다 — 무엇이 차감의 근거인가는
     * {@code PointLedger.refTypeOf}가 계속 소유한다.
     *
     * <p>취소된 퇴출({@code revoked_at})은 역분개 대상이라 애초에 후보가 아니다.
     */
    @Query("""
            SELECT e.id
              FROM Eviction e
             WHERE e.revokedAt IS NULL
               AND NOT EXISTS (
                     SELECT 1
                       FROM PointLedger pl
                      WHERE pl.memberId = e.memberId
                        AND pl.reason = :reason
                        AND pl.refType = :refType
                        AND pl.refId = e.id)
             ORDER BY e.id
            """)
    List<Long> findIdsToSettle(@Param("reason") PointReason reason,
                               @Param("refType") String refType);

    /**
     * SS-1·SS-8이 본인 행에 실을 퇴출 번호. {@code uk_eviction}이 세션·회원 쌍의 유일성을
     * 보장하므로 결과는 0행 아니면 1행이다.
     *
     * <p>이 번호가 없으면 이의 신청(AP-1) 경로에 들어갈 수 없다. 퇴출 순간의 SS-4 응답에만
     * 실려 있으면 그 응답을 놓친 사용자는 3일짜리 이의 기한을 손도 못 대고 흘려보낸다.
     */
    Optional<Eviction> findBySessionIdAndMemberId(Long sessionId, Long memberId);

    /**
     * MT-1 재매칭 쿨다운(D14)의 기준이 되는 마지막 퇴출. 이의가 인용된 퇴출은
     * {@code revokedAt}이 채워지고 쿨다운 판정에서 빠진다 — 잘못된 퇴출로 30분을
     * 묶어 두면 인용의 의미가 없다.
     */
    Optional<Eviction> findFirstByMemberIdAndRevokedAtIsNullOrderByCreatedAtDesc(Long memberId);
}
