package com.morak.report.repository;

import com.morak.report.entity.Report;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReportRepository extends JpaRepository<Report, Long> {

    /** uk_report의 사전 검사. 위반은 409 DUPLICATE_REPORT다. */
    boolean existsByCaseIdAndReporterId(Long caseId, Long reporterId);

    /** AD-2 신고자 목록. 접수 순으로 읽어야 무엇이 케이스를 연 신고인지 드러난다. */
    List<Report> findByCaseIdOrderByIdAsc(Long caseId);

    /**
     * AD-1 한 페이지분 신고를 한 번에 읽는다. 케이스별 신고 수({@code reportCount})와
     * 케이스를 연 사유({@code reasonCode} — report_case에는 사유 컬럼이 없다)가 둘 다
     * 필요한데, 집계 쿼리를 두 번 던지느니 행을 받아 Java에서 묶는 편이 낫다. 페이지 크기
     * 상한이 50이라 읽는 양이 정해져 있다.
     */
    List<Report> findByCaseIdInOrderByIdAsc(Collection<Long> caseIds);

    /**
     * 만 14세 미만 판정 계정의 파기(★D7)가 그 계정이 넣은 신고를 지운다. 탈퇴 파기(B4)는
     * 반대로 <b>남긴다</b> — 그쪽은 가입이 성립한 회원의 신고라 다른 사람의 케이스를 지탱하는
     * 기록이고, 이쪽은 애초에 존재하지 않았어야 할 계정의 입력이다.
     */
    void deleteByReporterId(Long reporterId);
}
