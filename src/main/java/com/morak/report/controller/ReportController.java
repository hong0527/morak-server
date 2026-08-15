package com.morak.report.controller;

import com.morak.common.security.LoginMember;
import com.morak.report.dto.request.ReportCreateRequest;
import com.morak.report.dto.response.ReportCreateResponse;
import com.morak.report.service.ReportService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * RP-1 신고. 연령 확인 게이트를 건너뛰는 유일한 참여 API다(인터셉터 SKIP_RULES) —
 * 미성년이 유해물을 보고도 신고하지 못하는 상태를 만들지 않는다.
 */
@RestController
@RequestMapping("/api/reports")
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reportService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ReportCreateResponse report(@LoginMember Long memberId,
                                       @Valid @RequestBody ReportCreateRequest request) {
        return reportService.report(memberId, request);
    }
}
