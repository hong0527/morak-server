import type { CalibrationProfile } from "./calibration.js";
import type { ScoringConfig } from "./config.js";
import type { FrameFeatures, Verdict } from "./types.js";

/**
 * 프레임 특징 → 0~1 점수 → HIT/UNCERTAIN/MISS.
 *
 * 왜 점수를 여기서 만드는가: FaceLandmarker 는 얼굴별 신뢰도 스칼라를 결과로 주지 않는다
 * (faceLandmarks / faceBlendshapes / facialTransformationMatrixes 뿐이다). 그래서
 * ai-pipeline.md §6이 말하는 "신뢰도"를 모델 출력에서 그대로 꺼낼 수 없고, 프레임 특징에서
 * 우리가 합성해야 한다. 이 함수가 그 합성 지점이며 하네스가 바꿔 끼우는 유일한 손잡이다.
 *
 * 상태를 들고 있는 이유: 미검출을 얼마나 믿을지가 "직전에 무엇을 보고 있었는가"에 달려 있다.
 * 저조도에서 고개를 숙여 추적이 끊긴 것과, 사람이 정말 나간 것을 프레임 하나로는 못 가른다.
 * 상태는 로그를 처음부터 접으면 그대로 복원되므로 하네스 재현성은 깨지지 않는다.
 */
export class Scorer {
  private lastPresentAtMs: number | null = null;
  private lastPitchDeg: number | null = null;
  /** 최근 pitch 원시값. 중앙값을 내려고 들고 있다 */
  private pitchWindow: number[] = [];

  constructor(
    private readonly config: ScoringConfig,
    private readonly profile: CalibrationProfile | null,
  ) {}

  reset(): void {
    this.lastPresentAtMs = null;
    this.lastPitchDeg = null;
    this.pitchWindow = [];
  }

  score(f: FrameFeatures): ScoreResult {
    const lowLight = f.luminance < this.config.lowLightLuminance;
    const thetaLo = lowLight ? this.config.thetaLoLowLight : this.config.thetaLo;

    let value: number;
    let reason: ScoreReason;

    if (f.present) {
      value = this.scorePresent(f);
      reason = "DETECTED";
      this.lastPresentAtMs = f.tMs;
      this.lastPitchDeg = this.stabilisePitch(f.pitchDeg);
    } else {
      const lifted = this.scoreAbsent(f, lowLight);
      value = lifted.value;
      reason = lifted.reason;
    }

    const verdict: Verdict =
      value >= this.config.thetaHi ? "HIT" : value >= thetaLo ? "UNCERTAIN" : "MISS";

    return { verdict, value, lowLight, reason };
  }

  /**
   * 얼굴이 잡힌 경우. 고개를 숙였든 아니든 잡혔으면 재실이다 —
   * "고개 숙임 = 재실"을 규칙으로 못 박은 것이 FaceLandmarker 를 고른 이유다(§3).
   * 여기서 걸러내는 것은 각도가 아니라 크기다. 멀리 있는 사진·포스터가 얼굴로 잡히는
   * 유령 검출만 배제한다.
   */
  private scorePresent(f: FrameFeatures): number {
    if (this.profile === null || f.faceSize === null) {
      // 캘리브레이션이 없으면 크기를 판정할 기준이 없다. 잡혔으면 그대로 믿는다.
      return 1;
    }
    const ratio = f.faceSize / this.profile.faceSize;
    const { minFaceSizeRatio, fullFaceSizeRatio, presentBaseScore } = this.config;
    const span = fullFaceSizeRatio - minFaceSizeRatio;
    const fit = span <= 0 ? 1 : clamp01((ratio - minFaceSizeRatio) / span);
    return presentBaseScore + (1 - presentBaseScore) * fit;
  }

  /**
   * 얼굴이 안 잡힌 경우. 0에서 시작해 "이 미검출을 믿기 어려운 사정"만큼 올린다.
   * 올라간 결과가 θ_lo 를 넘으면 UNCERTAIN 이 되어 미검출 카운터가 동결된다.
   */
  private scoreAbsent(f: FrameFeatures, lowLight: boolean): {
    value: number;
    reason: ScoreReason;
  } {
    let value = 0;
    let reason: ScoreReason = "ABSENT";

    if (lowLight) {
      value += this.config.liftLowLight;
      reason = "ABSENT_LOW_LIGHT";
    }

    const sinceLast =
      this.lastPresentAtMs === null ? Infinity : f.tMs - this.lastPresentAtMs;
    if (sinceLast <= this.config.recentTrackMs && this.isHeadDown(this.lastPitchDeg)) {
      value += this.config.liftRecentHeadDown;
      reason = lowLight ? "ABSENT_LOW_LIGHT_HEAD_DOWN" : "ABSENT_HEAD_DOWN";
    }

    return { value: clamp01(value), reason };
  }

  /**
   * 각도만 정리한다. **검출 여부에는 손대지 않는다** — 얼굴이 잡혔다는 사실은 각도가 이상해도
   * 그대로 재실이다(§3). 여기서 걸러낸 결과는 `isHeadDown` 한 곳에만 쓰인다.
   *
   * 원시값을 로그에 남기고 이 정리를 Scorer 에서 하는 이유: 하네스가 로그 위에서 판정을
   * 재계산하므로(README §5) 필터를 나중에 다시 튜닝할 수 있어야 한다. detector 에서 걸러
   * 버리면 로그에 원시 각도가 남지 않아 그 순간 튜닝이 불가능해진다.
   *
   * 중앙값을 **인과적으로**(직전 N개) 낸다. 앞뒤를 보는 3점 중앙값이 이상치 제거는 더 깨끗하지만
   * 미래 프레임을 기다려야 해서 라이브에서 500ms 가 밀린다.
   */
  private stabilisePitch(raw: number | null): number | null {
    if (raw === null || !Number.isFinite(raw)) return null;
    // 크기로 먼저 자른다. 자세 추정이 깨진 프레임은 행렬 자체는 멀쩡해서 이것 말고 단서가 없다.
    if (Math.abs(raw) > this.config.maxPlausiblePitchDeg) return this.lastPitchDeg;

    const window = Math.max(1, this.config.pitchMedianWindow);
    this.pitchWindow.push(raw);
    if (this.pitchWindow.length > window) this.pitchWindow.shift();

    const sorted = [...this.pitchWindow].sort((a, b) => a - b);
    return sorted[sorted.length >> 1] ?? raw;
  }

  /** 캘리브레이션 기준보다 이만큼 더 숙였는가. pitch 는 아래를 볼수록 음수 */
  private isHeadDown(pitchDeg: number | null): boolean {
    if (pitchDeg === null) return false;
    const base = this.profile?.pitchDeg ?? 0;
    return pitchDeg <= base - this.config.headDownPitchDeg;
  }
}

export interface ScoreResult {
  verdict: Verdict;
  value: number;
  lowLight: boolean;
  reason: ScoreReason;
}

export type ScoreReason =
  | "DETECTED"
  | "ABSENT"
  | "ABSENT_LOW_LIGHT"
  | "ABSENT_HEAD_DOWN"
  | "ABSENT_LOW_LIGHT_HEAD_DOWN";

function clamp01(v: number): number {
  return v < 0 ? 0 : v > 1 ? 1 : v;
}
