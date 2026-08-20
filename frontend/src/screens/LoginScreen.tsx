import { useState } from "react";
import { useNavigate } from "react-router-dom";

import { api } from "../api/client";
import { ApiError } from "../api/http";
import { kakaoConfigured, startKakaoLogin } from "../auth/kakao";
import { authStore } from "../auth/session";

/**
 * S1. 토큰 없이 부르는 유일한 일반 API.
 *
 * 마크업은 프로토타입 data-screen="login"(랜딩 브랜드 + SNS 버튼)을 옮긴 것이다.
 *
 * 로그인 경로가 둘이다.
 * - 카카오: 인가 페이지로 리다이렉트한다. 코드 처리는 돌아오는 곳인
 *   /login/kakao(KakaoCallbackScreen)가 한다. `VITE_KAKAO_REST_KEY` 가 빌드에 없으면
 *   리다이렉트할 수 없으므로 버튼은 그대로 두고 눌렀을 때 코드 로그인으로 안내한다.
 * - 심사·개발용 코드: provider DEV 로 보낸다. dev·demo 프로필의 DevSocialClient 가
 *   코드를 그대로 소셜 식별자로 써서, 같은 문자열이면 같은 회원이다. 카카오 심사
 *   계정이 이 문으로 들어오므로 실연동 뒤에도 지우면 안 된다.
 */
export default function LoginScreen() {
  const navigate = useNavigate();
  const [code, setCode] = useState("tester-1");
  const [marketing, setMarketing] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  function kakaoLogin() {
    if (!kakaoConfigured) {
      // 버튼을 숨기면 "카카오가 원래 없는 앱"처럼 보인다. 미설정임을 말해 준다.
      setNotice(
        "카카오 로그인 키(VITE_KAKAO_REST_KEY)가 설정되지 않은 빌드예요. 아래 심사·개발용 코드로 로그인해 주세요.",
      );
      return;
    }
    startKakaoLogin(marketing);
  }

  async function submitDevCode(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      // 약관 동의가 별도 화면이 아니라 이 요청에 함께 실린다. 기존 회원의 재로그인은
      // 빈 배열이어도 통과하지만 필드 자체는 빼면 안 된다.
      const res = await api.auth.login({
        provider: "DEV",
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
        <h1>모락</h1>
        <span>함께 시작하는 목표 달성</span>
      </div>
      <div className="wide-box">캠 스터디 6인 매칭</div>
      <form className="socials" onSubmit={submitDevCode}>
        <button className="kakao" type="button" onClick={kakaoLogin} disabled={busy}>
          <span className="icon kakao-icon" aria-hidden="true"></span>
          <span>카카오로 계속하기</span>
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
        {notice && <p className="muted">{notice}</p>}
        <details className="demo-account">
          <summary>심사·개발용 코드 로그인</summary>
          <label>
            체험 계정 ID — 같은 코드는 같은 계정이에요
            <input
              value={code}
              onChange={(e) => setCode(e.target.value)}
              placeholder="예) tester-1"
              autoComplete="username"
              required
            />
          </label>
          <button type="submit" disabled={busy}>
            {busy ? "로그인 중..." : "코드로 로그인"}
          </button>
        </details>
      </form>
      <p className="caption">
        SNS 계정으로 빠르게 가입하고 같은 시간을 고른 동료들과 함께 몰입하세요.
        계속하면 이용약관과 개인정보 처리방침에 동의하게 됩니다.
      </p>
      {error && <p className="error" style={{ textAlign: "center" }}>{error}</p>}
    </div>
  );
}
