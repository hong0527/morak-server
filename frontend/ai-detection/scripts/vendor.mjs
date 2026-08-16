/**
 * tasks-vision 런타임과 모델 파일을 harness/vendor 로 복사한다.
 *
 * 자체 호스팅이 원칙이다(ai-pipeline.md §4). CDN 을 쓰면 측정 환경과 서비스 환경이
 * 갈라지고, 발표 당일 CDN 이 느리면 그대로 시연이 막힌다.
 */
import { cp, mkdir, access, stat } from "node:fs/promises";
import { createWriteStream } from "node:fs";
import { Readable } from "node:stream";
import { pipeline } from "node:stream/promises";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const root = dirname(dirname(fileURLToPath(import.meta.url)));
const vendorDir = join(root, "harness", "vendor");
const taskVisionSrc = join(root, "node_modules", "@mediapipe", "tasks-vision");
const taskVisionDst = join(vendorDir, "tasks-vision");
const modelDir = join(vendorDir, "models");
const modelPath = join(modelDir, "face_landmarker.task");

const MODEL_URL =
  "https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task";

async function exists(path) {
  try {
    await access(path);
    return true;
  } catch {
    return false;
  }
}

async function main() {
  if (!(await exists(taskVisionSrc))) {
    console.error("node_modules/@mediapipe/tasks-vision 가 없다. npm install 을 먼저 하라.");
    process.exit(1);
  }

  await mkdir(vendorDir, { recursive: true });
  await cp(taskVisionSrc, taskVisionDst, { recursive: true });
  console.log(`복사: ${taskVisionDst}`);

  await mkdir(modelDir, { recursive: true });
  if (await exists(modelPath)) {
    const s = await stat(modelPath);
    console.log(`모델 이미 있음: ${modelPath} (${(s.size / 1e6).toFixed(2)}MB)`);
    return;
  }

  console.log(`모델 내려받는 중: ${MODEL_URL}`);
  const res = await fetch(MODEL_URL);
  if (!res.ok || !res.body) {
    console.error(
      `모델 내려받기 실패 (HTTP ${res.status}). 직접 받아 ${modelPath} 에 두어도 된다.`,
    );
    process.exit(1);
  }
  await pipeline(Readable.fromWeb(res.body), createWriteStream(modelPath));
  const s = await stat(modelPath);
  console.log(`저장: ${modelPath} (${(s.size / 1e6).toFixed(2)}MB)`);
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
