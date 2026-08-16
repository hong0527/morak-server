import { readFileSync, readdirSync } from "node:fs";
import { join, resolve } from "node:path";
import type { DeepPartial, DetectionConfig } from "../src/config.js";
import { isGroundTruth, isInferenceLog, type GroundTruth, type InferenceLog } from "./log-format.js";
import { replay } from "./replay.js";
import { aggregate, scoreClip, type ScoreReport } from "./score.js";

/**
 * 오탐률 측정 CLI. 추론은 하지 않는다 — 브라우저에서 뽑아 둔 로그만 읽는다.
 *
 *   node dist/harness/cli.js score  --logs <디렉터리> --labels <디렉터리>
 *   node dist/harness/cli.js sweep  --logs <디렉터리> --labels <디렉터리>
 *
 * score 는 현재 기본 설정으로 합격 여부를 낸다.
 * sweep 은 튜닝 손잡이를 순서대로 돌려 어떤 설정이 합격선을 넘는지 표로 낸다
 * (ai-pipeline.md §8: 검출 신뢰도 하향 → 미검출 판정 시간 3초→5초 → 검사 주기 → 모델 교체).
 */

interface Pair {
  log: InferenceLog;
  truth: GroundTruth;
}

function main(): void {
  const args = process.argv.slice(2);
  const command = args[0];
  const logsDir = flag(args, "--logs");
  const labelsDir = flag(args, "--labels");

  if (!command || !logsDir || !labelsDir) {
    console.error(
      "사용법: cli.js <score|sweep> --logs <디렉터리> --labels <디렉터리>",
    );
    process.exit(2);
    return;
  }

  const pairs = loadPairs(logsDir, labelsDir);
  if (pairs.length === 0) {
    console.error("짝이 맞는 로그·라벨이 없다. clipId 가 같은지 확인하라.");
    process.exit(1);
    return;
  }

  if (command === "score") runScore(pairs);
  else if (command === "sweep") runSweep(pairs);
  else {
    console.error(`알 수 없는 명령: ${command}`);
    process.exit(2);
  }
}

function runScore(pairs: Pair[]): void {
  const reports = pairs.map(({ log, truth }) => scoreClip(truth, replay(log), {}));
  printClips(reports);
  printAggregate(reports);
}

function runSweep(pairs: Pair[]): void {
  const candidates = sweepCandidates();
  const rows: string[] = [];

  rows.push(
    pad("설정", 46) +
      pad("경고", 6) +
      pad("오경고", 8) +
      pad("미탐", 6) +
      pad("프롬프트", 10) +
      "합격",
  );

  for (const { label, overrides } of candidates) {
    const reports = pairs.map(({ log, truth }) =>
      scoreClip(truth, replay(log, { overrides }), {}),
    );
    const agg = aggregate(reports);
    const missed = agg.totalLongAbsences - agg.totalDetectedLongAbsences;
    const pass = agg.passFalseWarnings && agg.passDetection;
    rows.push(
      pad(label, 46) +
        pad(String(agg.totalActualWarnings), 6) +
        pad(String(agg.totalFalseWarnings), 8) +
        pad(String(missed), 6) +
        pad(String(sum(reports.map((r) => r.promptCount))), 10) +
        (pass ? "O" : "X"),
    );
  }

  console.log(rows.join("\n"));
  console.log(
    "\n프롬프트 수는 서버로 나가지 않고 걸러진 자리다. 이 값이 크면 오탐이 프롬프트에\n" +
      "떠넘겨지고 있다는 뜻이라, 오경고 0건이어도 사용자 방해가 크다는 신호로 읽는다.",
  );
}

/** 튜닝 손잡이를 문서가 정한 순서대로 돌린다 */
function sweepCandidates(): { label: string; overrides: DeepPartial<DetectionConfig> }[] {
  const out: { label: string; overrides: DeepPartial<DetectionConfig> }[] = [];

  out.push({ label: "기본값", overrides: {} });

  // 1순위 — 검출 신뢰도 하향
  for (const thetaHi of [0.5, 0.45, 0.4]) {
    out.push({
      label: `θ_hi=${thetaHi}`,
      overrides: { scoring: { thetaHi } },
    });
  }
  for (const thetaLo of [0.3, 0.25, 0.2]) {
    out.push({
      label: `θ_lo=${thetaLo}`,
      overrides: { scoring: { thetaLo } },
    });
  }

  // 2순위 — 미검출 판정 시간 3초 → 5초
  for (const frames of [8, 10]) {
    out.push({
      label: `미검출 ${frames}프레임(${frames / 2}초)`,
      overrides: { stateMachine: { missFramesToCandidate: frames } },
    });
  }

  // 동결 상한 — 저조도 대응의 직접 손잡이
  for (const freeze of [15_000, 20_000]) {
    out.push({
      label: `동결상한 ${freeze / 1000}초`,
      overrides: { stateMachine: { uncertainFreezeMs: freeze } },
    });
  }

  // 조합 — 1순위와 2순위를 함께
  out.push({
    label: "θ_lo=0.25 + 미검출 10프레임",
    overrides: {
      scoring: { thetaLo: 0.25 },
      stateMachine: { missFramesToCandidate: 10 },
    },
  });

  return out;
}

function printClips(reports: ScoreReport[]): void {
  const rows = [
    pad("클립", 24) +
      pad("길이(분)", 10) +
      pad("정답경고", 10) +
      pad("실제경고", 10) +
      pad("오경고", 8) +
      pad("미탐", 6) +
      "프롬프트",
  ];
  for (const r of reports) {
    rows.push(
      pad(r.clipId, 24) +
        pad((r.durationMs / 60_000).toFixed(1), 10) +
        pad(String(r.truthWarnings.length), 10) +
        pad(String(r.actualWarnings.length), 10) +
        pad(String(r.falseWarnings.length), 8) +
        pad(String(r.missedAbsences.length), 6) +
        String(r.promptCount),
    );
  }
  console.log(rows.join("\n"));
}

function printAggregate(reports: ScoreReport[]): void {
  const agg = aggregate(reports);
  const rate =
    agg.falsePositiveRate === null
      ? "해당 없음(경고 0건)"
      : `${(agg.falsePositiveRate * 100).toFixed(1)}%`;

  console.log("");
  console.log(`클립 ${agg.clips}개, 총 ${agg.totalHours.toFixed(1)}시간`);
  console.log(`경고 ${agg.totalActualWarnings}건 중 오경고 ${agg.totalFalseWarnings}건 → 오탐률 ${rate}`);
  console.log(
    `90초 이상 이석 ${agg.totalLongAbsences}건 중 ${agg.totalDetectedLongAbsences}건 검출`,
  );
  console.log("");
  console.log(`합격 기준 1 (오경고 0건): ${agg.passFalseWarnings ? "통과" : "실패"}`);
  console.log(`합격 기준 2 (이석 검출 46/48 이상): ${agg.passDetection ? "통과" : "실패"}`);

  if (agg.totalActualWarnings === 0) {
    console.log(
      "\n경고가 0건이면 오탐률은 정의되지 않는다. 12시간 무사고는 '5% 미만'의 증명이 아니라\n" +
        "발생률 상한이 시간당 0.25건이라는 뜻이다(ai-pipeline.md §8).",
    );
  }
}

function loadPairs(logsDir: string, labelsDir: string): Pair[] {
  const logs = new Map<string, InferenceLog>();
  for (const file of jsonFiles(logsDir)) {
    const parsed: unknown = JSON.parse(readFileSync(file, "utf8"));
    if (isInferenceLog(parsed)) logs.set(parsed.clipId, parsed);
    else console.error(`추론 로그 형식이 아니다: ${file}`);
  }

  const pairs: Pair[] = [];
  for (const file of jsonFiles(labelsDir)) {
    const parsed: unknown = JSON.parse(readFileSync(file, "utf8"));
    if (!isGroundTruth(parsed)) {
      console.error(`정답 라벨 형식이 아니다: ${file}`);
      continue;
    }
    const log = logs.get(parsed.clipId);
    if (!log) {
      console.error(`라벨에 맞는 추론 로그가 없다: clipId=${parsed.clipId}`);
      continue;
    }
    pairs.push({ log, truth: parsed });
  }
  return pairs;
}

function jsonFiles(dir: string): string[] {
  const abs = resolve(dir);
  return readdirSync(abs)
    .filter((f) => f.endsWith(".json"))
    .map((f) => join(abs, f));
}

function flag(args: string[], name: string): string | null {
  const idx = args.indexOf(name);
  return idx >= 0 ? (args[idx + 1] ?? null) : null;
}

/** 한글은 터미널에서 두 칸을 차지한다. 글자 수로 맞추면 표가 어긋난다 */
function displayWidth(s: string): number {
  let width = 0;
  for (const ch of s) {
    const code = ch.codePointAt(0) ?? 0;
    const wide =
      (code >= 0x1100 && code <= 0x115f) ||
      (code >= 0x2e80 && code <= 0xa4cf) ||
      (code >= 0xac00 && code <= 0xd7a3) ||
      (code >= 0xf900 && code <= 0xfaff) ||
      (code >= 0xff00 && code <= 0xff60);
    width += wide ? 2 : 1;
  }
  return width;
}

function pad(s: string, width: number): string {
  const w = displayWidth(s);
  return w >= width ? `${s} ` : s + " ".repeat(width - w);
}

function sum(values: number[]): number {
  return values.reduce((a, b) => a + b, 0);
}

main();
