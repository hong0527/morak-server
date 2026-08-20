/**
 * 카카오 인가 리다이렉트의 준비물.
 *
 * REST 키는 빌드타임 env `VITE_KAKAO_REST_KEY` 로 온다. 비밀값이 아니다 — 인가 URL 에
 * 그대로 실려 주소창에 보이는 값이라 번들에 박혀도 문제가 없다. client_secret 은
 * 반대로 서버만 안다(토큰 교환은 서버가 한다).
 *
 * 마케팅 동의 체크박스는 카카오로 갔다 오는 사이에 리액트 상태가 사라지므로
 * sessionStorage 로 건네준다. 같은 탭에서만 유효하면 충분하다.
 */

const REST_KEY: string | undefined = import.meta.env.VITE_KAKAO_REST_KEY;

const MARKETING_KEY = "molock.login.marketing";

/** 키가 없으면 버튼은 남기되 코드 로그인으로 안내한다(LoginScreen). */
export const kakaoConfigured = Boolean(REST_KEY);

/** 토큰 교환 때 서버가 보내는 redirect_uri 와 정확히 같아야 한다(MORAK_KAKAO_REDIRECT_URI). */
export function kakaoRedirectUri(): string {
  return `${window.location.origin}/login/kakao`;
}

export function startKakaoLogin(marketingAgreed: boolean): void {
  try {
    window.sessionStorage.setItem(MARKETING_KEY, marketingAgreed ? "1" : "0");
  } catch {
    // 사파리 사생활 보호에서 막히면 마케팅 동의만 false 로 남는다. 로그인은 계속된다.
  }
  const params = new URLSearchParams({
    client_id: REST_KEY ?? "",
    redirect_uri: kakaoRedirectUri(),
    response_type: "code",
  });
  window.location.assign(`https://kauth.kakao.com/oauth/authorize?${params}`);
}

/** 콜백 화면이 읽는다. 읽고 나면 지워 다음 로그인에 새지 않게 한다. */
export function takeMarketingChoice(): boolean {
  try {
    const value = window.sessionStorage.getItem(MARKETING_KEY);
    window.sessionStorage.removeItem(MARKETING_KEY);
    return value === "1";
  } catch {
    return false;
  }
}
