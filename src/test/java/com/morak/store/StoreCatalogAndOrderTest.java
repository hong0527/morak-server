package com.morak.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.morak.common.dto.PageResponse;
import com.morak.common.error.BusinessException;
import com.morak.common.error.ErrorCode;
import com.morak.point.service.PointService;
import com.morak.point.type.PointReason;
import com.morak.store.dto.request.OrderCreateRequest;
import com.morak.store.dto.response.OrderCreateResponse;
import com.morak.store.dto.response.OrderDetailResponse;
import com.morak.store.dto.response.OrderSummaryResponse;
import com.morak.store.dto.response.ProductDetailResponse;
import com.morak.store.dto.response.ProductSummaryResponse;
import com.morak.store.service.ProductService;
import com.morak.store.service.StoreOrderService;
import com.morak.store.type.OrderStatus;
import com.morak.store.type.ProductStatus;
import com.morak.store.type.ProductType;
import com.morak.support.IntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * SR-1~SR-5 스토어 조회와 주문의 정상 경로.
 *
 * <p>이 도메인에는 지금까지 테스트가 하나도 없었다. 포인트 원장 쪽은 촘촘한데(멱등키·동시성)
 * 정작 <b>포인트를 쓰는 유일한 출구</b>인 주문은 한 번도 지나가 본 적이 없다.
 *
 * <p>주문이 지켜야 하는 것은 셋이 함께 성립하거나 함께 없어지는 것이다 — 재고 차감, 포인트
 * 차감과 원장, 주문 행. 그래서 성공 경로에서도 응답만 보지 않고 세 자리를 따로 읽는다.
 * 실패 경로에서 확인하는 것은 그 반대다: 아무것도 남지 않았는가.
 */
@DisplayName("스토어 조회와 주문")
class StoreCatalogAndOrderTest extends IntegrationTest {

    private static final int PRICE = 500;

    @Autowired
    private ProductService productService;

    @Autowired
    private StoreOrderService storeOrderService;

    @Autowired
    private PointService pointService;

    // ── SR-1·SR-2 조회 ──

    @Test
    @DisplayName("목록은 감춘 상품만 빼고 품절은 상태로 알린다")
    void 목록이_HIDDEN만_제외한다() {
        // 이 테스트가 죽으면: 공개 준비 중인 상품이 사용자에게 노출되거나(HIDDEN 누락),
        // 품절 상품이 목록에서 통째로 사라져 "있던 상품이 없어졌다"로 보인다.
        Long onSale = fixtures.createProduct(PRICE, 10);
        Long soldOut = fixtures.createSoldOutProduct(PRICE);
        Long hidden = fixtures.createHiddenProduct(PRICE, 10);

        PageResponse<ProductSummaryResponse> products =
                productService.getProducts(null, null, null);

        assertThat(products.content())
                .extracting(ProductSummaryResponse::productId)
                .containsExactly(onSale, soldOut)
                .doesNotContain(hidden);
        assertThat(products.content())
                .extracting(ProductSummaryResponse::status)
                .containsExactly(ProductStatus.ON_SALE, ProductStatus.SOLD_OUT);
        assertThat(products.totalElements()).isEqualTo(2);
    }

    @Test
    @DisplayName("종류로 거르면 그 종류만 남는다")
    void 목록이_종류로_걸러진다() {
        // 이 테스트가 죽으면: 스토어의 탭 전환이 아무 일도 하지 않거나 엉뚱한 종류를 보여준다.
        Long gifticon = fixtures.createProduct(ProductType.GIFTICON, "금액권", PRICE, 5);
        Long book = fixtures.createProduct(ProductType.BOOK, "도서", PRICE, 5);

        assertThat(productService.getProducts(ProductType.BOOK, null, null).content())
                .extracting(ProductSummaryResponse::productId)
                .containsExactly(book);
        assertThat(productService.getProducts(ProductType.GIFTICON, null, null).content())
                .extracting(ProductSummaryResponse::productId)
                .containsExactly(gifticon);
    }

    @Test
    @DisplayName("상세는 판매중 상품을 그대로 주고, 감춘 상품은 없는 것과 같은 응답이다")
    void 상세_조회와_감춘_상품() {
        // 이 테스트가 죽으면: 감춘 상품과 없는 상품이 다른 응답을 내게 된다. 구분되는 순간
        // id를 훑어 미공개 상품의 존재를 알아낼 수 있다.
        Long productId = fixtures.createProduct(ProductType.BOOK, "공부법", PRICE, 3);

        ProductDetailResponse detail = productService.getProduct(productId);
        assertThat(detail.productId()).isEqualTo(productId);
        assertThat(detail.name()).isEqualTo("공부법");
        assertThat(detail.pricePoint()).isEqualTo(PRICE);
        assertThat(detail.stock()).isEqualTo(3);
        assertThat(detail.status()).isEqualTo(ProductStatus.ON_SALE);

        Long hidden = fixtures.createHiddenProduct(PRICE, 10);
        assertThatThrownBy(() -> productService.getProduct(hidden))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.PRODUCT_NOT_FOUND);
        assertThatThrownBy(() -> productService.getProduct(999_999L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.PRODUCT_NOT_FOUND);
    }

    // ── SR-3 주문 ──

    @Test
    @DisplayName("주문 하나가 재고·포인트·주문 행을 함께 움직인다")
    void 주문_정상_경로() {
        // 이 테스트가 죽으면: 스토어의 본체가 멈춘다. 셋 중 하나만 어긋나도 사고다 —
        // 재고만 줄면 공짜 주문이고, 포인트만 빠지면 돈을 받고 물건을 주지 않은 것이다.
        Long memberId = fixtures.joinVerifiedMember("sr3-buyer");
        int before = pointService.getBalance(memberId);
        Long productId = fixtures.createProduct(PRICE, 10);

        OrderCreateResponse order = storeOrderService.order(memberId,
                new OrderCreateRequest(productId, 2, key()));

        assertThat(order.quantity()).isEqualTo(2);
        assertThat(order.pointAmount()).isEqualTo(PRICE * 2);
        assertThat(order.status()).isEqualTo(OrderStatus.ORDERED);
        assertThat(order.pointBalance()).isEqualTo(before - PRICE * 2);

        // 응답이 아니라 DB가 답해야 하는 세 자리
        assertThat(productService.getProduct(productId).stock()).isEqualTo(8);
        assertThat(fixtures.ledgerSum(memberId)).isEqualTo(before - PRICE * 2);
        assertThat(pointService.findLedgerId(memberId, PointReason.ORDER_SPEND, order.orderId()))
                .isNotNull();
        assertThat(fixtures.count("store_order", "id = ? AND member_id = ?",
                order.orderId(), memberId)).isEqualTo(1);
    }

    @Test
    @DisplayName("마지막 재고를 주문하면 상품이 품절로 바뀐다")
    void 재고가_0이_되면_품절이다() {
        // 이 테스트가 죽으면: 재고 0인 상품이 ON_SALE로 남아 목록에서 계속 구매 가능해 보인다.
        Long memberId = fixtures.joinVerifiedMember("sr3-last");
        Long productId = fixtures.createProduct(PRICE, 1);

        storeOrderService.order(memberId, new OrderCreateRequest(productId, 1, key()));

        assertThat(productService.getProduct(productId).status())
                .isEqualTo(ProductStatus.SOLD_OUT);
        assertThat(productService.getProduct(productId).stock()).isZero();
    }

    @Test
    @DisplayName("잔액이 모자라면 주문도 재고 차감도 남지 않는다")
    void 잔액_부족은_아무것도_남기지_않는다() {
        // 이 테스트가 죽으면: 결제에 실패한 주문이 재고만 깎고 사라진다. 롤백이 세 자리를
        // 함께 되돌리지 못하면 팔지도 않은 재고가 매일 줄어든다.
        Long memberId = fixtures.joinVerifiedMember("sr3-poor");
        int balance = pointService.getBalance(memberId);
        Long productId = fixtures.createProduct(balance + 1, 5);

        assertThatThrownBy(() -> storeOrderService.order(memberId,
                new OrderCreateRequest(productId, 1, key())))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INSUFFICIENT_POINT);

        assertThat(fixtures.countAll("store_order")).isZero();
        assertThat(productService.getProduct(productId).stock()).isEqualTo(5);
        assertThat(fixtures.ledgerSum(memberId)).isEqualTo(balance);
    }

    @Test
    @DisplayName("품절·재고 초과·감춘 상품·없는 상품은 각자의 코드로 거절된다")
    void 주문_거절_경로들() {
        // 이 테스트가 죽으면: 실패 사유가 뭉개져 사용자가 무엇을 고쳐야 하는지 알 수 없다.
        // 감춘 상품이 PRODUCT_NOT_FOUND가 아닌 다른 코드를 내면 존재가 새어 나간다.
        Long memberId = fixtures.joinVerifiedMember("sr3-reject");

        Long soldOut = fixtures.createSoldOutProduct(PRICE);
        assertThatThrownBy(() -> storeOrderService.order(memberId,
                new OrderCreateRequest(soldOut, 1, key())))
                .extracting("errorCode").isEqualTo(ErrorCode.OUT_OF_STOCK);

        Long scarce = fixtures.createProduct(PRICE, 2);
        assertThatThrownBy(() -> storeOrderService.order(memberId,
                new OrderCreateRequest(scarce, 3, key())))
                .extracting("errorCode").isEqualTo(ErrorCode.OUT_OF_STOCK);

        Long hidden = fixtures.createHiddenProduct(PRICE, 10);
        assertThatThrownBy(() -> storeOrderService.order(memberId,
                new OrderCreateRequest(hidden, 1, key())))
                .extracting("errorCode").isEqualTo(ErrorCode.PRODUCT_NOT_FOUND);

        assertThatThrownBy(() -> storeOrderService.order(memberId,
                new OrderCreateRequest(999_999L, 1, key())))
                .extracting("errorCode").isEqualTo(ErrorCode.PRODUCT_NOT_FOUND);

        assertThat(fixtures.countAll("store_order")).isZero();
    }

    @Test
    @DisplayName("같은 멱등키의 재전송은 두 번째 주문이 아니라 기존 주문 번호로 답한다")
    void 멱등키_재전송은_주문을_늘리지_않는다() {
        // 이 테스트가 죽으면: 버튼 더블클릭과 네트워크 재시도가 그대로 두 번의 결제가 된다.
        // details.orderId가 빠지면 클라이언트가 "재시도 실패"와 "이미 성공"을 구분하지 못한다.
        Long memberId = fixtures.joinVerifiedMember("sr3-idem");
        Long productId = fixtures.createProduct(PRICE, 10);
        String key = key();
        OrderCreateResponse first = storeOrderService.order(memberId,
                new OrderCreateRequest(productId, 1, key));

        assertThatThrownBy(() -> storeOrderService.order(memberId,
                new OrderCreateRequest(productId, 1, key)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.DUPLICATE_ORDER);

        assertThat(fixtures.countAll("store_order")).isEqualTo(1);
        assertThat(productService.getProduct(productId).stock()).isEqualTo(9);
        assertThat(pointService.getBalance(memberId)).isEqualTo(first.pointBalance());
    }

    // ── SR-4·SR-5 내 주문 ──

    @Test
    @DisplayName("내 주문 목록은 최신순이고 남의 주문은 섞이지 않는다")
    void 내_주문_목록() {
        // 이 테스트가 죽으면: 남의 구매 내역이 내 화면에 보이거나, 방금 한 주문이 목록 아래로
        // 밀려 "주문이 안 됐다"로 읽힌다.
        Long mine = fixtures.joinVerifiedMember("sr4-mine");
        Long other = fixtures.joinVerifiedMember("sr4-other");
        Long productId = fixtures.createProduct(PRICE, 10);

        clock.fixAt(BASE_TIME);
        Long older = storeOrderService.order(mine,
                new OrderCreateRequest(productId, 1, key())).orderId();
        clock.fixAt(BASE_TIME.plusMinutes(5));
        Long newer = storeOrderService.order(mine,
                new OrderCreateRequest(productId, 1, key())).orderId();
        storeOrderService.order(other, new OrderCreateRequest(productId, 1, key()));

        PageResponse<OrderSummaryResponse> orders =
                storeOrderService.getMyOrders(mine, null, null);

        assertThat(orders.totalElements()).isEqualTo(2);
        assertThat(orders.content())
                .extracting(OrderSummaryResponse::orderId)
                .containsExactly(newer, older);
    }

    @Test
    @DisplayName("주문 상세는 결제 원장을 함께 주고, 남의 주문은 403이다")
    void 주문_상세와_소유권() {
        // 이 테스트가 죽으면: 주문 번호만 바꿔 넣으면 남의 구매 내역이 열린다. 원장 번호가
        // 빠지면 사용자가 이 주문에 얼마가 나갔는지 포인트 내역과 맞춰 볼 수 없다.
        Long mine = fixtures.joinVerifiedMember("sr5-mine");
        Long other = fixtures.joinVerifiedMember("sr5-other");
        Long productId = fixtures.createProduct(ProductType.BOOK, "도서", PRICE, 10);
        String key = key();
        Long orderId = storeOrderService.order(mine,
                new OrderCreateRequest(productId, 2, key)).orderId();

        OrderDetailResponse detail = storeOrderService.getOrder(mine, orderId);
        assertThat(detail.orderId()).isEqualTo(orderId);
        assertThat(detail.type()).isEqualTo(ProductType.BOOK);
        assertThat(detail.quantity()).isEqualTo(2);
        assertThat(detail.pointAmount()).isEqualTo(PRICE * 2);
        assertThat(detail.idempotencyKey()).isEqualTo(key);
        assertThat(detail.pointLedgerId())
                .isEqualTo(pointService.findLedgerId(mine, PointReason.ORDER_SPEND, orderId));

        assertThatThrownBy(() -> storeOrderService.getOrder(other, orderId))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.FORBIDDEN);
        assertThatThrownBy(() -> storeOrderService.getOrder(mine, 999_999L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.ORDER_NOT_FOUND);
    }

    private String key() {
        return UUID.randomUUID().toString();
    }
}
