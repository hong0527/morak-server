/**
 * 감지 모듈이 브라우저에서 받아 가는 정적 자산의 경로.
 *
 * scripts/sync-ai-detection.mjs 가 public/ai 에 놓는다. CDN 을 쓰지 않는 이유는
 * 발표 당일 CDN 이 느리면 그대로 시연이 막히기 때문이다(ai-detection/README 7-3).
 */
export const DETECTION_ASSETS = {
  wasmBasePath: "/ai/wasm",
  modelAssetPath: "/ai/face_landmarker.task",
} as const;

/** 반드시 클래식 워커여야 한다. type:"module" 을 주면 모델이 로드되지 않는다(9-9) */
export const WORKER_URL = "/ai/worker-classic.js";

let prefetched = false;

/**
 * 모델 3.76MB 와 wasm 런타임을 HTTP 캐시에 미리 채운다.
 *
 * 매칭 대기 화면에서 부른다. 세션에 들어간 뒤에 받기 시작하면 그 시간만큼 입장이 밀린다.
 * 컨트롤러를 미리 만들지 않는 이유는 sessionId 가 매칭이 성사돼야 나오기 때문이다 —
 * 캐시만 데워 두면 입장 시점의 init() 이 네트워크를 타지 않는다.
 */
export async function prefetchDetectionAssets(): Promise<void> {
  if (prefetched) return;
  prefetched = true;
  const targets = [
    DETECTION_ASSETS.modelAssetPath,
    WORKER_URL,
    `${DETECTION_ASSETS.wasmBasePath}/vision_wasm_internal.js`,
    `${DETECTION_ASSETS.wasmBasePath}/vision_wasm_internal.wasm`,
  ];
  await Promise.all(
    targets.map((url) =>
      fetch(url, { cache: "force-cache" }).catch(() => {
        // 실패해도 입장을 막지 않는다. 그때는 init() 이 직접 받는다.
      }),
    ),
  );
}
