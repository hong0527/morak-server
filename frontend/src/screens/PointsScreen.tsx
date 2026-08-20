import { useCallback, useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";

import { api } from "../api/client";
import { ApiError } from "../api/http";
import TabBar from "../components/TabBar";
import type { ChargeCreateResponse, PointBalance } from "../api/types";

const PAGE_SIZE = 20;

/**
 * S11. 포인트. PT-1 이 잔액과 원장을 함께 준다 — 목록이 ledger 키 안에 들어가는
 * 유일한 응답이다. reasonLabel 이 한국어로 함께 오므로 enum 을 번역하지 않는다.
 *
 * 충전은 PY-1 → PY-2 두 단계인데 PG 테스트 키가 없어 결제창이 없다(frontend-guide §6-2).
 * DevPgClient 가 pgTid 접두사(fail- / mismatch-)로 실패 경로를 가르므로,
 * pgTid 입력을 화면에 그대로 두었다 — 실패 시연이 이것으로만 가능하다.
 */
export default function PointsScreen() {
  const navigate = useNavigate();
  const [data, setData] = useState<PointBalance | null>(null);
  const [page, setPage] = useState(0);
  const [error, setError] = useState<string | null>(null);

  const [amountKrw, setAmountKrw] = useState("10000");
  const [charge, setCharge] = useState<ChargeCreateResponse | null>(null);
  const [pgTid, setPgTid] = useState("");
  const [chargeError, setChargeError] = useState<string | null>(null);
  const [chargeDone, setChargeDone] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async (p: number) => {
    try {
      setData(await api.member.points({ page: p, size: PAGE_SIZE }));
      setError(null);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : String(e));
    }
  }, []);

  useEffect(() => {
    void load(page);
  }, [load, page]);

  async function createCharge() {
    setBusy(true);
    setChargeError(null);
    setChargeDone(null);
    try {
      const created = await api.payment.createCharge({ amountKrw: Number(amountKrw) });
      setCharge(created);
      // 결제창이 없으므로 승인에 쓸 거래번호를 여기서 만들어 둔다.
      // 실패를 재현하려면 이 값을 fail- 또는 mismatch- 로 시작하게 고치면 된다.
      setPgTid(`demo-${created.chargeId}-${Date.now()}`);
    } catch (e) {
      if (e instanceof ApiError && e.code === "VALIDATION_FAILED" && e.details?.amountKrw) {
        setChargeError("충전 금액은 1,000원부터 1,000,000원까지 입력할 수 있어요.");
      } else {
        setChargeError(e instanceof ApiError ? e.message : String(e));
      }
    } finally {
      setBusy(false);
    }
  }

  async function confirmCharge() {
    if (!charge) return;
    setBusy(true);
    setChargeError(null);
    try {
      const confirmed = await api.payment.confirmCharge(charge.chargeId, {
        pgOrderId: charge.pgOrderId,
        pgTid: pgTid.trim(),
        amountKrw: charge.amountKrw,
      });
      setChargeDone(
        `${confirmed.pointAmount.toLocaleString()}P가 적립됐어요. 현재 잔액은 ${confirmed.pointBalance.toLocaleString()}P예요.`,
      );
      setCharge(null);
      setPage(0);
      void load(0);
    } catch (e) {
      // 승인 실패여도 충전 건은 READY 로 남는다. pgTid 를 고쳐 다시 확인할 수 있게
      // charge 를 지우지 않는다.
      setChargeError(e instanceof ApiError ? e.message : String(e));
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
  if (!data) {
    return (
      <div className="screen">
        <p className="muted loading">불러오는 중이에요</p>
      </div>
    );
  }

  const ledger = data.ledger;

  return (
    <div className="screen with-tab">
      <div>
      <h1>포인트</h1>

      <div className="card">
        <div className="row" style={{ justifyContent: "space-between" }}>
          <span>잔액</span>
          <b>{data.balance.toLocaleString()}P</b>
        </div>
      </div>

      <h2>충전</h2>
      {!charge ? (
        <div className="row">
          <input
            type="number"
            style={{ maxWidth: 160 }}
            min={1000}
            max={1000000}
            step={1000}
            value={amountKrw}
            onChange={(e) => setAmountKrw(e.target.value)}
          />
          <span className="muted">원 (1,000 ~ 1,000,000)</span>
          <button className="primary" disabled={busy || !amountKrw} onClick={createCharge}>
            충전 요청
          </button>
        </div>
      ) : (
        <div className="card">
          <p>
            <b>{charge.amountKrw.toLocaleString()}원</b>을 결제하면{" "}
            <b>{charge.pointAmount.toLocaleString()}P</b>가 적립돼요.
          </p>
          <p className="muted">주문번호 {charge.pgOrderId}</p>
          <p className="muted">
            지금은 테스트 결제 환경이에요. 아래 거래번호로 결제 승인을 진행해 주세요.
          </p>
          <input value={pgTid} maxLength={64} onChange={(e) => setPgTid(e.target.value)} />
          <div className="row" style={{ marginTop: 12 }}>
            <button className="primary" disabled={busy || !pgTid.trim()} onClick={confirmCharge}>
              승인 확인
            </button>
            <button disabled={busy} onClick={() => setCharge(null)}>
              취소
            </button>
          </div>
        </div>
      )}
      {chargeDone && <p className="notice">{chargeDone}</p>}
      {chargeError && <p className="error">{chargeError}</p>}

      <h2>내역</h2>
      {ledger.content.length === 0 ? (
        <p className="empty">아직 포인트 내역이 없어요. 세션을 완주하면 포인트가 쌓여요.</p>
      ) : (
        <table>
          <thead>
            <tr>
              <th>시각</th>
              <th>내용</th>
              <th>변동</th>
              <th>잔액</th>
            </tr>
          </thead>
          <tbody>
            {ledger.content.map((item) => (
              <tr key={item.ledgerId}>
                <td>{item.createdAt.slice(0, 19).replace("T", " ")}</td>
                <td>{item.reasonLabel}</td>
                <td>
                  <b>
                    {item.delta >= 0 ? "+" : ""}
                    {item.delta.toLocaleString()}P
                  </b>
                </td>
                <td>{item.balanceAfter.toLocaleString()}P</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      {ledger.totalPages > 1 && (
        <div className="row" style={{ marginTop: 12 }}>
          <button disabled={page === 0} onClick={() => setPage(page - 1)}>
            이전
          </button>
          <span className="muted">
            {page + 1} / {ledger.totalPages}
          </span>
          <button disabled={page + 1 >= ledger.totalPages} onClick={() => setPage(page + 1)}>
            다음
          </button>
        </div>
      )}
      </div>
      <TabBar />
    </div>
  );
}
