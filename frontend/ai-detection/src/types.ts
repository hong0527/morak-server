/**
 * 파이프라인 전역에서 쓰는 타입.
 *
 * 시간은 전부 밀리초 정수 하나(`tMs`)로만 다룬다. 라이브에서는 epoch(Date.now()),
 * 하네스에서는 영상 재생 위치다. 상태기계는 이 값의 차이만 쓰므로 두 세계가 같은 코드를
 * 돈다 — 하네스가 실제 판정 로직을 그대로 재현할 수 있는 이유가 이것이다.
 */

/** 프레임 하나에 대한 3값 판정. ai-pipeline.md §6 */
export type Verdict = "HIT" | "UNCERTAIN" | "MISS";

/** ai-pipeline.md §5 전이표의 상태 */
export type DetectionState =
  | "PRESENT"
  | "OBSERVING"
  | "CANDIDATE"
  | "PROMPTING"
  | "DEFERRED"
  | "ABSENT_SENT";

/**
 * 추론 결과에서 뽑아낸 프레임별 원시 특징.
 *
 * 판정에 필요한 값을 스칼라 하나로 줄이지 않고 벡터로 남기는 것이 하네스의 전제다.
 * 임계값뿐 아니라 점수 함수 자체도 재추론 없이 갈아끼울 수 있어야 한다(ai-pipeline.md §8).
 */
export interface FrameFeatures {
  /** 프레임 시각 */
  tMs: number;
  /** 모델이 얼굴을 반환했는가. 모델 자체 임계는 낮게 두고 게이팅은 우리가 한다 */
  present: boolean;
  /** 랜드마크 바운딩박스의 정규화 대각 길이(0~1.41). 유령 검출 배제와 크기 적합도에 쓴다 */
  faceSize: number | null;
  /** 얼굴 변환 행렬에서 뽑은 오일러각(도). 아래를 보면 pitch가 음수 */
  pitchDeg: number | null;
  yawDeg: number | null;
  rollDeg: number | null;
  /** 프레임 평균 밝기 0~1. 저조도 모드 판정용 */
  luminance: number;
  /** 추론 소요 시간(ms). 발열·프레임레이트 조정 근거 */
  inferenceMs: number;
}

/** 상태기계가 내보내는 부수효과. 실행은 전부 바깥(메인 스레드)이 한다 */
export type Effect =
  /** 본인 확인 프롬프트를 띄운다 */
  | { kind: "SHOW_PROMPT" }
  /** 프롬프트를 내린다 */
  | { kind: "HIDE_PROMPT" }
  /** 3회 연속 미해소 — 카메라 각도·조명 안내 */
  | { kind: "SHOW_ADJUST_HINT" }
  /** SS-4 START. occurredAtMs 는 판단 시각이 아니라 실제 전이 프레임 시각이다 */
  | { kind: "ABSENCE_START"; occurredAtMs: number }
  /** SS-4 END. occurredAtMs 는 첫 재검출 프레임 시각이다 */
  | { kind: "ABSENCE_END"; occurredAtMs: number }
  /** 디버깅·하네스용 상태 전이 기록. 서버로 나가지 않는다 */
  | { kind: "STATE"; from: DetectionState; to: DetectionState; tMs: number };

/** SS-4 요청 본문 */
export interface AbsenceEventBody {
  type: "START" | "END";
  clientSeq: number;
  /** ISO8601 오프셋 포함 */
  occurredAt: string;
}

/** SS-4 200 응답 */
export interface AbsenceEventResponse {
  accepted: boolean;
  warningCount: number;
  evicted: boolean;
  evictionId: number | null;
  pointDelta: number;
  closedAbsenceSeconds: number | null;
}
