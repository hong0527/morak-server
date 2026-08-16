import type { CalibrationOutcome, CalibrationProfile } from "../calibration.js";
import type { DetectionConfig } from "../config.js";
import type { DetectorAssets } from "../detector.js";
import type { ScoreResult } from "../scoring.js";
import type { DetectionState, Effect, FrameFeatures } from "../types.js";

/**
 * 메인 스레드와 Worker 사이의 메시지 계약.
 *
 * 추론과 상태기계가 Worker 에 있는 이유: MediaPipe 의 추론 호출(`detect`)은 도는 동안
 * 스레드를 붙잡는다. 메인에서 돌리면 캠 화면이 끊긴다(open-decisions C-2 ①).
 * 네트워크 전송은 반대로 메인에 둔다 — 인증 토큰과 프롬프트 UI 가 거기 있고, Worker 가
 * 서버 계약까지 들면 상태기계의 순수성이 깨져 하네스가 같은 코드를 못 돌린다.
 */

export type MainToWorker =
  | { kind: "INIT"; assets: DetectorAssets; config: DetectionConfig; profile: CalibrationProfile | null }
  | { kind: "FRAME"; bitmap: ImageBitmap; tMs: number }
  /** 캘리브레이션 수집으로 전환. 끝나면 CALIBRATION_DONE 을 돌려주고 IDLE 로 간다 */
  | { kind: "START_CALIBRATION" }
  /** 감지 루프 시작 */
  | { kind: "START_DETECTION" }
  /** 감지 중단(화장실 모드 등). 프레임을 더 보내도 무시한다 */
  | { kind: "STOP" }
  | { kind: "PROMPT_TAP"; tMs: number }
  /** 새로고침 복구 — 세션 저장소에 열린 START 가 있었다 */
  | { kind: "RESTORE_OPEN_ABSENCE" }
  /** 서버가 구간을 대신 닫았다(SS-5). END 를 보내지 않고 상태만 정리한다 */
  | { kind: "SERVER_CLOSED_ABSENCE" }
  /** 프레임별 원시 특징을 메인으로 흘릴지. 하네스 녹화에서만 켠다 */
  | { kind: "SET_FEATURE_TAP"; enabled: boolean };

export type WorkerToMain =
  | { kind: "READY" }
  | { kind: "ERROR"; message: string; fatal: boolean }
  | {
      kind: "TICK";
      tMs: number;
      state: DetectionState;
      score: ScoreResult;
      effects: Effect[];
    }
  | { kind: "CALIBRATION_DONE"; outcome: CalibrationOutcome }
  | { kind: "FEATURES"; features: FrameFeatures; score: ScoreResult };
