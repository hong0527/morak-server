import { useEffect, useRef, useState } from "react";
import { Link, useNavigate, useSearchParams } from "react-router-dom";

import { api } from "../api/client";
import { ApiError } from "../api/http";
import { takeMarketingChoice } from "../auth/kakao";
import { authStore } from "../auth/session";

/**
 * 카카오 인가 페이지가 돌아오는 곳. `?code=` 를 AU-1 에 provider KAKAO 로 넘기고,
 * 성공하면 LoginScreen 과 같은 후처리(토큰 저장 → 생년월일 분기)로 합류한다.
 *
 * 화면이 하는 일은 그것뿐이라 상태는 "처리 중"과 "실패 사유" 둘이다. 실패는 여기서
 * 끝난 것이라 로그인 화면으로 돌아가는 길만 내준다 — 인가 코드는 1회용이어서 이
 * 주소를 새로고침해도 다시 성공할 수 없다.
 */
export default function KakaoCallbackScreen() {
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const [error, setError] = useState<string | null>(null);
  // StrictMode 가 이펙트를 두 번 돌린다. 인가 코드는 1회용이라 두 번째 호출은
  // 무조건 401이 되므로 한 번만 나가게 막는다.
  const started = useRef(false);

  useEffect(() => {
    if (started.current) return;
    started.current = true;

    const code = params.get("code");
    if (!code) {
      // 사용자가 카카오 동의 화면에서 취소하면 code 없이 error=access_denied 로 온다
      setError(
        params.get("error") === "access_denied"
          ? "카카오 로그인이 취소되었어요."
          : "인가 코드가 없어요. 로그인을 다시 시작해 주세요.",
      );
      return;
    }

    (async () => {
      try {
        const res = await api.auth.login({
          provider: "KAKAO",
          authorizationCode: code,
          agreements: [
            { type: "TOS", agreed: true },
            { type: "PRIVACY", agreed: true },
            { type: "MARKETING", agreed: takeMarketingChoice() },
          ],
        });
        authStore.signIn(res.accessToken, res.memberId);
        navigate(res.needsBirthdate ? "/birthdate" : "/", { replace: true });
      } catch (e) {
        if (e instanceof ApiError) {
          // 401 INVALID_SOCIAL_TOKEN: 코드 만료·재사용, redirect_uri 불일치, 서버 키 미설정.
          // 어느 쪽이든 사용자 해법은 재시도 하나다.
          setError(`${e.message} (${e.code})`);
        } else {
          setError(String(e));
        }
      }
    })();
  }, [navigate, params]);

  return (
    <div className="screen login">
      <div className="brand login-brand">
        <h1>모락</h1>
        <span>카카오 로그인</span>
      </div>
      {error === null ? (
        <p className="caption" style={{ textAlign: "center" }}>
          카카오 계정을 확인하고 있어요...
        </p>
      ) : (
        <div className="socials">
          <p className="error" style={{ textAlign: "center" }}>{error}</p>
          <Link to="/login" replace className="kakao" style={{ textAlign: "center" }}>
            로그인 화면으로 돌아가기
          </Link>
        </div>
      )}
    </div>
  );
}
