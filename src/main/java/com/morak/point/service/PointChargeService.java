package com.morak.point.service;

import com.morak.common.error.BusinessException;
import com.morak.common.error.ErrorCode;
import com.morak.point.client.PgClient;
import com.morak.point.client.PgPayment;
import com.morak.point.dto.request.ChargeConfirmRequest;
import com.morak.point.dto.request.ChargeCreateRequest;
import com.morak.point.dto.response.ChargeConfirmResponse;
import com.morak.point.dto.response.ChargeCreateResponse;
import com.morak.point.entity.PointCharge;
import com.morak.point.repository.PointChargeRepository;
import com.morak.point.type.ChargeStatus;
import com.morak.point.type.PointReason;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * PY-1·PY-2 포인트 충전과 PY-3 웹훅의 승인 처리.
 *
 * <p><b>이 단계가 막아야 하는 것은 하나다 — 돈은 빠졌는데 포인트가 없거나, 그 반대.</b>
 * 그래서 승인 확인은 두 경로로 들어온다. 클라이언트가 결제창에서 돌아와 부르는 PY-2와,
 * 클라이언트가 이탈해도 PG가 직접 알려주는 PY-3다. <b>웹훅은 유실되고 폴링은 늦으므로
 * 둘 다 필요하고, 둘 다 있으니 멱등이 필수다.</b> 두 경로가 {@link #settle}이라는 같은
 * 메서드를 지나는 것도 그래서다 — 다른 코드로 같은 일을 하면 언젠가 갈라진다.
 *
 * <p>중복 적립의 방어선은 세 겹이다. 순차 재호출은 {@code status=APPROVED} 검사가 걸러
 * 멱등 응답으로 끝내고, 동시 도달은 {@code uk_pl_dedup}(원장)이 막는다. {@code uk_pc_tid}는
 * 같은 거래가 서로 다른 충전 건에 붙는 경우를 잡는다. 앞의 두 검사는 지름길이고 <b>진짜
 * 방어선은 제약</b>이다 — 동시에 들어온 두 요청은 사전 검사를 함께 통과한다.
 *
 * <p>그래서 승인 경로는 {@code @Transactional}이 아니라 {@link TransactionTemplate}으로
 * 감쌌다(SR-3 주문과 같은 이유). 제약 위반은 트랜잭션이 끝나야 손에 들어오는데, 그때
 * 먼저 커밋한 쪽의 결과를 읽어 멱등 200으로 돌려주려면 잡는 자리가 트랜잭션 <b>밖</b>이어야
 * 한다. 애너테이션만 붙이면 그 자리가 없어 500이 나간다.
 *
 * <p>클라이언트가 보낸 금액은 어디서도 근거가 되지 않는다. 대조에만 쓰고, 실제 승인 여부와
 * 금액은 {@link PgClient}에 다시 묻는다. 믿는 순간 1원 결제로 10만 포인트가 들어온다.
 */
@Service
public class PointChargeService {

    private static final Logger log = LoggerFactory.getLogger(PointChargeService.class);

    private final PointChargeRepository pointChargeRepository;
    private final PointService pointService;
    private final PgClient pgClient;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private final String provider;
    private final int pointPerKrw;
    private final int minAmountKrw;
    private final int maxAmountKrw;
    private final int readyExpireMinutes;

    public PointChargeService(PointChargeRepository pointChargeRepository,
                              PointService pointService,
                              PgClient pgClient,
                              TransactionTemplate transactionTemplate,
                              Clock clock,
                              @Value("${morak.pg.provider}") String provider,
                              @Value("${morak.pg.point-per-krw}") int pointPerKrw,
                              @Value("${morak.pg.min-amount-krw}") int minAmountKrw,
                              @Value("${morak.pg.max-amount-krw}") int maxAmountKrw,
                              @Value("${morak.pg.ready-expire-minutes}") int readyExpireMinutes) {
        this.pointChargeRepository = pointChargeRepository;
        this.pointService = pointService;
        this.pgClient = pgClient;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
        this.provider = provider;
        this.pointPerKrw = pointPerKrw;
        this.minAmountKrw = minAmountKrw;
        this.maxAmountKrw = maxAmountKrw;
        this.readyExpireMinutes = readyExpireMinutes;
    }

    /**
     * PY-1. 충전 건만 만들고 포인트는 늘리지 않는다. 클라이언트는 받은 주문번호로 PG
     * 결제창을 띄운다.
     */
    @Transactional
    public ChargeCreateResponse create(Long memberId, ChargeCreateRequest request) {
        int amountKrw = request.amountKrw();
        if (amountKrw < minAmountKrw || amountKrw > maxAmountKrw) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, Map.of("amountKrw",
                    "%,d원 이상 %,d원 이하만 충전할 수 있습니다.".formatted(minAmountKrw, maxAmountKrw)));
        }
        // 환산 비율이 설정값이라 상한을 낮게 잡아도 곱셈이 int를 넘길 수 있다. 넘겨서 음수로
        // 뒤집히면 충전이 차감이 되므로 그 경로 자체를 남기지 않는다.
        long pointAmount = (long) amountKrw * pointPerKrw;
        if (pointAmount > Integer.MAX_VALUE) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    Map.of("amountKrw", "한 번에 충전할 수 있는 금액을 넘었습니다."));
        }

        LocalDateTime now = LocalDateTime.now(clock);
        // 주문번호가 충전 건 번호를 담아 id가 확정된 뒤에만 만들 수 있다.
        PointCharge charge = pointChargeRepository.saveAndFlush(
                PointCharge.ready(memberId, amountKrw, (int) pointAmount, now));
        charge.assignPgOrderId();
        pointChargeRepository.flush();
        log.info("충전 건 생성: charge={}, member={}, 금액={}원", charge.getId(), memberId, amountKrw);
        return ChargeCreateResponse.of(charge, provider);
    }

    /**
     * PY-2. 본인 충전 건만 확인할 수 있고, 요청이 가리키는 주문번호·금액이 그 건과 맞아야
     * PG에 물어본다.
     */
    public ChargeConfirmResponse confirm(Long memberId, Long chargeId,
                                         ChargeConfirmRequest request) {
        Settled settled;
        try {
            settled = transactionTemplate.execute(status -> {
                PointCharge charge = pointChargeRepository.findById(chargeId)
                        // 타인의 충전 건은 없는 것과 같은 응답이다. 구분해 주면 번호를 훑어
                        // 남의 결제 존재 여부를 알아낼 수 있다.
                        .filter(found -> found.getMemberId().equals(memberId))
                        .orElseThrow(() -> new BusinessException(ErrorCode.CHARGE_NOT_FOUND));
                if (!charge.getPgOrderId().equals(request.pgOrderId())
                        || charge.getAmountKrw() != request.amountKrw()) {
                    // 상태는 건드리지 않는다. 클라이언트의 오타 한 번으로 정상 결제 건이
                    // FAILED로 닫히면 되돌릴 길이 없다.
                    throw new BusinessException(ErrorCode.PAYMENT_AMOUNT_MISMATCH);
                }
                return settle(charge, request.pgTid());
            });
        } catch (DataIntegrityViolationException e) {
            // 웹훅(PY-3)과 동시에 승인했거나, 다른 충전 건이 쓴 거래 식별자를 보냈다. 어느
            // 쪽이든 이 트랜잭션은 통째로 롤백돼 이중 적립이 남지 않았다. 앞의 경우라면 먼저
            // 커밋한 쪽의 결과가 답이고, 뒤의 경우라면 이 건은 승인된 적이 없다.
            log.info("충전 승인이 제약에 부딪혀 롤백됐다: charge={}", chargeId);
            return readSettled(chargeId);
        }
        if (settled.failure() != null) {
            throw new BusinessException(settled.failure());
        }
        return settled.response();
    }

    /**
     * PY-3의 승인 통보. 웹훅은 우리 쪽 충전 건 번호를 모르고 자기가 받은 주문번호만 안다.
     *
     * <p>실패해도 예외를 밖으로 내보내지 않는다 — 호출부(웹훅 컨트롤러)는 어차피 200을
     * 돌려줘야 하고, 여기서 던지면 그 사실이 로그에 두 번 남을 뿐이다.
     */
    public void approveByPgOrderId(String pgOrderId, String pgTid, int amountKrw) {
        PointCharge found = pointChargeRepository.findByPgOrderId(pgOrderId).orElse(null);
        if (found == null) {
            // 200으로 흡수한다. 4xx를 주면 PG가 재시도를 반복한다.
            log.warn("알 수 없는 주문번호라 처리하지 않는다: pgOrderId={}", pgOrderId);
            return;
        }
        if (found.getAmountKrw() != amountKrw) {
            markFailed(found.getId(), "웹훅 금액 불일치: 충전=%d원, 웹훅=%d원"
                    .formatted(found.getAmountKrw(), amountKrw));
            return;
        }
        try {
            Settled settled = transactionTemplate.execute(status ->
                    settle(loadCharge(found.getId()), pgTid));
            if (settled.failure() != null) {
                log.warn("웹훅 승인이 적립 없이 끝났다: charge={}, code={}",
                        found.getId(), settled.failure());
            }
        } catch (DataIntegrityViolationException e) {
            // PY-2와 동시에 도달해 한쪽만 반영됐다. 이쪽이 롤백된 것이라 할 일이 없다.
            log.info("웹훅 승인이 제약에 부딪혀 롤백됐다: charge={}", found.getId());
        }
    }

    /**
     * B5. 승인도 실패도 오지 않은 채 기한을 넘긴 READY를 FAILED로 닫는다.
     *
     * <p>결제창을 띄우고 그냥 닫으면 PG는 아무것도 통보하지 않는다. 그 행을 그대로 두면
     * "결제된 건가"를 사람이 판단해야 하는 미결이 매일 쌓이고, 30일 지난 주문번호로 승인을
     * 시도하는 요청에도 계속 문이 열려 있다.
     *
     * <p>충전 건마다 트랜잭션을 나눈다 — 한 건의 실패가 같은 실행의 나머지를 되돌리면 안 된다.
     */
    public int expireStaleReady() {
        LocalDateTime cutoff = LocalDateTime.now(clock).minusMinutes(readyExpireMinutes);
        int expired = 0;
        for (Long chargeId : pointChargeRepository.findIdsToExpire(ChargeStatus.READY, cutoff)) {
            expired += expireIfStillReady(chargeId);
        }
        return expired;
    }

    /**
     * 목록을 읽은 뒤 승인이 도착했을 수 있다. 상태를 다시 확인하는 것이 그 창을 닫는다 —
     * 이미 APPROVED면 여기서 손대지 않는다.
     */
    private int expireIfStillReady(Long chargeId) {
        Boolean expired = transactionTemplate.execute(status -> {
            PointCharge charge = loadCharge(chargeId);
            if (charge.getStatus() != ChargeStatus.READY) {
                return false;
            }
            charge.fail();
            log.info("승인 기한이 지나 방치된 충전을 닫는다: charge={}, created={}",
                    chargeId, charge.getCreatedAt());
            return true;
        });
        return Boolean.TRUE.equals(expired) ? 1 : 0;
    }

    /** 결제창 생성 후 승인 기한이 지났는가. PG 승인 API의 10분 제한에 여유를 둔 값이다. */
    private boolean isStale(PointCharge charge) {
        return charge.getCreatedAt().plusMinutes(readyExpireMinutes)
                .isBefore(LocalDateTime.now(clock));
    }

    /** PY-3의 취소·중단·만료 통보. 이미 승인된 건은 되돌리지 않는다. */
    public void failByPgOrderId(String pgOrderId, String reason) {
        PointCharge found = pointChargeRepository.findByPgOrderId(pgOrderId).orElse(null);
        if (found == null) {
            log.warn("알 수 없는 주문번호라 처리하지 않는다: pgOrderId={}", pgOrderId);
            return;
        }
        markFailed(found.getId(), reason);
    }

    /**
     * 승인 확인의 본체. PY-2와 PY-3이 같은 자리를 지난다.
     *
     * <p>미승인·금액 어긋남을 예외로 던지지 않고 {@link Settled}로 돌려주는 이유는 그 경로가
     * <b>FAILED를 기록하고 커밋해야</b> 하기 때문이다. 여기서 던지면 트랜잭션이 롤백돼
     * 실패 기록까지 사라지고, 다음 호출이 다시 PG를 부른다.
     */
    private Settled settle(PointCharge charge, String pgTid) {
        if (charge.isApproved()) {
            // 멱등. 끝난 건에는 PG를 다시 부르지도, 원장을 다시 쓰지도 않는다.
            return Settled.of(response(charge));
        }
        if (charge.getStatus() == ChargeStatus.FAILED) {
            return Settled.failure(ErrorCode.PAYMENT_NOT_APPROVED);
        }
        if (isStale(charge)) {
            // PG에 물어보지 않고 닫는다. 승인 API 자체가 결제창 생성 후 10분까지만 받으므로
            // 그 배가 지난 요청은 이미 PG에서도 승인될 수 없다.
            charge.fail();
            log.warn("승인 기한이 지난 충전이라 닫는다: charge={}, created={}",
                    charge.getId(), charge.getCreatedAt());
            return Settled.failure(ErrorCode.PAYMENT_NOT_APPROVED);
        }

        PgPayment payment = pgClient.confirm(charge.getPgOrderId(), pgTid, charge.getAmountKrw());
        if (!payment.approved()) {
            charge.fail();
            log.warn("PG가 승인하지 않아 충전을 실패로 닫는다: charge={}, 사유={}",
                    charge.getId(), payment.failureReason());
            return Settled.failure(ErrorCode.PAYMENT_NOT_APPROVED);
        }
        if (payment.amountKrw() != charge.getAmountKrw()) {
            // 실제로 결제된 금액이 우리가 만든 주문 금액과 다르다. 어느 쪽을 적립해도 틀리는
            // 상황이라 적립하지 않고 닫은 뒤 사람이 대사하게 남긴다.
            charge.fail();
            log.error("PG 승인 금액이 충전 건과 달라 적립하지 않는다: charge={}, 충전={}원, PG={}원",
                    charge.getId(), charge.getAmountKrw(), payment.amountKrw());
            return Settled.failure(ErrorCode.PAYMENT_AMOUNT_MISMATCH);
        }

        LocalDateTime now = LocalDateTime.now(clock);
        charge.approve(payment.pgTid(), now);
        // 같은 거래가 다른 충전 건에 붙는 경우를 uk_pc_tid가 잡는다. 적립보다 먼저 드러내야
        // 원장을 쓴 뒤에 뒤집히지 않는다.
        pointChargeRepository.flush();
        pointService.award(charge.getMemberId(), charge.getPointAmount(), PointReason.CHARGE,
                charge.getId(), now);
        return Settled.of(response(charge));
    }

    /** 동시 도달로 롤백된 쪽이 먼저 커밋한 쪽의 결과를 읽는다. */
    private ChargeConfirmResponse readSettled(Long chargeId) {
        PointCharge charge = loadCharge(chargeId);
        if (!charge.isApproved()) {
            // 다른 건이 쓴 거래 식별자였거나 우리가 모르는 경합이다. 적립되지 않은 것은
            // 확실하므로 미승인으로 답한다.
            log.warn("제약 충돌로 롤백된 뒤에도 승인되지 않은 충전이다: charge={}, status={}",
                    chargeId, charge.getStatus());
            throw new BusinessException(ErrorCode.PAYMENT_NOT_APPROVED);
        }
        return response(charge);
    }

    private void markFailed(Long chargeId, String reason) {
        transactionTemplate.executeWithoutResult(status -> {
            PointCharge charge = loadCharge(chargeId);
            if (charge.getStatus() != ChargeStatus.READY) {
                log.info("이미 확정된 충전이라 실패로 바꾸지 않는다: charge={}, status={}",
                        chargeId, charge.getStatus());
                return;
            }
            charge.fail();
            log.warn("충전을 실패로 닫는다: charge={}, 사유={}", chargeId, reason);
        });
    }

    private PointCharge loadCharge(Long chargeId) {
        return pointChargeRepository.findById(chargeId)
                .orElseThrow(() -> new IllegalStateException("사라진 충전 건: " + chargeId));
    }

    private ChargeConfirmResponse response(PointCharge charge) {
        return ChargeConfirmResponse.of(charge, pointService.getBalance(charge.getMemberId()));
    }

    /**
     * 승인 처리의 결과. 둘 중 하나만 채워진다 — 성공이면 응답, 실패면 에러 코드다.
     * 실패를 값으로 들고 나오는 것은 FAILED 기록을 커밋한 뒤에 예외를 던지기 위해서다.
     */
    private record Settled(ChargeConfirmResponse response, ErrorCode failure) {

        static Settled of(ChargeConfirmResponse response) {
            return new Settled(response, null);
        }

        static Settled failure(ErrorCode failure) {
            return new Settled(null, failure);
        }
    }
}
