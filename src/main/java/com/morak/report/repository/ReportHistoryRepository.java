package com.morak.report.repository;

import com.morak.report.entity.ReportHistory;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 처리 이력은 AD-3이 넣고 AD-2가 읽는다. append-only라 수정·삭제 경로가 없다.
 */
public interface ReportHistoryRepository extends JpaRepository<ReportHistory, Long> {

    List<ReportHistory> findByCaseIdOrderByProcessedAtAscIdAsc(Long caseId);
}
