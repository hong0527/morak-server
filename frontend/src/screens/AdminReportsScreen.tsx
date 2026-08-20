import { useEffect, useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";

import { api } from "../api/client";
import type { ReportCaseDetail, ReportCasePage } from "../api/types";
import AdminNav from "../components/AdminNav";
import { AdminHeader } from "./AdminDashboardScreen";

const REASONS: Record<string, string> = { SEXUAL_CONTENT: "성적 콘텐츠", VIOLENT_THREAT: "폭력·위협", AD_SPAM: "광고·스팸", INAPPROPRIATE_SCREEN: "부적절한 화면", ETC: "기타" };
const STATUS: Record<string, string> = { PENDING: "처리 대기", RESOLVED: "조치 없이 종결", REJECTED: "기각", SANCTIONED: "제재 완료" };

export default function AdminReportsScreen() {
  const { caseId } = useParams();
  return caseId ? <ReportDetail caseId={Number(caseId)} /> : <ReportList />;
}

function ReportList() {
  const [page, setPage] = useState(0);
  const [status, setStatus] = useState("PENDING");
  const [severity, setSeverity] = useState("");
  const [overdue, setOverdue] = useState(false);
  const [q, setQ] = useState("");
  const [data, setData] = useState<ReportCasePage | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    setData(null); setError(null);
    void api.admin.reports({ page, size: 20, status: status || undefined, severity: severity || undefined, overdue: overdue || undefined, q: q.trim() || undefined })
      .then(setData).catch((e) => setError(String(e)));
  }, [page, status, severity, overdue, q]);

  return <AdminFrame title="신고 관리">
    <div className="admin-heading"><div><p className="eyebrow">신고 큐</p><h1>접수된 신고</h1></div><b>{data?.totalElements ?? "-"}건</b></div>
    <div className="admin-filters">
      <input aria-label="신고 대상 검색" placeholder="대상 닉네임 검색" value={q} onChange={(e) => { setQ(e.target.value); setPage(0); }} />
      <select aria-label="처리 상태" value={status} onChange={(e) => { setStatus(e.target.value); setPage(0); }}><option value="">전체 상태</option><option value="PENDING">처리 대기</option><option value="RESOLVED">종결</option><option value="REJECTED">기각</option><option value="SANCTIONED">제재 완료</option></select>
      <select aria-label="위험도" value={severity} onChange={(e) => { setSeverity(e.target.value); setPage(0); }}><option value="">전체 위험도</option><option value="HIGH">고위험</option><option value="NORMAL">일반</option></select>
      <label className="filter-check"><input type="checkbox" checked={overdue} onChange={(e) => { setOverdue(e.target.checked); setPage(0); }} /> 기한 초과만</label>
    </div>
    {error && <p className="error">{error}</p>}
    <div className="admin-list">
      {data?.content.map((item) => <Link className="admin-row" key={item.caseId} to={`/admin/reports/${item.caseId}`}>
        <div><span className={`admin-badge ${item.severity === "HIGH" ? "danger" : ""}`}>{item.severity === "HIGH" ? "고위험" : "일반"}</span>{item.overdue && <span className="admin-badge overdue">기한 초과</span>}<h3>{item.targetNickname ?? `신고 #${item.caseId}`}</h3><p>{REASONS[item.reasonCode ?? ""] ?? "사유 미상"} · 신고 {item.reportCount}건</p></div>
        <div className="admin-row-meta"><b>{STATUS[item.status]}</b><small>{formatDate(item.receivedAt)}</small><span>상세 보기 →</span></div>
      </Link>)}
      {data && data.content.length === 0 && <Empty />}
    </div>
    <Pager page={page} total={data?.totalPages ?? 0} setPage={setPage} />
  </AdminFrame>;
}

function ReportDetail({ caseId }: { caseId: number }) {
  const navigate = useNavigate();
  const [detail, setDetail] = useState<ReportCaseDetail | null>(null);
  const [decision, setDecision] = useState<"RESOLVED" | "REJECTED" | "SANCTIONED">("RESOLVED");
  const [note, setNote] = useState("");
  const [sanctionType, setSanctionType] = useState<"TEMP" | "PERMANENT">("TEMP");
  const [days, setDays] = useState(7);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => { void api.admin.report(caseId).then(setDetail).catch((e) => setError(String(e))); }, [caseId]);

  async function process() {
    setBusy(true); setError(null);
    try {
      await api.admin.processReport(caseId, { status: decision, reviewNote: note.trim() || undefined, sanction: decision === "SANCTIONED" ? { type: sanctionType, days: sanctionType === "TEMP" ? days : undefined } : undefined });
      navigate("/admin/reports", { replace: true });
    } catch (e) { setError(String(e)); } finally { setBusy(false); }
  }

  return <AdminFrame title="신고 상세">
    <button className="back" onClick={() => navigate(-1)}>‹</button>
    {!detail ? <p className="muted">{error ?? "불러오는 중..."}</p> : <>
      <div className="admin-heading"><div><p className="eyebrow">사건 #{detail.caseId}</p><h1>{detail.target.nickname ?? `세션 ${detail.target.sessionId}`}</h1></div><span className={`admin-badge ${detail.severity === "HIGH" ? "danger" : ""}`}>{detail.severity === "HIGH" ? "고위험" : "일반"}</span></div>
      <div className="admin-detail-grid">
        <section><h2>사건 요약</h2><Info label="현재 상태" value={STATUS[detail.status]} /><Info label="접수 시각" value={formatDate(detail.receivedAt)} /><Info label="처리 기한" value={formatDate(detail.slaDueAt)} /><Info label="관련 세션" value={detail.target.sessionId ? `#${detail.target.sessionId}` : "-"} /></section>
        <section><h2>신고 내용</h2>{detail.reporters.map((r, i) => <div className="admin-note" key={i}><b>{REASONS[r.reasonCode]}</b><p>{r.detail || "상세 내용 없음"}</p><small>{formatDate(r.receivedAt)}</small></div>)}</section>
        <section><h2>경고 기록</h2>{detail.targetWarnings.length ? detail.targetWarnings.map((w) => <p key={`${w.sessionId}-${w.seq}`}>세션 #{w.sessionId} · {w.seq}회차 · {formatDate(w.createdAt)}</p>) : <p className="muted">경고 기록 없음</p>}</section>
      </div>
      {detail.status === "PENDING" && <section className="admin-action"><h2>처리 결정</h2><div className="admin-filters"><select value={decision} onChange={(e) => setDecision(e.target.value as typeof decision)}><option value="RESOLVED">조치 없이 종결</option><option value="REJECTED">신고 기각</option><option value="SANCTIONED">제재 확정</option></select>{decision === "SANCTIONED" && <><select value={sanctionType} onChange={(e) => setSanctionType(e.target.value as typeof sanctionType)}><option value="TEMP">기간 제재</option><option value="PERMANENT">영구 제재</option></select>{sanctionType === "TEMP" && <input type="number" min="1" max="3650" value={days} onChange={(e) => setDays(Number(e.target.value))} aria-label="제재 일수" />}</>}</div><textarea rows={4} maxLength={500} value={note} onChange={(e) => setNote(e.target.value)} placeholder="검토 근거와 처리 메모" />{error && <p className="error">{error}</p>}<button className="cta" disabled={busy} onClick={process}>처리 확정</button></section>}
    </>}
  </AdminFrame>;
}

export function AdminFrame({ title, children }: { title: string; children: React.ReactNode }) { return <div className="screen admin-shell"><AdminHeader title={title} /><AdminNav /><main className="admin-content">{children}</main></div>; }
export function formatDate(value: string) { return new Intl.DateTimeFormat("ko-KR", { dateStyle: "short", timeStyle: "short" }).format(new Date(value)); }
export function Info({ label, value }: { label: string; value: React.ReactNode }) { return <p className="admin-info"><span>{label}</span><b>{value}</b></p>; }
export function Empty() { return <div className="admin-empty"><b>표시할 항목이 없습니다.</b><p>현재 조건에 해당하는 운영 항목이 없습니다.</p></div>; }
export function Pager({ page, total, setPage }: { page: number; total: number; setPage: (page: number) => void }) { return <div className="admin-pager"><button disabled={page === 0} onClick={() => setPage(page - 1)}>이전</button><span>{total ? `${page + 1} / ${total}` : "0 / 0"}</span><button disabled={page + 1 >= total} onClick={() => setPage(page + 1)}>다음</button></div>; }
