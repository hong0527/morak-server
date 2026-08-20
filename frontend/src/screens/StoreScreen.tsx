import { useCallback, useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";

import { api } from "../api/client";
import { ApiError } from "../api/http";
import TabBar from "../components/TabBar";
import type { OrderDetail, OrderPage, ProductDetail, ProductPage } from "../api/types";

const TYPE_LABEL: Record<ProductDetail["type"], string> = {
  GIFTICON: "기프티콘",
  BOOK: "도서",
};

/** 주문 확정 화면에 들고 들어가는 것. 키는 진입 시 한 번 만들고 끝까지 유지한다 */
interface Draft {
  product: ProductDetail;
  quantity: number;
  idempotencyKey: string;
}

/** 성공 응답과 중복 주문 조회 결과를 한 모양으로 그리기 위한 최소 공통분모 */
interface Placed {
  orderId: number;
  productName: string;
  quantity: number;
  pointAmount: number;
  /** OD-3 에는 잔액이 없어 중복 주문 경로에서는 비어 있다 */
  pointBalance: number | null;
}

/**
 * S12. 스토어. 목록(ST-1) → 상세(ST-2) → 주문(OD-1) → 내역(OD-2).
 *
 * idempotencyKey 는 주문 확정 화면에 들어갈 때 한 번 만들어 그 주문이 끝날 때까지
 * 유지한다. 재시도마다 새로 만들면 중복 탭에서 포인트가 두 번 빠진다.
 * 같은 키의 409 DUPLICATE_ORDER 는 실패가 아니다 — details.orderId 의 주문이 그 주문이다.
 */
export default function StoreScreen() {
  const navigate = useNavigate();
  const [products, setProducts] = useState<ProductPage | null>(null);
  const [orders, setOrders] = useState<OrderPage | null>(null);
  const [balance, setBalance] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);

  const [draft, setDraft] = useState<Draft | null>(null);
  const [placed, setPlaced] = useState<Placed | null>(null);
  const [orderError, setOrderError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    try {
      const [productPage, orderPage, me] = await Promise.all([
        api.store.products({ page: 0, size: 20 }),
        api.order.mine({ page: 0, size: 20 }),
        api.member.me(),
      ]);
      setProducts(productPage);
      setOrders(orderPage);
      setBalance(me.pointBalance);
      setError(null);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : String(e));
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  async function openDraft(productId: number) {
    setBusy(true);
    setOrderError(null);
    setPlaced(null);
    try {
      const product = await api.store.product(productId);
      setDraft({ product, quantity: 1, idempotencyKey: crypto.randomUUID() });
    } catch (e) {
      setOrderError(e instanceof ApiError ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }

  async function placeOrder() {
    if (!draft) return;
    setBusy(true);
    setOrderError(null);
    try {
      const created = await api.order.create({
        productId: draft.product.productId,
        quantity: draft.quantity,
        idempotencyKey: draft.idempotencyKey,
      });
      setPlaced({
        orderId: created.orderId,
        productName: created.productName,
        quantity: created.quantity,
        pointAmount: created.pointAmount,
        pointBalance: created.pointBalance,
      });
      setDraft(null);
      void load();
    } catch (e) {
      if (e instanceof ApiError && e.code === "DUPLICATE_ORDER" && e.details?.orderId) {
        // 같은 키가 이미 접수됐다 = 앞선 요청이 성공한 것이다. 그 주문을 보여준다.
        const existing: OrderDetail = await api.order.get(Number(e.details.orderId));
        setPlaced({
          orderId: existing.orderId,
          productName: existing.productName,
          quantity: existing.quantity,
          pointAmount: existing.pointAmount,
          pointBalance: null,
        });
        setDraft(null);
        void load();
      } else if (e instanceof ApiError && e.code === "INSUFFICIENT_POINT") {
        setOrderError("포인트가 부족해요.");
      } else if (e instanceof ApiError && e.code === "OUT_OF_STOCK") {
        setOrderError("품절된 상품이에요.");
      } else {
        setOrderError(e instanceof ApiError ? e.message : String(e));
      }
    } finally {
      setBusy(false);
    }
  }

  if (error) {
    return (
      <div className="screen">
        <p className="error">{error}</p>
        <button onClick={() => navigate("/")}>홈으로</button>
      </div>
    );
  }
  if (!products) {
    return (
      <div className="screen">
        <p className="muted loading">불러오는 중이에요</p>
      </div>
    );
  }

  return (
    <div className="screen with-tab">
      <div>
      <h1>스토어</h1>

      <div className="card">
        <div className="row" style={{ justifyContent: "space-between" }}>
          <span>잔액</span>
          <b>{balance !== null ? `${balance.toLocaleString()}P` : "-"}</b>
        </div>
      </div>

      {placed && (
        <div className="notice">
          주문이 완료됐어요. {placed.productName} {placed.quantity}개 ·{" "}
          {placed.pointAmount.toLocaleString()}P 사용
          {placed.pointBalance !== null &&
            ` · 남은 포인트 ${placed.pointBalance.toLocaleString()}P`}
        </div>
      )}

      {draft && (
        <div className="card">
          <p>
            <b>{draft.product.name}</b>
          </p>
          {draft.product.description && <p className="muted">{draft.product.description}</p>}
          <div className="row">
            <span>수량</span>
            <input
              type="number"
              style={{ maxWidth: 80 }}
              min={1}
              max={draft.product.stock}
              value={draft.quantity}
              onChange={(e) =>
                setDraft({ ...draft, quantity: Math.max(1, Number(e.target.value) || 1) })
              }
            />
            <span>
              합계 <b>{(draft.product.pricePoint * draft.quantity).toLocaleString()}P</b>
            </span>
          </div>
          <div className="row" style={{ marginTop: 12 }}>
            <button className="primary" disabled={busy} onClick={placeOrder}>
              구매 확정
            </button>
            <button disabled={busy} onClick={() => setDraft(null)}>
              취소
            </button>
          </div>
        </div>
      )}
      {orderError && <p className="error">{orderError}</p>}

      <h2>상품</h2>
      {products.content.length === 0 ? (
        <p className="empty">지금은 판매 중인 상품이 없어요. 곧 새 상품이 채워질 예정이에요.</p>
      ) : (
        <table>
          <thead>
            <tr>
              <th>상품</th>
              <th>분류</th>
              <th>가격</th>
              <th>재고</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            {products.content.map((p) => (
              <tr key={p.productId}>
                <td>{p.name}</td>
                <td>{TYPE_LABEL[p.type]}</td>
                <td>{p.pricePoint.toLocaleString()}P</td>
                <td>{p.status === "SOLD_OUT" ? "품절" : p.stock}</td>
                <td>
                  <button
                    disabled={busy || p.status !== "ON_SALE"}
                    onClick={() => openDraft(p.productId)}
                  >
                    구매
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      <h2>구매 내역</h2>
      {!orders || orders.content.length === 0 ? (
        <p className="empty">아직 구매 내역이 없어요. 모은 포인트로 상품을 바꿔 보세요.</p>
      ) : (
        <table>
          <thead>
            <tr>
              <th>시각</th>
              <th>상품</th>
              <th>수량</th>
              <th>포인트</th>
              <th>상태</th>
            </tr>
          </thead>
          <tbody>
            {orders.content.map((o) => (
              <tr key={o.orderId}>
                <td>{o.orderedAt.slice(0, 19).replace("T", " ")}</td>
                <td>{o.productName}</td>
                <td>{o.quantity}</td>
                <td>-{o.pointAmount.toLocaleString()}P</td>
                <td>{o.status === "ORDERED" ? "주문됨" : "취소됨"}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      </div>
      <TabBar />
    </div>
  );
}
