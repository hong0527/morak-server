package com.morak.session.repository;

import com.morak.session.entity.Eviction;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EvictionRepository extends JpaRepository<Eviction, Long> {

    /**
     * MT-1 재매칭 쿨다운(D14)의 기준이 되는 마지막 퇴출. 이의가 인용된 퇴출은
     * {@code revokedAt}이 채워지고 쿨다운 판정에서 빠진다 — 잘못된 퇴출로 30분을
     * 묶어 두면 인용의 의미가 없다.
     */
    Optional<Eviction> findFirstByMemberIdAndRevokedAtIsNullOrderByCreatedAtDesc(Long memberId);
}
