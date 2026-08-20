import { useEffect, useState } from "react";
import { Link, useNavigate } from "react-router-dom";

import { api } from "../api/client";
import { ApiError } from "../api/http";
import { authStore } from "../auth/session";
import TabBar from "../components/TabBar";
import type { MemberMe } from "../api/types";

/**
 * 내 정보. 마크업은 프로토타입 data-screen="profile" 을 옮기되, 서버에 없는 것
 * (닉네임 수정·자기소개·알림 설정·매너점수)은 자리째 뺐다. 데이터는 AU-2(me) 하나다.
 *
 * 닉네임은 서버가 만든 표시용 값이라 수정 입력이 아니라 표시만 한다(스키마 주석).
 */
export default function ProfileScreen() {
  const navigate = useNavigate();
  const [me, setMe] = useState<MemberMe | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [withdrawalPending, setWithdrawalPending] = useState(false);

  useEffect(() => {
    let cancelled = false;
    void (async () => {
      try {
        const data = await api.member.me();
        if (cancelled) return;
        setMe(data);
      } catch (e) {
        if (cancelled) return;
        // 탈퇴 유예 중엔 조회가 막힐 수 있다. 남는 경로는 철회뿐이라 그리로 안내한다.
        if (e instanceof ApiError && e.code === "WITHDRAWAL_PENDING") {
          setWithdrawalPending(true);
        } else {
          setError(e instanceof ApiError ? e.message : String(e));
        }
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  if (withdrawalPending) {
    return (
      <div className="screen">
        <h1>내 정보</h1>
        <div className="notice">탈퇴 유예 기간이에요. 철회하시면 다시 이용할 수 있어요.</div>
        <Link to="/leave">
          <button className="cta">탈퇴 철회하러 가기</button>
        </Link>
        <button className="ghost" onClick={() => authStore.signOut()}>
          로그아웃
        </button>
      </div>
    );
  }

  if (error) return <div className="screen"><p className="error">{error}</p></div>;
  if (!me) return <div className="screen"><p className="muted loading">불러오는 중이에요</p></div>;

  return (
    <div className="screen with-tab">
      <div>
        <h1>내 정보</h1>

        <div className="card settings">
          <h3>프로필 정보</h3>
          <p>
            닉네임 <b>{me.nickname}</b>
          </p>
          <p>
            보유 포인트 <b>{me.pointBalance.toLocaleString()}P</b>
          </p>
          <p>
            연속 완주 <b>{me.streak?.current ?? 0}일</b>
          </p>
          <p>
            연령 확인 <b>{me.ageVerification === "VERIFIED" ? "확인됨" : "미확인"}</b>
          </p>
          <p>
            캠 분석 동의 <b>{me.mediaConsented ? "동의함" : "동의 안 함"}</b>
          </p>
        </div>

        {me.badges.length > 0 && (
          <div className="card">
            <h3>보유 뱃지</h3>
            {me.badges.map((b) => (
              <span className="pill" key={b.code}>
                {b.code}
              </span>
            ))}
          </div>
        )}

        {me.role === "ADMIN" && (
          <button className="admin-entry" onClick={() => navigate("/admin")}>
            운영자 페이지
            <span>신고·이의·세션 관리 →</span>
          </button>
        )}

        <button className="ghost" onClick={() => navigate("/records")}>
          내 기록 보기
        </button>
        <button className="ghost" onClick={() => authStore.signOut()}>
          로그아웃
        </button>
        <button className="link" onClick={() => navigate("/leave")}>
          회원탈퇴
        </button>
      </div>
      <TabBar />
    </div>
  );
}
