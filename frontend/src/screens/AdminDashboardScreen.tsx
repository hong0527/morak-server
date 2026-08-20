import { useEffect, useState } from "react";
import { Link } from "react-router-dom";

import { api } from "../api/client";
import AdminNav from "../components/AdminNav";

type Counts = { reports: number; appeals: number; sessions: number; withdrawals: number };

export default function AdminDashboardScreen() {
  const [counts, setCounts] = useState<Counts | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    void Promise.all([
      api.admin.reports({ status: "PENDING", page: 0, size: 1 }),
      api.admin.appeals({ status: "PENDING", page: 0, size: 1 }),
      api.admin.sessions({ status: "LIVE", page: 0, size: 1 }),
      api.admin.withdrawals({ status: "WITHDRAW_PENDING", page: 0, size: 1 }),
    ]).then(([reports, appeals, sessions, withdrawals]) => setCounts({
      reports: reports.totalElements,
      appeals: appeals.totalElements,
      sessions: sessions.totalElements,
      withdrawals: withdrawals.totalElements,
    })).catch((e) => setError(e instanceof Error ? e.message : String(e)));
  }, []);

  return (
    <div className="screen admin-shell">
      <AdminHeader title="운영 관리" />
      <AdminNav />
      <section className="admin-content">
        <div className="admin-heading">
          <div><p className="eyebrow">오늘의 운영 현황</p><h1>빠른 검토가 필요한 항목</h1></div>
          <span className="status-dot">실시간</span>
        </div>
        {error && <p className="error">현황을 불러오지 못했습니다. {error}</p>}
        <div className="admin-kpis">
          <Kpi to="/admin/reports" label="미처리 신고" value={counts?.reports} urgent />
          <Kpi to="/admin/appeals" label="대기 이의" value={counts?.appeals} />
          <Kpi to="/admin/sessions" label="진행 세션" value={counts?.sessions} />
          <Kpi to="/admin/withdrawals" label="탈퇴 유예" value={counts?.withdrawals} />
        </div>
        <div className="admin-guide">
          <h2>운영 원칙</h2>
          <p>고위험 신고는 24시간, 일반 신고와 이의신청은 72시간 안에 처리합니다.</p>
          <p>영상은 저장되지 않으므로 신고 사유, 경고 기록, 세션 이력을 함께 확인합니다.</p>
        </div>
      </section>
    </div>
  );
}

export function AdminHeader({ title }: { title: string }) {
  return <header className="admin-topbar"><Link to="/">모락</Link><b>{title}</b><Link to="/profile">내 정보</Link></header>;
}

function Kpi({ to, label, value, urgent = false }: { to: string; label: string; value?: number; urgent?: boolean }) {
  return <Link className={`admin-kpi${urgent ? " urgent" : ""}`} to={to}><span>{label}</span><b>{value ?? "-"}</b><small>목록 보기 →</small></Link>;
}
