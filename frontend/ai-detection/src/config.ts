/**
 * 튜닝 파라미터. 전부 ai-pipeline.md §11이 "설계 초기값"이라고 못 박은 값이고,
 * §8의 측정을 통과할 때까지는 확정값이 아니다.
 *
 * 이 값들은 클라이언트에 둔다. 서버로 빼면 "판정은 서버, 클라이언트는 사실 보고만"이라는
 * 경계가 흐려진다(ai-pipeline.md §8 마지막 문단).
 */

export interface ScoringConfig {
  /** 이 이상이면 HIT */
  thetaHi: number;
  /** 이 미만이면 MISS. 사이 구간이 UNCERTAIN */
  thetaLo: number;
  /** 저조도에서 쓰는 완화된 θ_lo */
  thetaLoLowLight: number;
  /** 프레임 평균 밝기가 이 아래면 저조도 모드 */
  lowLightLuminance: number;

  /**
   * 캘리브레이션 기준 얼굴 크기 대비 하한. 이보다 작게 잡힌 얼굴은 점수가 θ_lo 아래로
   * 떨어진다 — 멀리 있는 포스터·사진의 유령 검출을 배제하는 장치(ai-pipeline.md §6).
   */
  minFaceSizeRatio: number;
  /** 이 비율 이상이면 크기 적합도 만점 */
  fullFaceSizeRatio: number;
  /** 얼굴이 잡혔을 때 크기 적합도 0에서의 기본 점수 */
  presentBaseScore: number;

  /**
   * 아래는 "얼굴이 안 잡혔을 때 그 음성 판정을 얼마나 믿을지"의 계수다.
   * 조건이 나쁠수록 미검출을 덜 믿어 UNCERTAIN 쪽으로 올린다.
   */
  /** 저조도일 때 음성 판정에 더하는 점수 */
  liftLowLight: number;
  /** 직전까지 추적되던 얼굴이 고개 숙임 자세였다면 더하는 점수 */
  liftRecentHeadDown: number;
  /** "직전"의 범위 */
  recentTrackMs: number;
  /** 캘리브레이션 기준 각도보다 이만큼 더 숙였으면 고개 숙임으로 본다(도) */
  headDownPitchDeg: number;

  /**
   * 이 크기를 넘는 pitch 는 자세 추정이 깨진 것으로 보고 **각도만 버린다**(검출 여부는 살린다).
   * 실측에서 정면 영상인데 pitch 50°, yaw 86° 가 나오는 프레임이 있었다 — 그때도 행렬 자체는
   * 멀쩡한 회전행렬이라 수치로는 걸러지지 않고 크기로만 걸러진다.
   * IMAGE 모드로 옮긴 뒤로는 관측되지 않지만(pitch 범위 ±13°) 실기기에서 다시 나올 여지를 막는다.
   */
  maxPlausiblePitchDeg: number;
  /** 고개 숙임 판정에 쓸 pitch 를 최근 몇 개의 중앙값으로 볼지. 1이면 필터를 끈다 */
  pitchMedianWindow: number;

  /**
   * FaceLandmarker 자체 임계. 낮게 둔다 — 모델이 먼저 잘라버리면 UNCERTAIN 구간을
   * 우리가 관측할 수 없고, 하네스에서 임계를 다시 올리는 것도 불가능해진다.
   */
  modelDetectionConfidence: number;
}

export interface StateMachineConfig {
  /** 추론 주기(ai-pipeline.md §4). 고정값이다 — 발열을 재는 코드도 주기를 바꾸는 경로도 없다 */
  inferenceHz: number;
  /** 연속 미검출 몇 프레임에 CANDIDATE로 올릴지. 6프레임 = 3초 @2Hz */
  missFramesToCandidate: number;
  /** 연속 검출 몇 프레임에 복귀로 볼지. 3프레임 = 1.5초 @2Hz */
  returnFramesToPresent: number;
  /** t0 기준 프롬프트를 띄우기까지 */
  promptDelayMs: number;
  /** 프롬프트 무응답 판정까지 */
  promptAnswerWindowMs: number;
  /** 탭 횟수별 유예. 반복될수록 늘려 소모를 줄인다(ai-pipeline.md §5) */
  deferralGraceMs: number[];
  /** 이 횟수만큼 탭했는데도 해소되지 않으면 각도·조명 안내 */
  adjustHintAfterTaps: number;
  /** UNCERTAIN 동결 상한 */
  uncertainFreezeMs: number;
  /** 저조도 모드에서의 동결 상한 */
  uncertainFreezeLowLightMs: number;
  /**
   * 프레임 간격이 이보다 벌어지면 미관측 구간으로 본다. 관측하지 않은 시간에는 사건을
   * 만들지 않는다(ai-pipeline.md §7-3).
   */
  observationGapMs: number;
}

export interface TransportConfig {
  /** SS-4 최소 간격. 서버의 absence-min-interval-seconds 와 같아야 한다 */
  minIntervalMs: number;
  /** 429 재전송 대기. 같은 clientSeq 로 다시 보낸다 */
  retryAfterRateLimitMs: number;
  /** 네트워크 오류 재시도 초기 대기 */
  networkRetryBaseMs: number;
  /** 네트워크 오류 재시도 상한 */
  networkRetryMaxMs: number;
}

export interface CalibrationConfig {
  /** 캘리브레이션 수집 시간 */
  durationMs: number;
  /** 이 프레임 수를 못 채우면 실패로 본다 */
  minSamples: number;
}

export interface ServerRuleConfig {
  /** session.absence-threshold-seconds */
  absenceThresholdSeconds: number;
  /** session.absence-warning-escalation-seconds. 첫 경고 뒤 추가 경고가 붙는 눈금 */
  absenceWarningEscalationSeconds: number;
  /** session.evict-warning-count */
  evictWarningCount: number;
}

export interface DetectionConfig {
  scoring: ScoringConfig;
  stateMachine: StateMachineConfig;
  transport: TransportConfig;
  calibration: CalibrationConfig;
  serverRule: ServerRuleConfig;
}

export const DEFAULT_CONFIG: DetectionConfig = {
  scoring: {
    thetaHi: 0.6,
    thetaLo: 0.35,
    thetaLoLowLight: 0.2,
    lowLightLuminance: 0.25,
    minFaceSizeRatio: 0.45,
    fullFaceSizeRatio: 0.75,
    presentBaseScore: 0.35,
    liftLowLight: 0.3,
    liftRecentHeadDown: 0.25,
    recentTrackMs: 4000,
    headDownPitchDeg: 15,
    maxPlausiblePitchDeg: 60,
    pitchMedianWindow: 3,
    modelDetectionConfidence: 0.2,
  },
  stateMachine: {
    inferenceHz: 2,
    missFramesToCandidate: 6,
    returnFramesToPresent: 3,
    promptDelayMs: 30_000,
    promptAnswerWindowMs: 15_000,
    deferralGraceMs: [120_000, 240_000, 360_000],
    adjustHintAfterTaps: 3,
    uncertainFreezeMs: 10_000,
    uncertainFreezeLowLightMs: 20_000,
    observationGapMs: 3000,
  },
  transport: {
    minIntervalMs: 5000,
    retryAfterRateLimitMs: 5000,
    networkRetryBaseMs: 2000,
    networkRetryMaxMs: 30_000,
  },
  calibration: {
    durationMs: 8000,
    minSamples: 10,
  },
  serverRule: {
    absenceThresholdSeconds: 60,
    absenceWarningEscalationSeconds: 300,
    evictWarningCount: 3,
  },
};

/** 부분 설정을 기본값 위에 얹는다. 하네스의 임계값 스윕이 이걸 쓴다 */
export function mergeConfig(
  base: DetectionConfig,
  patch: DeepPartial<DetectionConfig>,
): DetectionConfig {
  return {
    scoring: { ...base.scoring, ...patch.scoring },
    stateMachine: { ...base.stateMachine, ...patch.stateMachine },
    transport: { ...base.transport, ...patch.transport },
    calibration: { ...base.calibration, ...patch.calibration },
    serverRule: { ...base.serverRule, ...patch.serverRule },
  };
}

export type DeepPartial<T> = {
  [K in keyof T]?: T[K] extends object ? Partial<T[K]> : T[K];
};
