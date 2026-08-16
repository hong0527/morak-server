import type { TransportConfig } from "../config.js";
import type { AbsenceEventBody, AbsenceEventResponse } from "../types.js";
import { AbsenceSeqStore, type PendingEvent } from "./seq-store.js";

/** 번호 건너뛰기 상한. 이 이상 늘려도 앞 탭이 그만큼 썼을 리 없다(5초에 하나) */
const MAX_DUPLICATE_JUMP = 1024;

/**
 * SS-4 전송 큐.
 *
 * 서버 계약에서 이 파일이 지켜야 하는 것 넷:
 *
 * 1. **순서.** START 다음에 END. 큐는 엄격한 FIFO 이고 앞의 것이 끝나기 전에 뒤를 보내지 않는다.
 * 2. **5초 페이싱.** 같은 참가자의 직전 이벤트로부터 5초 안에 다시 보내면 429 다.
 *    미리 지키면 429 자체가 잘 안 난다.
 * 3. **429 를 받으면 같은 clientSeq 로 5초 뒤 재전송.** 새 번호를 매기면 서버의 중복 제거가
 *    깨진다. 특히 END 가 유실되면 짝 없는 START 가 되어 세션 종료 시각까지 자리비움으로
 *    정산된다 — 잠깐 비운 사람이 최대로 과대 계상되는 경로라 이 재전송이 계약이다.
 * 4. **409 DUPLICATE_ABSENCE_EVENT 는 성공이다.** 서버 상태가 이미 반영돼 있다는 뜻이라
 *    정상 종료로 취급하고 큐에서 뺀다.
 */

export type TransportOutcome =
  | { kind: "OK"; response: AbsenceEventResponse }
  /** 409 DUPLICATE_ABSENCE_EVENT — 이미 반영됨 */
  | { kind: "DUPLICATE" }
  /** 429 ABSENCE_RATE_LIMITED */
  | { kind: "RATE_LIMITED" }
  /** 더 보내봐야 소용없는 상태(퇴출·세션 종료·참가자 아님·세션 없음) */
  | { kind: "TERMINAL"; code: string }
  /** 400 VALIDATION_FAILED — occurredAt 이 허용 범위 밖. 단말 시계가 어긋났다는 신호다 */
  | { kind: "INVALID"; code: string }
  /**
   * 401 — 토큰이 만료·무효다. 재시도로는 절대 풀리지 않고, 새 토큰이 오면 그대로 풀린다.
   * NETWORK_ERROR 로 흘리면 세션이 끝날 때까지 백오프로 두드리기만 하고 아무도 모른다(W2).
   */
  | { kind: "UNAUTHORIZED"; code: string }
  | { kind: "NETWORK_ERROR"; message: string };

export interface AbsenceTransport {
  post(body: AbsenceEventBody): Promise<TransportOutcome>;
}

export interface SenderHooks {
  /** 서버가 경고·퇴출을 실어 보냈다. 화면에 그린다(§0-7 — 내 경고는 내 호출의 응답에 실린다) */
  onAccepted?(response: AbsenceEventResponse, event: PendingEvent): void;
  /** 더 보낼 수 없는 상태가 됐다. 감지 루프를 멈춘다 */
  onTerminal?(code: string): void;
  /** occurredAt 이 거절됐다. 시계 문제라 사용자에게 알릴 것은 없고 로그가 필요하다 */
  onInvalid?(code: string, event: PendingEvent): void;
  /**
   * 토큰이 만료됐다(401). 전송이 **멈춘 채 큐를 붙들고** 있으므로, 앱이 다시 로그인시킨 뒤
   * `resume()` 을 부르면 쌓인 이벤트가 그대로 나간다. 부르지 않으면 영영 나가지 않는다.
   */
  onUnauthorized?(code: string): void;
  /**
   * **보낸 적 없는 번호인데 서버가 중복이라고 답했다.** 다른 탭·기기가 그 번호를 이미 쓴 것이라
   * 이 이벤트는 서버에 기록되지 않았다. 번호를 다시 매겨 재전송하며, 이 훅은 그 사실을 알린다.
   *
   * 정상 동작에서는 절대 불리지 않아야 한다 — 불린다면 번호 발급의 바닥(`sessionStartedAtMs`)이
   * 없거나 틀렸다는 신호다(W1).
   */
  onUnexpectedDuplicate?(event: PendingEvent, reissuedAs: number | null): void;
}

export class AbsenceEventSender {
  private draining = false;
  private lastAttemptAtMs = 0;
  private networkBackoffMs: number;
  private stopped = false;
  /**
   * `stopped` 와 다르다. **큐를 계속 받되 흘리지만 않는다.** 401 이 이 상태를 만든다 —
   * 토큰이 돌아오면 그동안 쌓인 자리비움이 그대로 나가야 하므로 enqueue 를 막으면 안 된다.
   */
  private paused = false;
  /** 예상 못 한 409 가 연속으로 날 때 번호를 얼마나 건너뛸지. 실패할수록 배로 늘린다 */
  private duplicateJump = 1;
  private timer: ReturnType<typeof setTimeout> | null = null;

  constructor(
    private readonly store: AbsenceSeqStore,
    private readonly transport: AbsenceTransport,
    private readonly config: TransportConfig,
    private readonly hooks: SenderHooks = {},
  ) {
    this.networkBackoffMs = config.networkRetryBaseMs;
  }

  /**
   * 이벤트를 큐에 넣는다. 번호 발급과 영속화는 store 가 한다.
   * @param occurredAtMs 실제 전이가 일어난 프레임의 시각. 판단이 끝난 시각이 아니다.
   */
  enqueue(type: "START" | "END", occurredAtMs: number): void {
    if (this.stopped) return;
    this.store.enqueue(type, toIsoWithOffset(occurredAtMs));
    void this.drain();
  }

  /**
   * 새로고침 후 남아 있던 큐를 다시 흘린다. 401 로 멈춘 뒤 새 토큰을 받았을 때도 이것을 부른다.
   */
  resume(): void {
    this.stopped = false;
    this.paused = false;
    void this.drain();
  }

  stop(): void {
    this.stopped = true;
    if (this.timer !== null) clearTimeout(this.timer);
    this.timer = null;
  }

  /** 큐는 계속 받되 전송만 멈춘다. `resume()` 으로 푼다 */
  private pause(): void {
    this.paused = true;
    if (this.timer !== null) clearTimeout(this.timer);
    this.timer = null;
  }

  /** 전송이 멈춰 있는가(401 대기 중). 화면이 배지를 띄울 근거 */
  get isPaused(): boolean {
    return this.paused;
  }

  get hasOpenStart(): boolean {
    return this.store.hasOpenStart;
  }

  get pendingCount(): number {
    return this.store.pending.length;
  }

  private async drain(): Promise<void> {
    if (this.draining || this.stopped || this.paused) return;
    this.draining = true;
    try {
      while (!this.stopped && !this.paused) {
        const next = this.store.pending[0];
        if (!next) return;

        const waitMs = this.config.minIntervalMs - (Date.now() - this.lastAttemptAtMs);
        if (waitMs > 0) {
          this.scheduleDrain(waitMs);
          return;
        }

        this.lastAttemptAtMs = Date.now();
        // 몇 번째 시도인지를 **markAttempt 전에** 읽는다. store.pending 은 배열만 복사하고
        // 원소는 같은 객체라, 먼저 올리면 next.attempts 가 이미 증가해 있어 첫 시도를
        // 재전송으로 오독한다.
        const attempts = next.attempts + 1;
        // 올리는 것 자체는 보내기 전에 해야 한다. 응답을 못 받고 새로고침돼도 "한 번 보낸 번호"로
        // 남아야 뒤이은 409 를 재전송 흡수로 옳게 읽는다.
        this.store.markAttempt(next.clientSeq);
        const outcome = await this.transport.post({
          type: next.type,
          clientSeq: next.clientSeq,
          occurredAt: next.occurredAt,
        });

        if (!this.handle(outcome, { ...next, attempts })) return;
      }
    } finally {
      this.draining = false;
    }
  }

  /** @returns 큐를 계속 흘려도 되면 true */
  private handle(outcome: TransportOutcome, event: PendingEvent): boolean {
    switch (outcome.kind) {
      case "OK":
        this.networkBackoffMs = this.config.networkRetryBaseMs;
        this.store.complete(event.clientSeq);
        if (event.type === "END") this.store.clearOpenStart();
        this.hooks.onAccepted?.(outcome.response, event);
        if (outcome.response.evicted) {
          // 퇴출됐으면 더 보낼 것이 없다. 남은 큐를 버린다.
          this.store.abandon();
          this.stop();
          return false;
        }
        return true;

      case "DUPLICATE":
        this.networkBackoffMs = this.config.networkRetryBaseMs;
        // 두 번 이상 보낸 번호면 내 재전송이 흡수된 것이다 — 계약대로 성공이다(api-spec SS-4).
        if (event.attempts > 1) {
          this.duplicateJump = 1;
          this.store.complete(event.clientSeq);
          if (event.type === "END") this.store.clearOpenStart();
          return true;
        }
        // 처음 보낸 번호인데 이미 있다 = 다른 탭·기기가 쓴 번호다. 내 이벤트는 기록되지 않았다.
        // 여기서 성공으로 취급하면 자리비움이 조용히 사라진다(W1).
        return this.reissueAfterDuplicate(event);

      case "RATE_LIMITED":
        // 같은 clientSeq 그대로 둔 채 기다린다. 큐에서 빼지 않는 것이 핵심이다.
        this.scheduleDrain(this.config.retryAfterRateLimitMs);
        return false;

      case "NETWORK_ERROR":
        this.scheduleDrain(this.networkBackoffMs);
        this.networkBackoffMs = Math.min(
          this.networkBackoffMs * 2,
          this.config.networkRetryMaxMs,
        );
        return false;

      case "INVALID":
        // 재전송해도 같은 시각으로는 같은 이유로 거절된다. 큐에서는 뺀다.
        this.store.complete(event.clientSeq);
        // **열린 START 표시는 지우지 않는다.** 지우는 쪽이 직관적으로 보이는 자리다 —
        // "END 처리가 끝났으니 구간도 닫힌 것"으로 읽히기 때문이다. 그런데 이 분기는
        // END 가 **거절된** 경로다. 서버에는 짝 없는 START 가 그대로 남아 있고, 그것은
        // 세션 종료 시각까지 자리비움으로 정산된다(60분 세션에서 57분이 적힌 실측이 있다).
        // 표시를 지우면 클라이언트는 "열린 구간이 없다"고 믿게 되어 새로고침 복구
        // (RESTORE_OPEN_ABSENCE)마저 못 쓰고, 그 START 를 닫을 방법이 영영 없어진다.
        // 남겨 두면 다음 start() 가 구간을 복원해 시계가 정상으로 돌아온 뒤 END 를 다시 낸다.
        // START 까지 함께 거절된 경우에는 서버에 열린 구간이 없어 짝 없는 END 가 나가는데,
        // 그것은 서버가 closedAbsenceSeconds=null 로 흘려보내 무해하다 — 한쪽은 경고가
        // 붙고 다른 쪽은 아무 일도 없으므로 남기는 쪽이 옳다.
        this.hooks.onInvalid?.(outcome.code, event);
        return true;

      case "UNAUTHORIZED":
        // **큐를 버리지 않는다.** 버리면 그동안의 자리비움이 사라지고, 계속 두드리면 세션이
        // 끝날 때까지 아무도 모른 채 30초 백오프만 돈다(W2). 붙들고 멈춘 뒤 알린다.
        this.pause();
        this.hooks.onUnauthorized?.(outcome.code);
        return false;

      case "TERMINAL":
        this.store.abandon();
        this.stop();
        this.hooks.onTerminal?.(outcome.code);
        return false;
    }
  }

  /**
   * 보낸 적 없는 번호에 409 가 왔다. 번호를 건너뛰어 다시 매기고 재전송한다.
   *
   * 건너뛰는 폭을 배로 늘리는 이유: 앞 탭이 몇 번까지 썼는지 알 방법이 없다. 한 칸씩 올리면
   * 앞 탭이 쓴 개수만큼 왕복해야 해서 5초 페이싱과 곱해지면 오래 걸린다. 배로 늘리면
   * 몇 번 만에 넘어선다. 성공하면 폭을 1 로 되돌린다.
   */
  private reissueAfterDuplicate(event: PendingEvent): boolean {
    const floor = event.clientSeq + this.duplicateJump;
    this.duplicateJump = Math.min(this.duplicateJump * 2, MAX_DUPLICATE_JUMP);
    const reissued = this.store.reissuePending(floor);
    const mine = reissued.find((e) => e.type === event.type && e.occurredAt === event.occurredAt);
    this.hooks.onUnexpectedDuplicate?.(event, mine?.clientSeq ?? null);
    // 페이싱을 지켜 다시 흘린다. 큐 맨 앞이 방금 새 번호를 받은 그 이벤트다.
    this.scheduleDrain(this.config.retryAfterRateLimitMs);
    return false;
  }

  private scheduleDrain(delayMs: number): void {
    if (this.timer !== null) clearTimeout(this.timer);
    this.timer = setTimeout(() => {
      this.timer = null;
      void this.drain();
    }, delayMs);
  }
}

/**
 * fetch 기반 기본 구현. 인증 헤더는 앱이 주입한다.
 */
export class FetchAbsenceTransport implements AbsenceTransport {
  constructor(
    private readonly url: string,
    private readonly getHeaders: () => Record<string, string> | Promise<Record<string, string>>,
  ) {}

  async post(body: AbsenceEventBody): Promise<TransportOutcome> {
    let res: Response;
    try {
      const headers = await this.getHeaders();
      res = await fetch(this.url, {
        method: "POST",
        headers: { "Content-Type": "application/json", ...headers },
        body: JSON.stringify(body),
      });
    } catch (e) {
      return { kind: "NETWORK_ERROR", message: e instanceof Error ? e.message : String(e) };
    }

    if (res.ok) {
      const response = (await res.json()) as AbsenceEventResponse;
      return { kind: "OK", response };
    }

    const code = await readErrorCode(res);

    if (res.status === 429) return { kind: "RATE_LIMITED" };
    if (res.status === 409 && code === "DUPLICATE_ABSENCE_EVENT") return { kind: "DUPLICATE" };
    if (res.status === 400) return { kind: "INVALID", code };
    // 401 은 재시도로 풀리지 않는다. 새 토큰이 와야 풀리므로 별도 결과로 낸다(W2).
    if (res.status === 401) return { kind: "UNAUTHORIZED", code };
    if (res.status === 409 || res.status === 403 || res.status === 404) {
      return { kind: "TERMINAL", code };
    }
    // 5xx 는 서버가 회복할 수 있다. 네트워크 오류와 같은 백오프로 다시 보낸다.
    return { kind: "NETWORK_ERROR", message: `HTTP ${res.status} ${code}` };
  }
}

async function readErrorCode(res: Response): Promise<string> {
  try {
    const body = (await res.json()) as { error?: { code?: string } };
    return body.error?.code ?? `HTTP_${res.status}`;
  } catch {
    return `HTTP_${res.status}`;
  }
}

/**
 * 서버는 ISO-8601 오프셋 표기를 요구한다(api-spec §0-1). toISOString() 은 Z 로 끝나는데
 * 그것도 유효한 오프셋 표기이지만, 서버 허용 범위가 [세션 시작 −5초, 현재 +5초]라
 * **표기가 아니라 값이 맞는지가 중요하다.** 단말 시계가 5초 이상 어긋나면 400 이 난다.
 */
export function toIsoWithOffset(epochMs: number): string {
  const d = new Date(epochMs);
  const offsetMin = -d.getTimezoneOffset();
  const sign = offsetMin >= 0 ? "+" : "-";
  const abs = Math.abs(offsetMin);
  const hh = pad(Math.floor(abs / 60));
  const mm = pad(abs % 60);
  return (
    `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}` +
    `T${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}` +
    `${sign}${hh}:${mm}`
  );
}

function pad(n: number): string {
  return n < 10 ? `0${n}` : String(n);
}
