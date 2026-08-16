/**
 * 서버 호출의 바닥. 화면과 client.ts 는 fetch 를 직접 부르지 않는다.
 *
 * 여기 모아 둔 것은 전부 "모든 화면에 똑같이 적용되는 규칙"이다
 * (frontend-guide.md §2). 화면마다 다시 쓰면 한 군데는 반드시 빠뜨린다.
 */
import type { ErrorBody } from "./types";

/**
 * 서버가 내려주는 실패는 전부 이 모양 하나다.
 * 화면 분기는 message 가 아니라 code 로 한다 — 문구는 다듬어져도 코드는 안 바뀐다(§2-4).
 */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
    readonly details: Record<string, string> | null,
  ) {
    super(message);
    this.name = "ApiError";
  }

  /** 400 VALIDATION_FAILED 의 {필드명: 메시지} 를 폼에 그대로 붙일 때 쓴다 */
  fieldErrors(): Record<string, string> {
    return this.code === "VALIDATION_FAILED" && this.details ? this.details : {};
  }
}

type TokenReader = () => string | null;
type UnauthorizedHandler = (code: string) => void;

let readToken: TokenReader = () => null;
let onUnauthorized: UnauthorizedHandler = () => {};

/** auth/session.ts 가 앱 시작 때 한 번 연결한다 */
export function configureHttp(options: {
  readToken: TokenReader;
  onUnauthorized: UnauthorizedHandler;
}): void {
  readToken = options.readToken;
  onUnauthorized = options.onUnauthorized;
}

const BASE = import.meta.env.VITE_API_BASE ?? "";

// 503 LOCK_ACQUISITION_FAILED 는 경합으로 밀린 것이고 트랜잭션이 통째로 롤백돼
// 아무것도 바뀌지 않았다. 그래서 이것만 자동 재시도한다(§2-5).
// 500 은 어디까지 처리됐는지 알 수 없어 절대 재시도하지 않는다.
const LOCK_RETRIES = 2;
const LOCK_RETRY_DELAY_MS = 300;

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

export interface RequestOptions {
  query?: Record<string, string | number | boolean | undefined>;
  body?: unknown;
  /** 토큰 없이 부르는 유일한 일반 API 는 로그인뿐이다 */
  anonymous?: boolean;
  signal?: AbortSignal;
}

async function once<T>(
  method: string,
  path: string,
  options: RequestOptions,
): Promise<T> {
  const url = new URL(BASE + path, window.location.origin);
  for (const [key, value] of Object.entries(options.query ?? {})) {
    if (value !== undefined) url.searchParams.set(key, String(value));
  }

  const headers: Record<string, string> = {};
  if (options.body !== undefined) headers["Content-Type"] = "application/json";
  if (!options.anonymous) {
    const token = readToken();
    if (token) headers["Authorization"] = `Bearer ${token}`;
  }

  const res = await fetch(url.pathname + url.search, {
    method,
    headers,
    body: options.body === undefined ? undefined : JSON.stringify(options.body),
    signal: options.signal,
  });

  if (res.ok) {
    // 204 를 돌려주는 곳이 여럿이다(캠 동의·자율 퇴장). 본문을 읽으면 깨진다.
    if (res.status === 204 || res.headers.get("content-length") === "0") {
      return undefined as T;
    }
    return (await res.json()) as T;
  }

  let code = `HTTP_${res.status}`;
  let message = res.statusText;
  let details: Record<string, string> | null = null;
  try {
    const body = (await res.json()) as ErrorBody;
    if (body?.error) {
      code = body.error.code ?? code;
      message = body.error.message ?? message;
      details = (body.error.details as Record<string, string> | null) ?? null;
    }
  } catch {
    // 서버가 아닌 곳(프록시·정적 서버)이 낸 오류면 본문이 JSON 이 아니다.
  }

  // 토큰 만료는 재로그인 말고 방법이 없다(§2-6). 갱신 엔드포인트가 아예 없다.
  if (res.status === 401) onUnauthorized(code);

  throw new ApiError(res.status, code, message, details);
}

export async function request<T>(
  method: string,
  path: string,
  options: RequestOptions = {},
): Promise<T> {
  for (let attempt = 0; ; attempt += 1) {
    try {
      return await once<T>(method, path, options);
    } catch (e) {
      const retryable =
        e instanceof ApiError &&
        e.code === "LOCK_ACQUISITION_FAILED" &&
        attempt < LOCK_RETRIES;
      if (!retryable) throw e;
      await sleep(LOCK_RETRY_DELAY_MS);
    }
  }
}

export const GET = <T>(path: string, o?: RequestOptions) => request<T>("GET", path, o);
export const POST = <T>(path: string, o?: RequestOptions) => request<T>("POST", path, o);
export const PUT = <T>(path: string, o?: RequestOptions) => request<T>("PUT", path, o);
export const PATCH = <T>(path: string, o?: RequestOptions) => request<T>("PATCH", path, o);
export const DELETE = <T>(path: string, o?: RequestOptions) => request<T>("DELETE", path, o);

/** 에러 코드로 분기할 때. `e instanceof ApiError && e.code === x` 를 줄인 것 */
export function isApiError(e: unknown, ...codes: string[]): e is ApiError {
  return e instanceof ApiError && (codes.length === 0 || codes.includes(e.code));
}
