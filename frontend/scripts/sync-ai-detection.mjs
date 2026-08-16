/**
 * 감지 모듈을 빌드하고 브라우저가 받아 갈 정적 자산을 public/ai 로 옮긴다.
 *
 * 메인 스레드 코드는 node_modules/molock-ai-detection 심볼릭 링크로 그대로 import 하지만
 * 셋은 파일로 놓여야 한다.
 *
 *   worker-classic.js    번들러가 만든 IIFE 한 덩이. 모듈 워커로는 모델이 뜨지 않는다
 *                        (ai-detection/README.md 9-9). 그래서 esbuild 를 거친 결과물만 쓴다.
 *   wasm/                MediaPipe 런타임. CDN 을 쓰지 않는다 — 발표 당일 CDN 이 느리면
 *                        그대로 시연이 막힌다(7-3).
 *   face_landmarker.task 3.76MB 모델. 위와 같은 이유로 자체 호스팅한다.
 *
 * 임계값을 고쳤으면(ai-detection/src/config.ts) 이 스크립트 하나만 다시 돌리면 된다.
 */
import { execFileSync } from "node:child_process";
import { cpSync, existsSync, mkdirSync, rmSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const ai = resolve(root, "ai-detection");
const publicAi = join(root, "public", "ai");

function fail(message) {
  console.error(`\n감지 모듈 동기화 실패\n${message}\n`);
  process.exit(1);
}

if (!existsSync(ai)) {
  fail(
    `${ai} 가 없다.\n` +
      `감지 모듈은 이 저장소 안의 ai-detection/ 폴더다. 클론이 온전한지 본다.\n` +
      `README.md "시작하는 법" 을 본다.`,
  );
}

if (!existsSync(join(ai, "node_modules"))) {
  fail(`ai-detection 에 의존성이 없다. 먼저 거기서 npm install 을 돌린다.`);
}

const model = join(ai, "harness", "vendor", "models", "face_landmarker.task");
const wasm = join(ai, "harness", "vendor", "tasks-vision", "wasm");
if (!existsSync(model) || !existsSync(wasm)) {
  fail(
    `모델과 wasm 런타임이 없다. ai-detection 에서 다음을 돌린다.\n\n  cd ${ai}\n  npm run vendor\n\n` +
      `3.76MB 모델을 네트워크에서 받아 harness/vendor 에 둔다. 한 번만 하면 된다.`,
  );
}

// ai-detection 의 명령을 그대로 부른다. esbuild 옵션을 여기 복제하면 두 곳이 어긋난다.
console.log("ai-detection 빌드 중...");
for (const script of ["build", "build:worker"]) {
  execFileSync("npm", ["run", script], { cwd: ai, stdio: "inherit" });
}

const worker = join(ai, "dist", "worker-classic.js");
if (!existsSync(worker)) fail(`${worker} 가 만들어지지 않았다.`);

rmSync(publicAi, { recursive: true, force: true });
mkdirSync(publicAi, { recursive: true });
cpSync(worker, join(publicAi, "worker-classic.js"));
cpSync(model, join(publicAi, "face_landmarker.task"));
cpSync(wasm, join(publicAi, "wasm"), { recursive: true });

console.log(`감지 자산 배치 완료 → public/ai (worker-classic.js, face_landmarker.task, wasm/)`);
