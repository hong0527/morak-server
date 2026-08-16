/**
 * clientSeq 와 미전송 큐를 브라우저 저장소에 보존한다.
 *
 * 왜 저장하나(api-spec.md SS-4, ai-pipeline.md §7-2):
 * - clientSeq 를 유실하고 1부터 다시 시작하면 서버가 `UNIQUE(session_id, member_id, client_seq)`
 *   로 중복 처리해 **새 이벤트가 조용히 사라진다.**
 * - 열린 START 를 새로고침으로 잊으면 END 가 영영 안 나가고, 세션 종료 시각까지
 *   자리비움으로 정산된다.
 *
 * ## 저장소를 sessionStorage 에서 localStorage 로 바꾼 이유 (W1)
 *
 * sessionStorage 는 **탭 단위**라 새로고침만 넘긴다. 탭을 닫았다 다시 들어가거나, 새 탭에서
 * 열거나, 브라우저가 재시작하거나, iOS 가 메모리 압박으로 탭을 버리면 전부 비어 있다.
 * 그러면 `nextSeq` 가 1 로 되돌아가고 서버가 전부 409 로 거절하는데 클라이언트는 그것을
 * 성공으로 취급해 **그 세션의 감지가 통째로 꺼진다.** 4시간 세션에서 탭이 닫히는 것은
 * 평범한 일이라 실측으로 재현됐다(P-wiring W1).
 *
 * ## 저장에 의존하지 않는 바닥 — `sessionStartedAtMs`
 *
 * 저장소를 바꿔도 기기·브라우저를 바꾸면 여전히 비어 있다. 그래서 저장값과 무관하게
 * **번호의 바닥을 "세션 시작으로부터 경과한 초"로 잡는다.**
 *
 * 이것이 충돌하지 않는 근거는 5초 페이싱이다. 앞선 저장소가 T0 에 시작해 바닥 F0 을 잡았다면
 * 시각 T 까지 쓸 수 있는 번호는 많아야 `F0 + (T-T0)/5` 다. 뒤에 만들어진 저장소의 바닥은
 * `F0 + (T-T0)` 이라 항상 그보다 크다. 즉 **나중에 만들어진 저장소일수록 기준점이 커서
 * 앞 탭이 쓴 번호와 겹치지 않는다.** 서버 계약은 전혀 건드리지 않는다
 * (`clientSeq` 는 `long @PositiveOrZero` 다).
 *
 * `sessionStartedAtMs` 를 주지 않으면 바닥이 1 이라 저장소에만 의존한다. 같은 브라우저의
 * 탭 문제는 그래도 막히지만 기기를 바꾸면 다시 깨진다. **주는 것을 강하게 권한다.**
 */

/** localStorage / sessionStorage 가 만족하는 최소 모양. 테스트가 갈아끼운다 */
export interface StorageLike {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
  removeItem(key: string): void;
}

export interface PendingEvent {
  clientSeq: number;
  type: "START" | "END";
  /** ISO8601, 오프셋 포함 */
  occurredAt: string;
  /**
   * 이 번호로 실제 전송을 시도한 횟수. 409 DUPLICATE 가 왔을 때
   * "내 재전송이 흡수된 것"과 "내가 보낸 적 없는 번호인데 이미 있다"를 가르는 값이다.
   * 새로고침을 넘겨야 판단이 옳으므로 저장한다.
   */
  attempts: number;
}

export interface SeqStoreOptions {
  sessionId: number | string;
  /** 같은 브라우저를 여러 회원이 쓰는 경우 키를 가른다 */
  memberId?: number | string;
  /**
   * 세션 시작 시각(epoch ms). 번호 발급의 **바닥**이 된다. 없으면 저장소에만 의존한다.
   * SS-1 이 내리는 세션 시작 시각을 그대로 넘기면 된다.
   */
  sessionStartedAtMs?: number;
  /** 테스트용. 기본은 Date.now */
  now?: () => number;
  /** 테스트용. 기본은 localStorage → sessionStorage 순으로 고른다 */
  storage?: StorageLike | null;
}

interface StoredState {
  nextSeq: number;
  pending: PendingEvent[];
  /** 열린 START 의 clientSeq. 없으면 null */
  openStartSeq: number | null;
}

const EMPTY: StoredState = { nextSeq: 1, pending: [], openStartSeq: null };

export class AbsenceSeqStore {
  private readonly key: string;
  private readonly storage: StorageLike | null;
  private readonly now: () => number;
  private readonly sessionStartedAtMs: number | undefined;
  private state: StoredState;

  constructor(options: SeqStoreOptions | number | string) {
    const opts: SeqStoreOptions =
      typeof options === "object" ? options : { sessionId: options };

    this.now = opts.now ?? (() => Date.now());
    this.sessionStartedAtMs = opts.sessionStartedAtMs;
    this.storage = opts.storage !== undefined ? opts.storage : pickStorage();
    this.key =
      `molock.absence.v1.${opts.sessionId}` +
      (opts.memberId === undefined ? "" : `.${opts.memberId}`);

    const stored = this.read();
    this.state = { ...stored, nextSeq: Math.max(stored.nextSeq, this.seqFloor()) };
  }

  /** 새로고침 전에 열려 있던 START 가 있는가 */
  get hasOpenStart(): boolean {
    return this.state.openStartSeq !== null;
  }

  get pending(): PendingEvent[] {
    return [...this.state.pending];
  }

  /** 다음에 발급될 번호. 진단용 */
  get nextSeq(): number {
    return this.state.nextSeq;
  }

  /**
   * 큐 끝에 붙이고 번호를 매긴다. 번호는 여기서만 발급한다.
   *
   * 발급 직전에 저장소를 다시 읽는 이유: 같은 세션을 두 탭이 동시에 열면 두 인스턴스가
   * 각자의 메모리 상태로 번호를 매겨 충돌한다. 저장값과 바닥을 함께 취해 max 를 쓰면
   * 뒤에 매기는 쪽이 항상 앞을 넘어선다.
   */
  enqueue(type: "START" | "END", occurredAt: string): PendingEvent {
    const seq = Math.max(this.state.nextSeq, this.read().nextSeq, this.seqFloor());
    const event: PendingEvent = { clientSeq: seq, type, occurredAt, attempts: 0 };
    this.state.nextSeq = seq + 1;
    this.state.pending.push(event);
    if (type === "START") this.state.openStartSeq = event.clientSeq;
    this.write();
    return event;
  }

  /** 전송을 한 번 시도했다. 409 판정의 근거라 저장까지 한다 */
  markAttempt(clientSeq: number): void {
    const event = this.state.pending.find((e) => e.clientSeq === clientSeq);
    if (!event) return;
    event.attempts += 1;
    this.write();
  }

  /**
   * 큐에 남은 이벤트 전부에 새 번호를 다시 매긴다.
   *
   * 쓰는 자리는 하나다 — **보낸 적 없는 번호에 409 가 온 경우**(다른 탭이 그 번호를 이미 썼다).
   * 그 이벤트는 서버에 기록되지 않았으므로 버리면 자리비움이 사라진다. 번호만 바꿔 다시 보낸다.
   *
   * 큐 전체를 다시 매기는 이유는 순서 때문이다. 앞의 START 만 큰 번호로 밀면 뒤의 END 가
   * 더 작은 번호가 되어 읽는 사람이 헷갈린다(서버는 도착 순서로 짝을 짓지만 번호가 역전된
   * 기록을 남길 이유가 없다). FIFO 순서 그대로 연속 번호를 준다.
   *
   * @param floor 이 번호 이상에서 다시 시작한다
   */
  reissuePending(floor: number): PendingEvent[] {
    let seq = Math.max(floor, this.seqFloor());
    let openStartSeq: number | null = null;
    for (const event of this.state.pending) {
      event.clientSeq = seq;
      event.attempts = 0;
      if (event.type === "START") openStartSeq = seq;
      seq += 1;
    }
    this.state.nextSeq = seq;
    // 큐에 START 가 없으면 열린 구간 표시는 그대로 둔다 — 이미 서버에 닿은 START 일 수 있다.
    if (openStartSeq !== null) this.state.openStartSeq = openStartSeq;
    this.write();
    return this.pending;
  }

  /**
   * 전송이 끝난(성공 또는 중복 확인된) 이벤트를 큐에서 뺀다.
   * openStartSeq 는 여기서 건드리지 않는다 — END 가 서버에 닿았을 때만 clearOpenStart 로 지운다.
   */
  complete(clientSeq: number): void {
    this.state.pending = this.state.pending.filter((e) => e.clientSeq !== clientSeq);
    this.write();
  }

  /** END 가 서버에 도달했다. 이제 열린 구간이 없다 */
  clearOpenStart(): void {
    this.state.openStartSeq = null;
    this.write();
  }

  /**
   * 아직 보내지 않은 START 를 버린다. 화장실 모드 시작(SS-5)이 자리비움 구간을 서버에서
   * 닫아 버리는 순간에 쓴다 — 이때 뒤늦게 START 가 도착하면 PAUSED 구간 안에 짝 없는
   * 자리비움이 열려 복귀 후 10분짜리로 판정된다. 큐에 남은 END 는 그대로 둔다.
   * 짝 없는 END 는 서버가 `closedAbsenceSeconds=null` 로 흘려보내 무해하다.
   */
  dropPendingStarts(): void {
    this.state.pending = this.state.pending.filter((e) => e.type !== "START");
    this.state.openStartSeq = null;
    this.write();
  }

  /** 서버가 상태를 종결시켰다(퇴출·세션 종료). 남은 큐를 버린다 */
  abandon(): void {
    this.state.pending = [];
    this.state.openStartSeq = null;
    this.write();
  }

  /**
   * 세션 시작으로부터 흐른 초. 번호가 저장소 없이도 단조 증가하게 만드는 바닥이다.
   * `sessionStartedAtMs` 가 없으면 1(=바닥 없음)이다.
   */
  private seqFloor(): number {
    if (this.sessionStartedAtMs === undefined) return 1;
    const elapsedSec = Math.floor((this.now() - this.sessionStartedAtMs) / 1000);
    return elapsedSec > 1 ? elapsedSec : 1;
  }

  private read(): StoredState {
    if (!this.storage) return { ...EMPTY, pending: [] };
    try {
      const raw = this.storage.getItem(this.key);
      if (!raw) return { ...EMPTY, pending: [] };
      const parsed: unknown = JSON.parse(raw);
      if (typeof parsed !== "object" || parsed === null) return { ...EMPTY, pending: [] };
      const s = parsed as Partial<StoredState>;
      return {
        nextSeq: typeof s.nextSeq === "number" && s.nextSeq > 0 ? s.nextSeq : 1,
        pending: Array.isArray(s.pending) ? s.pending.map(normalise) : [],
        openStartSeq: typeof s.openStartSeq === "number" ? s.openStartSeq : null,
      };
    } catch {
      return { ...EMPTY, pending: [] };
    }
  }

  private write(): void {
    if (!this.storage) return;
    try {
      this.storage.setItem(this.key, JSON.stringify(this.state));
    } catch {
      // 저장이 막혀도 메모리 상태로는 계속 돈다. 복구만 포기한다.
    }
  }
}

/** 예전 판이 저장한 큐에는 attempts 가 없다. 0 으로 읽는다 */
function normalise(e: PendingEvent): PendingEvent {
  return { ...e, attempts: typeof e.attempts === "number" ? e.attempts : 0 };
}

/**
 * localStorage 를 먼저 고른다. Safari 사생활 보호 모드처럼 접근 자체가 던지는 환경이 있어
 * 실제로 써 보고 판단한다. 둘 다 못 쓰면 메모리로만 돈다(새로고침 복구를 포기한다).
 */
function pickStorage(): StorageLike | null {
  for (const get of [() => localStorage, () => sessionStorage]) {
    try {
      const s = get();
      const probe = "molock.probe";
      s.setItem(probe, "1");
      s.removeItem(probe);
      return s;
    } catch {
      // 다음 후보로
    }
  }
  return null;
}
