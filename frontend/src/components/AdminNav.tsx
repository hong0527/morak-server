import { NavLink } from "react-router-dom";

const ITEMS = [
  ["/admin", "요약"],
  ["/admin/reports", "신고"],
  ["/admin/appeals", "이의"],
  ["/admin/sessions", "세션"],
  ["/admin/withdrawals", "탈퇴"],
] as const;

export default function AdminNav() {
  return (
    <nav className="admin-nav" aria-label="운영자 메뉴">
      {ITEMS.map(([to, label]) => (
        <NavLink key={to} to={to} end={to === "/admin"}>{label}</NavLink>
      ))}
    </nav>
  );
}
