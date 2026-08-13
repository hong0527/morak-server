package com.morak.point.controller;

import com.morak.common.security.LoginMember;
import com.morak.point.dto.response.PointBalanceResponse;
import com.morak.point.service.PointService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * PT-1 포인트 잔액·원장 조회. 경로는 회원 아래지만 내려주는 것은 포인트 도메인의 데이터라
 * 포인트 서비스가 소유한다(SS-9의 MySessionController와 같은 기준).
 */
@RestController
@RequestMapping("/api/members/me/points")
@RequiredArgsConstructor
public class PointController {

    private final PointService pointService;

    @GetMapping
    public PointBalanceResponse getMyPoints(@LoginMember Long memberId,
                                            @RequestParam(required = false) Integer page,
                                            @RequestParam(required = false) Integer size) {
        return pointService.getMyPoints(memberId, page, size);
    }
}
