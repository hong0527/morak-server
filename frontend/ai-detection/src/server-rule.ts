import type { ServerRuleConfig } from "./config.js";

/**
 * 서버의 자리비움 정산 규칙을 그대로 옮긴 것(api-spec.md SS-4 "서버 판정").
 *
 * 클라이언트 코드는 이걸 판정에 쓰지 않는다 — 판정은 서버가 한다(★D4). 여기 있는 이유는
 * 하나다. 오탐률 측정에서 "정답 라벨 구간에 서버와 동일한 60초 규칙을 돌려 정답 경고
 * 시퀀스를 만든다"(ai-pipeline.md §8)는 절차가 이 함수를 필요로 한다. 정답 쪽과 실제 쪽에
 * 같은 함수를 돌려야 비교가 성립한다.
 *
 * 서버 값이 바뀌면 여기도 바꿔야 한다. 지금 기준은
 * `session.absence-threshold-seconds=60`, `session.absence-warning-escalation-seconds=300`,
 * `session.evict-warning-count=3`.
 */

export interface AbsenceReport {
  type: "START" | "END";
  occurredAtMs: number;
}

export interface SettledInterval {
  startMs: number;
  endMs: number;
  /**
   * 판정에 쓰인 초. `endMs - startMs` 가 아니다 — 세션 종료 이후를 잘라낸 뒤 정수 초로
   * 내린 값이라, 종료를 넘긴 구간에서는 실제 간격보다 짧다.
   */
  seconds: number;
  /** 임계를 넘겨 경고가 붙었는가 */
  warned: boolean;
  /**
   * 이 구간이 만드는 경고 수. 서버는 길이에 비례해 여러 회를 붙인다 —
   * 임계를 넘기면 1회, 그 뒤로는 눈금을 넘길 때마다 1회씩 더다.
   */
  warningCount: number;
  /** END 를 못 받아 세션 종료 시각으로 닫힌 구간인가 */
  closedBySessionEnd: boolean;
}

export interface SettlementResult {
  intervals: SettledInterval[];
  /**
   * 붙은 경고를 순서대로 편 것. seq 1..n 에 대응한다.
   * **한 구간이 여러 회를 만들면 그 구간이 여러 번 들어간다** — 길이가 판정에 들어가므로
   * 구간 수와 경고 수가 더는 같지 않다.
   */
  warnings: SettledInterval[];
  evicted: boolean;
  /** 퇴출된 경우 그 시점의 구간 */
  evictedAt: SettledInterval | null;
}

/**
 * @param sessionEndMs 세션 예정 종료 시각. 짝 없는 START 를 닫는 시각이자, 모든 구간의
 *   길이를 끊는 자리다(SS-4 서버 판정 4).
 */
export function settle(
  events: AbsenceReport[],
  sessionEndMs: number,
  config: ServerRuleConfig,
): SettlementResult {
  const openStarts: number[] = [];
  const intervals: SettledInterval[] = [];

  const ordered = [...events].sort((a, b) => a.occurredAtMs - b.occurredAtMs);

  for (const ev of ordered) {
    if (ev.type === "START") {
      openStarts.push(ev.occurredAtMs);
      continue;
    }
    // "직전 미종료 START 와 짝을 지어" — 가장 나중에 열린 것과 맞춘다.
    const startMs = openStarts.pop();
    if (startMs === undefined) {
      // 짝 없는 END. 서버는 closedAbsenceSeconds=null 로 흘려보낸다.
      continue;
    }
    intervals.push(makeInterval(startMs, ev.occurredAtMs, sessionEndMs, config, false));
  }

  for (const startMs of openStarts) {
    intervals.push(makeInterval(startMs, sessionEndMs, sessionEndMs, config, true));
  }

  intervals.sort((a, b) => a.startMs - b.startMs);

  // 구간 하나가 여러 회를 만들 수 있으므로 회수만큼 펴 넣는다. 서버가 상한에서 멈추는 것과
  // 맞추려고 여기서도 퇴출 회수를 넘겨 쌓지 않는다.
  const warnings: SettledInterval[] = [];
  for (const interval of intervals) {
    for (let i = 0; i < interval.warningCount; i += 1) {
      if (warnings.length >= config.evictWarningCount) break;
      warnings.push(interval);
    }
  }
  const evicted = warnings.length >= config.evictWarningCount;

  return {
    intervals,
    warnings,
    evicted,
    evictedAt: evicted ? (warnings[config.evictWarningCount - 1] ?? null) : null,
  };
}

/**
 * 정답 라벨(스톱워치로 찍은 이석 구간)을 서버 규칙에 넣기 위해 START/END 쌍으로 편다.
 */
export function labelsToReports(
  absences: { startMs: number; endMs: number }[],
): AbsenceReport[] {
  const reports: AbsenceReport[] = [];
  for (const a of absences) {
    reports.push({ type: "START", occurredAtMs: a.startMs });
    reports.push({ type: "END", occurredAtMs: a.endMs });
  }
  return reports;
}

/**
 * @param sessionEndMs 길이를 끊는 자리. `startMs`/`endMs` 는 보고된 시각 그대로 남긴다 —
 *   오탐률 측정이 정답 경고와 실제 경고를 `startMs` 로 맞춰 붙이므로(harness/score.ts)
 *   여기를 끊으면 종료 뒤 구간이 전부 같은 시각으로 뭉쳐 짝짓기가 무너진다.
 */
function makeInterval(
  startMs: number,
  endMs: number,
  sessionEndMs: number,
  config: ServerRuleConfig,
  closedBySessionEnd: boolean,
): SettledInterval {
  // 서버는 구간의 양끝을 세션 예정 종료 시각에서 끊고 잰다(AbsenceJudgeService.absentSeconds).
  // 세션이 끝난 뒤는 자리를 지킬 의무가 없는 시간이라 경고 판정에 실리면 안 되고, 끝만 끊으면
  // 종료 뒤에 시작한 구간이 음수를 낸다.
  const judgedStartMs = Math.min(startMs, sessionEndMs);
  const judgedEndMs = Math.min(endMs, sessionEndMs);
  // 서버는 Duration.getSeconds() 로 정수 초까지 내린 값으로 판정한다. 밀리초를 남긴 채
  // 계산하면 60.5초 구간이 여기서만 경고가 되고, 아래 -1 도 1초가 아니라 1밀리초가 된다.
  const seconds = Math.max(0, Math.floor((judgedEndMs - judgedStartMs) / 1000));
  // "초과"다. 정확히 60초는 경고가 아니다. 넘긴 뒤로는 눈금마다 한 회씩 는다.
  const warningCount =
    seconds > config.absenceThresholdSeconds
      ? 1 +
        Math.floor(
          (seconds - config.absenceThresholdSeconds - 1) /
            config.absenceWarningEscalationSeconds,
        )
      : 0;
  return {
    startMs,
    endMs,
    seconds,
    warned: warningCount > 0,
    warningCount,
    closedBySessionEnd,
  };
}
