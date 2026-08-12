package com.morak.store.controller;

import com.morak.common.dto.PageResponse;
import com.morak.common.security.LoginMember;
import com.morak.store.dto.request.OrderCreateRequest;
import com.morak.store.dto.response.OrderCreateResponse;
import com.morak.store.dto.response.OrderDetailResponse;
import com.morak.store.dto.response.OrderSummaryResponse;
import com.morak.store.service.StoreOrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** SR-3·SR-4·SR-5 주문. 주문 대상은 언제나 토큰의 주인 본인이다. */
@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

    private final StoreOrderService storeOrderService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OrderCreateResponse order(@LoginMember Long memberId,
                                     @Valid @RequestBody OrderCreateRequest request) {
        return storeOrderService.order(memberId, request);
    }

    @GetMapping
    public PageResponse<OrderSummaryResponse> getMyOrders(
            @LoginMember Long memberId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return storeOrderService.getMyOrders(memberId, page, size);
    }

    @GetMapping("/{orderId}")
    public OrderDetailResponse getOrder(@LoginMember Long memberId, @PathVariable Long orderId) {
        return storeOrderService.getOrder(memberId, orderId);
    }
}
