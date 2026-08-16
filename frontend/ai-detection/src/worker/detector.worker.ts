import { CalibrationCollector, type CalibrationProfile } from "../calibration.js";
import { DEFAULT_CONFIG, type DetectionConfig } from "../config.js";
import { FaceDetectorRunner } from "../detector.js";
import { Scorer } from "../scoring.js";
import { AbsenceStateMachine } from "../state-machine.js";
import type { MainToWorker, WorkerToMain } from "./protocol.js";

/**
 * Worker 엔트리. 추론 → 3값 판정 → 상태기계까지가 여기서 돈다.
 *
 * 번들러에 묶이지 않도록 Worker 인스턴스 생성은 앱이 한다. README 의 "붙이는 법" 참조.
 */

interface WorkerContext {
  onmessage: ((e: MessageEvent<MainToWorker>) => void) | null;
  postMessage(message: WorkerToMain): void;
}

const ctx = globalThis as unknown as WorkerContext;

type Mode = "IDLE" | "CALIBRATING" | "DETECTING";

let config: DetectionConfig = DEFAULT_CONFIG;
let runner: FaceDetectorRunner | null = null;
let scorer: Scorer | null = null;
let machine: AbsenceStateMachine | null = null;
let calibrator: CalibrationCollector | null = null;
let mode: Mode = "IDLE";
let featureTap = false;
/** 추론이 밀리는 동안 도착한 프레임은 버린다. 큐에 쌓으면 지연만 누적된다 */
let busy = false;

ctx.onmessage = (event: MessageEvent<MainToWorker>): void => {
  const msg = event.data;
  switch (msg.kind) {
    case "INIT":
      void init(msg.assets, msg.config, msg.profile);
      return;
    case "FRAME":
      void onFrame(msg.bitmap, msg.tMs);
      return;
    case "START_CALIBRATION":
      calibrator = new CalibrationCollector(config.calibration);
      mode = "CALIBRATING";
      return;
    case "START_DETECTION":
      mode = "DETECTING";
      return;
    case "STOP":
      mode = "IDLE";
      return;
    case "PROMPT_TAP": {
      if (!machine) return;
      const effects = machine.onPromptTap(msg.tMs);
      if (effects.length > 0) {
        ctx.postMessage({
          kind: "TICK",
          tMs: msg.tMs,
          state: machine.currentState,
          score: { verdict: "UNCERTAIN", value: 0, lowLight: false, reason: "ABSENT" },
          effects,
        });
      }
      return;
    }
    case "RESTORE_OPEN_ABSENCE":
      machine?.restoreOpenAbsence();
      return;
    case "SERVER_CLOSED_ABSENCE":
      machine?.absorbServerSideClose();
      return;
    case "SET_FEATURE_TAP":
      featureTap = msg.enabled;
      return;
  }
};

async function init(
  assets: { wasmBasePath: string; modelAssetPath: string },
  cfg: DetectionConfig,
  profile: CalibrationProfile | null,
): Promise<void> {
  try {
    config = cfg;
    runner = new FaceDetectorRunner(cfg.scoring);
    await runner.load(assets);
    scorer = new Scorer(cfg.scoring, profile);
    machine = new AbsenceStateMachine(cfg.stateMachine);
    ctx.postMessage({ kind: "READY" });
  } catch (e) {
    ctx.postMessage({
      kind: "ERROR",
      message: e instanceof Error ? e.message : String(e),
      fatal: true,
    });
  }
}

async function onFrame(bitmap: ImageBitmap, tMs: number): Promise<void> {
  if (mode === "IDLE" || !runner || !runner.loaded || !scorer || !machine || busy) {
    bitmap.close();
    return;
  }
  busy = true;
  try {
    const features = runner.detect(bitmap, tMs);

    if (mode === "CALIBRATING") {
      if (featureTap) {
        ctx.postMessage({ kind: "FEATURES", features, score: scorer.score(features) });
      }
      const done = calibrator?.push(features) ?? true;
      if (done && calibrator) {
        const outcome = calibrator.finish();
        calibrator = null;
        mode = "IDLE";
        // 새 기준으로 점수기를 다시 만든다.
        scorer = new Scorer(config.scoring, outcome.profile);
        ctx.postMessage({ kind: "CALIBRATION_DONE", outcome });
      }
      return;
    }

    const score = scorer.score(features);
    if (featureTap) ctx.postMessage({ kind: "FEATURES", features, score });

    const effects = machine.onFrame(features.tMs, score.verdict, score.lowLight);
    ctx.postMessage({
      kind: "TICK",
      tMs: features.tMs,
      state: machine.currentState,
      score,
      effects,
    });
  } catch (e) {
    ctx.postMessage({
      kind: "ERROR",
      message: e instanceof Error ? e.message : String(e),
      fatal: false,
    });
  } finally {
    bitmap.close();
    busy = false;
  }
}
