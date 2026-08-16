import type { CalibrationProfile } from "../src/calibration.js";
import type { FrameFeatures } from "../src/types.js";

/**
 * 추론 로그의 스키마.
 *
 * 하네스의 전제는 **추론과 판정의 분리**다(ai-pipeline.md §8). 영상마다 추론은 한 번만
 * 돌려 이 로그를 남기고, 임계값 튜닝은 로그 위에서 재계산한다. 재녹화·재추론 없이 몇 초
 * 만에 결과가 나와야 튜닝이 성립한다.
 *
 * 그래서 로그에는 판정 결과가 아니라 **원시 특징**을 남긴다. 임계값뿐 아니라 점수 함수
 * 자체(scoring.ts)를 바꿔도 같은 로그로 다시 돌릴 수 있어야 하기 때문이다.
 * 판정값(verdict)이나 상태를 로그에 굳히면 그 순간 로그가 특정 임계값에 묶인다.
 */
export interface InferenceLog {
  version: 1;
  /** 어느 영상인가 */
  clipId: string;
  /** 언제 추론했나 */
  recordedAt: string;
  /** 어떤 모델·설정으로 추론했나. 모델을 바꾸면 재추론이 필요하다 */
  model: {
    modelAssetPath: string;
    /** 추론 시점의 모델 자체 임계. 이 값보다 높은 임계는 로그로 재현할 수 없다 */
    modelDetectionConfidence: number;
    inferenceHz: number;
  };
  /** 이 클립을 찍을 때 쓴 캘리브레이션. 없으면 null */
  calibration: CalibrationProfile | null;
  /** 영상 길이(ms) */
  durationMs: number;
  frames: FrameFeatures[];
}

/**
 * 정답 라벨. 녹화 중 스톱워치로 이석 시각만 찍는 방식이라 인당 정리 10분이면 끝난다
 * (ai-pipeline.md §8).
 *
 * 시각은 영상 재생 위치(ms) 기준이다. 추론 로그의 tMs 와 같은 시계여야 대조가 성립한다.
 */
export interface GroundTruth {
  version: 1;
  clipId: string;
  /** 실제로 자리를 비운 구간 */
  absences: { startMs: number; endMs: number; note?: string }[];
  /** 촬영 조건. 축을 최소 3회씩 덮었는지 세는 데 쓴다 */
  conditions?: {
    device?: string;
    lighting?: string;
    glasses?: boolean;
    posture?: string;
  };
  durationMs: number;
}

export function isInferenceLog(v: unknown): v is InferenceLog {
  if (typeof v !== "object" || v === null) return false;
  const log = v as Partial<InferenceLog>;
  return log.version === 1 && Array.isArray(log.frames) && typeof log.clipId === "string";
}

export function isGroundTruth(v: unknown): v is GroundTruth {
  if (typeof v !== "object" || v === null) return false;
  const gt = v as Partial<GroundTruth>;
  return gt.version === 1 && Array.isArray(gt.absences) && typeof gt.clipId === "string";
}
