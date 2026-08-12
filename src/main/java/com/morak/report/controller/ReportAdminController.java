package com.morak.report.controller;

import com.morak.common.dto.PageResponse;
import com.morak.common.security.LoginMember;
import com.morak.report.dto.request.ReportProcessRequest;
import com.morak.report.dto.response.ReportCaseDetailResponse;
import com.morak.report.dto.response.ReportCaseSummaryResponse;
import com.morak.report.dto.response.ReportProcessResponse;
import com.morak.report.service.ReportAdminService;
import com.morak.report.type.ReportSeverity;
import com.morak.report.type.ReportStatus;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * AD-1·AD-2·AD-3 신고 콘솔. 관리자 역할 검사는 전역 인터셉터가 {@code /api/admin/**}
 * 전체에 걸므로 여기에는 없다(명세 §0-2 ③).
 */
@RestController
@RequestMapping("/api/admin/reports")
@RequiredArgsConstructor
public class ReportAdminController {

    private final ReportAdminService reportAdminService;

    @GetMapping
    public PageResponse<ReportCaseSummaryResponse> getCases(
            @RequestParam(required = false) ReportStatus status,
            @RequestParam(required = false) ReportSeverity severity,
            @RequestParam(required = false) Boolean overdue,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return reportAdminService.getCases(status, severity, overdue, q, page, size);
    }

    @GetMapping("/{caseId}")
    public ReportCaseDetailResponse getCase(@PathVariable Long caseId) {
        return reportAdminService.getCase(caseId);
    }

    @PatchMapping("/{caseId}")
    public ReportProcessResponse process(@LoginMember Long adminId,
                                         @PathVariable Long caseId,
                                         @Valid @RequestBody ReportProcessRequest request) {
        return reportAdminService.process(adminId, caseId, request);
    }
}
