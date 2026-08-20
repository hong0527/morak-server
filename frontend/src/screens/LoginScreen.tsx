import { useState } from "react";
import { useNavigate } from "react-router-dom";

import { api } from "../api/client";
import { ApiError } from "../api/http";
import { authStore } from "../auth/session";

/**
 * S1. 토큰 없이 부르는 유일한 일반 API.
 *
 * 마크업은 프로토타입 data-screen="login"(랜딩 브랜드 + SNS 버튼)을 옮긴 것이다.
 *
 * **아직 진짜 소셜 로그인이 아니다**(frontend-guide §6-1). 카카오·애플 키를 못 받아
 * dev 프로필의 DevSocialClient 가 authorizationCode 를 그대로 소셜 사용자 식별자로 쓴다.
 * 같은 문자열이면 같은 회원이라, 아래 입력칸에 아무 문자열이나 넣어 계정을 만들면 된다.
 * 그래서 소셜 버튼 4개 중 실제로 동작하는 카카오 버튼 하나만 두었다.
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
    <div className="screen login">
      <div className="brand login-brand">
        <h1>MoLock</h1>
        <span>함께 모여 몰입하는 실시간 캠 스터디</span>
      </div>
      <div className="wide-box">캠 스터디 6인 매칭</div>
      <form className="socials" onSubmit={submit}>
        <label className="muted">
          authorizationCode — 개발용 로그인이다. 아무 문자열이나 넣으면 그 문자열이 곧
          계정이 된다.
          <input value={code} onChange={(e) => setCode(e.target.value)} required />
        </label>
        <button className="kakao" type="submit" disabled={busy}>
          <span className="icon kakao-dot"></span>
          {busy ? "로그인 중..." : "Continue with Kakao"}
        </button>
        <label className="row" style={{ gap: 6, fontWeight: 500 }}>
          <input
            type="checkbox"
            style={{ width: "auto" }}
            checked={marketing}
            onChange={(e) => setMarketing(e.target.checked)}
          />
          <span className="muted">마케팅 수신 동의 (선택)</span>
        </label>
      </form>
      <p className="caption">
        SNS 계정으로 빠르게 가입하고 같은 시간을 고른 동료들과 함께 몰입하세요.
        이용약관·개인정보 처리방침은 필수라 자동으로 동의 처리됩니다.
      </p>
      {error && <p className="error" style={{ textAlign: "center" }}>{error}</p>}
    </div>
  );
}
