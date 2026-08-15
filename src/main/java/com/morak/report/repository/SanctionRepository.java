package com.morak.report.repository;

import com.morak.report.entity.Sanction;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SanctionRepository extends JpaRepository<Sanction, Long> {

    /**
     * 전역 인터셉터 ④가 유효 제재 판정에 쓴다. 유효 여부(starts_at <= now AND
     * (ends_at IS NULL OR ends_at > now))는 쿼리가 아니라 {@link Sanction#isEffectiveAt}로
     * 판정한다 — 판정식이 두 곳에 있으면 언젠가 어긋난다.
     */
    List<Sanction> findByMemberId(Long memberId);

    /**
     * MT-1 후보 조회가 대기열 전원의 제재를 한 번에 읽는다. 회원마다 따로 물으면 대기열
     * 크기만큼 쿼리가 늘고, 그 사이 제재가 걸린 회원이 후보에 섞일 수 있다.
     */
    List<Sanction> findByMemberIdIn(Collection<Long> memberIds);
}
