/**
 * 헤드리스 크롬 + 가짜 카메라로 실시간 경로를 끝까지 돌린다.
 *
 * README §8 이 "브라우저에서만 확인할 수 있다"로 접어 둔 것들 — 모델 로딩·추론, rVFC 프레임
 * 취득, Worker 통신, sessionStorage 보존, 캘리브레이션 — 을 카메라 없이 확인한다.
 * 크롬의 `--use-file-for-fake-video-capture` 가 y4m 파일을 웹캠인 것처럼 물려 준다.
 *
 * **이것으로 확인되지 않는 것**: 실제 사람 얼굴에 대한 판정 정확도. 합성 영상은 사람이
 * 아니다. 여기서 확인하는 것은 "파이프라인이 끝까지 돈다"까지다.
 *
 * 개발 전용이다. puppeteer-core 는 devDependency 이고 서비스 코드는 이 파일을 모른다.
 *
 *   npm run browser-check                               # 합성 영상으로 파이프라인만
 *   npm run video-check -- --video=/경로/clip.mov      # 사람이 찍은 영상으로 (README 6-1)
 *   node harness/browser-check.mjs --server=http://localhost:8160   # 서버까지 이어서
 *   node harness/browser-check.mjs --bare-worker       # 번들러 없이 워커가 뜨는지 (실패 예상)
 *
 * 주요 인자
 *   --video=경로     mov·mp4·webm 무엇이든. ffmpeg 이 y4m 으로 바꾼다(--width/--height/--fps)
 *   --classicWorker  클래식 워커 번들을 쓴다. 모듈 워커로는 모델이 안 뜬다(README 9-9)
 *   --runMs=60000    감지를 몇 밀리초 돌릴지. 영상 길이보다 길게 준다
 *   --bucket=5       영상 분석 표의 구간 크기(초)
 *   --skipCalibration=1  캘리브레이션을 건너뛴다. **크기 판정이 함께 꺼지므로** 기본은 켠다
 */
import { createServer } from "node:http";
import { readFile, stat, mkdir } from "node:fs/promises";
import { existsSync, readdirSync } from "node:fs";
import { execFile } from "node:child_process";
import { promisify } from "node:util";
import { tmpdir } from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import puppeteer from "puppeteer-core";

const execFileAsync = promisify(execFile);
const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const VENDOR_BUNDLE = "/harness/vendor/tasks-vision/vision_bundle.mjs";

const args = Object.fromEntries(
  process.argv.slice(2).map((a) => {
    const [k, v] = a.replace(/^--/, "").split("=");
    return [k, v ?? true];
  }),
);

const MIME = {
  ".html": "text/html; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".mjs": "text/javascript; charset=utf-8",
  ".cjs": "text/javascript; charset=utf-8",
  ".json": "application/json; charset=utf-8",
  ".map": "application/json; charset=utf-8",
  ".wasm": "application/wasm",
  ".task": "application/octet-stream",
  ".y4m": "video/x-yuv4mpegvideo",
};

/**
 * 모듈 Worker 에는 임포트맵이 적용되지 않는다. `dist` 의 베어 스펙파이어
 * (`@mediapipe/tasks-vision`)를 브라우저가 해석할 방법이 없어 워커가 통째로 못 뜬다.
 * 서비스에서는 번들러가 이 자리를 메운다 — 여기서는 서버가 대신 그 일을 한다.
 * `--bare-worker` 로 돌리면 이 치환을 끄고 실제로 어떻게 실패하는지 볼 수 있다.
 */
function rewriteBareSpecifier(source) {
  return source.replaceAll('from "@mediapipe/tasks-vision"', `from "${VENDOR_BUNDLE}"`);
}

/**
 * MediaPipe 의 wasm 로더(`vision_wasm_*.js`)는 UMD 클래식 스크립트다. 최상위 `var ModuleFactory`
 * 가 전역이 되는 것을 전제로 라이브러리가 `self.ModuleFactory` 를 읽는다.
 *
 * 그런데 모듈 워커에서는 `importScripts` 가 TypeError 로 튕기고(실측 확인), 라이브러리가
 * 동적 `import()` 로 대체한다. 그러면 이 파일이 **모듈로 평가돼** 최상위 var 가 모듈 스코프에
 * 갇히고 전역이 세워지지 않는다 — `ModuleFactory not set.` 이 그것이다.
 *
 * 여기서는 마지막에 전역 대입 한 줄을 붙여 그 간극을 메운다. 서비스에서 이 자리를 메우는 것은
 * 번들러이거나(워커를 클래식으로 뽑는다) 자산 복사 단계다. README §9 에 적어 두었다.
 */
function exposeModuleFactory(source) {
  return `${source}\n;globalThis.ModuleFactory = ModuleFactory;\n`;
}

/**
 * 6인 세션 하나를 열고 그중 한 명의 토큰을 돌려준다. `server-contract.ts` 가 하는 것과 같다.
 * 브라우저가 SS-4 를 쏘려면 **지금 살아 있는 세션의 참가자**여야 한다.
 */
async function bootstrapSession(base) {
  const call = async (method, path, token, body) => {
    const res = await fetch(`${base}${path}`, {
      method,
      headers: {
        "Content-Type": "application/json",
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
      },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    const text = await res.text();
    return { status: res.status, json: text ? JSON.parse(text) : null };
  };

  const onboard = async (code) => {
    const login = await call("POST", "/api/auth/login", null, {
      provider: "KAKAO",
      authorizationCode: code,
      agreements: [
        { type: "TOS", agreed: true },
        { type: "PRIVACY", agreed: true },
      ],
    });
    if (login.status !== 200) {
      throw new Error(`로그인 실패 ${login.status}: ${JSON.stringify(login.json)} — dev 프로필로 떠 있는지 확인하라`);
    }
    const token = login.json.accessToken;
    await call("POST", "/api/members/me/birthdate", token, { birthDate: "1998-04-11" });
    return { memberId: login.json.memberId, token };
  };

  const stamp = Date.now();
  const me = await onboard(`browser-${stamp}-0`);
  await call("POST", "/api/match-requests", me.token, { targetMinutes: 60 });
  for (let i = 1; i < 6; i += 1) {
    const other = await onboard(`browser-${stamp}-${i}`);
    await call("POST", "/api/match-requests", other.token, { targetMinutes: 60 });
  }
  const poll = await call("GET", "/api/match-requests/me", me.token);
  const sessionId = poll.json?.sessionId;
  if (!sessionId) throw new Error(`세션이 열리지 않았다: ${JSON.stringify(poll.json)}`);
  return { sessionId, token: me.token, memberId: me.memberId };
}

function startServer(port, { rewriteWorkerGraph, proxyTo }) {
  const server = createServer(async (req, res) => {
    try {
      let urlPath = decodeURIComponent(new URL(req.url, "http://x").pathname);

      // 배포 문서 §7 이 규정한 구성 그대로 — 프론트와 API 를 같은 오리진에 둔다.
      // 서버에 CORS 설정이 없어서(deployment.md) 다른 오리진으로 부르면 브라우저가 응답을 버린다.
      if (proxyTo && urlPath.startsWith("/api/")) {
        const chunks = [];
        for await (const c of req) chunks.push(c);
        const upstream = await fetch(`${proxyTo}${req.url}`, {
          method: req.method,
          headers: {
            "Content-Type": req.headers["content-type"] ?? "application/json",
            ...(req.headers.authorization ? { authorization: req.headers.authorization } : {}),
          },
          body: chunks.length ? Buffer.concat(chunks) : undefined,
        });
        const text = await upstream.text();
        res.writeHead(upstream.status, {
          "Content-Type": upstream.headers.get("content-type") ?? "application/json",
        });
        res.end(text);
        return;
      }
      const isWorkerGraph = urlPath.startsWith("/worker-graph/");
      if (isWorkerGraph) urlPath = urlPath.slice("/worker-graph".length);

      const filePath = path.join(ROOT, urlPath);
      if (!filePath.startsWith(ROOT) || !existsSync(filePath)) {
        res.writeHead(404).end("not found");
        return;
      }
      const info = await stat(filePath);
      if (info.isDirectory()) {
        res.writeHead(404).end("directory");
        return;
      }
      const ext = path.extname(filePath);
      let body = await readFile(filePath);
      if (isWorkerGraph && rewriteWorkerGraph && (ext === ".js" || ext === ".mjs")) {
        let text = rewriteBareSpecifier(body.toString("utf8"));
        if (/vision_wasm(_nosimd)?(_module)?_internal\.js$/.test(filePath)) {
          text = exposeModuleFactory(text);
        }
        body = Buffer.from(text, "utf8");
      }
      res.writeHead(200, {
        "Content-Type": MIME[ext] ?? "application/octet-stream",
        "Content-Length": body.length,
        // 워커 안에서 wasm 을 받아 가므로 캐시를 끈다. 재실행마다 같은 조건이어야 한다.
        "Cache-Control": "no-store",
      });
      res.end(body);
    } catch (e) {
      res.writeHead(500).end(String(e));
    }
  });
  return new Promise((resolve) => server.listen(port, () => resolve(server)));
}

/**
 * 가짜 카메라에 물릴 y4m. 얼굴이 없는 영상이다.
 *
 * y4m 은 무압축이라 크다(640x480 12초 ≈ 83MB). 저장소에 두지 않고 임시 폴더에 만든다.
 * 크롬이 파일 끝에 닿으면 처음부터 다시 재생하므로 짧게 만들어도 된다.
 */
async function ensureFixture() {
  const dir = path.join(tmpdir(), "molock-fake-cam");
  await mkdir(dir, { recursive: true });
  const out = path.join(dir, "no-face-320x240.y4m");
  if (existsSync(out)) return out;
  await execFileAsync("ffmpeg", [
    "-hide_banner", "-loglevel", "error",
    "-f", "lavfi",
    // 균일한 회색에 옅은 잡음. 얼굴로 잡힐 만한 구조가 없다.
    "-i", "color=c=gray:s=320x240:r=10,noise=alls=6:allf=t",
    "-t", "8", "-pix_fmt", "yuv420p", out, "-y",
  ]);
  return out;
}

/**
 * 사람이 찍어 준 영상을 가짜 카메라가 먹는 형식으로 바꾼다.
 *
 * 크롬이 받는 것은 y4m(무압축) 하나뿐이라 `.mov`·`.mp4`·`.webm` 무엇이든 여기서 변환한다.
 * 해상도·프레임률도 여기서 맞춘다 — 찍는 사람이 신경 쓸 것을 없앤다.
 *
 * y4m 은 초당 약 4.6MB(640x480 10fps)다. 1분이면 280MB 정도라 임시 폴더에만 만든다.
 */
async function convertVideo(input, { width, height, fps }) {
  if (!existsSync(input)) throw new Error(`영상 파일이 없다: ${input}`);
  const dir = path.join(tmpdir(), "molock-fake-cam");
  await mkdir(dir, { recursive: true });
  const base = path.basename(input).replace(/\.[^.]+$/, "");
  const out = path.join(dir, `${base}-${width}x${height}@${fps}.y4m`);

  console.log(`변환:   ${input}\n     → ${width}x${height} ${fps}fps y4m`);
  await execFileAsync("ffmpeg", [
    "-hide_banner", "-loglevel", "error",
    "-i", input,
    // 비율이 달라도 잘라서 맞춘다. 세로 영상을 넣어도 돈다.
    "-vf", `scale=${width}:${height}:force_original_aspect_ratio=increase,` +
           `crop=${width}:${height},fps=${fps}`,
    "-an", "-pix_fmt", "yuv420p", out, "-y",
  ]);
  const info = await stat(out);
  console.log(`     → ${(info.size / 1024 / 1024).toFixed(0)}MB`);
  return out;
}

/** 영상 대본과 맞대 읽는 요약. 초 단위로 접어서 사람이 눈으로 대조할 수 있게 만든다. */
function analyseSamples(samples, videoStartAtMs, bucketSec) {
  if (!samples?.length) return null;
  const at = (s) => (s.tMs - videoStartAtMs) / 1000;
  const present = samples.filter((s) => s.present);

  // 초당 한 글자. '#' 검출, '.' 미검출. 대본의 이석 구간이 여기서 눈에 띈다.
  const lastSec = Math.ceil(at(samples.at(-1)));
  const timeline = [];
  for (let sec = 0; sec <= lastSec; sec += 1) {
    const inSec = samples.filter((s) => Math.floor(at(s)) === sec);
    timeline.push(inSec.length === 0 ? " " : inSec.some((s) => s.present) ? "#" : ".");
  }

  // 미검출이 이어진 구간. 실제로 자리를 비운 시각과 대조하면 된다.
  const gaps = [];
  let start = null;
  for (const s of samples) {
    if (!s.present && start === null) start = at(s);
    if (s.present && start !== null) {
      gaps.push({ fromSec: start, toSec: at(s), lenSec: at(s) - start });
      start = null;
    }
  }
  if (start !== null) gaps.push({ fromSec: start, toSec: at(samples.at(-1)), lenSec: at(samples.at(-1)) - start });

  const buckets = [];
  for (let t = 0; t <= lastSec; t += bucketSec) {
    const inB = samples.filter((s) => at(s) >= t && at(s) < t + bucketSec);
    if (!inB.length) continue;
    const det = inB.filter((s) => s.present);
    buckets.push({
      fromSec: t,
      frames: inB.length,
      presentPct: (det.length / inB.length) * 100,
      pitchMedian: median(det.map((s) => s.pitchDeg).filter((v) => typeof v === "number")),
      faceSizeMedian: median(det.map((s) => s.faceSize).filter((v) => typeof v === "number")),
      lumMedian: median(inB.map((s) => s.luminance).filter((v) => typeof v === "number")),
    });
  }

  return {
    frames: samples.length,
    presentPct: (present.length / samples.length) * 100,
    timeline: timeline.join(""),
    gaps: gaps.filter((g) => g.lenSec >= 1),
    buckets,
    pitchAll: {
      min: Math.min(...present.map((s) => s.pitchDeg ?? NaN).filter(Number.isFinite)),
      median: median(present.map((s) => s.pitchDeg).filter((v) => typeof v === "number")),
      max: Math.max(...present.map((s) => s.pitchDeg ?? NaN).filter(Number.isFinite)),
    },
  };
}

function fmt(v, digits = 1) {
  return typeof v === "number" && Number.isFinite(v) ? v.toFixed(digits) : "—";
}

function median(arr) {
  if (!arr.length) return null;
  const s = [...arr].sort((a, b) => a - b);
  return s[Math.floor(s.length / 2)];
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
  throw new Error("크롬을 찾지 못했다. --chrome=경로 로 지정하라.");
}

async function main() {
  const port = Number(args.port ?? 8790);
  const bareWorker = Boolean(args["bare-worker"]);
  const fixture = args.video
    ? await convertVideo(String(args.video), {
        width: Number(args.width ?? 640),
        height: Number(args.height ?? 480),
        fps: Number(args.fps ?? 10),
      })
    : await ensureFixture();
  let session = null;
  if (args.server) {
    session = await bootstrapSession(String(args.server));
    console.log(`세션:   ${session.sessionId} (회원 ${session.memberId}, 6인 매칭 완료)`);
  }
  const server = await startServer(port, {
    rewriteWorkerGraph: !bareWorker,
    proxyTo: args.server ? String(args.server) : null,
  });
  const executablePath = args.chrome ? String(args.chrome) : findChrome();

  console.log(`크롬:   ${executablePath}`);
  console.log(`영상:   ${fixture}`);
  console.log(`워커:   ${bareWorker ? "베어 스펙파이어 그대로(번들러 없음)" : "번들러 대신 서버가 치환"}`);

  const browser = await puppeteer.launch({
    executablePath,
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
  const consoleErrors = [];
  const badResponses = [];
  page.on("console", (m) => {
    if (m.type() === "error") consoleErrors.push(m.text());
  });
  page.on("pageerror", (e) => consoleErrors.push(`pageerror: ${e.message}`));
  page.on("response", (r) => {
    if (r.status() >= 400) badResponses.push(`${r.status()} ${r.url()}`);
  });
  page.on("requestfailed", (r) => badResponses.push(`FAILED ${r.url()} ${r.failure()?.errorText}`));

  const q = new URLSearchParams();
  q.set("sessionId", String(args.session ?? 1));
  q.set("runMs", String(args.runMs ?? 20000));
  if (session) {
    // 같은 오리진(프록시)으로 부른다. crossOrigin 을 주면 일부러 다른 오리진으로 쏜다.
    q.set("server", args.crossOrigin ? String(args.server) : "");
    q.set("token", session.token);
    q.set("sessionId", String(session.sessionId));
  }
  if (args.fastPrompt) q.set("fastPrompt", "1");
  if (args.skipCalibration) q.set("skipCalibration", "1");
  if (args.mainThread) q.set("mainThread", "1");
  if (args.mainIters) q.set("mainIters", String(args.mainIters));
  if (args.mainGapMs) q.set("mainGapMs", String(args.mainGapMs));
  if (args.classicWorker) q.set("classicWorker", "1");

  const url = `http://localhost:${port}/harness/live-page.html?${q}`;
  console.log(`주소:   ${url}\n`);
  await page.goto(url, { waitUntil: "load" });

  const timeoutMs = Number(args.timeoutMs ?? 180000);
  try {
    await page.waitForFunction("window.__probe && window.__probe.done === true", {
      timeout: timeoutMs,
    });
  } catch {
    console.log("!! 시간 안에 끝나지 않았다. 그때까지 모인 것만 출력한다.");
  }

  const probe = await page.evaluate(() => {
    const p = window.__probe ?? {};
    return {
      steps: p.steps, errors: p.errors, frames: p.frames,
      states: p.states, effects: p.effects, posts: p.posts, verdicts: p.verdicts,
      inferenceMs: p.inferenceMs, calibration: p.calibration,
      modelLoadMs: p.modelLoadMs, videoSize: p.videoSize, hasRvfc: p.hasRvfc,
      seqStoreAtStart: p.seqStoreAtStart, seqStoreEnd: p.seqStoreEnd,
      lastFeatures: p.lastFeatures, ticks: (p.ticks ?? []).length,
      workerEnv: p.workerEnv, mainThread: p.mainThread,
      samples: p.samples, videoStartAtMs: p.videoStartAtMs, detectStartAtMs: p.detectStartAtMs,
    };
  });

  // 새로고침 뒤 clientSeq 가 살아남는가 — README 가 "가장 먼저 확인할 항목"으로 지목한 자리.
  let afterReload = null;
  if (!bareWorker && !args["no-reload-check"]) {
    await page.reload({ waitUntil: "load" });
    afterReload = await page.evaluate((key) => {
      try {
        return JSON.parse(sessionStorage.getItem(key) ?? "null");
      } catch {
        return null;
      }
    }, `molock.absence.v1.${args.session ?? 1}`);
  }

  await browser.close();
  server.close();

  const ms = probe.inferenceMs ?? [];
  const sorted = [...ms].sort((a, b) => a - b);
  const pct = (p) => (sorted.length ? sorted[Math.floor(sorted.length * p)] : null);

  console.log("─".repeat(64));
  for (const s of probe.steps ?? []) console.log(`  ${s.name} ${s.detail ?? ""}`);
  console.log("─".repeat(64));
  console.log(`영상            ${probe.videoSize?.w}x${probe.videoSize?.h}`);
  console.log(`rVFC            ${probe.hasRvfc}`);
  console.log(`모듈 워커 환경  ${JSON.stringify(probe.workerEnv)}`);
  if (probe.mainThread) {
    const im = [...probe.mainThread.inferMs].sort((a,b)=>a-b);
    console.log(`메인 로딩       ${probe.mainThread.loadMs}ms`);
    console.log(`메인 추론       ${im.length}회 중앙 ${im[Math.floor(im.length/2)]?.toFixed(1)}ms 최대 ${im.at(-1)?.toFixed(1)}ms`);
    console.log(`메인 검출       ${probe.mainThread.present}/${probe.mainThread.total} (${(100*probe.mainThread.present/probe.mainThread.total).toFixed(0)}%)`);
    console.log(`메인 특징       ${JSON.stringify(probe.mainThread.features)}`);
  }
  console.log(`모델 로딩       ${probe.modelLoadMs}ms`);
  console.log(`추론 프레임     ${probe.frames}장, tick ${probe.ticks}회`);
  console.log(`프레임 간격     중앙 ${pct(0.5)}ms / p90 ${pct(0.9)}ms (목표 500ms=2Hz)`);
  console.log(`캘리브레이션    ${JSON.stringify(probe.calibration && {
    elapsedMs: probe.calibration.elapsedMs,
    hasProfile: probe.calibration.hasProfile,
    warnings: probe.calibration.warnings,
  })}`);
  console.log(`상태 전이       ${(probe.states ?? []).join(" → ")}`);
  console.log(`효과            ${(probe.effects ?? []).map((e) => e.kind).join(", ") || "없음"}`);
  console.log(`SS-4 전송       ${(probe.posts ?? []).length}건`);
  for (const p of probe.posts ?? []) {
    console.log(`   ${p.mode} ${p.body.type} seq=${p.body.clientSeq} at=${p.body.occurredAt}` +
      (p.outcome ? ` → ${p.outcome.kind}` : ""));
  }
  console.log(`서버 판정       ${JSON.stringify(probe.verdicts)}`);
  console.log(`저장소(시작)    ${JSON.stringify(probe.seqStoreAtStart)}`);
  console.log(`저장소(끝)      ${JSON.stringify(probe.seqStoreEnd)}`);
  if (afterReload !== null) console.log(`저장소(새로고침) ${JSON.stringify(afterReload)}`);
  console.log(`마지막 특징     ${JSON.stringify(probe.lastFeatures)}`);
  if ((probe.errors ?? []).length) console.log(`오류            ${JSON.stringify(probe.errors, null, 2)}`);
  if (consoleErrors.length) console.log(`콘솔 오류       ${consoleErrors.slice(0, 5).join("\n                ")}`);
  if (badResponses.length) console.log(`실패한 요청     ${badResponses.slice(0, 8).join("\n                ")}`);

  if (args.video) {
    const a = analyseSamples(probe.samples, probe.videoStartAtMs, Number(args.bucket ?? 5));
    if (!a) {
      console.log("\n영상 분석: 프레임이 하나도 없다. 위 오류를 먼저 본다.");
    } else {
      console.log("\n" + "═".repeat(64));
      console.log("영상 분석 — 대본과 맞대어 읽는다");
      console.log("═".repeat(64));
      console.log(`전체 검출률   ${a.presentPct.toFixed(1)}% (${a.frames}프레임)`);
      console.log(`pitch(검출)   최소 ${fmt(a.pitchAll.min)} · 중앙 ${fmt(a.pitchAll.median)} · 최대 ${fmt(a.pitchAll.max)}`);
      console.log(`\n초당 타임라인 ('#' 얼굴 검출 · '.' 미검출) — 0초가 영상 시작`);
      for (let i = 0; i < a.timeline.length; i += 60) {
        console.log(`  ${String(i).padStart(3)}s |${a.timeline.slice(i, i + 60)}|`);
      }
      console.log(`\n미검출이 이어진 구간 (1초 이상)`);
      if (!a.gaps.length) console.log("  없음 — 영상 내내 얼굴이 잡혔다");
      for (const g of a.gaps) {
        console.log(`  ${g.fromSec.toFixed(1)}s ~ ${g.toSec.toFixed(1)}s  (${g.lenSec.toFixed(1)}초)`);
      }
      console.log(`\n구간별 (${args.bucket ?? 5}초 단위)`);
      console.log("  시작    프레임  검출률   pitch중앙  얼굴크기  밝기");
      for (const b of a.buckets) {
        console.log(`  ${String(b.fromSec).padStart(4)}s   ${String(b.frames).padStart(5)}  ` +
          `${b.presentPct.toFixed(0).padStart(5)}%  ${fmt(b.pitchMedian).padStart(9)}  ` +
          `${fmt(b.faceSizeMedian, 3).padStart(8)}  ${fmt(b.lumMedian, 2).padStart(5)}`);
      }
      console.log("\n읽는 법은 README 6-1 을 본다.");
    }
  }
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
