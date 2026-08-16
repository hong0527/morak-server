import { useSyncExternalStore } from "react";
import { authStore, type AuthState } from "./session";

/** 토큰 상태를 화면이 구독한다. 상태관리 라이브러리 없이 이거면 충분하다 */
export function useAuth(): AuthState {
  return useSyncExternalStore(
    (cb) => authStore.subscribe(cb),
    () => authStore.get(),
  );
}
