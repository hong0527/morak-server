import type { CalibrationConfig } from "./config.js";
import type { FrameFeatures } from "./types.js";

/**
 * 입장 전 캘리브레이션(ai-pipeline.md §6).
 *
 * 매칭 대기 화면에서 8초 동안 평상시 자세를 재서 개인별 기준을 잡는다.
 * 값은 브라우저 로컬에만 두고 서버로 보내지 않는다 — 영상 파생물 무저장 원칙과 정합.
 */
export interface CalibrationProfile {
  /** 기준 얼굴 크기(정규화 대각 길이)의 중앙값 */
  faceSize: number;
  /** 기준 pitch 중앙값(도). 이 사람의 "평상시 고개 각도"다 */
  pitchDeg: number;
  /** 기준 조도 중앙값 */
  luminance: number;
  /** 수집에 성공한 프레임 수 */
  samples: number;
  /** 전체 프레임 중 얼굴이 잡힌 비율. 낮으면 환경이 나쁘다는 신호다 */
  detectionRate: number;
  /** 언제 잰 값인지. 조명이 바뀌면 다시 재야 한다 */
  measuredAtMs: number;
}

export interface CalibrationOutcome {
  profile: CalibrationProfile | null;
  /** 사용자에게 보여줄 안내. 조명이 부족해도 입장을 막지는 않는다 */
  warnings: CalibrationWarning[];
}

export type CalibrationWarning =
  | "TOO_FEW_SAMPLES"
  | "LOW_DETECTION_RATE"
  | "LOW_LIGHT"
  | "FACE_TOO_SMALL";

export class CalibrationCollector {
  private readonly samples: FrameFeatures[] = [];
  private startedAtMs: number | null = null;

  constructor(private readonly config: CalibrationConfig) {}

  /** @returns 수집이 끝났으면 true */
  push(f: FrameFeatures): boolean {
    if (this.startedAtMs === null) this.startedAtMs = f.tMs;
    this.samples.push(f);
    return f.tMs - this.startedAtMs >= this.config.durationMs;
  }

  get elapsedMs(): number {
    if (this.startedAtMs === null || this.samples.length === 0) return 0;
    const last = this.samples[this.samples.length - 1];
    return last ? last.tMs - this.startedAtMs : 0;
  }

  finish(): CalibrationOutcome {
    const warnings: CalibrationWarning[] = [];
    const detected = this.samples.filter((s) => s.present);
    const detectionRate =
      this.samples.length === 0 ? 0 : detected.length / this.samples.length;

    if (detected.length < this.config.minSamples) {
      warnings.push("TOO_FEW_SAMPLES");
      return { profile: null, warnings };
    }
    if (detectionRate < 0.7) warnings.push("LOW_DETECTION_RATE");

    const faceSize = median(detected.map((s) => s.faceSize).filter(isNumber));
    const pitchDeg = median(detected.map((s) => s.pitchDeg).filter(isNumber));
    // 표본이 하나라도 있으면 median 은 값을 준다. 위에서 minSamples 를 이미 통과했다.
    const luminance = median(this.samples.map((s) => s.luminance)) ?? 0.5;

    if (luminance < 0.25) warnings.push("LOW_LIGHT");
    // 얼굴이 화면에서 너무 작으면 이후 크기 적합도 판정이 전부 경계에 몰린다.
    if (faceSize !== null && faceSize < 0.12) warnings.push("FACE_TOO_SMALL");

    const last = this.samples[this.samples.length - 1];
    return {
      profile: {
        faceSize: faceSize ?? 0.25,
        pitchDeg: pitchDeg ?? 0,
        luminance,
        samples: detected.length,
        detectionRate,
        measuredAtMs: last ? last.tMs : 0,
      },
      warnings,
    };
  }
}

const STORAGE_KEY = "molock.calibration.v1";

/**
 * localStorage 에 둔다. 세션이 아니라 기기·자리에 붙는 값이라 세션을 넘겨 재사용하는 것이
 * 맞고, 조명이 바뀌면 다시 재라는 안내로 커버한다.
 */
export function saveCalibration(profile: CalibrationProfile): void {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(profile));
  } catch {
    // 사파리 프라이빗 모드 등에서 저장이 막힐 수 있다. 캘리브레이션 없이도 동작해야 한다.
  }
}

export function loadCalibration(): CalibrationProfile | null {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return null;
    const parsed: unknown = JSON.parse(raw);
    if (typeof parsed !== "object" || parsed === null) return null;
    const p = parsed as Partial<CalibrationProfile>;
    if (typeof p.faceSize !== "number" || typeof p.pitchDeg !== "number") return null;
    return p as CalibrationProfile;
  } catch {
    return null;
  }
}

function isNumber(v: number | null): v is number {
  return typeof v === "number" && Number.isFinite(v);
}

function median(values: number[]): number | null {
  if (values.length === 0) return null;
  const sorted = [...values].sort((a, b) => a - b);
  const mid = sorted.length >> 1;
  if (sorted.length % 2 === 1) return sorted[mid] ?? null;
  const lo = sorted[mid - 1];
  const hi = sorted[mid];
  if (lo === undefined || hi === undefined) return null;
  return (lo + hi) / 2;
}
