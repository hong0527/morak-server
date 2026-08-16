/**
 * 프레임 밝기 측정. 저조도 모드 판정에 쓴다(ai-pipeline.md §6).
 *
 * 32x18 로 줄여서 잰다. 조도는 전역 통계라 해상도가 필요 없고, 전체 픽셀을 읽으면
 * 추론보다 비싼 일이 되기 쉽다. getImageData 는 동기라서 반드시 Worker 안에서만 부른다.
 */
export class LuminanceMeter {
  private canvas: OffscreenCanvas;
  private ctx: OffscreenCanvasRenderingContext2D | null;

  constructor(
    private readonly width = 32,
    private readonly height = 18,
  ) {
    this.canvas = new OffscreenCanvas(this.width, this.height);
    this.ctx = this.canvas.getContext("2d", { willReadFrequently: true });
  }

  /** @returns 0~1. 실패하면 0.5 — 저조도로도 정상으로도 판정하지 않는 중립값 */
  measure(bitmap: ImageBitmap): number {
    if (!this.ctx) return 0.5;
    try {
      this.ctx.drawImage(bitmap, 0, 0, this.width, this.height);
      const { data } = this.ctx.getImageData(0, 0, this.width, this.height);
      let sum = 0;
      for (let i = 0; i < data.length; i += 4) {
        const r = data[i] ?? 0;
        const g = data[i + 1] ?? 0;
        const b = data[i + 2] ?? 0;
        // Rec.601 휘도. 사람 눈의 민감도를 반영한 가중치다.
        sum += 0.299 * r + 0.587 * g + 0.114 * b;
      }
      const pixels = data.length / 4;
      return pixels === 0 ? 0.5 : sum / pixels / 255;
    } catch {
      return 0.5;
    }
  }
}
