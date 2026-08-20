import { useEffect, useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";

import { api } from "../api/client";
import type { AppealDetail, AppealPage } from "../api/types";
import { AdminFrame, Empty, Info, Pager, formatDate } from "./AdminReportsScreen";

const STATUS: Record<string, string> = { PENDING: "검토 대기", ACCEPTED: "인용", REJECTED: "기각", CLOSED: "심사 종료" };

export default function AdminAppealsScreen() {
  const { appealId } = useParams();
  return appealId ? <AppealDetailView appealId={Number(appealId)} /> : <AppealList />;
}

function AppealList() {
  const [page, setPage] = useState(0);
  const [status, setStatus] = useState("PENDING");
  const [overdue, setOverdue] = useState(false);
  const [data, setData] = useState<AppealPage | null>(null);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => { setData(null); void api.admin.appeals({ page, size: 20, status: status || undefined, overdue: overdue || undefined }).then(setData).catch((e) => setError(String(e))); }, [page, status, overdue]);
  return <AdminFrame title="이의신청 관리">
    <div className="admin-heading"><div><p className="eyebrow">이의 큐</p><h1>퇴출 이의신청</h1></div><b>{data?.totalElements ?? "-"}건</b></div>
    <div className="admin-filters"><select value={status} onChange={(e) => { setStatus(e.target.value); setPage(0); }}><option value="">전체 상태</option><option value="PENDING">검토 대기</option><option value="ACCEPTED">인용</option><option value="REJECTED">기각</option><option value="CLOSED">심사 종료</option></select><label className="filter-check"><input type="checkbox" checked={overdue} onChange={(e) => { setOverdue(e.target.checked); setPage(0); }} /> 기한 초과만</label></div>
    {error && <p className="error">{error}</p>}
    <div className="admin-list">{data?.content.map((item) => <Link className="admin-row" key={item.appealId} to={`/admin/appeals/${item.appealId}`}><div>{item.overdue && <span className="admin-badge overdue">기한 초과</span>}<h3>{item.nickname}</h3><p>세션 #{item.sessionId} · 경고 {item.warningCount}회</p></div><div className="admin-row-meta"><b>{STATUS[item.status]}</b><small>{formatDate(item.createdAt)}</small><span>검토하기 →</span></div></Link>)}{data && !data.content.length && <Empty />}</div>
    <Pager page={page} total={data?.totalPages ?? 0} setPage={setPage} />
  </AdminFrame>;
}

function AppealDetailView({ appealId }: { appealId: number }) {
  const navigate = useNavigate();
  const [detail, setDetail] = useState<AppealDetail | null>(null);
  const [decision, setDecision] = useState<"ACCEPTED" | "REJECTED">("ACCEPTED");
  const [note, setNote] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => { void api.admin.appeal(appealId).then(setDetail).catch((e) => setError(String(e))); }, [appealId]);
  async function process() { setBusy(true); setError(null); try { await api.admin.processAppeal(appealId, { decision, note: note.trim() || undefined }); navigate("/admin/appeals", { replace: true }); } catch (e) { setError(String(e)); } finally { setBusy(false); } }
  return <AdminFrame title="이의 상세"><button className="back" onClick={() => navigate(-1)}>‹</button>{!detail ? <p className="muted">{error ?? "불러오는 중..."}</p> : <><div className="admin-heading"><div><p className="eyebrow">이의 #{detail.appealId}</p><h1>{detail.nickname ?? `회원 #${detail.memberId}`}</h1></div>{detail.overdue && <span className="admin-badge overdue">기한 초과</span>}</div><div className="admin-detail-grid"><section><h2>퇴출 정보</h2><Info label="세션" value={`#${detail.eviction.sessionId}`} /><Info label="퇴출 시각" value={formatDate(detail.eviction.evictedAt)} /><Info label="경고" value={`${detail.eviction.warningCount}회`} /><Info label="차감 포인트" value={`${detail.eviction.pointPenalty}P`} /></section><section><h2>신청자 진술</h2><div className="admin-note"><p>{detail.reasonText}</p><small>{formatDate(detail.createdAt)}</small></div></section><section className="admin-wide"><h2>경고 근거</h2><div className="admin-table-wrap"><table><thead><tr><th>#</th><th>근거</th><th>지속</th><th>전송 지연</th><th>동시 미검출</th></tr></thead><tbody>{detail.warnings.map((w) => <tr key={w.seq}><td>{w.seq}</td><td>{w.basis === "ABSENCE" ? "자리비움" : "화장실 초과"}</td><td>{w.absentSeconds == null ? "-" : `${w.absentSeconds}초`}</td><td>{w.reportSkewSeconds == null ? "-" : `${w.reportSkewSeconds}초`}</td><td>{w.concurrentReporterCount ?? "-"} / {detail.sessionParticipantCount}</td></tr>)}</tbody></table></div></section></div>{detail.status === "PENDING" && <section className="admin-action"><h2>심사 결정</h2><div className="admin-filters"><select value={decision} onChange={(e) => setDecision(e.target.value as typeof decision)}><option value="ACCEPTED">이의 인용</option><option value="REJECTED">이의 기각</option></select></div><textarea rows={4} maxLength={500} value={note} onChange={(e) => setNote(e.target.value)} placeholder="판단 근거를 기록하세요." />{error && <p className="error">{error}</p>}<button className="cta" disabled={busy} onClick={process}>심사 확정</button></section>}</>}</AdminFrame>;
}
