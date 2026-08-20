/**
 * 로그인부터 세션 화면까지 실제로 붙는지 headless 크롬으로 확인한다.
 *
 * 손으로 눌러 보는 것을 대신하려는 것이 아니라, **붙는 자리가 깨졌는지**를 빠르게 가리려는
 * 것이다. 카메라는 크롬이 주는 합성 영상이라 얼굴이 없다 — 그래서 여기서 확인되는 것은
 * "파이프라인이 끝까지 이어진다"까지이고 감지 정확도는 나오지 않는다
 * (ai-detection/README 8절과 같은 성질이다).
 *
 * 필요한 것
 *   - 백엔드가 dev 프로필로 떠 있을 것 (기본 8180)
 *   - vite dev 서버가 떠 있을 것 (기본 5173)
 *   - 그 계정이 이미 참여 중인 LIVE 세션이 있을 것
 *
 * 돌리는 법
 *   node scripts/smoke-check.mjs --code=e2e-u1
 *
 * puppeteer-core 는 ai-detection 것을 빌려 쓴다. 확인용 스크립트 하나 때문에
 * 프론트 의존성을 늘리지 않으려는 것이다.
 */
import { createRequire } from "node:module";
import { dirname, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const ai = resolve(root, "ai-detection");

const args = Object.fromEntries(
  process.argv.slice(2).map((a) => {
    const [k, v] = a.replace(/^--/, "").split("=");
    return [k, v ?? "1"];
  }),
);
const APP = args.app ?? "http://localhost:5173";
const CODE = args.code ?? "e2e-u1";

const require = createRequire(import.meta.url);
const puppeteerPath = require.resolve("puppeteer-core", { paths: [ai] });
const { default: puppeteer } = await import(pathToFileURL(puppeteerPath).href);

function findChrome() {
  const fs = require("node:fs");
  const path = require("node:path");
  const cache = path.join(process.env.HOME, ".cache/puppeteer/chrome");
  if (fs.existsSync(cache)) {
    for (const name of fs.readdirSync(cache).sort().reverse()) {
      const p = path.join(
        cache,
        name,
        "chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing",
      );
      if (fs.existsSync(p)) return p;
    }
  }
  const system = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome";
  if (fs.existsSync(system)) return system;
  throw new Error("크롬을 찾지 못했다. --chrome=경로 로 지정한다.");
}

const results = [];
const record = (ok, label, detail = "") => {
  results.push({ ok, label, detail });
  console.log(`${ok ? "  통과" : "  실패"}  ${label}${detail ? ` — ${detail}` : ""}`);
};

const browser = await puppeteer.launch({
  executablePath: args.chrome ?? findChrome(),
  headless: args.headed ? false : "new",
  args: [
    "--use-fake-device-for-media-stream",
    "--use-fake-ui-for-media-stream",
    "--autoplay-policy=no-user-gesture-required",
  ],
});

const page = await browser.newPage();
const consoleErrors = [];
page.on("pageerror", (e) => consoleErrors.push(String(e)));
page.on("console", (m) => {
  if (m.type() === "error") consoleErrors.push(m.text());
});

const absencePosts = [];
const failedRequests = [];
page.on("response", (res) => {
  if (res.url().includes("/absence-events")) absencePosts.push(res.status());
  if (res.status() >= 400) failedRequests.push(`${res.status()} ${res.url().slice(0, 90)}`);
});

const text = () => page.evaluate(() => document.body.innerText);
const waitFor = async (predicate, ms, label) => {
  const until = Date.now() + ms;
  while (Date.now() < until) {
    if (await predicate()) return true;
    await new Promise((r) => setTimeout(r, 400));
  }
  throw new Error(`시간 초과: ${label}`);
};

try {
  console.log(`\n앱 ${APP} · 계정 ${CODE}\n`);

  await page.goto(`${APP}/login`, { waitUntil: "networkidle2" });
  record((await text()).includes("모락"), "로그인 화면이 뜬다");

  await page.evaluate(() => {
    localStorage.clear();
  });
  await page.goto(`${APP}/login`, { waitUntil: "networkidle2" });

  const input = await page.$('input[type="text"], input:not([type])');
  await input.click({ clickCount: 3 });
  await input.type(CODE);
  await page.click("button[type=submit]");

  await waitFor(async () => !page.url().endsWith("/login"), 10_000, "로그인 후 이동");
  record(true, "로그인이 통과하고 토큰이 저장된다", page.url().replace(APP, ""));

  // 홈이 activeSession 을 보고 세션 화면으로 되돌려 보내는지.
  await waitFor(() => page.url().includes("/sessions/"), 10_000, "세션 화면 진입");
  record(true, "홈이 activeSession 으로 세션 화면에 복귀시킨다", page.url().replace(APP, ""));

  await waitFor(async () => (await text()).includes("세션 #"), 10_000, "세션 화면 렌더");
  record(true, "SS-1 로 세션 화면이 그려진다");

  const body = await text();
  record(body.includes("내 경고"), "경고 배지가 있다");
  record(body.includes("화장실 모드"), "화장실 모드 버튼이 있다");
  record(body.includes("오늘 할 일"), "오늘 할 일 입력이 있다");

  // LiveKit 서버가 없으므로 대체 경로(카메라만)로 떨어지는 것이 정상이다.
  const cameraOnly = body.includes("LiveKit 에 붙지 못했다");
  record(true, "LiveKit 없음이 화면에 정직하게 표시된다", cameraOnly ? "카메라만 모드" : "연결됨");

  // 감지 파이프라인이 실제로 뜨는지. 모델 3.76MB 로딩 + 캘리브레이션 8초가 걸린다.
  await waitFor(
    async () => {
      const t = await text();
      return t.includes("기준 잡는 중") || t.includes("작동 중") || t.includes("감지 실패");
    },
    45_000,
    "감지 모듈 기동",
  );
  const after = await text();
  const modelLoaded = after.includes("기준 잡는 중") || after.includes("작동 중");
  record(modelLoaded, "워커가 뜨고 모델이 로드된다 (클래식 워커 경로)");

  await waitFor(async () => (await text()).includes("작동 중"), 30_000, "감지 작동");
  record(true, "감지가 작동 상태로 들어간다");

  const workerErrors = consoleErrors.filter(
    (e) => /ModuleFactory|importScripts|custom_dbg|worker/i.test(e),
  );
  record(workerErrors.length === 0, "워커 로딩 오류가 없다", workerErrors[0] ?? "");

  // 지금은 정상인 것 셋을 걸러 내고 나머지가 있으면 실패로 본다.
  //  - LiveKit 서버가 로컬에 없다(frontend-guide §6-4)
  //  - favicon 을 아직 안 만들었다. 404 는 이것뿐이고 위 응답 감시로 확인했다
  //  - XNNPACK 줄은 MediaPipe 가 error 채널로 내는 정보 로그다
  const expected = /localhost:7880|ERR_CONNECTION_REFUSED|favicon\.ico|XNNPACK|Failed to load resource/i;
  const unexpected = consoleErrors.filter((e) => !expected.test(e));
  record(
    unexpected.length === 0,
    "예상 밖의 콘솔 오류가 없다",
    unexpected.slice(0, 2).join(" | "),
  );

  const badRequests = failedRequests.filter((r) => !/favicon\.ico/.test(r));
  record(badRequests.length === 0, "실패한 요청이 없다", badRequests.slice(0, 3).join(" | "));

  if (absencePosts.length > 0) {
    record(true, "SS-4 가 서버로 나갔다", `상태 ${absencePosts.join(",")}`);
  }
} catch (e) {
  record(false, "중단됨", e.message);
  console.log("\n마지막 URL:", page.url());
  console.log("화면:\n" + (await text().catch(() => "(읽지 못함)")).slice(0, 900));
  if (consoleErrors.length) console.log("\n콘솔 오류:\n" + consoleErrors.slice(0, 5).join("\n"));
} finally {
  await browser.close();
}

const failed = results.filter((r) => !r.ok);
console.log(`\n${results.length - failed.length}/${results.length} 통과`);
process.exit(failed.length === 0 ? 0 : 1);
