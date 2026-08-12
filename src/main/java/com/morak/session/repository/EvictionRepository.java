package com.morak.session.repository;

import com.morak.session.entity.Eviction;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface EvictionRepository extends JpaRepository<Eviction, Long> {

    /**
     * B1이 패널티 차감 여부를 확인할 퇴출. 퇴출 트랜잭션은 원장을 만들지 않고 이 행만
     * 남기므로(4단계), 차감 주체는 배치 하나로 일원화된다 — 퇴출 경로가 직접 차감하면
     * 주체가 둘이 되어 어느 쪽이 넣었는지 모르는 -300이 생긴다.
     *
     * <p>이미 차감된 건까지 함께 읽는다. 걸러내는 일은 원장의 멱등 검사가 하고, 여기서
     * 조건을 흉내 내면 point 도메인의 규약이 session 도메인 쿼리에 복제된다.
     * 취소된 퇴출({@code revoked_at})은 역분개 대상이라 애초에 후보가 아니다.
     */
    @Query("SELECT e.id FROM Eviction e WHERE e.revokedAt IS NULL ORDER BY e.id")
    List<Long> findIdsToSettle();

    /**
     * MT-1 재매칭 쿨다운(D14)의 기준이 되는 마지막 퇴출. 이의가 인용된 퇴출은
     * {@code revokedAt}이 채워지고 쿨다운 판정에서 빠진다 — 잘못된 퇴출로 30분을
     * 묶어 두면 인용의 의미가 없다.
     */
    Optional<Eviction> findFirstByMemberIdAndRevokedAtIsNullOrderByCreatedAtDesc(Long memberId);
}
