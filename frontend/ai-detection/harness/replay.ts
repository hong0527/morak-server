import type { CalibrationProfile } from "../src/calibration.js";
import { DEFAULT_CONFIG, mergeConfig, type DeepPartial, type DetectionConfig } from "../src/config.js";
import { Scorer } from "../src/scoring.js";
import { AbsenceStateMachine } from "../src/state-machine.js";
import type { AbsenceReport } from "../src/server-rule.js";
import type { DetectionState, Verdict } from "../src/types.js";
import type { InferenceLog } from "./log-format.js";

/**
 * 로그 위에서 판정을 재계산한다. 추론은 하지 않는다.
 *
 * 실제 서비스와 **같은 Scorer·같은 AbsenceStateMachine** 을 돌린다. 하네스용으로 따로
 * 구현하면 그 순간 측정한 대상이 서비스가 아니게 된다.
 *
 * 여기서 재현하지 않는 것은 프롬프트 탭 하나뿐이다. 녹화 영상에는 탭이 없고, 실제로도
 * 자리를 비운 사람은 탭할 수 없다. 즉 이 재계산은 **무응답 경로**만 돈다 — 확인 프롬프트가
 * 오탐만 걸러내고 정탐은 그대로 통과시킨다는 §5의 주장과 정확히 같은 조건이다.
 */

export interface ReplayOptions {
  /** 기본값 위에 얹을 부분 설정. 임계값 스윕이 이걸 쓴다 */
  overrides?: DeepPartial<DetectionConfig>;
  /**
   * 점수 계산에 쓸 캘리브레이션. 지정하지 않으면 로그에 기록된 값을 쓴다.
   * 캘리브레이션 없이 어떻게 되는지 보려면 null 을 명시한다.
   */
  calibration?: CalibrationProfile | null;
}

export interface ReplayTrace {
  tMs: number;
  verdict: Verdict;
  score: number;
  lowLight: boolean;
  state: DetectionState;
}

export interface ReplayResult {
  config: DetectionConfig;
  /** 서버로 나갔을 이벤트. 이것이 판정의 산출물이다 */
  reports: AbsenceReport[];
  /** 프롬프트가 뜬 횟수. 오탐이 걸러진 자리를 세는 데 쓴다 */
  promptCount: number;
  trace: ReplayTrace[];
  durationMs: number;
}

export function replay(log: InferenceLog, options: ReplayOptions = {}): ReplayResult {
  const config = mergeConfig(DEFAULT_CONFIG, options.overrides ?? {});
  const calibration =
    options.calibration !== undefined ? options.calibration : log.calibration;

  const scorer = new Scorer(config.scoring, calibration);
  const machine = new AbsenceStateMachine(config.stateMachine);

  const reports: AbsenceReport[] = [];
  const trace: ReplayTrace[] = [];
  let promptCount = 0;

  for (const features of log.frames) {
    const score = scorer.score(features);
    const effects = machine.onFrame(features.tMs, score.verdict, score.lowLight);

    for (const effect of effects) {
      if (effect.kind === "ABSENCE_START") {
        reports.push({ type: "START", occurredAtMs: effect.occurredAtMs });
      } else if (effect.kind === "ABSENCE_END") {
        reports.push({ type: "END", occurredAtMs: effect.occurredAtMs });
      } else if (effect.kind === "SHOW_PROMPT") {
        promptCount += 1;
      }
    }

    trace.push({
      tMs: features.tMs,
      verdict: score.verdict,
      score: score.value,
      lowLight: score.lowLight,
      state: machine.currentState,
    });
  }

  return { config, reports, promptCount, trace, durationMs: log.durationMs };
}

/**
 * 임계값 스윕. 같은 로그를 여러 설정으로 돌린다.
 * 추론이 없으므로 조합 수십 개도 몇 초다 — 그것이 이 구조를 택한 이유다.
 */
export function sweep(
  log: InferenceLog,
  candidates: DeepPartial<DetectionConfig>[],
  calibration?: CalibrationProfile | null,
): { overrides: DeepPartial<DetectionConfig>; result: ReplayResult }[] {
  return candidates.map((overrides) => ({
    overrides,
    result: replay(log, calibration !== undefined ? { overrides, calibration } : { overrides }),
  }));
}
