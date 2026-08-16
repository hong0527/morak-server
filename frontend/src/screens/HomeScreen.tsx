import { useEffect, useState } from "react";
import { Link, useNavigate } from "react-router-dom";

import { api } from "../api/client";
import { ApiError } from "../api/http";
import { authStore } from "../auth/session";
import type { MemberMe } from "../api/types";

/** S4. GET /api/members/me 하나로 화면 전체가 채워진다 */
export default function HomeScreen() {
  const navigate = useNavigate();
  const [me, setMe] = useState<MemberMe | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    void (async () => {
      try {
        const data = await api.member.me();
        if (cancelled) return;
        setMe(data);

        // 진행 중인 세션이 있으면 곧장 세션 화면으로 되돌려 보낸다.
        // 이 확인을 빠뜨리면 앱을 껐다 켠 사용자가 자기가 참여 중인 세션을 못 찾는다.
        if (data.activeSession) {
          navigate(`/sessions/${data.activeSession.sessionId}`, { replace: true });
        }
      } catch (e) {
        if (cancelled) return;
        // 탈퇴 유예 중이면 조회 API 까지 전부 막힌다(§2-7 ②). 남는 건 내 정보와 탈퇴 철회뿐이다.
        if (e instanceof ApiError && e.code === "WITHDRAWAL_PENDING") {
          setError("탈퇴 유예 중이다. 철회하면 다시 쓸 수 있다.");
        } else if (e instanceof ApiError && e.code === "AGE_NOT_VERIFIED") {
          navigate("/birthdate", { replace: true });
        } else {
          setError(String(e));
        }
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [navigate]);

  if (error) return <div className="screen"><p className="error">{error}</p></div>;
  if (!me) return <div className="screen"><p className="muted">불러오는 중...</p></div>;

  return (
    <div className="screen">
      <div className="row" style={{ justifyContent: "space-between" }}>
        <h1>{me.nickname}</h1>
        <button onClick={() => authStore.signOut()}>로그아웃</button>
      </div>

      <div className="card">
        <div className="row" style={{ justifyContent: "space-between" }}>
          <span>보유 포인트</span>
          <b>{me.pointBalance.toLocaleString()}P</b>
        </div>
        <div className="row" style={{ justifyContent: "space-between" }}>
          <span>연속 완주</span>
          <b>{me.streak?.current ?? 0}일</b>
        </div>
        <div className="row" style={{ justifyContent: "space-between" }}>
          <span>목표</span>
          <b>
            {me.goal
              ? `${me.goal.periodDays}일 중 ${me.goal.progressDays}일`
              : "설정 안 함"}
          </b>
        </div>
      </div>

      {me.sanction && (
        <div className="notice">
          제재 중이다. {me.sanction.endsAt ? `${me.sanction.endsAt} 까지` : "영구 제재다"}.
        </div>
      )}

      <h2>시작하기</h2>
      {/* 캠 동의를 매칭 전에 받는다. 안 받으면 매칭은 되는데 세션에 못 들어간다 */}
      {me.mediaConsented ? (
        <Link to="/match">
          <button className="primary">매칭 시작</button>
        </Link>
      ) : (
        <Link to="/media-consent">
          <button className="primary">캠 분석 동의하고 시작</button>
        </Link>
      )}

      <h2>그 밖에</h2>
      <div className="row">
        <Link to="/goal"><button>목표 설정</button></Link>
        <Link to="/records"><button>내 기록</button></Link>
        <Link to="/points"><button>포인트</button></Link>
        <Link to="/store"><button>스토어</button></Link>
      </div>
    </div>
  );
}
