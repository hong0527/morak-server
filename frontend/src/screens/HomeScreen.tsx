import { useEffect, useState } from "react";
import { Link, useNavigate } from "react-router-dom";

import { api } from "../api/client";
import { ApiError } from "../api/http";
import TabBar from "../components/TabBar";
import type { MemberMe } from "../api/types";

/**
 * S4. GET /api/members/me 하나로 화면 전체가 채워진다.
 * 마크업은 프로토타입 data-screen="home"(제목 + stats 카드 + pill + 하단 탭)을 옮겼다.
 * 로그아웃은 프로토타입처럼 내 정보 화면으로 옮겼다.
 */
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
          setError("탈퇴 유예 기간이에요. 철회하시면 다시 이용할 수 있어요.");
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
  if (!me) return <div className="screen"><p className="muted loading">불러오는 중이에요</p></div>;

  return (
    <div className="screen with-tab">
      <div>
        <h1>{me.nickname}님,<br />오늘도 몰입해 볼까요?</h1>

        <div className="card">
          <h3>내 현황</h3>
          <div className="stats three">
            <span>
              닉네임
              <br />
              <b>{me.nickname}</b>
            </span>
            <span>
              보유 포인트
              <br />
              <b>{me.pointBalance.toLocaleString()}P</b>
            </span>
            <span>
              연속 완주
              <br />
              <b>{me.streak?.current ?? 0}일</b>
            </span>
          </div>
          {me.goal && (
            <div className="pill">
              목표 {me.goal.periodDays}일 중 {me.goal.progressDays}일째
            </div>
          )}
          {(me.streak?.current ?? 0) > 0 && (
            <div className="pill">연속 {me.streak.current}일 완주 중</div>
          )}
        </div>

        {me.sanction && (
          <div className="notice">
            {me.sanction.endsAt
              ? `이용이 제한된 상태예요. ${me.sanction.endsAt}까지 제한이 적용돼요.`
              : "계정 이용이 영구 제한된 상태예요."}
          </div>
        )}

        <div className="card">
          <h3>시작하기</h3>
          <p className="muted" style={{ fontSize: 12 }}>
            공부할 시간을 고르면 같은 시간을 고른 6명이 한 팀이 돼요.
          </p>
          {/* 캠 동의를 매칭 전에 받는다. 안 받으면 매칭은 되는데 세션에 못 들어간다 */}
          {me.mediaConsented ? (
            <Link to="/match">
              <button className="cta">매칭 시작</button>
            </Link>
          ) : (
            <Link to="/media-consent">
              <button className="cta">캠 분석 동의하고 시작</button>
            </Link>
          )}
        </div>

        <h3>바로가기</h3>
        <div className="chips">
          <Link to="/goal"><button>목표 설정</button></Link>
          <Link to="/records"><button>내 기록</button></Link>
        </div>
      </div>
      <TabBar />
    </div>
  );
}
