import type { CalibrationProfile } from "../src/calibration.js";
import { fromPauseStart } from "../src/client/controller.js";
import { AbsenceEventSender, type AbsenceTransport, type TransportOutcome } from "../src/client/sender.js";
import { AbsenceSeqStore, type StorageLike } from "../src/client/seq-store.js";
import { DEFAULT_CONFIG } from "../src/config.js";
import { Scorer, type ScoreResult } from "../src/scoring.js";
import { settle, labelsToReports } from "../src/server-rule.js";
import { AbsenceStateMachine } from "../src/state-machine.js";
import type { AbsenceEventBody, Effect, FrameFeatures, Verdict } from "../src/types.js";
import { replay } from "./replay.js";
import { scoreClip } from "./score.js";
import type { GroundTruth, InferenceLog } from "./log-format.js";

/**
 * 상태기계와 서버 규칙의 자체 점검. 합성 입력으로 계약을 확인한다.
 *
 * 브라우저 실동작(카메라·모델 추론)은 여기서 검증할 수 없다. 여기서 확인하는 것은
 * "판정 로직이 설계 문서대로 도는가"뿐이다.
 *
 *   node dist/harness/selftest.js
 */

let failures = 0;

function check(name: string, actual: unknown, expected: unknown): void {
  const a = JSON.stringify(actual);
  const e = JSON.stringify(expected);
  if (a === e) {
    console.log(`  통과  ${name}`);
  } else {
    failures += 1;
    console.log(`  실패  ${name}\n        기대: ${e}\n        실제: ${a}`);
  }
}

/** 2Hz 로 프레임을 흘려 넣고 START/END 만 뽑는다 */
function run(
  script: { verdict: Verdict; frames: number; lowLight?: boolean }[],
  taps: number[] = [],
): {
  events: { type: string; at: number; emittedAt: number }[];
  prompts: number[];
  effects: Effect[];
} {
  const machine = new AbsenceStateMachine(DEFAULT_CONFIG.stateMachine);
  const stepMs = 500;
  // at = occurredAt(서버로 나가는 시각), emittedAt = 실제로 그 판단이 내려진 프레임 시각.
  // 둘을 따로 두는 이유는 이 파이프라인의 계약이 정확히 "둘이 다르다"이기 때문이다.
  const events: { type: string; at: number; emittedAt: number }[] = [];
  const prompts: number[] = [];
  const all: Effect[] = [];
  const tapQueue = [...taps];

  let t = 0;
  for (const seg of script) {
    for (let i = 0; i < seg.frames; i += 1) {
      const effects = machine.onFrame(t, seg.verdict, seg.lowLight ?? false);
      collect(effects);
      // 프롬프트가 떠 있는 동안 예약된 탭 시각이 지났으면 탭한다.
      if (tapQueue.length > 0 && tapQueue[0] !== undefined && t >= tapQueue[0]) {
        tapQueue.shift();
        collect(machine.onPromptTap(t));
      }
      t += stepMs;
    }
  }

  function collect(effects: Effect[]): void {
    for (const e of effects) {
      all.push(e);
      if (e.kind === "ABSENCE_START") {
        events.push({ type: "START", at: e.occurredAtMs, emittedAt: t });
      }
      if (e.kind === "ABSENCE_END") {
        events.push({ type: "END", at: e.occurredAtMs, emittedAt: t });
      }
      if (e.kind === "SHOW_PROMPT") prompts.push(t);
    }
  }

  return { events, prompts, effects: all };
}

/** 전이 효과에서 도착 상태만 뽑는다. 카운터 계열 판정은 이걸로 본다 */
function statesOf(effects: Effect[]): string[] {
  return effects.filter((e) => e.kind === "STATE").map((e) => (e as { to: string }).to);
}

console.log("상태기계");

// 1. 짧은 미검출은 아무것도 만들지 않는다.
{
  const { events, prompts } = run([
    { verdict: "HIT", frames: 10 },
    { verdict: "MISS", frames: 4 }, // 2초 — 3초 미만
    { verdict: "HIT", frames: 10 },
  ]);
  check("2초 미검출: 이벤트 없음", events, []);
  check("2초 미검출: 프롬프트 없음", prompts.length, 0);
}

// 2. 30초를 넘겨도 프롬프트만 뜨고, 탭하면 서버로 아무것도 안 나간다.
{
  // t=5000 에 첫 MISS → t0=5000. 프롬프트는 t0+30초 = 35000 에 뜬다.
  const { events, prompts } = run(
    [
      { verdict: "HIT", frames: 10 },
      { verdict: "MISS", frames: 120 }, // 60초
      { verdict: "HIT", frames: 10 },
    ],
    [36_000],
  );
  check("프롬프트 1회", prompts.length, 1);
  check("탭하면 서버 전송 없음", events, []);
}

// 3. 무응답이면 START 가 나가고, occurredAt 은 판단 시각이 아니라 t0 다.
{
  const { events } = run([
    { verdict: "HIT", frames: 10 }, // 0~4500
    { verdict: "MISS", frames: 200 }, // t0 = 5000
    { verdict: "HIT", frames: 10 },
  ]);
  // 프롬프트 35000 → 무응답 15초 → 50000 에 START. 시각은 t0=5000.
  check("START 1건 + END 1건", events.map((e) => e.type), ["START", "END"]);
  check("START occurredAt = t0", events[0]?.at, 5000);
  // 보내는 시각과 적는 시각이 다르다는 것이 §7-1 의 계약이다. 적는 시각만 확인하면
  // 프롬프트 지연·무응답 창이 어떤 값이든 통과한다.
  check("START 전송은 프롬프트(35초) + 무응답 15초 뒤", events[0]?.emittedAt, 50_000);
  // MISS 200프레임 = 5000~104500. 첫 HIT 는 105000.
  check("END occurredAt = 첫 재검출 프레임", events[1]?.at, 105_000);
  // 복귀는 3프레임(1.5초) 연속 검출이므로 전송은 세 번째 프레임에서 일어난다
  check("END 전송은 3프레임째", events[1]?.emittedAt, 106_000);
}

// 3-1. 복귀 임계는 3프레임이다. 한 장의 깜빡임이 자리비움 구간을 닫지 못한다.
{
  const { events } = run([
    { verdict: "HIT", frames: 10 },
    { verdict: "MISS", frames: 200 }, // START 까지 간다
    { verdict: "HIT", frames: 2 }, // 1초 — 임계 미만
    { verdict: "MISS", frames: 20 },
    { verdict: "HIT", frames: 2 }, // 또 1초
    { verdict: "MISS", frames: 20 },
  ]);
  // 한 장짜리 검출로 END 가 나가면 실제로는 자리에 없는 사람의 구간이 잘게 쪼개져,
  // 60초를 넘긴 자리비움이 임계 미만 조각 여럿으로 흩어진다
  check("2프레임 검출로는 END 가 나가지 않음", events.map((e) => e.type), ["START"]);
}

// 4. 탭한 뒤 무응답이면 시각은 마지막 탭 시각이다.
{
  const { events } = run(
    [
      { verdict: "HIT", frames: 10 },
      { verdict: "MISS", frames: 600 }, // 300초
      { verdict: "HIT", frames: 10 },
    ],
    [36_000],
  );
  check("탭 후 무응답: START occurredAt = 마지막 탭 시각", events[0]?.at, 36_000);
}

// 5. UNCERTAIN 은 카운터를 동결한다. 동결 상한(10초)까지는 미검출로 세지 않는다.
//
// 판정을 상태 전이로 본다. 이벤트·프롬프트로 보면 t0+30초라는 프롬프트 지연 때문에 짧은
// 클립에서는 동결이 있든 없든 똑같이 "아무 일도 없음"이 되어, 3값 논리를 지워도 통과한다.
// CANDIDATE 도달 여부는 미검출 카운터만 보고 갈리므로 동결을 정확히 겨눈다.
{
  const { effects } = run([
    { verdict: "HIT", frames: 10 },
    { verdict: "MISS", frames: 2 }, // 1초 — 미검출 2프레임(임계 6 미만)
    { verdict: "UNCERTAIN", frames: 20 }, // 10초 — 동결 상한 이내
  ]);
  const states = statesOf(effects);
  // 동결이 없으면 미검출이 22프레임으로 세어져 CANDIDATE 로 올라간다
  check("동결 중에는 CANDIDATE 로 올라가지 않음", states.includes("CANDIDATE"), false);
  check("동결 구간의 상태는 OBSERVING", states.at(-1), "OBSERVING");
}

// 6. 동결 상한을 넘기면 MISS 로 격하된다. 무한 보류를 막는 장치다.
{
  const { effects, prompts } = run([
    { verdict: "HIT", frames: 10 },
    { verdict: "MISS", frames: 2 },
    { verdict: "UNCERTAIN", frames: 200 }, // 100초 — 상한 10초를 크게 넘김
    { verdict: "HIT", frames: 10 },
  ]);
  // 상한이 없으면 영원히 동결돼 CANDIDATE 에 닿지 못한다
  check("동결 상한 초과 후 CANDIDATE 도달", statesOf(effects).includes("CANDIDATE"), true);
  check("동결 상한 초과 후 프롬프트 발생", prompts.length >= 1, true);
}

// 6-1. 저조도에서는 동결 상한이 20초로 늘어난다.
// 같은 15초 UNCERTAIN 이 조도에 따라 갈리는지를 본다 — 한쪽만 보면 상한값이 어떤 값이든 통과한다.
{
  const script = (lowLight: boolean) => [
    { verdict: "HIT" as const, frames: 10 },
    { verdict: "MISS" as const, frames: 2 },
    { verdict: "UNCERTAIN" as const, frames: 30, lowLight }, // 15초
  ];
  check(
    "저조도 아님: 15초 UNCERTAIN 은 상한(10초)을 넘겨 CANDIDATE",
    statesOf(run(script(false)).effects).includes("CANDIDATE"),
    true,
  );
  check(
    "저조도: 같은 15초가 상한(20초) 안이라 아직 동결",
    statesOf(run(script(true)).effects).includes("CANDIDATE"),
    false,
  );
}

// 7. 프레임 공백(탭 백그라운드)은 사건을 만들지 않는다.
{
  const machine = new AbsenceStateMachine(DEFAULT_CONFIG.stateMachine);
  const events: string[] = [];
  for (let t = 0; t < 5000; t += 500) machine.onFrame(t, "HIT", false);
  for (let t = 5000; t < 20_000; t += 500) machine.onFrame(t, "MISS", false);
  // 5분 공백 후 복귀
  for (const e of machine.onFrame(320_000, "MISS", false)) events.push(e.kind);
  check("공백 뒤 첫 프레임에서 전송 없음", events.filter((k) => k.startsWith("ABSENCE")), []);
  check("공백 뒤 상태는 OBSERVING", machine.currentState, "OBSERVING");
}

// 8. 열린 START 는 새로고침 뒤에도 END 로 닫힌다.
{
  const machine = new AbsenceStateMachine(DEFAULT_CONFIG.stateMachine);
  machine.restoreOpenAbsence();
  check("복원 직후 열린 구간 있음", machine.hasOpenAbsence, true);
  const events: { kind: string; at?: number }[] = [];
  for (let t = 0; t < 3000; t += 500) {
    for (const e of machine.onFrame(t, "HIT", false)) {
      if (e.kind === "ABSENCE_END") events.push({ kind: e.kind, at: e.occurredAtMs });
    }
  }
  check("복원 후 복귀하면 END 전송", events.length, 1);
  check("END occurredAt = 첫 재검출 프레임", events[0]?.at, 0);
}

console.log("\n서버 규칙(SS-4 재현)");

{
  const r = settle(
    labelsToReports([
      { startMs: 0, endMs: 30_000 }, // 30초 — 경고 없음
      { startMs: 60_000, endMs: 121_000 }, // 61초 — 경고
      { startMs: 200_000, endMs: 260_000 }, // 정확히 60초 — "초과"가 아니라 경고 없음
    ]),
    600_000,
    DEFAULT_CONFIG.serverRule,
  );
  check("60초 초과만 경고", r.warnings.length, 1);
  check("퇴출 아님", r.evicted, false);
}

{
  const r = settle(
    [{ type: "START", occurredAtMs: 100_000 }],
    600_000,
    DEFAULT_CONFIG.serverRule,
  );
  check("짝 없는 START 는 세션 종료 시각으로 닫힘", r.intervals[0]?.endMs, 600_000);
  // 500초짜리 구간이다. 임계(60초)를 넘겼으니 1회, 눈금(300초)을 한 번 더 넘겼으니 2회다.
  check("그 결과 경고", r.warnings.length, 2);
}

{
  // 길이가 판정에 들어가는지. 서버가 이 계산을 바꾸면 여기가 어긋나 오탐률 측정이 통째로
  // 틀어진다 — 정답 경고 시퀀스를 만드는 것이 이 함수다(README 5절).
  const one = settle(
    labelsToReports([{ startMs: 0, endMs: 61_000 }]),
    3_600_000,
    DEFAULT_CONFIG.serverRule,
  );
  check("임계 바로 위는 1회", one.warnings.length, 1);

  const long = settle(
    labelsToReports([{ startMs: 0, endMs: 2_700_000 }]), // 45분
    3_600_000,
    DEFAULT_CONFIG.serverRule,
  );
  check("45분 한 구간이면 상한까지 쌓여 퇴출", long.evicted, true);
  check("상한을 넘겨 쌓지 않는다", long.warnings.length, DEFAULT_CONFIG.serverRule.evictWarningCount);
}

{
  const r = settle(
    labelsToReports([
      { startMs: 0, endMs: 70_000 },
      { startMs: 100_000, endMs: 180_000 },
      { startMs: 200_000, endMs: 280_000 },
    ]),
    600_000,
    DEFAULT_CONFIG.serverRule,
  );
  check("경고 3회면 퇴출", r.evicted, true);
}

{
  // 서버는 Duration.getSeconds() 로 정수 초까지 내린 값을 판정에 넣는다(AbsenceWarningPolicy).
  // 여기서 밀리초를 남긴 채 같은 식을 쓰면 -1 이 1초가 아니라 1밀리초가 되어 소수점이 붙은
  // 구간마다 경고가 한 회씩 더 붙는다. 위 케이스들은 전부 정수 초라 그 차이가 드러나지 않는다.
  const interval = (durationMs: number) =>
    settle(
      labelsToReports([{ startMs: 0, endMs: durationMs }]),
      3_600_000,
      DEFAULT_CONFIG.serverRule,
    ).intervals[0];

  // 60.5초는 서버가 60초로 내리는 값이라 "초과"가 아니다.
  check("60.5초는 내리면 임계와 같아 경고 없음", interval(60_500)?.warningCount, 0);
  check("그 초도 반올림이 아니라 내림이다", interval(60_500)?.seconds, 60);
  check("61.0초부터 1회", interval(61_000)?.warningCount, 1);
  // 눈금(300초)을 넘는 자리도 기준은 같다. 360.5초는 내리면 360초라 아직 첫 회 안이다.
  check("360.5초는 아직 1회", interval(360_500)?.warningCount, 1);
  check("361.0초부터 2회", interval(361_000)?.warningCount, 2);
  check("660.5초는 아직 2회", interval(660_500)?.warningCount, 2);
  check("661.0초부터 3회 — 한 구간만으로 퇴출되는 자리다", interval(661_000)?.warningCount, 3);
}

{
  // 서버는 구간의 양끝을 세션 예정 종료 시각에서 끊고 잰다(AbsenceJudgeService.absentSeconds).
  // ends_at 이 지났는데 종료 배치가 아직 돌지 않은 최대 1분 동안 세션은 LIVE라 이런 구간이
  // 실제로 도착한다. 세션이 끝난 뒤는 자리를 지킬 의무가 없는 시간이라 경고에 실리면 안 된다.
  const endMs = 600_000;
  const settleOne = (startMs: number, closeMs: number) =>
    settle(
      labelsToReports([{ startMs, endMs: closeMs }]),
      endMs,
      DEFAULT_CONFIG.serverRule,
    ).intervals[0];

  // 종료 5초 뒤에 START, 200초 뒤에 END. 겹친 시간이 없으니 0초다. 양끝을 끊지 않으면 195초가
  // 되고, 시작만 두고 끝만 끊으면 뺄셈이 뒤집혀 -5초가 나온다.
  const afterEnd = settleOne(endMs + 5_000, endMs + 200_000);
  check("종료 뒤에만 걸친 구간은 0초", afterEnd?.seconds, 0);
  check("그래서 경고도 없다", afterEnd?.warningCount, 0);

  // 55초 비우고 종료 20초 뒤에 END 가 도착한 경우. 끊지 않으면 75초가 되어 경고가 붙는데,
  // 같은 자리비움을 종료 배치가 먼저 집었다면 55초라 경고가 없다. 누가 먼저 닿았느냐로
  // 결과가 갈리면 안 된다.
  const straddling = settleOne(endMs - 55_000, endMs + 20_000);
  check("종료를 걸친 구간은 종료 시각까지만 센다", straddling?.seconds, 55);
  check("임계 이하라 경고 없음", straddling?.warningCount, 0);
}

console.log("\n각도 안정화 (scoring.ts)");

{
  // 각도가 쓰이는 곳은 `isHeadDown` 한 곳뿐이고, 그 결과는 미검출 프레임의 reason 으로 드러난다.
  // ABSENT_HEAD_DOWN 이 나왔다는 것은 "직전까지 고개 숙임으로 추적되던 중"으로 판단했다는 뜻이다.
  const profile: CalibrationProfile = {
    faceSize: 0.3,
    pitchDeg: 0,
    luminance: 0.5,
    samples: 16,
    detectionRate: 1,
    measuredAtMs: 0,
  };
  const frame = (tMs: number, pitchDeg: number | null): FrameFeatures => ({
    tMs,
    present: pitchDeg !== null,
    faceSize: pitchDeg !== null ? 0.3 : null,
    pitchDeg,
    yawDeg: 0,
    rollDeg: 0,
    luminance: 0.5,
    inferenceMs: 20,
  });

  /** 각도 대본을 흘려 넣고 마지막(미검출) 프레임의 reason 을 돌려준다 */
  const reasonAfter = (pitches: (number | null)[], overrides = {}) => {
    const scorer = new Scorer({ ...DEFAULT_CONFIG.scoring, ...overrides }, profile);
    let last: ScoreResult | undefined;
    pitches.forEach((p, i) => {
      last = scorer.score(frame(i * 500, p));
    });
    return last?.reason;
  };

  // 정상: 실제로 고개를 숙이고 있다가 얼굴을 놓치면 그 미검출을 덜 믿는다(§6 보호 장치).
  check(
    "지속된 고개 숙임은 그대로 인식된다",
    reasonAfter([-20, -20, -20, null]),
    "ABSENT_HEAD_DOWN",
  );

  // 자세 추정이 깨진 프레임(-104°). 방어가 둘 겹쳐 있어 하나씩 따로 겨눈다.
  check(
    "허용치를 넘는 pitch 는 각도만 버린다",
    reasonAfter([0, 0, -104, null]),
    "ABSENT",
  );
  // 중앙값을 꺼 두고 게이트만 남긴다 → 여전히 막혀야 한다
  check(
    "중앙값 없이도 크기 게이트 혼자 막는다",
    reasonAfter([0, 0, -104, null], { pitchMedianWindow: 1 }),
    "ABSENT",
  );
  // 둘 다 풀면 반대로 나와야 한다 — 이 규칙에 테스트가 있다는 증거다(README 8-1)
  check(
    "둘 다 풀면 같은 대본이 고개 숙임으로 오판된다",
    reasonAfter([0, 0, -104, null], { pitchMedianWindow: 1, maxPlausiblePitchDeg: 1000 }),
    "ABSENT_HEAD_DOWN",
  );

  // 허용치 안이지만 튄 값(-40°). 게이트는 못 잡고 중앙값 창이 잡는다.
  check(
    "허용치 안의 단발 이상치는 중앙값이 흡수한다",
    reasonAfter([0, 0, -40, null]),
    "ABSENT",
  );
  check(
    "중앙값 창을 1로 두면 같은 대본이 고개 숙임으로 오판된다",
    reasonAfter([0, 0, -40, null], { pitchMedianWindow: 1 }),
    "ABSENT_HEAD_DOWN",
  );

  // 검출 여부는 각도와 무관해야 한다 — "얼굴이 잡히면 각도 무관 재실"(§3).
  const scorer = new Scorer(DEFAULT_CONFIG.scoring, profile);
  check("이상 각도 프레임도 검출은 HIT 이다", scorer.score(frame(0, -104)).verdict, "HIT");
}

console.log("\n전송 계층 — 조용히 사라지는 경로 (P-wiring W1·W2·W6)");

{
  /** 브라우저 저장소 대역. Node 에는 localStorage 가 없다 */
  class FakeStorage implements StorageLike {
    private map = new Map<string, string>();
    getItem(k: string): string | null {
      return this.map.get(k) ?? null;
    }
    setItem(k: string, v: string): void {
      this.map.set(k, v);
    }
    removeItem(k: string): void {
      this.map.delete(k);
    }
    /** 탭을 닫아 sessionStorage 가 날아간 상황 */
    wipe(): void {
      this.map.clear();
    }
  }

  /** 원하는 결과를 순서대로 돌려주는 대역 전송 */
  class FakeTransport implements AbsenceTransport {
    sent: AbsenceEventBody[] = [];
    constructor(private readonly plan: (body: AbsenceEventBody, n: number) => TransportOutcome) {}
    post(body: AbsenceEventBody): Promise<TransportOutcome> {
      this.sent.push({ ...body });
      return Promise.resolve(this.plan(body, this.sent.length));
    }
  }

  const NOW = 1_700_000_000_000;
  const okResponse = {
    accepted: true,
    warningCount: 0,
    evicted: false,
    evictionId: null,
    pointDelta: 0,
    closedAbsenceSeconds: null,
  };
  // 페이싱을 0 으로 둬야 테스트가 5초씩 기다리지 않는다. 페이싱 자체는 이 테스트의 대상이 아니다.
  const fastTransport = { ...DEFAULT_CONFIG.transport, minIntervalMs: 0, retryAfterRateLimitMs: 0 };
  const tick = () => new Promise((r) => setTimeout(r, 5));

  // ── W1. 번호 발급 ────────────────────────────────────────────────────
  {
    // 첫 탭: 세션 시작 100초 뒤에 들어와 이벤트 둘을 쓴다.
    const storage = new FakeStorage();
    const first = new AbsenceSeqStore({
      sessionId: 1,
      sessionStartedAtMs: NOW,
      now: () => NOW + 100_000,
      storage,
    });
    first.enqueue("START", "x");
    first.enqueue("END", "y");
    const firstMax = first.nextSeq - 1;

    // 탭을 닫는다(sessionStorage 였다면 여기서 전부 날아간다). 200초째에 새로 들어온다.
    storage.wipe();
    const second = new AbsenceSeqStore({
      sessionId: 1,
      sessionStartedAtMs: NOW,
      now: () => NOW + 200_000,
      storage,
    });
    check(
      "저장소가 비어도 세션 경과초가 번호의 바닥이 된다",
      second.enqueue("START", "z").clientSeq > firstMax,
      true,
    );

    // 되돌려 확인 — sessionStartedAtMs 가 없으면 1 로 되돌아가 앞 실행과 겹친다(W1 그 자체다)
    storage.wipe();
    const noFloor = new AbsenceSeqStore({ sessionId: 1, now: () => NOW + 200_000, storage });
    check(
      "바닥이 없으면 1 로 되돌아가 앞 실행과 겹친다",
      noFloor.enqueue("START", "z").clientSeq <= firstMax,
      true,
    );
  }

  {
    // 저장소가 살아 있으면(탭만 새로 열었다) 이어받는다 — localStorage 로 바꾼 효과
    const storage = new FakeStorage();
    const a = new AbsenceSeqStore({ sessionId: 2, storage });
    a.enqueue("START", "x");
    a.enqueue("END", "y");
    const b = new AbsenceSeqStore({ sessionId: 2, storage });
    check("저장소가 남아 있으면 번호를 이어받는다", b.enqueue("START", "z").clientSeq, 3);
  }

  // ── W1. 보낸 적 없는 번호에 409 가 오면 재발급한다 ────────────────────
  {
    const storage = new FakeStorage();
    const store = new AbsenceSeqStore({ sessionId: 3, storage });
    // 1번은 앞 탭이 이미 썼다고 서버가 답한다. 재발급된 번호는 받아 준다.
    const transport = new FakeTransport((body) =>
      body.clientSeq === 1 ? { kind: "DUPLICATE" } : { kind: "OK", response: okResponse },
    );
    let duplicated: { seq: number; reissuedAs: number | null } | null = null;
    const sender = new AbsenceEventSender(store, transport, fastTransport, {
      onUnexpectedDuplicate: (e, reissuedAs) =>
        (duplicated = { seq: e.clientSeq, reissuedAs }),
    });
    sender.enqueue("START", NOW);
    await tick();

    check("처음 보낸 번호의 409 는 훅으로 알린다", duplicated !== null, true);
    check("그 이벤트를 버리지 않고 다시 보낸다", transport.sent.length >= 2, true);
    check("재전송은 다른 번호로 나간다", transport.sent[1]?.clientSeq !== 1, true);
    check("결국 큐가 비워진다(자리비움이 사라지지 않는다)", sender.pendingCount, 0);
  }

  {
    // 되돌려 확인 — 재전송(attempts>1)의 409 는 계약대로 성공이다. 재발급하면 안 된다.
    const storage = new FakeStorage();
    const store = new AbsenceSeqStore({ sessionId: 4, storage });
    // 1회차는 네트워크 오류, 2회차는 중복 → 내 재전송이 흡수된 정상 경로
    const transport = new FakeTransport((_b, n) =>
      n === 1 ? { kind: "NETWORK_ERROR", message: "끊김" } : { kind: "DUPLICATE" },
    );
    let duplicated = false;
    const sender = new AbsenceEventSender(
      store,
      transport,
      { ...fastTransport, networkRetryBaseMs: 0 },
      { onUnexpectedDuplicate: () => (duplicated = true) },
    );
    sender.enqueue("START", NOW);
    await tick();
    check("재전송이 흡수된 409 는 성공이다", sender.pendingCount, 0);
    check("그때는 훅을 부르지 않는다", duplicated, false);
    check("번호도 그대로다", transport.sent.every((b) => b.clientSeq === 1), true);
  }

  // ── W2. 401 ──────────────────────────────────────────────────────────
  {
    const storage = new FakeStorage();
    const store = new AbsenceSeqStore({ sessionId: 5, storage });
    let unauthorized: string | null = null;
    let terminal: string | null = null;
    let expired = true;
    const transport = new FakeTransport(() =>
      expired ? { kind: "UNAUTHORIZED", code: "TOKEN_EXPIRED" } : { kind: "OK", response: okResponse },
    );
    const sender = new AbsenceEventSender(store, transport, fastTransport, {
      onUnauthorized: (code) => (unauthorized = code),
      onTerminal: (code) => (terminal = code),
    });

    sender.enqueue("START", NOW);
    await tick();
    check("401 은 훅으로 알린다", unauthorized, "TOKEN_EXPIRED");
    check("401 은 종료가 아니다", terminal, null);
    check("큐를 버리지 않는다", sender.pendingCount, 1);
    check("전송이 멈춘다", sender.isPaused, true);

    const sentWhilePaused = transport.sent.length;
    // 멈춰 있는 동안에도 감지는 돈다. 이벤트를 계속 받아야 한다.
    sender.enqueue("END", NOW + 70_000);
    await tick();
    check("멈춘 동안에도 이벤트를 큐에 받는다", sender.pendingCount, 2);
    check("멈춘 동안 전송은 하지 않는다", transport.sent.length, sentWhilePaused);

    // 다시 로그인했다.
    expired = false;
    sender.resume();
    await tick();
    check("재로그인 후 쌓인 것이 전부 나간다", sender.pendingCount, 0);
    check("열린 START 도 닫힌다", sender.hasOpenStart, false);
  }

  {
    // 되돌려 확인 — 401 을 NETWORK_ERROR 로 흘리면(고치기 전 동작) 아무도 모르고 큐만 남는다
    const storage = new FakeStorage();
    const store = new AbsenceSeqStore({ sessionId: 6, storage });
    let unauthorized: string | null = null;
    const transport = new FakeTransport(() => ({
      kind: "NETWORK_ERROR",
      message: "HTTP 401 TOKEN_EXPIRED",
    }));
    const sender = new AbsenceEventSender(
      store,
      transport,
      { ...fastTransport, networkRetryBaseMs: 1, networkRetryMaxMs: 1 },
      { onUnauthorized: (code) => (unauthorized = code) },
    );
    sender.enqueue("START", NOW);
    await tick();
    check("401 을 NETWORK_ERROR 로 흘리면 훅이 불리지 않는다", unauthorized, null);
    check("그리고 계속 두드린다", transport.sent.length > 1, true);
    sender.stop();
  }

  // ── W3. 거절된 END 는 열린 구간 표시를 지우지 않는다 ──────────────────
  {
    const storage = new FakeStorage();
    const store = new AbsenceSeqStore({ sessionId: 7, storage });
    // START 는 통과하고 END 만 시계 문제로 거절되는 상황
    const transport = new FakeTransport((body) =>
      body.type === "END"
        ? { kind: "INVALID", code: "VALIDATION_FAILED" }
        : { kind: "OK", response: okResponse },
    );
    let invalid = 0;
    const sender = new AbsenceEventSender(store, transport, fastTransport, {
      onInvalid: () => (invalid += 1),
    });
    sender.enqueue("START", NOW);
    sender.enqueue("END", NOW + 71_000);
    await tick();

    check("거절된 END 는 훅으로 알린다", invalid, 1);
    check("큐에서는 뺀다", sender.pendingCount, 0);
    // 서버에는 짝 없는 START 가 남아 있다. 표시를 지우면 그것을 닫을 길이 없어진다.
    check("열린 START 표시는 남긴다", sender.hasOpenStart, true);
  }

  {
    // 정상 경로는 그대로 닫혀야 한다 — 위 규칙이 "항상 안 지운다"가 되면 안 된다
    const storage = new FakeStorage();
    const store = new AbsenceSeqStore({ sessionId: 8, storage });
    const transport = new FakeTransport(() => ({ kind: "OK", response: okResponse }));
    const sender = new AbsenceEventSender(store, transport, fastTransport, {});
    sender.enqueue("START", NOW);
    sender.enqueue("END", NOW + 71_000);
    await tick();
    check("받아들여진 END 는 열린 구간을 닫는다", sender.hasOpenStart, false);
  }

  // ── W6. SS-5 경고가 SS-4 와 같은 훅으로 온다 ──────────────────────────
  {
    const mapped = fromPauseStart({
      warningIssued: true,
      warningCount: 2,
      closedAbsenceSeconds: 64,
    });
    check("SS-5 경고가 SS-4 응답 모양으로 옮겨진다", mapped.warningCount, 2);
    check("마감된 구간 초도 함께 옮겨진다", mapped.closedAbsenceSeconds, 64);
    check("SS-5 로는 퇴출이 나지 않는다", mapped.evicted, false);
  }
}

console.log("\n하네스 재계산·대조");

{
  // 합성 로그: 0~600초 재실, 600~750초 이석(150초), 750~1200초 재실.
  const frames = [];
  for (let t = 0; t < 1_200_000; t += 500) {
    const absent = t >= 600_000 && t < 750_000;
    frames.push({
      tMs: t,
      present: !absent,
      faceSize: absent ? null : 0.3,
      pitchDeg: absent ? null : 0,
      yawDeg: absent ? null : 0,
      rollDeg: absent ? null : 0,
      luminance: 0.5,
      inferenceMs: 20,
    });
  }
  const log: InferenceLog = {
    version: 1,
    clipId: "synthetic",
    recordedAt: new Date(0).toISOString(),
    model: { modelAssetPath: "-", modelDetectionConfidence: 0.2, inferenceHz: 2 },
    calibration: {
      faceSize: 0.3,
      pitchDeg: 0,
      luminance: 0.5,
      samples: 16,
      detectionRate: 1,
      measuredAtMs: 0,
    },
    durationMs: 1_200_000,
    frames,
  };
  const truth: GroundTruth = {
    version: 1,
    clipId: "synthetic",
    durationMs: 1_200_000,
    absences: [{ startMs: 600_000, endMs: 750_000 }],
  };

  const replayed = replay(log);
  const report = scoreClip(truth, replayed);

  check("합성 이석: 정답 경고 1건", report.truthWarnings.length, 1);
  check("합성 이석: 실제 경고 1건", report.actualWarnings.length, 1);
  check("오경고 0건", report.falseWarnings.length, 0);
  check("미탐 0건", report.missedAbsences.length, 0);
  check("오탐률 0", report.falsePositiveRate, 0);
  check("START 시각이 실제 이석 시작과 일치", replayed.reports[0]?.occurredAtMs, 600_000);
}

{
  // 검출이 깜빡이기만 하는 클립. 오경고가 0이어야 한다.
  const frames = [];
  for (let t = 0; t < 600_000; t += 500) {
    const flicker = Math.floor(t / 500) % 7 === 0;
    frames.push({
      tMs: t,
      present: !flicker,
      faceSize: flicker ? null : 0.3,
      pitchDeg: flicker ? null : -20,
      yawDeg: 0,
      rollDeg: 0,
      luminance: 0.2,
      inferenceMs: 20,
    });
  }
  const log: InferenceLog = {
    version: 1,
    clipId: "flicker",
    recordedAt: new Date(0).toISOString(),
    model: { modelAssetPath: "-", modelDetectionConfidence: 0.2, inferenceHz: 2 },
    calibration: {
      faceSize: 0.3,
      pitchDeg: 0,
      luminance: 0.2,
      samples: 16,
      detectionRate: 1,
      measuredAtMs: 0,
    },
    durationMs: 600_000,
    frames,
  };
  const truth: GroundTruth = { version: 1, clipId: "flicker", durationMs: 600_000, absences: [] };
  const report = scoreClip(truth, replay(log));
  check("깜빡임만 있는 클립: 오경고 0건", report.falseWarnings.length, 0);
  check("깜빡임만 있는 클립: 프롬프트도 0회", report.promptCount, 0);
}

console.log(failures === 0 ? "\n전부 통과" : `\n실패 ${failures}건`);
if (failures > 0) process.exit(1);
