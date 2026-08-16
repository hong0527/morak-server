import { useState } from "react";
import { useNavigate } from "react-router-dom";

import { api } from "../api/client";
import { ApiError } from "../api/http";
import { authStore } from "../auth/session";

/**
 * S1. 토큰 없이 부르는 유일한 일반 API.
 *
 * **아직 진짜 소셜 로그인이 아니다**(frontend-guide §6-1). 카카오·애플 키를 못 받아
 * dev 프로필의 DevSocialClient 가 authorizationCode 를 그대로 소셜 사용자 식별자로 쓴다.
 * 같은 문자열이면 같은 회원이라, 아래 입력칸에 아무 문자열이나 넣어 계정을 만들면 된다.
 *
 * 실제 SDK 를 붙일 때 바뀌는 것은 authorizationCode 에 들어가는 값뿐이고
 * 이 화면의 나머지 코드는 그대로 간다.
 */
export default function LoginScreen() {
  const navigate = useNavigate();
  const [code, setCode] = useState("tester-1");
  const [marketing, setMarketing] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      // 약관 동의가 별도 화면이 아니라 이 요청에 함께 실린다. 기존 회원의 재로그인은
      // 빈 배열이어도 통과하지만 필드 자체는 빼면 안 된다.
      const res = await api.auth.login({
        provider: "KAKAO",
        authorizationCode: code,
        agreements: [
          { type: "TOS", agreed: true },
          { type: "PRIVACY", agreed: true },
          { type: "MARKETING", agreed: marketing },
        ],
      });
      authStore.signIn(res.accessToken, res.memberId);

      if (res.loginResult === "RESTORED") {
        // 탈퇴 유예 중이던 계정이 로그인으로 되살아났다. 안내를 띄울 자리다.
        // TODO: "돌아온 것을 환영한다" 안내
      }
      // ageVerification enum 을 프론트가 해석하지 않아도 되게 서버가 계산해 준 값이다.
      navigate(res.needsBirthdate ? "/birthdate" : "/", { replace: true });
    } catch (e) {
      if (e instanceof ApiError) {
        // 거절 경로: INVALID_SOCIAL_TOKEN / REJOIN_BLOCKED / UNDER_AGE_SIGNUP_BLOCKED / UNAUTHORIZED
        setError(`${e.message} (${e.code})`);
      } else {
        setError(String(e));
      }
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="screen">
      <h1>MoLock 로그인</h1>
      <div className="notice">
        개발용 로그인이다. 아무 문자열이나 넣으면 그 문자열이 곧 계정이 된다. 같은 값을 다시
        넣으면 같은 계정으로 들어온다.
      </div>
      <form onSubmit={submit}>
        <label>
          <div className="muted">authorizationCode</div>
          <input value={code} onChange={(e) => setCode(e.target.value)} required />
        </label>
        <div className="row" style={{ margin: "12px 0" }}>
          <label className="row" style={{ gap: 6 }}>
            <input
              type="checkbox"
              style={{ width: "auto" }}
              checked={marketing}
              onChange={(e) => setMarketing(e.target.checked)}
            />
            <span className="muted">마케팅 수신 동의 (선택)</span>
          </label>
        </div>
        <p className="muted">이용약관·개인정보 처리방침은 필수라 자동으로 동의 처리된다.</p>
        <button className="primary" type="submit" disabled={busy}>
          {busy ? "로그인 중..." : "로그인"}
        </button>
      </form>
      {error && <p className="error">{error}</p>}
    </div>
  );
}
