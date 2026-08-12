package com.morak.session.repository;

import com.morak.session.entity.Eviction;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface EvictionRepository extends JpaRepository<Eviction, Long> {

    /**
     * B1이 패널티 차감 여부를 확인할 퇴출. 차감은 퇴출 트랜잭션이 즉시 하므로 여기서 걸리는
     * 것은 그 트랜잭션이 원장을 남기지 못하고 끊긴 건뿐이다 — 안전망이라 대상이 비어 있는
     * 것이 정상이다.
     *
     * <p>이미 차감된 건까지 함께 읽는다. 걸러내는 일은 원장의 멱등 검사가 하고, 여기서
     * 조건을 흉내 내면 point 도메인의 규약이 session 도메인 쿼리에 복제된다.
     * 취소된 퇴출({@code revoked_at})은 역분개 대상이라 애초에 후보가 아니다.
     */
    @Query("SELECT e.id FROM Eviction e WHERE e.revokedAt IS NULL ORDER BY e.id")
    List<Long> findIdsToSettle();

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
