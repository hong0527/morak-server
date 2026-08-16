/**
 * 서버 오퍼레이션 전부. api-spec.md 의 번호(AU-1, SS-4 …)를 주석에 남겨 두었으니
 * 명세를 찾아볼 때 그 번호로 검색하면 된다.
 *
 * 요청 본문 타입은 생성된 operations 에서 그대로 꺼내 쓴다. 손으로 다시 적으면
 * 서버가 필드를 바꿨을 때 여기만 옛 모양으로 남는다.
 *
 * 여기 없는 것 둘 — `POST /webhooks/livekit`(SS-10)과 `POST /webhooks/payment`(PY-3)는
 * LiveKit·PG 가 서버로 직접 부르는 것이라 프론트가 부를 일이 없다. 그래서 넣지 않았다.
 * openapi.yaml 의 46개 중 프론트가 부르는 것은 44개다.
 */
import { DELETE, GET, PATCH, POST, PUT } from "./http";
import type { operations } from "./schema.gen";
import type * as T from "./types";

type Body<K extends keyof operations> = operations[K] extends {
  requestBody?: { content: { "application/json": infer B } };
}
  ? B
  : never;

/**
 * 목록은 전부 같은 모양이다. size 는 최대 50 이고 넘기면 400 이다(§2-8).
 *
 * interface 가 아니라 type 인 이유는 interface 에 암묵적 인덱스 시그니처가 없어
 * 쿼리 문자열로 넘길 때 타입이 안 맞기 때문이다.
 */
export type PageQuery = {
  page?: number;
  size?: number;
};

// ── 인증 (AU) ────────────────────────────────────────────────────────────────

export const auth = {
  /**
   * AU-1. 토큰 없이 부르는 유일한 일반 API.
   * agreements 는 기존 회원이어도 필드 자체가 필수라 빈 배열이라도 보낸다.
   */
  login: (body: Body<"login">) =>
    POST<T.LoginResponse>("/api/auth/login", { body, anonymous: true }),
};

// ── 회원 (ME) ────────────────────────────────────────────────────────────────

export const member = {
  /** AU-2. 홈 화면 전체가 이것 하나로 채워진다. activeSession 을 반드시 본다 */
  me: () => GET<T.MemberMe>("/api/members/me"),

  /** ME-1. 되돌릴 수 없다. 만 14세 미만이면 계정이 영구히 잠긴다 */
  verifyAge: (birthDate: string) =>
    POST<T.AgeVerificationResponse>("/api/members/me/birthdate", {
      body: { birthDate } satisfies Body<"verifyAge">,
    }),

  /** ME-4. 탈퇴 신청. 유예 중에는 조회 API 까지 전부 막힌다(§2-7 ②) */
  withdraw: () => POST<T.WithdrawalResponse>("/api/members/me/withdrawal"),

  /** ME-5. 탈퇴 철회. 성공 204 */
  cancelWithdrawal: () => DELETE<void>("/api/members/me/withdrawal"),

  /** ME-2. 캠 분석 동의. true 만 받고 철회 API 는 v1 에 없다. 성공 204 */
  agreeMediaConsent: () =>
    POST<void>("/api/members/me/media-consent", {
      body: { agreed: true } satisfies Body<"agreeMediaConsent">,
    }),

  /** ME-3. 목표 기간. 허용값 7 / 14 / 30 */
  setGoal: (periodDays: T.Goal["periodDays"]) =>
    PUT<T.Goal>("/api/members/me/goal", {
      body: { periodDays } satisfies Body<"setGoal">,
    }),

  /** MS-1. 내 세션 기록 */
  mySessions: (query: PageQuery & { status?: T.SessionStatus } = {}) =>
    GET<T.MySessionPage>("/api/members/me/sessions", { query }),

  /** AP-2. 내가 낸 퇴출 이의 목록 */
  myAppeals: (query: PageQuery = {}) =>
    GET<T.MyAppealPage>("/api/members/me/appeals", { query }),

  /** PT-1. 잔액과 내역을 함께 준다. 목록이 ledger 키 안에 들어가는 유일한 예외다 */
  points: (query: PageQuery = {}) =>
    GET<T.PointBalance>("/api/members/me/points", { query }),
};

// ── 매칭 (MT) ────────────────────────────────────────────────────────────────

export const match = {
  /** MT-1. 허용값 60 / 120 / 180 / 240 */
  create: (targetMinutes: T.TargetMinutes) =>
    POST<T.MatchRequest>("/api/match-requests", {
      body: { targetMinutes } satisfies Body<"createMatchRequest">,
    }),

  /** MT-2. 폴링. 매칭 성사를 아는 방법이 이것뿐이다(§2-1) */
  mine: (signal?: AbortSignal) =>
    GET<T.MatchRequest>("/api/match-requests/me", { signal }),

  /** MT-3. 대기 취소. 성공 204 */
  cancel: (matchRequestId: number) =>
    DELETE<void>(`/api/match-requests/${matchRequestId}`),
};

// ── 세션 (SS) ────────────────────────────────────────────────────────────────

export const session = {
  /**
   * SS-1. 세션 조회. **입장할 때 한 번만 부르면 안 된다.**
   * 남의 퇴장·퇴출·화장실 모드와 세션 조기 종료를 아는 경로가 이것뿐이다.
   */
  get: (sessionId: number, signal?: AbortSignal) =>
    GET<T.SessionDetail>(`/api/sessions/${sessionId}`, { signal }),

  /**
   * SS-2. LiveKit 접속 정보. 수명이 3600초인데 세션은 240분까지 있다.
   * **재접속할 때 저장해 둔 토큰을 재사용하지 말고 이것을 다시 부른다.**
   */
  issueToken: (sessionId: number) =>
    POST<T.LivekitToken>(`/api/sessions/${sessionId}/token`),

  /** SS-3. 오늘 할 일. 최대 50자, 빈 문자열은 400 */
  setGoalText: (sessionId: number, goalText: string) =>
    PUT<T.SessionGoalResponse>(`/api/sessions/${sessionId}/goal`, {
      body: { goalText } satisfies Body<"setSessionGoal">,
    }),

  /**
   * SS-4. 자리비움 보고. **화면이 직접 부르지 않는다** — 감지 모듈이 자기 전송 큐로 보낸다
   * (src/detection/). 순번 보존·429 재전송·409 흡수가 거기 들어 있어서,
   * 화면이 따로 부르면 그 규약이 깨진다.
   */
  reportAbsenceEvent: (sessionId: number, body: T.AbsenceEventRequest) =>
    POST<T.AbsenceEventResponse>(`/api/sessions/${sessionId}/absence-events`, { body }),

  /** SS-5. 화장실 모드 시작. 세션당 한 번. 응답의 warningIssued 를 반드시 화면에 반영한다 */
  startPause: (sessionId: number) =>
    POST<T.PauseStartResponse>(`/api/sessions/${sessionId}/pause`),

  /** SS-6. 복귀. status 가 ACTIVE 라고 단정하면 안 된다 — EVICTED 가 온다 */
  endPause: (sessionId: number) =>
    DELETE<T.PauseEndResponse>(`/api/sessions/${sessionId}/pause`),

  /** SS-7. 자율 퇴장. DELETE 인데 본문이 필수다. 성공 204 */
  leave: (sessionId: number, reason: T.LeaveReason) =>
    DELETE<void>(`/api/sessions/${sessionId}/participation`, {
      body: { reason } satisfies Body<"leaveSession">,
    }),

  /**
   * SS-8. 결과. 정시 종료 직후 최대 1분간 409 SESSION_NOT_ENDED 다.
   * **그 409 는 오류가 아니다.** 재시도는 screens/ResultScreen.tsx 가 한다.
   */
  result: (sessionId: number) =>
    GET<T.SessionResult>(`/api/sessions/${sessionId}/result`),
};

/** SS-9. 스티커 목록. 전송은 서버를 거치지 않고 LiveKit 데이터 채널로 간다 */
export const stickers = {
  list: () => GET<{ stickers: T.StickerItem[] }>("/api/stickers"),
};

// ── 퇴출 이의 (AP) ───────────────────────────────────────────────────────────

export const appeal = {
  /** AP-1. 필드명이 reason 이 아니라 reasonText 다. 최대 200자, 퇴출 후 3일 이내 */
  create: (evictionId: number, reasonText: string) =>
    POST<T.AppealResponse>(`/api/evictions/${evictionId}/appeals`, {
      body: { reasonText } satisfies Body<"createAppeal">,
    }),
};

// ── 스토어 · 주문 (ST · OD) ──────────────────────────────────────────────────

export const store = {
  /** ST-1 */
  products: (query: PageQuery & { type?: T.ProductDetail["type"] } = {}) =>
    GET<T.ProductPage>("/api/store/products", { query }),

  /** ST-2 */
  product: (productId: number) => GET<T.ProductDetail>(`/api/store/products/${productId}`),
};

export const order = {
  /** OD-1. idempotencyKey 를 재시도마다 새로 만들지 않는다 */
  create: (body: Body<"createOrder">) =>
    POST<T.OrderCreateResponse>("/api/orders", { body }),

  /** OD-2 */
  mine: (query: PageQuery = {}) => GET<T.OrderPage>("/api/orders", { query }),

  /** OD-3 */
  get: (orderId: number) => GET<T.OrderDetail>(`/api/orders/${orderId}`),
};

// ── 포인트 충전 (PY) ─────────────────────────────────────────────────────────

export const payment = {
  /** PY-1. 결제창이 없어 PY-2 를 바로 이어 부르면 포인트가 들어온다 */
  createCharge: (body: Body<"createCharge">) =>
    POST<T.ChargeCreateResponse>("/api/points/charges", { body }),

  /** PY-2. pgTid 가 `fail-` 이면 거절, `mismatch-` 면 금액 불일치로 갈린다 */
  confirmCharge: (chargeId: number, body: Body<"confirmCharge">) =>
    POST<T.ChargeConfirmResponse>(`/api/points/charges/${chargeId}/confirm`, { body }),
};

// ── 신고 (RP) ────────────────────────────────────────────────────────────────

export const report = {
  /** RP-1 */
  create: (body: Body<"createReport">) =>
    POST<T.ReportCreateResponse>("/api/reports", { body }),
};

// ── 관리자 (AD) ──────────────────────────────────────────────────────────────
// 전부 role=ADMIN 이라야 하고 아니면 403 FORBIDDEN_ROLE 이다.
// 관리자 화면을 같은 앱에 둘지 별도 웹으로 뺄지는 아직 팀 결정 전이다(frontend-guide §8).

export const admin = {
  /** AD-1 */
  reports: (
    query: PageQuery & {
      status?: string;
      severity?: string;
      overdue?: boolean;
      q?: string;
    } = {},
  ) => GET<T.ReportCasePage>("/api/admin/reports", { query }),

  /** AD-2 */
  report: (caseId: number) => GET<T.ReportCaseDetail>(`/api/admin/reports/${caseId}`),

  /** AD-3 */
  processReport: (caseId: number, body: Body<"processReport">) =>
    PATCH<T.ReportProcessResponse>(`/api/admin/reports/${caseId}`, { body }),

  /** AD-4 */
  createSanction: (memberId: number, body: Body<"createSanction">) =>
    POST<T.SanctionResponse>(`/api/admin/members/${memberId}/sanctions`, { body }),

  /** AD-5 */
  appeals: (query: PageQuery & { status?: string; overdue?: boolean } = {}) =>
    GET<T.AppealPage>("/api/admin/appeals", { query }),

  /** AD-6 */
  appeal: (appealId: number) => GET<T.AppealDetail>(`/api/admin/appeals/${appealId}`),

  /** AD-7 */
  processAppeal: (appealId: number, body: Body<"processAppeal">) =>
    PATCH<T.AppealProcessResponse>(`/api/admin/appeals/${appealId}`, { body }),

  /** AD-8 */
  sessions: (query: PageQuery & { status?: T.SessionStatus } = {}) =>
    GET<T.AdminSessionPage>("/api/admin/sessions", { query }),

  /** AD-9 */
  withdrawals: (
    query: PageQuery & { status?: "WITHDRAW_PENDING" | "DELETED" } = {},
  ) => GET<T.WithdrawalPage>("/api/admin/withdrawals", { query }),
};

// ── 개발 전용 (DV) ───────────────────────────────────────────────────────────
// dev 프로필에서만 등록된다. 운영에서는 404 다.
// 시연·테스트에서 세션을 통째로 만들거나 시계를 앞으로 당길 때 쓴다.

export const dev = {
  clock: () => GET<T.DevClockResponse>("/api/dev/clock"),

  setClock: (body: Body<"setDevClock">) =>
    POST<T.DevClockResponse>("/api/dev/clock", { body }),

  /** 계정 6개를 만들어 세션 하나를 즉시 연다. 매칭을 기다리지 않고 세션 화면을 보고 싶을 때 */
  seedSessions: (body: Body<"seedDevSessions">) =>
    POST<T.DevSessionSeedResponse>("/api/dev/sessions/seed", { body }),

  /** 배치를 지금 돌린다. 세션 종료 정산을 기다리지 않고 결과 화면을 보려면 이것을 부른다 */
  triggerBatch: (name: T.DevBatchName) =>
    POST<T.DevBatchResponse>(`/api/dev/batches/${name}`),
};

export const api = {
  auth,
  member,
  match,
  session,
  stickers,
  appeal,
  store,
  order,
  payment,
  report,
  admin,
  dev,
};
