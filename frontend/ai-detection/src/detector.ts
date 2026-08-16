import {
  FaceLandmarker,
  FilesetResolver,
  type FaceLandmarkerResult,
} from "@mediapipe/tasks-vision";
import type { ScoringConfig } from "./config.js";
import { LuminanceMeter } from "./luminance.js";
import type { FrameFeatures } from "./types.js";

/**
 * FaceLandmarker 래퍼. 프레임 하나 → FrameFeatures 하나.
 *
 * 모델과 wasm 은 CDN 이 아니라 자체 호스팅한다(ai-pipeline.md §4). 매칭 대기 화면에서
 * 미리 받아 두면 입장 시 3.76MB 를 기다리지 않는다.
 *
 * 위임(delegate)은 CPU 로 고정한다. GPU 위임은 WebGL2 를 쓰는데 이 파이프라인에 필요 없고,
 * 6인 영상 수신·송출과 GPU 를 다투게 만들 이유가 없다. 멀티스레드 wasm 은 아예 배제다 —
 * SharedArrayBuffer 때문에 COOP/COEP 헤더가 강제되고 그러면 소셜 로그인 팝업이 깨진다(§4).
 *
 * **runningMode 는 IMAGE 다. VIDEO 가 아니다.**
 * VIDEO 모드는 직전 프레임의 ROI 로 추적을 먼저 시도하는데 우리는 2Hz 로 샘플링해서 직전
 * 프레임이 500ms 전이다. 그 ROI 가 낡아 추적이 어긋나고, 어긋난 crop 에 얼굴을 맞추느라
 * 검출을 놓치거나 자세 행렬이 통째로 뒤집힌다. 같은 영상·같은 프레임으로 잰 차이는
 * 검출률 73.1% → 99.4%, pitch 범위 -26~+51° → -13~+11° 다(README 6-1 표).
 * IMAGE 모드가 더 빠르기까지 하다 — VIDEO 는 추적에 실패한 뒤 검출로 되돌아가 두 번 일한다.
 */

export interface DetectorAssets {
  /** tasks-vision 의 wasm 디렉터리 경로. 자체 호스팅 경로를 넣는다 */
  wasmBasePath: string;
  /** face_landmarker.task 파일 경로 */
  modelAssetPath: string;
}

export class FaceDetectorRunner {
  private landmarker: FaceLandmarker | null = null;
  private readonly luminance = new LuminanceMeter();

  constructor(private readonly scoring: ScoringConfig) {}

  async load(assets: DetectorAssets): Promise<void> {
    const fileset = await FilesetResolver.forVisionTasks(assets.wasmBasePath);
    this.landmarker = await FaceLandmarker.createFromOptions(fileset, {
      baseOptions: {
        modelAssetPath: assets.modelAssetPath,
        delegate: "CPU",
      },
      runningMode: "IMAGE",
      numFaces: 1,
      // 모델 자체 게이트를 낮게 둔다. 여기서 잘라버리면 UNCERTAIN 구간을 관측할 수 없고
      // 하네스에서 임계를 다시 올리는 것도 불가능해진다(scoring.ts 주석 참조).
      minFaceDetectionConfidence: this.scoring.modelDetectionConfidence,
      minFacePresenceConfidence: this.scoring.modelDetectionConfidence,
      minTrackingConfidence: this.scoring.modelDetectionConfidence,
      // 각도를 재려면 이게 켜져 있어야 한다. 이 한 줄이 FaceLandmarker 를 고른 이유다.
      outputFacialTransformationMatrixes: true,
      outputFaceBlendshapes: false,
    });
  }

  get loaded(): boolean {
    return this.landmarker !== null;
  }

  /** 호출자가 bitmap.close() 를 책임진다 */
  detect(bitmap: ImageBitmap, tMs: number): FrameFeatures {
    if (!this.landmarker) throw new Error("FaceLandmarker 가 아직 로드되지 않았다");

    const luminance = this.luminance.measure(bitmap);

    const startedAt = performanceNow();
    // IMAGE 모드라 타임스탬프를 주지 않는다. 프레임 사이에 아무 상태도 들고 가지 않으므로
    // 프레임 순서가 뒤바뀌거나 구간이 비어도 판정이 오염되지 않는다.
    const result = this.landmarker.detect(bitmap);
    const inferenceMs = performanceNow() - startedAt;

    return toFeatures(result, tMs, luminance, inferenceMs);
  }

  close(): void {
    this.landmarker?.close();
    this.landmarker = null;
  }
}

export function toFeatures(
  result: FaceLandmarkerResult,
  tMs: number,
  luminance: number,
  inferenceMs: number,
): FrameFeatures {
  const landmarks = result.faceLandmarks?.[0];
  if (!landmarks || landmarks.length === 0) {
    return {
      tMs,
      present: false,
      faceSize: null,
      pitchDeg: null,
      yawDeg: null,
      rollDeg: null,
      luminance,
      inferenceMs,
    };
  }

  const matrix = result.facialTransformationMatrixes?.[0];
  const euler = matrix ? eulerFromColumnMajor(matrix.data) : null;

  return {
    tMs,
    present: true,
    faceSize: boundingDiagonal(landmarks),
    pitchDeg: euler?.pitchDeg ?? null,
    yawDeg: euler?.yawDeg ?? null,
    rollDeg: euler?.rollDeg ?? null,
    luminance,
    inferenceMs,
  };
}

/**
 * 랜드마크 바운딩박스의 대각 길이. 좌표가 0~1 정규화라 화면 해상도에 무관하다.
 * 캘리브레이션 기준값과의 비율만 쓰므로 절대값의 의미는 없다.
 */
function boundingDiagonal(points: { x: number; y: number }[]): number {
  let minX = Infinity;
  let minY = Infinity;
  let maxX = -Infinity;
  let maxY = -Infinity;
  for (const p of points) {
    if (p.x < minX) minX = p.x;
    if (p.x > maxX) maxX = p.x;
    if (p.y < minY) minY = p.y;
    if (p.y > maxY) maxY = p.y;
  }
  const w = maxX - minX;
  const h = maxY - minY;
  return Math.sqrt(w * w + h * h);
}

/**
 * 4x4 열우선 변환 행렬에서 오일러각(도)을 뽑는다. OpenCV 의 XYZ 분해와 같은 식이다.
 *
 * **열우선 판독은 실측으로 확인했다.** 실제 행렬의 마지막 네 값이 `[0.00, 12.45, -64.08, 1.00]`
 * 로 평행이동(카메라에서 64cm 앞)이 마지막 열에 있다 — `data[col*4+row]` 가 맞다.
 * 회전부에 스케일도 섞여 있지 않다(544프레임 전부 열 노름 1.0000, 직교오차 0, 행렬식 1.0000).
 * proto 주석이 "Uniform scale" 을 언급해 정규화가 필요한가 걱정할 자리인데 불필요하다.
 *
 * 랜드마크 6점 + solvePnP 로 바꿀 이유도 없다. 이 행렬은 468점 전부에 가중 Procrustes 를
 * 맞춘 결과라 정보량이 더 많고, PnP 는 우리가 모르는 카메라 내부 파라미터를 요구한다.
 *
 * 부호 규약은 **여전히 미확인이다** — pitch 가 "아래를 볼 때 음수"인지 양수인지에 따라
 * scoring.ts 의 고개 숙임 판정 부등호가 뒤집힌다. README 7-1 참조.
 */
export function eulerFromColumnMajor(
  data: number[] | Float32Array,
): { pitchDeg: number; yawDeg: number; rollDeg: number } | null {
  if (data.length < 16) return null;
  const at = (row: number, col: number): number => data[col * 4 + row] ?? 0;

  const r00 = at(0, 0);
  const r10 = at(1, 0);
  const r20 = at(2, 0);
  const r21 = at(2, 1);
  const r22 = at(2, 2);
  const r11 = at(1, 1);
  const r12 = at(1, 2);

  const sy = Math.sqrt(r00 * r00 + r10 * r10);
  const singular = sy < 1e-6;

  const x = singular ? Math.atan2(-r12, r11) : Math.atan2(r21, r22);
  const y = Math.atan2(-r20, sy);
  const z = singular ? 0 : Math.atan2(r10, r00);

  const deg = 180 / Math.PI;
  return { pitchDeg: x * deg, yawDeg: y * deg, rollDeg: z * deg };
}

function performanceNow(): number {
  return typeof performance !== "undefined" ? performance.now() : Date.now();
}
