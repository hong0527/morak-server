package com.morak.session.controller;

import com.morak.common.dto.WebhookAckResponse;
import com.morak.session.service.LiveKitWebhookService;
import livekit.LivekitWebhook.WebhookEvent;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * SS-10. LiveKit이 부르는 경로이고 클라이언트는 부르지 않는다.
 *
 * <p>JWT 게이트를 전부 건너뛰는 대신 서명 검증이 신원을 보장한다(AuthInterceptor의 SKIP_RULES).
 * <b>검증이 이 메서드의 첫 줄인 것은 규칙이다</b> — 뒤로 밀리면 그 사이의 코드가 검증되지 않은
 * 입력을 만진다.
 *
 * <p>본문을 DTO로 받지 않고 문자열로 받는 이유는 서명이 <b>본문 바이트의 해시</b>에 걸려
 * 있기 때문이다. 역직렬화했다가 다시 직렬화하면 공백·필드 순서가 달라져 해시가 어긋난다.
 */
@RestController
@RequestMapping("/api/webhooks/livekit")
@RequiredArgsConstructor
public class LiveKitWebhookController {

    private static final Logger log = LoggerFactory.getLogger(LiveKitWebhookController.class);

    private final LiveKitWebhookService liveKitWebhookService;

    @PostMapping
    public WebhookAckResponse receive(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestBody String body) {
        WebhookEvent event = liveKitWebhookService.verify(authorization, body);
        try {
            liveKitWebhookService.handle(event);
        } catch (Exception e) {
            // 처리 실패도 200이다. 5xx를 내면 LiveKit이 재시도를 반복해 중복 처리가 늘어난다.
            log.error("웹훅 처리 실패: event={}", event.getEvent(), e);
        }
        return WebhookAckResponse.ok();
    }
}
