import type { ServerRuleConfig } from "../src/config.js";
import { labelsToReports, settle, type SettledInterval } from "../src/server-rule.js";
import type { GroundTruth } from "./log-format.js";
import type { ReplayResult } from "./replay.js";

/**
 * 판정 결과를 정답과 대조한다(ai-pipeline.md §8).
 *
 * 절차:
 * 1. 정답 라벨 구간에 **서버와 동일한 60초 규칙**을 돌려 "정답 경고 시퀀스"를 만든다.
 * 2. 재계산된 이벤트에도 같은 규칙을 돌려 "실제 경고 시퀀스"를 만든다.
 * 3. 둘을 ±10초로 대조한다.
 *
 * 이렇게 하면 "30초 물 마시러 감"처럼 임계 미만이라 경고가 없어야 정상인 경우가 자동으로
 * 옳게 처리된다 — 정답 쪽에서도 경고가 만들어지지 않기 때문이다.
 *
 * **오탐률 = 부여된 경고 중 잘못 부여된 경고의 비율.** 분모가 경고 수이지 프레임 수가
 * 아니라는 점이 중요하다. 사용자가 실제로 입는 피해와 일치시킨 정의다.
 */

export interface ScoreOptions {
  /** 정답과 실제 경고를 같은 것으로 볼 시간 여유 */
  toleranceMs?: number;
  /** 미탐의 정의: 이 길이 이상의 이석에서 경고가 누락된 것 */
  missThresholdMs?: number;
  serverRule?: ServerRuleConfig;
}

export interface ScoreReport {
  clipId: string;
  durationMs: number;

  /** 정답 라벨에 서버 규칙을 돌려 나온 경고 */
  truthWarnings: SettledInterval[];
  /** 재계산된 이벤트에 서버 규칙을 돌려 나온 경고 */
  actualWarnings: SettledInterval[];

  /** 정답에 짝이 없는 경고. 이것이 오경고다 */
  falseWarnings: SettledInterval[];
  matched: { truth: SettledInterval; actual: SettledInterval }[];
  /** 미탐 판정의 분모 — missThresholdMs 이상인 정답 이석의 수 */
  longAbsenceCount: number;
  /** 그중 경고가 안 난 것 */
  missedAbsences: { startMs: number; endMs: number }[];

  /** falseWarnings / actualWarnings. 경고가 0건이면 null — 0/0 을 0%로 적지 않는다 */
  falsePositiveRate: number | null;
  /** 실제로 퇴출까지 갔는가 */
  evicted: boolean;
  truthEvicted: boolean;
  /** 프롬프트가 뜬 횟수. 여기서 걸러진 만큼이 서버에 남지 않은 오탐이다 */
  promptCount: number;
}

const DEFAULT_SERVER_RULE: ServerRuleConfig = {
  absenceThresholdSeconds: 60,
  absenceWarningEscalationSeconds: 300,
  evictWarningCount: 3,
};

export function scoreClip(
  truth: GroundTruth,
  replayed: ReplayResult,
  options: ScoreOptions = {},
): ScoreReport {
  const toleranceMs = options.toleranceMs ?? 10_000;
  const missThresholdMs = options.missThresholdMs ?? 90_000;
  const rule = options.serverRule ?? replayed.config.serverRule ?? DEFAULT_SERVER_RULE;
  const endMs = truth.durationMs;

  const truthSettlement = settle(labelsToReports(truth.absences), endMs, rule);
  const actualSettlement = settle(replayed.reports, endMs, rule);

  const truthWarnings = truthSettlement.warnings;
  const actualWarnings = actualSettlement.warnings;

  // 정답 경고 하나에 실제 경고 하나만 붙인다. 하나의 정답에 둘이 붙으면 나머지는 오경고다.
  const usedTruth = new Set<number>();
  const matched: { truth: SettledInterval; actual: SettledInterval }[] = [];
  const falseWarnings: SettledInterval[] = [];

  for (const actual of actualWarnings) {
    let bestIdx = -1;
    let bestDelta = Infinity;
    truthWarnings.forEach((t, idx) => {
      if (usedTruth.has(idx)) return;
      const delta = Math.abs(t.startMs - actual.startMs);
      if (delta <= toleranceMs && delta < bestDelta) {
        bestDelta = delta;
        bestIdx = idx;
      }
    });
    const hit = bestIdx >= 0 ? truthWarnings[bestIdx] : undefined;
    if (hit) {
      usedTruth.add(bestIdx);
      matched.push({ truth: hit, actual });
    } else {
      falseWarnings.push(actual);
    }
  }

  // 미탐은 "90초 이상 이석에서 경고가 누락된 것"이다. 정답 경고가 아니라 정답 이석에서 센다.
  const longAbsences = truth.absences.filter((a) => a.endMs - a.startMs >= missThresholdMs);
  const missedAbsences = longAbsences.filter(
    (a) => !actualWarnings.some((w) => Math.abs(w.startMs - a.startMs) <= toleranceMs),
  );

  return {
    clipId: truth.clipId,
    durationMs: truth.durationMs,
    truthWarnings,
    actualWarnings,
    falseWarnings,
    matched,
    longAbsenceCount: longAbsences.length,
    missedAbsences,
    falsePositiveRate:
      actualWarnings.length === 0 ? null : falseWarnings.length / actualWarnings.length,
    evicted: actualSettlement.evicted,
    truthEvicted: truthSettlement.evicted,
    promptCount: replayed.promptCount,
  };
}

export interface AggregateReport {
  clips: number;
  totalHours: number;
  totalActualWarnings: number;
  totalFalseWarnings: number;
  /** 90초 이상 정답 이석의 총 수 */
  totalLongAbsences: number;
  /** 그중 경고가 난 수 */
  totalDetectedLongAbsences: number;
  falsePositiveRate: number | null;
  /** 합격 기준: 오경고 0건 */
  passFalseWarnings: boolean;
  /** 합격 기준: 90초 이상 이석의 검출 */
  passDetection: boolean;
}

/**
 * 합격 기준(ai-pipeline.md §8): 팀원 6명이 2시간씩(총 12시간) 재실 대본을 녹화해
 * **오경고 0건**, 90초 이상 이석 48건 중 **46건 이상** 경고 발생.
 */
export function aggregate(
  reports: ScoreReport[],
  criteria: { minDetectionRatio?: number } = {},
): AggregateReport {
  const minDetectionRatio = criteria.minDetectionRatio ?? 46 / 48;

  const totalActualWarnings = sum(reports.map((r) => r.actualWarnings.length));
  const totalFalseWarnings = sum(reports.map((r) => r.falseWarnings.length));
  const totalDurationMs = sum(reports.map((r) => r.durationMs));

  const longTotal = sum(reports.map((r) => r.longAbsenceCount));
  const longDetected = longTotal - sum(reports.map((r) => r.missedAbsences.length));

  return {
    clips: reports.length,
    totalHours: totalDurationMs / 3_600_000,
    totalActualWarnings,
    totalFalseWarnings,
    totalLongAbsences: longTotal,
    totalDetectedLongAbsences: longDetected,
    falsePositiveRate:
      totalActualWarnings === 0 ? null : totalFalseWarnings / totalActualWarnings,
    passFalseWarnings: totalFalseWarnings === 0,
    passDetection: longTotal === 0 ? true : longDetected / longTotal >= minDetectionRatio,
  };
}

function sum(values: number[]): number {
  return values.reduce((a, b) => a + b, 0);
}
