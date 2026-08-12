package com.morak.store.service;

import com.morak.common.dto.PageParams;
import com.morak.common.dto.PageResponse;
import com.morak.common.error.BusinessException;
import com.morak.common.error.ErrorCode;
import com.morak.point.service.PointService;
import com.morak.point.type.PointReason;
import com.morak.store.dto.request.OrderCreateRequest;
import com.morak.store.dto.response.OrderCreateResponse;
import com.morak.store.dto.response.OrderDetailResponse;
import com.morak.store.dto.response.OrderSummaryResponse;
import com.morak.store.entity.Product;
import com.morak.store.entity.StoreOrder;
import com.morak.store.repository.ProductRepository;
import com.morak.store.repository.StoreOrderRepository;
import com.morak.store.type.ProductStatus;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * SR-3·SR-4·SR-5 주문. 포인트 전액 결제만 지원한다(D16).
 *
 * <p><b>주문은 세 가지를 함께 한다 — 재고 조건부 차감, 포인트 조건부 차감 + 원장 기록, 주문 행
 * 생성.</b> 하나라도 실패하면 전부 롤백이다. 포인트만 빠지고 주문이 없는 상태나, 주문은 있는데
 * 재고가 그대로인 상태를 만들지 않는다.
 *
 * <p>중복 주문의 방어선은 {@code uk_so_idem}이다. 서비스의 사전 조회는 순차 재전송을 위한
 * 지름길일 뿐이라, 동시에 들어온 두 요청은 조회를 함께 통과한다. 그래서 주문 경로만
 * {@code @Transactional}이 아니라 {@link TransactionTemplate}으로 감쌌다 — 제약 위반은
 * 트랜잭션이 끝나야 손에 들어오는데, 그때 기존 주문 번호를 찾아 409로 돌려주려면 잡는 자리가
 * 트랜잭션 <b>밖</b>이어야 한다. 메서드에 애너테이션만 붙이면 그 자리가 없어 500이 나간다.
 *
 * <p>주문 취소·환불은 v1 보류다(FR-506). {@code OrderStatus.CANCELLED}는 자리만 비워 둔 값이고
 * 여기에 전이 경로가 없는 것은 누락이 아니다.
 */
@Service
@RequiredArgsConstructor
public class StoreOrderService {

    /**
     * 최신순. {@code orderedAt}만으로 정렬하면 같은 시각의 주문에서 페이지마다 순서가 달라져
     * 같은 행이 두 번 보이거나 빠진다. id로 동률을 깬다.
     */
    private static final Sort ORDER_SORT =
            Sort.by(Sort.Direction.DESC, "orderedAt").and(Sort.by(Sort.Direction.DESC, "id"));

    private final StoreOrderRepository storeOrderRepository;
    private final ProductRepository productRepository;
    private final PointService pointService;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    /** SR-3. */
    public OrderCreateResponse order(Long memberId, OrderCreateRequest request) {
        try {
            return transactionTemplate.execute(status -> place(memberId, request));
        } catch (DataIntegrityViolationException e) {
            // 같은 키의 두 요청이 동시에 들어와 제약에 부딪힌 경우. 이 트랜잭션은 통째로
            // 롤백됐으므로 이중 차감은 남지 않는다. 먼저 커밋한 주문을 찾아 계약대로 답한다.
            throw duplicateOrder(request.idempotencyKey());
        }
    }

    private OrderCreateResponse place(Long memberId, OrderCreateRequest request) {
        storeOrderRepository.findByIdempotencyKey(request.idempotencyKey())
                .ifPresent(existing -> {
                    throw duplicateOrder(request.idempotencyKey());
                });

        Product product = productRepository.findById(request.productId())
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));
        // 감춘 상품과 없는 상품은 같은 응답이다. 구분해 주면 id를 훑어 미공개 상품의 존재를
        // 알아낼 수 있다.
        if (product.getStatus() == ProductStatus.HIDDEN) {
            throw new BusinessException(ErrorCode.PRODUCT_NOT_FOUND);
        }
        if (!product.isOrderable()) {
            throw new BusinessException(ErrorCode.OUT_OF_STOCK);
        }

        int quantity = request.quantity();
        if (productRepository.decreaseStock(product.getId(), quantity, ProductStatus.ON_SALE) == 0) {
            throw new BusinessException(ErrorCode.OUT_OF_STOCK);
        }
        Product ordered = productRepository.findById(product.getId())
                .orElseThrow(() -> new IllegalStateException(
                        "차감한 상품이 사라졌다: " + product.getId()));
        if (ordered.getStock() == 0) {
            ordered.markSoldOut();
        }

        // 수량은 재고 조건에 먼저 걸리지만, 곱셈이 int를 넘겨 음수로 뒤집히면 차감이 지급이
        // 된다. 그 경로 자체를 남기지 않는다.
        long pointAmount = (long) ordered.getPricePoint() * quantity;
        if (pointAmount > Integer.MAX_VALUE) {
            throw new BusinessException(ErrorCode.INSUFFICIENT_POINT);
        }

        LocalDateTime now = LocalDateTime.now(clock);
        // 원장의 ref가 주문 번호라 주문 행이 먼저 확정돼야 한다.
        StoreOrder order = storeOrderRepository.saveAndFlush(
                StoreOrder.place(memberId, ordered.getId(), quantity, (int) pointAmount,
                        request.idempotencyKey(), now));
        int pointBalance = pointService.spend(memberId, (int) pointAmount,
                PointReason.ORDER_SPEND, order.getId(), now);
        return OrderCreateResponse.of(order, ordered, pointBalance);
    }

    /** SR-4. */
    @Transactional(readOnly = true)
    public PageResponse<OrderSummaryResponse> getMyOrders(Long memberId, Integer page,
                                                          Integer size) {
        PageParams params = PageParams.of(page, size);
        Page<StoreOrder> orders =
                storeOrderRepository.findByMemberId(memberId, params.toPageable(ORDER_SORT));
        Map<Long, Product> products = loadProducts(orders.getContent());
        return PageResponse.of(orders,
                order -> OrderSummaryResponse.of(order, products.get(order.getProductId())));
    }

    /** SR-5. */
    @Transactional(readOnly = true)
    public OrderDetailResponse getOrder(Long memberId, Long orderId) {
        StoreOrder order = storeOrderRepository.findById(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND));
        // 타인의 주문은 403이다. 퇴출(AP-1)과 방향이 반대로 보이지만 기준은 같다 — 그 자원의
        // 기본 응답을 따른다. 주문은 번호를 아는 것만으로 새어 나갈 사실이 없고, 없는 주문은
        // 이미 404라 여기서 404를 또 쓰면 본인 주문의 오타와 남의 주문을 구분할 수 없다.
        if (!order.getMemberId().equals(memberId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        Product product = productRepository.findById(order.getProductId())
                .orElseThrow(() -> new IllegalStateException(
                        "주문의 상품이 없다: order=" + orderId));
        Long pointLedgerId =
                pointService.findLedgerId(memberId, PointReason.ORDER_SPEND, order.getId());
        return OrderDetailResponse.of(order, product, pointLedgerId);
    }

    private BusinessException duplicateOrder(String idempotencyKey) {
        // details.orderId가 있어야 클라이언트가 "재시도 실패"와 "이미 성공한 주문"을 구분해
        // 주문 상세로 넘어갈 수 있다.
        Map<String, Object> details = storeOrderRepository.findByIdempotencyKey(idempotencyKey)
                .<Map<String, Object>>map(order -> Map.of("orderId", order.getId()))
                .orElse(Map.of());
        return new BusinessException(ErrorCode.DUPLICATE_ORDER, details);
    }

    /**
     * 주문마다 상품을 따로 읽으면 페이지 하나에 질의가 20번 붙는다. 한 번에 모아 읽는다 —
     * FK가 있어 빠지는 상품은 없다.
     */
    private Map<Long, Product> loadProducts(Collection<StoreOrder> orders) {
        Set<Long> productIds = orders.stream()
                .map(StoreOrder::getProductId)
                .collect(Collectors.toSet());
        return productRepository.findAllById(productIds).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
    }
}
