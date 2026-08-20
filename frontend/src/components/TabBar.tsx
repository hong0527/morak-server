import { NavLink } from "react-router-dom";

/**
 * 프로토타입 .tab 마크업의 이식. 프로토타입 탭은 홈·매칭·그룹·리포트·내 정보인데
 * 그룹·리포트는 구 기획이라, 실제 라우트가 있는 홈·매칭·포인트·스토어·내 정보로 채웠다.
 * 아이콘은 프로토타입 app.js 의 것을 그대로 쓰고(홈·매칭·내 정보) 나머지 둘만 같은
 * 획 스타일로 그렸다.
 */
const TABS: [to: string, label: string, icon: React.ReactNode][] = [
  [
    "/",
    "홈",
    <svg viewBox="0 0 24 24"><path d="M3 10.5 12 3l9 7.5v9.5h-6v-6H9v6H3z" /></svg>,
  ],
  [
    "/match",
    "매칭",
    <svg viewBox="0 0 24 24"><circle cx="8" cy="8" r="3" /><circle cx="16" cy="9" r="2.5" /><circle cx="12" cy="16" r="3" /><path d="M9.5 10.5 11 13M14.2 10.8 13 13" /></svg>,
  ],
  [
    "/points",
    "포인트",
    <svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="8" fill="none" /><path d="M9.5 16V8h3.2a2.6 2.6 0 0 1 0 5.2H9.5" fill="none" /></svg>,
  ],
  [
    "/store",
    "스토어",
    <svg viewBox="0 0 24 24"><path d="M5 9h14l-1 11H6z" fill="none" /><path d="M9 11V7a3 3 0 0 1 6 0v4" fill="none" /></svg>,
  ],
  [
    "/profile",
    "내 정보",
    <svg viewBox="0 0 24 24"><circle cx="12" cy="8" r="3" /><path d="M5 20c.7-4 3-6 7-6s6.3 2 7 6z" /></svg>,
  ],
];

export default function TabBar() {
  return (
    <nav className="tab">
      {TABS.map(([to, label, icon]) => (
        <NavLink key={to} to={to} end={to === "/"} aria-label={label}>
          {icon}
          <span>{label}</span>
        </NavLink>
      ))}
    </nav>
  );
}
