package com.morak.session.repository;

import com.morak.session.entity.AppealCase;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * 퇴출 이의. AP-1이 쓰고 AD-5·AD-6이 읽는다.
 *
 * <p>AD-5의 필터가 {@code status}·{@code overdue} 두 개이고 각각 생략 가능한데, 후자는
 * 컬럼 비교가 아니라 두 컬럼과 현재 시각의 식이라 메서드 이름 쿼리로 표현되지 않는다.
 * 신고 큐({@code ReportCaseRepository})와 같은 이유로 {@link JpaSpecificationExecutor}를
 * 쓴다 — 빠진 조건은 Java에서 걸러져 SQL에 나가지 않는다.
 */
public interface AppealCaseRepository
        extends JpaRepository<AppealCase, Long>, JpaSpecificationExecutor<AppealCase> {

    /**
     * AP-1의 재신청 판정. <b>방어선은 이 검사가 아니라 {@code uk_ap_eviction}이다</b> —
     * 동시에 들어온 두 신청은 이 조회를 함께 통과할 수 있다. 검사를 두는 이유는 흔한 순차
     * 재신청을 제약 위반이 아니라 409로 되돌려 주기 위해서다.
     */
    boolean existsByEvictionId(Long evictionId);

    /** AP-2 내 이의 목록. 정렬은 호출부가 {@code Pageable}에 실어 준다. */
    Page<AppealCase> findByMemberId(Long memberId, Pageable pageable);
}
