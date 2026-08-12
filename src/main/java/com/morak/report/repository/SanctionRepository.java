package com.morak.report.repository;

import com.morak.report.entity.Sanction;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SanctionRepository extends JpaRepository<Sanction, Long> {

    /**
     * 전역 인터셉터 ④가 유효 제재 판정에 쓴다. 유효 여부(starts_at <= now AND
     * (ends_at IS NULL OR ends_at > now))는 쿼리가 아니라 {@link Sanction#isEffectiveAt}로
     * 판정한다 — 판정식이 두 곳에 있으면 언젠가 어긋난다.
     */
    List<Sanction> findByMemberId(Long memberId);
}
