import { CalibrationCollector, type CalibrationProfile } from "../src/calibration.js";
import { DEFAULT_CONFIG, type DetectionConfig } from "../src/config.js";
import { FaceDetectorRunner, type DetectorAssets } from "../src/detector.js";
import type { FrameFeatures } from "../src/types.js";
import type { InferenceLog } from "./log-format.js";

/**
 * 녹화 영상 하나를 추론해 프레임별 원시 특징 로그를 만든다. **영상당 딱 한 번 도는 쪽**이다.
 *
 * 브라우저에서 돈다. 측정은 실제 서비스와 동일한 라이브러리·모델로 해야 하고
 * (ai-pipeline.md §8), wasm 추론 결과가 런타임에 따라 미세하게 다를 수 있어 Node 로
 * 옮기면 측정 대상이 달라진다.
 *
 * Worker 를 쓰지 않는 이유: 여기서는 캠 화면이 끊길 일이 없고(오프라인 도구다),
 * 메인에서 돌리면 진행률 표시와 오류 추적이 훨씬 간단하다. 추론 코드 자체는 서비스와 같다.
 */

export interface RecordOptions {
  video: HTMLVideoElement;
  clipId: string;
  assets: DetectorAssets;
  config?: DetectionConfig;
  /**
   * 앞부분 이 길이만큼으로 캘리브레이션을 잡는다. 대본상 클립 도입부는 평상시 자세다.
   * 0 이면 캘리브레이션 없이 기록한다(그 경우 replay 는 크기 판정을 건너뛴다).
   */
  calibrationWindowMs?: number;
  /** 미리 잰 프로필을 그대로 쓰려면 */
  calibration?: CalibrationProfile | null;
  onProgress?(ratio: number, frames: number): void;
}

/**
 * 영상을 처음부터 끝까지 재생하며 추론한다.
 *
 * 프레임 시각은 벽시계가 아니라 **재생 위치(mediaTime)**다. 재생이 밀리거나 배속이 달라도
 * 같은 로그가 나와야 하고, 정답 라벨(스톱워치 시각)과 같은 시계여야 대조가 성립한다.
 */
export async function recordClip(options: RecordOptions): Promise<InferenceLog> {
  const config = options.config ?? DEFAULT_CONFIG;
  const video = options.video;

  if (typeof video.requestVideoFrameCallback !== "function") {
    throw new Error("requestVideoFrameCallback 이 없는 브라우저다. 크롬·사파리 최신에서 돌려라.");
  }

  const runner = new FaceDetectorRunner(config.scoring);
  await runner.load(options.assets);

  const intervalMs = 1000 / config.stateMachine.inferenceHz;
  const frames: FrameFeatures[] = [];
  const calibrationWindowMs = options.calibrationWindowMs ?? config.calibration.durationMs;
  const collector =
    options.calibration === undefined && calibrationWindowMs > 0
      ? new CalibrationCollector({ ...config.calibration, durationMs: calibrationWindowMs })
      : null;

  let lastSampleMs = -Infinity;
  let busy = false;

  await new Promise<void>((resolvePlayback, rejectPlayback) => {
    const step = (): void => {
      video.requestVideoFrameCallback((_now, metadata) => {
        void onFrame(metadata.mediaTime);
      });
    };

    const onFrame = async (mediaTimeSec: number): Promise<void> => {
      const tMs = Math.round(mediaTimeSec * 1000);
      if (busy || tMs - lastSampleMs < intervalMs) {
        if (!video.ended) step();
        else resolvePlayback();
        return;
      }
      lastSampleMs = tMs;
      busy = true;
      try {
        const bitmap = await createImageBitmap(video);
        try {
          const features = runner.detect(bitmap, tMs);
          frames.push(features);
          collector?.push(features);
          options.onProgress?.(
            video.duration > 0 ? mediaTimeSec / video.duration : 0,
            frames.length,
          );
        } finally {
          bitmap.close();
        }
      } catch (e) {
        rejectPlayback(e instanceof Error ? e : new Error(String(e)));
        return;
      } finally {
        busy = false;
      }
      if (!video.ended) step();
      else resolvePlayback();
    };

    video.addEventListener("ended", () => resolvePlayback(), { once: true });
    video.addEventListener(
      "error",
      () => rejectPlayback(new Error("영상 디코딩에 실패했다")),
      { once: true },
    );

    video.currentTime = 0;
    void video.play().then(step).catch(rejectPlayback);
  });

  runner.close();

  const calibration =
    options.calibration !== undefined
      ? options.calibration
      : (collector?.finish().profile ?? null);

  return {
    version: 1,
    clipId: options.clipId,
    recordedAt: new Date().toISOString(),
    model: {
      modelAssetPath: options.assets.modelAssetPath,
      modelDetectionConfidence: config.scoring.modelDetectionConfidence,
      inferenceHz: config.stateMachine.inferenceHz,
    },
    calibration,
    durationMs: Math.round((video.duration || 0) * 1000),
    frames,
  };
}
