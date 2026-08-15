package com.morak.point.controller;

import com.morak.common.dto.WebhookAckResponse;
import com.morak.point.service.PaymentWebhookService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * PY-3. PG가 부르는 경로이고 클라이언트는 부르지 않는다(SS-10 LiveKit 웹훅과 같은 자리).
 *
 * <p>서명 검증이 이 메서드의 첫 줄인 것은 규칙이다. 본문을 DTO가 아니라 문자열로 받는
 * 이유는 서명이 본문 바이트에 걸려 있기 때문이다.
 */
@RestController
@RequestMapping("/api/webhooks/payment")
@RequiredArgsConstructor
public class PaymentWebhookController {

    private static final Logger log = LoggerFactory.getLogger(PaymentWebhookController.class);

    private final PaymentWebhookService paymentWebhookService;

    @PostMapping
    public WebhookAckResponse receive(
            @RequestHeader(value = PaymentWebhookService.SIGNATURE_HEADER, required = false)
            String signature,
            @RequestBody String body) {
        paymentWebhookService.verify(signature, body);
        try {
            paymentWebhookService.handle(body);
        } catch (Exception e) {
            // 처리 실패도 200이다. 5xx를 내면 PG가 재시도를 반복해 중복 처리가 늘어난다.
            log.error("결제 웹훅 처리 실패", e);
        }
        return WebhookAckResponse.ok();
    }
}
