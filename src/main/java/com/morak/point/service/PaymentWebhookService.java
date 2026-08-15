package com.morak.point.service;

import com.morak.common.error.BusinessException;
import com.morak.common.error.ErrorCode;
import com.morak.point.dto.request.PaymentWebhookRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * PY-3 PG 결제 웹훅. PG가 부르는 경로이고 클라이언트는 부르지 않는다.
 *
 * <p>JWT 게이트를 전부 건너뛰는 대신 서명이 신원을 보장한다(AuthInterceptor의 SKIP_RULES).
 * <b>JWT skip과 무인증은 다르다</b> — 검증이 빠지면 누구나 남의 계정에 포인트를 꽂을 수 있는
 * 공개 엔드포인트가 된다.
 *
 * <p>서명 규약은 우리가 정했다. 토스페이먼츠 테스트 모드가 웹훅 본문에 서명을 붙여 주지
 * 않아 검증할 재료가 없기 때문이다. {@code X-Morak-Signature} 헤더에 <b>본문 바이트의
 * HMAC-SHA256을 소문자 hex로</b> 담고 키는 {@code morak.pg.secret-key}를 쓴다 — LiveKit
 * 웹훅과 같은 발상이고, 12단계에서 실제 PG 규약이 정해지면 이 클래스만 바꾼다.
 *
 * <p>본문을 DTO가 아니라 문자열로 받는 것도 그래서다. 서명이 <b>본문 바이트</b>에 걸려
 * 있어 역직렬화 후 다시 직렬화하면 공백·필드 순서가 달라져 해시가 어긋난다.
 */
@Service
public class PaymentWebhookService {

    private static final Logger log = LoggerFactory.getLogger(PaymentWebhookService.class);

    /** 우리가 정한 서명 헤더. 실제 PG 규약이 정해지면 12단계에서 바뀐다. */
    public static final String SIGNATURE_HEADER = "X-Morak-Signature";

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String STATUS_DONE = "DONE";
    /** 승인되지 않고 끝난 상태들. 전부 충전 건을 FAILED로 닫는다. */
    private static final Set<String> FAILED_STATUSES = Set.of("CANCELED", "ABORTED", "EXPIRED");

    private final SecretKeySpec signingKey;
    private final JsonMapper jsonMapper;
    private final PointChargeService pointChargeService;

    public PaymentWebhookService(JsonMapper jsonMapper,
                                 PointChargeService pointChargeService,
                                 @Value("${morak.pg.secret-key}") String secretKey) {
        this.signingKey = new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8),
                HMAC_ALGORITHM);
        this.jsonMapper = jsonMapper;
        this.pointChargeService = pointChargeService;
    }

    /**
     * 서명 검증. <b>컨트롤러의 첫 줄이어야 한다</b> — 뒤로 밀리면 그 사이의 코드가 검증되지
     * 않은 입력을 만진다.
     */
    public void verify(String signature, String body) {
        if (signature == null || signature.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_WEBHOOK_SIGNATURE);
        }
        byte[] expected = sign(body).getBytes(StandardCharsets.UTF_8);
        byte[] actual = signature.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8);
        // 앞에서부터 비교하다 다른 곳에서 멈추면 응답 시간이 정답을 흘린다.
        if (!MessageDigest.isEqual(expected, actual)) {
            log.warn("결제 웹훅 서명 검증 실패");
            throw new BusinessException(ErrorCode.INVALID_WEBHOOK_SIGNATURE);
        }
    }

    /**
     * 승인 통보는 PY-2와 같은 서비스 메서드로 넘긴다. 몇 번 재수신돼도 적립은 1회다.
     *
     * <p>모르는 주문번호·읽을 수 없는 본문은 처리하지 않고 넘어간다. 컨트롤러가 그래도
     * 200을 돌려주므로 PG의 재시도 폭주를 부르지 않는다.
     */
    public void handle(String body) {
        PaymentWebhookRequest event = jsonMapper.readValue(body, PaymentWebhookRequest.class);
        PaymentWebhookRequest.Data data = event.data();
        if (data == null || data.orderId() == null || data.orderId().isBlank()) {
            log.warn("주문번호가 없는 결제 웹훅이라 처리하지 않는다: eventType={}", event.eventType());
            return;
        }

        String status = data.status();
        if (STATUS_DONE.equals(status)) {
            if (data.paymentKey() == null || data.paymentKey().isBlank()
                    || data.totalAmount() == null) {
                // 승인이라면서 거래 식별자나 금액이 없다. 대조할 재료가 없으니 적립하지 않는다.
                log.warn("승인 통보에 거래 식별자·금액이 없어 처리하지 않는다: orderId={}", data.orderId());
                return;
            }
            pointChargeService.approveByPgOrderId(data.orderId(), data.paymentKey(),
                    data.totalAmount());
        } else if (FAILED_STATUSES.contains(status)) {
            pointChargeService.failByPgOrderId(data.orderId(), "PG 통보 상태: " + status);
        } else {
            // 우리가 쓰지 않는 상태 변경이 같은 URL로 온다. 처리하지 않는 것이 정상이다.
            log.debug("처리 대상이 아닌 상태라 넘긴다: orderId={}, status={}", data.orderId(), status);
        }
    }

    /**
     * 본문 서명값. 발신 쪽이 없어 검증만 하는 코드지만, 규약을 문서가 아니라 여기에 두어야
     * 12단계에서 실제 PG 규약으로 갈아끼울 자리가 한 곳으로 남는다.
     */
    private String sign(String body) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(signingKey);
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("웹훅 서명 계산 실패", e);
        }
    }
}
