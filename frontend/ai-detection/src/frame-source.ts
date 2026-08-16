/**
 * 프레임 취득.
 *
 * 경로는 `<video>` + requestVideoFrameCallback 하나로 통일한다(ai-pipeline.md §4).
 * MediaStreamTrackProcessor 는 파이어폭스에 없고 크롬·사파리도 스펙 세대가 달라 구현이
 * 갈린다. wasm 추론에서는 어느 경로든 CPU 복사 총량이 같아 성능 손해가 미미하다.
 *
 * **카메라를 새로 열지 않는다.** 이미 LiveKit 로컬 트랙이 붙어 있는 `<video>` 엘리먼트를
 * 받아 쓴다. getUserMedia 를 다시 부르면 카메라를 두 번 잡게 되고 기기에 따라 화면이 멈춘다
 * (open-decisions C-2 ⑦).
 */

export interface FrameSample {
  /** 추론에 넘길 프레임. 소비자가 close() 책임을 진다 */
  bitmap: ImageBitmap;
  /** 프레임 시각. 라이브에서는 epoch ms */
  tMs: number;
}

export interface FrameSource {
  start(onFrame: (sample: FrameSample) => void): void;
  stop(): void;
  readonly running: boolean;
}

/**
 * rVFC 는 lib.dom 타입에는 있지만 파이어폭스 등 일부 브라우저 런타임에는 없다.
 * 타입이 있다고 존재를 믿으면 안 된다 — 실행 시점에 확인한다.
 */
export function supportsVideoFrameCallback(video: HTMLVideoElement): boolean {
  return typeof video.requestVideoFrameCallback === "function";
}

export interface VideoFrameSourceOptions {
  /** 초당 몇 장을 추론에 넘길지. 나머지 프레임은 그냥 흘려보낸다 */
  hz: number;
  /**
   * 프레임 시각을 무엇으로 볼지.
   * - "wall": Date.now(). 라이브용 — occurredAt 이 절대 시각이어야 한다.
   * - "media": video.currentTime. 하네스용 — 재생 속도와 무관하게 재현된다.
   */
  clock: "wall" | "media";
  /** 추론에 넘길 해상도 상한. 큰 프레임을 그대로 넘기면 복사 비용만 든다 */
  maxWidth?: number;
}

/**
 * rVFC 로 프레임을 받아 지정한 주기로만 ImageBitmap 을 만들어 넘긴다.
 *
 * 매 콜백마다 비트맵을 만들지 않는 이유: rVFC 는 영상 프레임레이트(보통 30fps)로 불리는데
 * 추론은 2Hz다. 필요 없는 15장의 복사를 미리 잘라낸다.
 */
export class VideoFrameSource implements FrameSource {
  private handle: number | null = null;
  private lastEmitMs = 0;
  private callback: ((sample: FrameSample) => void) | null = null;
  private stopped = true;
  private pending = false;

  constructor(
    private readonly video: HTMLVideoElement,
    private readonly options: VideoFrameSourceOptions,
  ) {}

  get running(): boolean {
    return !this.stopped;
  }

  start(onFrame: (sample: FrameSample) => void): void {
    if (!supportsVideoFrameCallback(this.video)) {
      throw new Error(
        "requestVideoFrameCallback 을 지원하지 않는 브라우저다. ai-pipeline.md §4 는 이 경로 하나만 쓴다.",
      );
    }
    this.callback = onFrame;
    this.stopped = false;
    this.lastEmitMs = 0;
    this.schedule();
  }

  stop(): void {
    this.stopped = true;
    this.callback = null;
    if (this.handle !== null) this.video.cancelVideoFrameCallback(this.handle);
    this.handle = null;
  }

  private schedule(): void {
    if (this.stopped) return;
    this.handle = this.video.requestVideoFrameCallback((_now, metadata) => {
      void this.onVideoFrame(metadata);
    });
  }

  private async onVideoFrame(metadata: VideoFrameCallbackMetadata): Promise<void> {
    if (this.stopped) return;

    const intervalMs = 1000 / this.options.hz;
    const nowMs = Date.now();

    // 앞선 비트맵 생성이 아직 안 끝났으면 이번 프레임은 버린다. 밀리면 지연만 쌓인다.
    if (this.pending || nowMs - this.lastEmitMs < intervalMs) {
      this.schedule();
      return;
    }
    this.lastEmitMs = nowMs;
    this.pending = true;

    try {
      const bitmap = await this.createBitmap();
      const tMs =
        this.options.clock === "media" ? Math.round(metadata.mediaTime * 1000) : nowMs;
      if (this.stopped) {
        bitmap.close();
      } else {
        this.callback?.({ bitmap, tMs });
      }
    } catch {
      // 트랙이 끊기는 순간 createImageBitmap 이 던진다. 다음 프레임에서 회복한다.
    } finally {
      this.pending = false;
      this.schedule();
    }
  }

  private createBitmap(): Promise<ImageBitmap> {
    const maxWidth = this.options.maxWidth;
    if (!maxWidth || this.video.videoWidth <= maxWidth) {
      return createImageBitmap(this.video);
    }
    const scale = maxWidth / this.video.videoWidth;
    return createImageBitmap(this.video, {
      resizeWidth: Math.round(this.video.videoWidth * scale),
      resizeHeight: Math.round(this.video.videoHeight * scale),
      resizeQuality: "low",
    });
  }
}
