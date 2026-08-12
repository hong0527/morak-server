package com.morak.point.controller;

import com.morak.common.security.LoginMember;
import com.morak.point.dto.request.ChargeConfirmRequest;
import com.morak.point.dto.request.ChargeCreateRequest;
import com.morak.point.dto.response.ChargeConfirmResponse;
import com.morak.point.dto.response.ChargeCreateResponse;
import com.morak.point.service.PointChargeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** PY-1·PY-2 포인트 충전. 충전 대상은 언제나 토큰의 주인 본인이다. */
@RestController
@RequestMapping("/api/points/charges")
@RequiredArgsConstructor
public class PointChargeController {

    private final PointChargeService pointChargeService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ChargeCreateResponse create(@LoginMember Long memberId,
                                       @Valid @RequestBody ChargeCreateRequest request) {
        return pointChargeService.create(memberId, request);
    }

    @PostMapping("/{chargeId}/confirm")
    public ChargeConfirmResponse confirm(@LoginMember Long memberId,
                                         @PathVariable Long chargeId,
                                         @Valid @RequestBody ChargeConfirmRequest request) {
        return pointChargeService.confirm(memberId, chargeId, request);
    }
}
