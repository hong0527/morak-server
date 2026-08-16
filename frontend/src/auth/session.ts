/**
 * 접속 토큰 보관.
 *
 * 리프레시 토큰이 없다(§2-6). 만료되면 재로그인 말고 방법이 없으므로 여기서 하는 일은
 * "들고 있다가 요청에 싣고, 만료되면 알린다" 셋뿐이다.
 *
 * localStorage 를 쓰는 이유는 새로고침과 탭 재개를 넘겨야 하기 때문이다.
 * 토큰 수명이 24시간이라 어제 로그인한 토큰으로 오늘 들어오는 경우가 실제로 생긴다.
 */
import { configureHttp } from "../api/http";

const TOKEN_KEY = "molock.accessToken";
const MEMBER_KEY = "molock.memberId";

type Listener = (state: AuthState) => void;

export interface AuthState {
  accessToken: string | null;
  memberId: number | null;
  /** 마지막 401 의 코드. TOKEN_EXPIRED 면 만료, UNAUTHORIZED 면 토큰이 없거나 깨진 것 */
  expiredReason: string | null;
}

function read(key: string): string | null {
  try {
    return window.localStorage.getItem(key);
  } catch {
    // 사파리 사생활 보호에서 접근이 막힐 수 있다. 그 경우 이 탭 안에서만 유지된다.
    return null;
  }
}

function write(key: string, value: string | null): void {
  try {
    if (value === null) window.localStorage.removeItem(key);
    else window.localStorage.setItem(key, value);
  } catch {
    /* 위와 같음 */
  }
}

let state: AuthState = {
  accessToken: read(TOKEN_KEY),
  memberId: Number(read(MEMBER_KEY)) || null,
  expiredReason: null,
};

const listeners = new Set<Listener>();

function emit(): void {
  for (const l of listeners) l(state);
}

export const authStore = {
  get: (): AuthState => state,

  subscribe(listener: Listener): () => void {
    listeners.add(listener);
    return () => listeners.delete(listener);
  },

  signIn(accessToken: string, memberId: number): void {
    write(TOKEN_KEY, accessToken);
    write(MEMBER_KEY, String(memberId));
    state = { accessToken, memberId, expiredReason: null };
    emit();
  },

  signOut(): void {
    write(TOKEN_KEY, null);
    write(MEMBER_KEY, null);
    state = { accessToken: null, memberId: null, expiredReason: null };
    emit();
  },

  /**
   * 401 을 받았다. 토큰을 지우고 로그인 화면으로 보낸다.
   *
   * 세션 중이라면 감지 모듈은 이때 멈추지 않고 이벤트를 큐에 쌓는다. 다시 로그인한 뒤
   * resumeAfterReauth() 를 불러야 쌓인 것이 나간다 — 그 연결은 detection/useAbsenceDetection.ts 에 있다.
   */
  markExpired(code: string): void {
    write(TOKEN_KEY, null);
    state = { accessToken: null, memberId: state.memberId, expiredReason: code };
    emit();
  },
};

configureHttp({
  readToken: () => authStore.get().accessToken,
  onUnauthorized: (code) => authStore.markExpired(code),
});
