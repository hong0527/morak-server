/**
 * 모델 선택 재검증용 비교 스크립트. **서비스 코드가 아니다.**
 *
 * 같은 영상·같은 프레임을 FaceLandmarker / FaceDetector / PoseLandmarker 셋에 동시에 먹여
 * 검출률과 추론 시간을 같은 조건에서 비교한다. 동시에 FaceLandmarker 의 변환행렬 원본
 * 16개 값을 프레임마다 남겨 pitch 이상치의 원인을 사후에 판정한다.
 *
 *   node harness/modelcheck.mjs --video=/tmp/molock-modelcheck/cc0-face.webm --hz=2  --runMs=110000
 *   node harness/modelcheck.mjs --video=/tmp/molock-modelcheck/cc0-face.webm --hz=10 --runMs=110000
 *
 * 결과 JSON 은 --out 경로에 남긴다. 분석은 modelcheck-analyze.mjs 가 한다.
 */
import { createServer } from "node:http";
import { readFile, writeFile, mkdir, stat } from "node:fs/promises";
import { existsSync, readdirSync } from "node:fs";
import { execFile } from "node:child_process";
import { promisify } from "node:util";
import { tmpdir } from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import puppeteer from "puppeteer-core";

const execFileAsync = promisify(execFile);
const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const EXTRA = "/tmp/molock-modelcheck";

const args = Object.fromEntries(
  process.argv.slice(2).map((a) => {
    const [k, v] = a.replace(/^--/, "").split("=");
    return [k, v ?? true];
  }),
);

const MIME = {
  ".html": "text/html; charset=utf-8",
  ".mjs": "text/javascript; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".wasm": "application/wasm",
  ".task": "application/octet-stream",
  ".tflite": "application/octet-stream",
  ".map": "application/json; charset=utf-8",
};

const PAGE = /* html */ `<!doctype html><meta charset="utf-8"><title>modelcheck</title>
<video id="v" autoplay muted playsinline></video>
<script type="module">
import { FilesetResolver, FaceLandmarker, FaceDetector, PoseLandmarker }
  from "/harness/vendor/tasks-vision/vision_bundle.mjs";

const q = new URLSearchParams(location.search);
const hz = Number(q.get("hz") ?? 2);
const runMs = Number(q.get("runMs") ?? 110000);
const periodMs = 1000 / hz;

const log = [];
window.__STATE = { phase: "init", frames: 0 };

async function main() {
  const fileset = await FilesetResolver.forVisionTasks("/harness/vendor/tasks-vision/wasm");

  // 셋 다 CPU 위임·VIDEO 모드로 고정한다. 조건을 다르게 두면 비교가 성립하지 않는다.
  const landmarker = await FaceLandmarker.createFromOptions(fileset, {
    baseOptions: { modelAssetPath: "/harness/vendor/models/face_landmarker.task", delegate: "CPU" },
    runningMode: "VIDEO",
    numFaces: 1,
    minFaceDetectionConfidence: 0.2,
    minFacePresenceConfidence: 0.2,
    minTrackingConfidence: 0.2,
    outputFacialTransformationMatrixes: true,
    // 지금 서비스는 끄고 있다. 쓸 만한 신호인지 보려고 여기서만 켠다.
    outputFaceBlendshapes: true,
  });

  // 같은 모델을 IMAGE 모드로 하나 더. VIDEO 모드는 추적 ROI 에 기대는데 2Hz 면 직전 프레임이
  // 500ms 전이라 그 ROI 가 낡는다. IMAGE 모드는 매 프레임 검출기를 다시 돌린다.
  // 둘의 차이가 곧 "추적이 도움이 되는가"의 답이다.
  const landmarkerImg = await FaceLandmarker.createFromOptions(fileset, {
    baseOptions: { modelAssetPath: "/harness/vendor/models/face_landmarker.task", delegate: "CPU" },
    runningMode: "IMAGE",
    numFaces: 1,
    minFaceDetectionConfidence: 0.2,
    minFacePresenceConfidence: 0.2,
    minTrackingConfidence: 0.2,
    outputFacialTransformationMatrixes: true,
    outputFaceBlendshapes: false,
  });

  const detector = await FaceDetector.createFromOptions(fileset, {
    baseOptions: { modelAssetPath: "/extra/blaze_face_short_range.tflite", delegate: "CPU" },
    runningMode: "VIDEO",
    minDetectionConfidence: 0.2,
  });

  const pose = await PoseLandmarker.createFromOptions(fileset, {
    baseOptions: { modelAssetPath: "/extra/pose_landmarker_lite.task", delegate: "CPU" },
    runningMode: "VIDEO",
    numPoses: 1,
    minPoseDetectionConfidence: 0.2,
    minPosePresenceConfidence: 0.2,
    minTrackingConfidence: 0.2,
    outputSegmentationMasks: false,
  });

  window.__STATE.phase = "models-loaded";

  const stream = await navigator.mediaDevices.getUserMedia({ video: true });
  const v = document.getElementById("v");
  v.srcObject = stream;
  await v.play();
  window.__STATE.phase = "running";

  const startedAt = performance.now();
  let nextAt = 0;
  let lastTs = -1;

  const onFrame = (now, meta) => {
    const t = performance.now() - startedAt;
    if (t >= runMs) { window.__STATE.phase = "done"; window.__RESULT = log; return; }
    if (t >= nextAt) {
      nextAt = t + periodMs;
      // 실제 경과 ms 를 준다. detector.ts 가 하는 것과 같다 — 1,2,3.. 을 주면 추적기가
      // 프레임 간격을 1ms 로 알고 시간적 평활이 실제와 다르게 돈다.
      const ts = Math.round(t) > lastTs ? Math.round(t) : lastTs + 1;
      lastTs = ts;
      sample(t, ts, v);
    }
    v.requestVideoFrameCallback(onFrame);
  };
  v.requestVideoFrameCallback(onFrame);

  function sample(tMs, ts, v) {
    const rec = { tMs };

    let a = performance.now();
    const fl = landmarker.detectForVideo(v, ts);
    rec.flMs = performance.now() - a;
    const lm = fl.faceLandmarks?.[0];
    rec.fl = lm && lm.length ? readFace(fl, lm) : null;

    a = performance.now();
    const fi = landmarkerImg.detect(v);
    rec.fiMs = performance.now() - a;
    const lmi = fi.faceLandmarks?.[0];
    rec.fi = lmi && lmi.length
      ? { diag: null, mat: fi.facialTransformationMatrixes?.[0] ? Array.from(fi.facialTransformationMatrixes[0].data) : null }
      : null;

    a = performance.now();
    const fd = detector.detectForVideo(v, ts);
    rec.fdMs = performance.now() - a;
    const d = fd.detections?.[0];
    rec.fd = d
      ? { score: d.categories?.[0]?.score ?? null,
          w: d.boundingBox?.width ?? null, h: d.boundingBox?.height ?? null }
      : null;

    a = performance.now();
    const pl = pose.detectForVideo(v, ts);
    rec.plMs = performance.now() - a;
    const p = pl.landmarks?.[0];
    rec.pl = p && p.length ? readPose(p) : null;

    log.push(rec);
    window.__STATE.frames = log.length;
  }

  function readFace(fl, lm) {
    let minX = 1e9, minY = 1e9, maxX = -1e9, maxY = -1e9;
    for (const p of lm) {
      if (p.x < minX) minX = p.x; if (p.x > maxX) maxX = p.x;
      if (p.y < minY) minY = p.y; if (p.y > maxY) maxY = p.y;
    }
    const w = maxX - minX, h = maxY - minY;
    const m = fl.facialTransformationMatrixes?.[0];
    const bs = fl.faceBlendshapes?.[0]?.categories;
    return {
      n: lm.length,
      // 랜드마크에 visibility/presence 가 실제로 실려 오는지 확인하려고 첫 점을 통째로 본다
      p0: { x: lm[0].x, y: lm[0].y, z: lm[0].z,
            vis: lm[0].visibility ?? null },
      diag: Math.sqrt(w * w + h * h),
      cx: (minX + maxX) / 2, cy: (minY + maxY) / 2,
      mat: m ? Array.from(m.data) : null,
      matRows: m ? m.rows : null, matCols: m ? m.columns : null,
      bsN: bs ? bs.length : 0,
      // 자리비움 판정에 쓸 만한 후보 몇 개만
      bs: bs ? pick(bs, ["eyeBlinkLeft", "eyeBlinkRight", "eyeLookDownLeft", "eyeLookDownRight", "jawOpen"]) : null,
    };
  }

  function pick(cats, names) {
    const out = {};
    for (const c of cats) if (names.includes(c.categoryName)) out[c.categoryName] = c.score;
    return out;
  }

  function readPose(p) {
    // 0 코, 11/12 어깨, 23/24 엉덩이(책상에 가려 안 보이는 자리)
    const g = (i) => p[i] ? { x: p[i].x, y: p[i].y, vis: p[i].visibility ?? null } : null;
    return { n: p.length, nose: g(0), shL: g(11), shR: g(12), hipL: g(23), hipR: g(24) };
  }
}

main().catch((e) => { window.__STATE.phase = "error"; window.__STATE.error = String(e?.stack || e); });
</script>`;

async function convertVideo(input, { width, height, fps }) {
  const dir = path.join(tmpdir(), "molock-fake-cam");
  await mkdir(dir, { recursive: true });
  const base = path.basename(input).replace(/\.[^.]+$/, "");
  const out = path.join(dir, `${base}-${width}x${height}@${fps}.y4m`);
  if (existsSync(out)) return out;
  await execFileAsync("ffmpeg", [
    "-hide_banner", "-loglevel", "error", "-i", input,
    "-vf", `scale=${width}:${height}:force_original_aspect_ratio=increase,crop=${width}:${height},fps=${fps}`,
    "-an", "-pix_fmt", "yuv420p", out, "-y",
  ], { maxBuffer: 1 << 28 });
  console.log(`변환:   ${out} (${((await stat(out)).size / 1e6).toFixed(0)}MB)`);
  return out;
}

function startServer(port) {
  const server = createServer(async (req, res) => {
    const url = new URL(req.url, "http://x");
    if (url.pathname === "/" || url.pathname === "/index.html") {
      res.writeHead(200, { "Content-Type": MIME[".html"] });
      res.end(PAGE);
      return;
    }
    const file = url.pathname.startsWith("/extra/")
      ? path.join(EXTRA, url.pathname.slice("/extra/".length))
      : path.join(ROOT, url.pathname);
    try {
      const body = await readFile(file);
      res.writeHead(200, { "Content-Type": MIME[path.extname(file)] ?? "application/octet-stream" });
      res.end(body);
    } catch {
      res.writeHead(404).end("no");
    }
  });
  return new Promise((r) => server.listen(port, () => r(server)));
}

function findChrome() {
  const cache = path.join(process.env.HOME, ".cache/puppeteer/chrome");
  if (existsSync(cache)) {
    const builds = [];
    for (const name of readdirSync(cache)) {
      const p = path.join(cache, name, "chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing");
      if (existsSync(p)) builds.push(p);
    }
    if (builds.length) return builds.sort().at(-1);
  }
  const system = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome";
  if (existsSync(system)) return system;
  throw new Error("크롬을 찾지 못했다");
}

async function main() {
  const port = Number(args.port ?? 8795);
  const hz = Number(args.hz ?? 2);
  const runMs = Number(args.runMs ?? 110000);
  const fixture = await convertVideo(String(args.video), {
    width: Number(args.width ?? 640),
    height: Number(args.height ?? 480),
    fps: Number(args.fps ?? 10),
  });
  const server = await startServer(port);
  const browser = await puppeteer.launch({
    executablePath: findChrome(),
    headless: true,
    args: [
      "--use-fake-ui-for-media-stream",
      "--use-fake-device-for-media-stream",
      `--use-file-for-fake-video-capture=${fixture}`,
      "--no-sandbox",
      "--autoplay-policy=no-user-gesture-required",
    ],
  });
  const page = await browser.newPage();
  const errors = [];
  page.on("console", (m) => { if (m.type() === "error") errors.push(m.text()); });
  page.on("pageerror", (e) => errors.push(`pageerror: ${e.message}`));

  console.log(`실행:   hz=${hz} runMs=${runMs}`);
  await page.goto(`http://localhost:${port}/?hz=${hz}&runMs=${runMs}`, { waitUntil: "load" });

  const deadline = Date.now() + runMs + 120000;
  let state;
  for (;;) {
    state = await page.evaluate(() => window.__STATE);
    if (state.phase === "done" || state.phase === "error") break;
    if (Date.now() > deadline) throw new Error(`시간 초과: ${JSON.stringify(state)}`);
    await new Promise((r) => setTimeout(r, 2000));
  }
  if (state.phase === "error") throw new Error(state.error);

  const result = await page.evaluate(() => window.__RESULT);
  const out = String(args.out ?? `/tmp/molock-modelcheck/run-${hz}hz.json`);
  await writeFile(out, JSON.stringify({ hz, runMs, frames: result.length, log: result }));
  console.log(`완료:   ${result.length} 프레임 → ${out}`);
  if (errors.length) console.log(`콘솔 오류 ${errors.length}건:`, errors.slice(0, 5));

  await browser.close();
  server.close();
}

main().catch((e) => { console.error(e); process.exit(1); });
