import type { CalibrationOutcome } from "../calibration.js";
import { loadCalibration, saveCalibration } from "../calibration.js";
import { DEFAULT_CONFIG, type DetectionConfig } from "../config.js";
import type { DetectorAssets } from "../detector.js";
import { VideoFrameSource, type FrameSource } from "../frame-source.js";
import type {
  AbsenceEventResponse,
  DetectionState,
  Effect,
  FrameFeatures,
} from "../types.js";
import type { MainToWorker, WorkerToMain } from "../worker/protocol.js";
import { AbsenceEventSender, type AbsenceTransport } from "./sender.js";
import { AbsenceSeqStore } from "./seq-store.js";
import type { ScoreResult } from "../scoring.js";

/**
 * 메인 스레드 오케스트레이터. 프레임 펌프 · Worker · 프롬프트 UI · 전송 큐를 잇는다.
 *
 * UI 는 콜백으로만 다룬다. 프레임워크를 고르지 않기 위해서다 — 이 폴더의 코드는 담당자의
 * 프론트 프로젝트에 그대로 옮겨 붙일 물건이다.
 */

export interface ControllerHooks {
  /** "자리에 계신가요?" 프롬프트를 띄운다. 탭하면 tapPrompt() 를 부른다 */
  onShowPrompt(): void;
  onHidePrompt(): void;
  /** 3회 연속 미해소 — 카메라 각도·조명 조정 안내 */
  onShowAdjustHint?(): void;
  /**
   * 서버가 경고·퇴출을 실어 보냈다. **SS-4 응답과 SS-5(화장실 시작) 응답이 모두 여기로 온다** —
   * 경고를 그리는 자리를 하나로 모으기 위해서다(W6). SS-5 쪽은 `onRestroomStarted(응답)` 에
   * 응답을 넘겨야 전달된다.
   */
  onServerVerdict?(response: AbsenceEventResponse): void;
  /** 더 보낼 수 없는 상태(퇴출·세션 종료 등). 감지를 멈춘다 */
  onTerminal?(code: string): void;
  /**
   * 토큰이 만료돼 전송이 멈췄다(401). **감지는 계속 돌고 이벤트는 큐에 쌓인다.**
   * 앱이 다시 로그인시킨 뒤 `resumeAfterReauth()` 를 부르면 쌓인 것이 그대로 나간다.
   * 부르지 않으면 세션이 끝날 때까지 하나도 나가지 않는다.
   */
  onAuthExpired?(code: string): void;
  onError?(message: string, fatal: boolean): void;
  /** 디버그·개발용 */
  onTick?(info: { tMs: number; state: DetectionState; score: ScoreResult }): void;
  onFeatures?(features: FrameFeatures, score: ScoreResult): void;
}

export interface ControllerOptions {
  /**
   * 이미 LiveKit 로컬 트랙이 붙어 있는 `<video>`. 새로 getUserMedia 를 부르지 않는다
   * (open-decisions C-2 ⑦).
   */
  video: HTMLVideoElement;
  /** Worker 인스턴스를 만드는 함수. 번들러마다 문법이 달라 앱이 준다 */
  createWorker: () => Worker;
  sessionId: number | string;
  /**
   * 세션 시작 시각(epoch ms). **강하게 권한다** — 이것이 있어야 탭을 닫았다 다시 들어오거나
   * 기기를 바꿔도 `clientSeq` 가 앞선 실행과 겹치지 않는다(W1). 없으면 저장소에만 의존해
   * 기기·브라우저를 바꾸는 경우 그 세션의 이벤트가 전부 조용히 사라질 수 있다.
   * SS-1 이 내리는 세션 시작 시각을 그대로 넣으면 된다.
   */
  sessionStartedAtMs?: number;
  /** 같은 브라우저를 여러 회원이 쓰는 경우 저장소 키를 가른다 */
  memberId?: number | string;
  assets: DetectorAssets;
  transport: AbsenceTransport;
  hooks: ControllerHooks;
  config?: DetectionConfig;
  /** 추론에 넘길 프레임 폭 상한 */
  maxFrameWidth?: number;
  /** 하네스 녹화처럼 프레임별 원시 특징이 필요한 경우 */
  emitFeatures?: boolean;
}

/**
 * SS-5 응답에서 우리가 쓰는 부분만. 전체 타입을 여기 복제하지 않는 이유는 감지 모듈이
 * 세션 API 전체를 알 필요가 없기 때문이다 — 구조적으로 맞으면 그대로 넘길 수 있다.
 */
export interface PauseStartLike {
  warningIssued: boolean;
  warningCount: number;
  closedAbsenceSeconds: number | null;
}

/**
 * SS-5 응답을 SS-4 응답 모양으로 옮긴다. 화면이 경고를 그리는 코드를 두 벌 갖지 않게 하려는
 * 것이 목적이라 필드를 억지로 채우지 않는다 — 퇴출·포인트는 SS-5 에 없는 정보다.
 *
 * SS-5 로는 퇴출이 나지 않는다. 3회째는 화장실 모드 자체가 409 로 막혀 시작되지 않는다.
 */
export function fromPauseStart(r: PauseStartLike): AbsenceEventResponse {
  return {
    accepted: true,
    warningCount: r.warningCount,
    evicted: false,
    evictionId: null,
    pointDelta: 0,
    closedAbsenceSeconds: r.closedAbsenceSeconds,
  };
}

export class AbsenceDetectionController {
  private readonly config: DetectionConfig;
  private readonly store: AbsenceSeqStore;
  private readonly sender: AbsenceEventSender;
  private worker: Worker | null = null;
  private source: FrameSource | null = null;
  private ready = false;
  private disposed = false;
  private calibrationResolve: ((outcome: CalibrationOutcome) => void) | null = null;

  constructor(private readonly options: ControllerOptions) {
    this.config = options.config ?? DEFAULT_CONFIG;
    this.store = new AbsenceSeqStore({
      sessionId: options.sessionId,
      memberId: options.memberId,
      sessionStartedAtMs: options.sessionStartedAtMs,
    });
    this.sender = new AbsenceEventSender(
      this.store,
      options.transport,
      this.config.transport,
      {
        onAccepted: (response) => options.hooks.onServerVerdict?.(response),
        onTerminal: (code) => {
          this.stop();
          options.hooks.onTerminal?.(code);
        },
        onInvalid: (code) =>
          options.hooks.onError?.(`SS-4 거절: ${code} (단말 시계 확인 필요)`, false),
        // 감지는 멈추지 않는다. 이벤트는 큐에 쌓이고 resumeAfterReauth() 가 풀어 준다.
        onUnauthorized: (code) => options.hooks.onAuthExpired?.(code),
        onUnexpectedDuplicate: (event, reissuedAs) =>
          options.hooks.onError?.(
            `clientSeq ${event.clientSeq} 가 서버에 이미 있다 — 다른 탭·기기가 쓴 번호다. ` +
              `${reissuedAs ?? "?"} 로 다시 매겨 보낸다. ` +
              `sessionStartedAtMs 를 넘기지 않았다면 그것이 원인이다`,
            false,
          ),
      },
    );
  }

  /** Worker 를 띄우고 모델을 로드한다. 매칭 대기 화면에서 미리 부른다 */
  async init(): Promise<void> {
    const worker = this.options.createWorker();
    this.worker = worker;
    worker.onmessage = (e: MessageEvent<WorkerToMain>) => this.onWorkerMessage(e.data);

    const readyPromise = new Promise<void>((resolve, reject) => {
      const timer = setTimeout(
        () => reject(new Error("모델 로딩이 30초 안에 끝나지 않았다")),
        30_000,
      );
      this.readyResolve = () => {
        clearTimeout(timer);
        resolve();
      };
      this.readyReject = (message: string) => {
        clearTimeout(timer);
        reject(new Error(message));
      };
    });

    this.post({
      kind: "INIT",
      assets: this.options.assets,
      config: this.config,
      profile: loadCalibration(),
    });
    if (this.options.emitFeatures) {
      this.post({ kind: "SET_FEATURE_TAP", enabled: true });
    }
    await readyPromise;
  }

  private readyResolve: (() => void) | null = null;
  private readyReject: ((message: string) => void) | null = null;

  /**
   * 캘리브레이션(8초). **세션 입장 후, start() 직전에 부른다.**
   *
   * 대기 화면에서 부를 수 없다 — 이 클래스는 LiveKit 로컬 트랙이 이미 붙어 있는 `<video>` 를
   * 받아 쓰는데(`ControllerOptions.video`) 대기 화면에는 프리뷰가 없어 넘길 엘리먼트가 없다.
   * 대신 그 8초 동안은 감지가 돌지 않는다.
   *
   * 조명이 부족하면 warnings 로 알리되 세션 진행을 막지는 않는다(ai-pipeline.md §6).
   */
  async calibrate(): Promise<CalibrationOutcome> {
    this.requireReady();
    this.startFrames();
    this.post({ kind: "START_CALIBRATION" });
    const outcome = await new Promise<CalibrationOutcome>((resolve) => {
      this.calibrationResolve = resolve;
    });
    this.stopFrames();
    if (outcome.profile) saveCalibration(outcome.profile);
    return outcome;
  }

  /** 세션 입장 후 감지 시작 */
  start(): void {
    this.requireReady();

    // 새로고침으로 상태기계는 사라졌지만 열린 START 는 세션 저장소에 남아 있다.
    // 복원하지 않으면 END 가 영영 안 나가고 세션 종료 시각까지 자리비움으로 정산된다.
    if (this.store.hasOpenStart) {
      this.post({ kind: "RESTORE_OPEN_ABSENCE" });
    }

    this.sender.resume();
    this.post({ kind: "START_DETECTION" });
    this.startFrames();
  }

  /** 감지를 멈춘다. 열린 START 는 저장소에 남아 다음 start() 에서 복원된다 */
  stop(): void {
    this.stopFrames();
    this.post({ kind: "STOP" });
  }

  /** 프롬프트를 탭했다 */
  tapPrompt(): void {
    this.post({ kind: "PROMPT_TAP", tMs: Date.now() });
  }

  /**
   * SS-5(화장실 모드)가 **200 으로 성공한 뒤에** 부른다.
   *
   * 서버가 그 시각으로 열린 자리비움 구간을 대신 닫으므로 클라이언트는 END 를 보내지 않는다.
   * 아직 보내지 않은 START 도 버린다 — PAUSED 구간 안에 뒤늦게 열리면 복귀 후 10분짜리
   * 자리비움으로 판정된다.
   *
   * **이것을 부르지 않으면 화장실에 있던 시간이 그대로 자리비움으로 판정돼 경고가 붙는다.**
   * 카메라를 꺼도 `<video>` 가 검은 프레임을 계속 흘리면 상태기계는 자리비움을 연다.
   * 실측으로 재현됐다(P-wiring W4). SS-5 성공 직후에 반드시 부른다.
   *
   * @param response SS-5 응답. 넘기면 그 안의 경고가 `onServerVerdict` 로 전달된다(W6).
   *   Pause 시작 시 열린 구간이 마감되면서 경고가 붙을 수 있는데, 그 사실은 SS-5 응답에만
   *   실린다. 넘기지 않으면 사용자는 경고가 는 것을 모르고 다음 경고에 퇴출된다.
   */
  onRestroomStarted(response?: PauseStartLike): void {
    this.stopFrames();
    this.post({ kind: "STOP" });
    this.post({ kind: "SERVER_CLOSED_ABSENCE" });
    this.store.dropPendingStarts();
    if (response?.warningIssued) {
      this.options.hooks.onServerVerdict?.(fromPauseStart(response));
    }
  }

  /**
   * 401 로 전송이 멈춘 뒤 새 토큰을 받았을 때 부른다. 그동안 쌓인 이벤트가 그대로 나간다.
   *
   * `transport` 가 헤더를 매번 다시 읽으므로(`getHeaders` 가 호출 시점에 불린다) 새 토큰은
   * 앱이 자기 저장소를 갱신하는 것으로 충분하다. 여기서는 큐만 다시 흘린다.
   */
  resumeAfterReauth(): void {
    this.sender.resume();
  }

  /** 전송이 401 로 멈춰 있는가. 화면이 배지를 띄울 근거 */
  get isSendingPaused(): boolean {
    return this.sender.isPaused;
  }

  /** SS-6(복귀) 이후 감지를 재개한다 */
  onRestroomEnded(): void {
    this.post({ kind: "START_DETECTION" });
    this.startFrames();
  }

  dispose(): void {
    this.disposed = true;
    this.stopFrames();
    this.sender.stop();
    this.worker?.terminate();
    this.worker = null;
  }

  private startFrames(): void {
    if (this.source?.running) return;
    this.source = new VideoFrameSource(this.options.video, {
      hz: this.config.stateMachine.inferenceHz,
      clock: "wall",
      maxWidth: this.options.maxFrameWidth ?? 640,
    });
    this.source.start(({ bitmap, tMs }) => {
      if (this.disposed || !this.worker) {
        bitmap.close();
        return;
      }
      this.worker.postMessage({ kind: "FRAME", bitmap, tMs } satisfies MainToWorker, [
        bitmap,
      ]);
    });
  }

  private stopFrames(): void {
    this.source?.stop();
    this.source = null;
  }

  private onWorkerMessage(msg: WorkerToMain): void {
    switch (msg.kind) {
      case "READY":
        this.ready = true;
        this.readyResolve?.();
        return;
      case "ERROR":
        if (msg.fatal && !this.ready) this.readyReject?.(msg.message);
        this.options.hooks.onError?.(msg.message, msg.fatal);
        return;
      case "CALIBRATION_DONE":
        this.calibrationResolve?.(msg.outcome);
        this.calibrationResolve = null;
        return;
      case "FEATURES":
        this.options.hooks.onFeatures?.(msg.features, msg.score);
        return;
      case "TICK":
        this.options.hooks.onTick?.({ tMs: msg.tMs, state: msg.state, score: msg.score });
        for (const effect of msg.effects) this.applyEffect(effect);
        return;
    }
  }

  private applyEffect(effect: Effect): void {
    switch (effect.kind) {
      case "SHOW_PROMPT":
        this.options.hooks.onShowPrompt();
        return;
      case "HIDE_PROMPT":
        this.options.hooks.onHidePrompt();
        return;
      case "SHOW_ADJUST_HINT":
        this.options.hooks.onShowAdjustHint?.();
        return;
      case "ABSENCE_START":
        this.sender.enqueue("START", effect.occurredAtMs);
        return;
      case "ABSENCE_END":
        this.sender.enqueue("END", effect.occurredAtMs);
        return;
      case "STATE":
        return;
    }
  }

  private post(msg: MainToWorker): void {
    this.worker?.postMessage(msg);
  }

  private requireReady(): void {
    if (!this.ready) throw new Error("init() 이 끝나기 전에는 쓸 수 없다");
  }
}
