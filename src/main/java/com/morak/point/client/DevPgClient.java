package com.morak.point.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 개발용 PG 클라이언트. PG를 호출하지 않고 받은 값을 그대로 승인된 것으로 돌려준다.
 * 실제 토스페이먼츠 구현은 테스트 키 발급 후 12단계에서 추가하고, 그때 이 클래스는 dev
 * 프로필에만 남는다({@code DevSocialClient}와 같은 자리).
 *
 * <p>{@code @Profile("dev")}와 {@code morak.dev.enabled} 이중 스위치다. 프로필 실수 하나로
 * <b>운영에서 결제 없이 포인트가 적립되는</b> 사고를 막는다. dev가 아닌 프로필에는 대신
 * {@link RejectingPgClient}가 주입돼 어떤 거래도 승인되지 않으므로, 결제 경로가 조용히 열려
 * 있는 상태는 만들 수 없다.
 *
 * <p>실패·금액 불일치 경로도 개발 중에 재현할 수 있어야 한다. 거래 식별자의 접두사로
 * 가른다 — 승인 응답의 모양을 바꾸는 것은 PG 쪽이고, 우리가 조작할 수 있는 입력은
 * 클라이언트(또는 웹훅)가 보내는 {@code pgTid}뿐이다.
 */
@Component
@Profile("dev")
@ConditionalOnProperty(name = "morak.dev.enabled", havingValue = "true")
public class DevPgClient implements PgClient {

    private static final Logger log = LoggerFactory.getLogger(DevPgClient.class);

    /** 이 접두사면 PG가 거절한 것으로 흉내낸다. */
    private static final String REJECT_PREFIX = "fail-";
    /** 이 접두사면 승인은 됐지만 금액이 어긋난 것으로 흉내낸다. */
    private static final String MISMATCH_PREFIX = "mismatch-";

    @Override
    public PgPayment confirm(String pgOrderId, String pgTid, int amountKrw) {
        if (pgTid == null || pgTid.isBlank()) {
            return PgPayment.rejected("거래 식별자가 없다");
        }
        if (pgTid.startsWith(REJECT_PREFIX)) {
            log.info("개발용 PG 거절 응답: order={}, tid={}", pgOrderId, pgTid);
            return PgPayment.rejected("개발용 거절 응답(" + REJECT_PREFIX + " 접두사)");
        }
        if (pgTid.startsWith(MISMATCH_PREFIX)) {
            log.info("개발용 PG 금액 불일치 응답: order={}, tid={}", pgOrderId, pgTid);
            return PgPayment.approved(pgTid, amountKrw + 1);
        }
        return PgPayment.approved(pgTid, amountKrw);
    }
}
