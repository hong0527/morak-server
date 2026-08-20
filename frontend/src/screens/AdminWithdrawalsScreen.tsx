import { useEffect, useState } from "react";

import { api } from "../api/client";
import type { WithdrawalPage } from "../api/types";
import { AdminFrame, Empty, Pager, formatDate } from "./AdminReportsScreen";

export default function AdminWithdrawalsScreen() {
  const [page, setPage] = useState(0);
  const [status, setStatus] = useState<"WITHDRAW_PENDING" | "DELETED" | "">("");
  const [data, setData] = useState<WithdrawalPage | null>(null);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => { setData(null); void api.admin.withdrawals({ page, size: 20, status: status || undefined }).then(setData).catch((e) => setError(e instanceof Error ? e.message : String(e))); }, [page, status]);
  return <AdminFrame title="탈퇴 현황"><div className="admin-heading"><div><p className="eyebrow">개인정보 파기</p><h1>탈퇴 계정 현황</h1></div><b>{data?.totalElements ?? "-"}명</b></div><div className="admin-filters"><select value={status} onChange={(e) => { setStatus(e.target.value as typeof status); setPage(0); }}><option value="">전체 상태</option><option value="WITHDRAW_PENDING">30일 유예 중</option><option value="DELETED">파기 완료</option></select></div><div className="admin-guide"><h2>처리 안내</h2><p>탈퇴 요청 후 30일 동안 계정은 유예 상태이며, 기간이 지나면 배치가 개인정보를 자동 파기합니다.</p></div>{error && <p className="error">{error}</p>}<div className="admin-list">{data?.content.map((item) => <div className="admin-row static" key={item.memberId}><div><span className={`admin-badge ${item.status === "WITHDRAW_PENDING" ? "pending" : ""}`}>{item.status === "WITHDRAW_PENDING" ? "유예 중" : "파기 완료"}</span><h3>회원 #{item.memberId}</h3><p>요청 {formatDate(item.requestedAt)}</p></div><div className="admin-row-meta"><b>{item.deletedAt ? `파기 ${formatDate(item.deletedAt)}` : `예정 ${formatDate(item.deleteScheduledAt)}`}</b></div></div>)}{data && !data.content.length && <Empty />}</div><Pager page={page} total={data?.totalPages ?? 0} setPage={setPage} /></AdminFrame>;
}
