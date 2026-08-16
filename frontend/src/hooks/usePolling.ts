import { useEffect, useRef, useState } from "react";

/**
 * 일정 간격으로 다시 물어본다. 서버가 아무것도 밀어 주지 않으므로(§2-1)
 * 매칭 성사와 세션 상태는 이 방법으로만 안다.
 *
 * 두 가지를 지킨다.
 * - 앞 요청이 끝나기 전에 다음 요청을 보내지 않는다. 서버가 느려지면 폴링이 겹쳐
 *   부하가 배로 뛴다(2코어에서 동시 조회 30이면 CPU 가 포화한다).
 * - 탭이 백그라운드면 쉰다. 돌아오면 즉시 한 번 부른다.
 */
export function usePolling<T>(
  fetcher: (signal: AbortSignal) => Promise<T>,
  intervalMs: number,
  options: { enabled?: boolean; deps?: unknown[] } = {},
): { data: T | null; error: unknown; refresh: () => void } {
  const enabled = options.enabled ?? true;
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [nonce, setNonce] = useState(0);
  const fetcherRef = useRef(fetcher);
  fetcherRef.current = fetcher;

  const deps = options.deps ?? [];

  useEffect(() => {
    if (!enabled) return;
    let stopped = false;
    let timer: number | undefined;
    const controller = new AbortController();

    const tick = async () => {
      if (stopped) return;
      if (document.visibilityState === "hidden") {
        timer = window.setTimeout(tick, intervalMs);
        return;
      }
      try {
        const next = await fetcherRef.current(controller.signal);
        if (stopped) return;
        setData(next);
        setError(null);
      } catch (e) {
        if (stopped || controller.signal.aborted) return;
        setError(e);
      }
      if (!stopped) timer = window.setTimeout(tick, intervalMs);
    };

    void tick();

    const onVisible = () => {
      if (document.visibilityState === "visible" && !stopped) {
        window.clearTimeout(timer);
        void tick();
      }
    };
    document.addEventListener("visibilitychange", onVisible);

    return () => {
      stopped = true;
      window.clearTimeout(timer);
      controller.abort();
      document.removeEventListener("visibilitychange", onVisible);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [enabled, intervalMs, nonce, ...deps]);

  return { data, error, refresh: () => setNonce((n) => n + 1) };
}
