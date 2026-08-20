import { useEffect, useState } from "react";

import { api } from "../api/client";
import type { AdminSessionPage, SessionStatus } from "../api/types";
import { AdminFrame, Empty, Pager, formatDate } from "./AdminReportsScreen";

export default function AdminSessionsScreen() {
  const [page, setPage] = useState(0);
  const [status, setStatus] = useState<SessionStatus | "">("LIVE");
  const [data, setData] = useState<AdminSessionPage | null>(null);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => { setData(null); void api.admin.sessions({ page, size: 20, status: status || undefined }).then(setData).catch((e) => setError(String(e))); }, [page, status]);
  return <AdminFrame title="세션 모니터링"><div className="admin-heading"><div><p className="eyebrow">실시간 운영</p><h1>세션 현황</h1></div><b>{data?.totalElements ?? "-"}개</b></div><div className="admin-filters"><select value={status} onChange={(e) => { setStatus(e.target.value as typeof status); setPage(0); }}><option value="">전체 상태</option><option value="LIVE">진행 중</option><option value="ENDED">종료</option><option value="CANCELLED">취소</option></select></div>{error && <p className="error">{error}</p>}<div className="session-admin-list">{data?.content.map((session) => <article className="admin-session" key={session.sessionId}><header><div><span className={`admin-badge ${session.status === "LIVE" ? "live" : ""}`}>{session.status === "LIVE" ? "진행 중" : session.status === "ENDED" ? "종료" : "취소"}</span><h3>세션 #{session.sessionId}</h3><p>{session.targetMinutes / 60}시간 · {formatDate(session.startedAt)} ~ {formatDate(session.endsAt)}</p></div><div className="session-counts"><b>{session.activeCount + session.pausedCount}명 참여</b><small>활동 {session.activeCount} · 화장실 {session.pausedCount} · 퇴장 {session.leftCount} · 퇴출 {session.evictedCount}</small></div></header><div className="participant-grid">{session.participants.map((p) => <div key={p.memberId}><span className={`participant-state ${p.status.toLowerCase()}`}></span><b>{p.nickname}</b><small>{p.paused ? "화장실" : p.status} · 경고 {p.warningCount}</small></div>)}</div></article>)}{data && !data.content.length && <Empty />}</div><Pager page={page} total={data?.totalPages ?? 0} setPage={setPage} /></AdminFrame>;
}
