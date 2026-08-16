import type { StateMachineConfig } from "./config.js";
import type { DetectionState, Effect, Verdict } from "./types.js";

/**
 * ai-pipeline.md §5 전이표의 구현.
 *
 *   PRESENT
 *     → (첫 미검출, 이 시각이 t0)      OBSERVING
 *     → (6프레임 연속 미검출 = 3초)     CANDIDATE
 *     → (t0 + 30초)                    PROMPTING   "자리에 계신가요?"
 *          ├ 탭함        → DEFERRED (유예 후 재관찰, 만료되면 다시 묻는다)
 *          └ 15초 무응답 → ABSENT_SENT  START 전송 (occurredAt = t0, 탭이 있었으면 마지막 탭 시각)
 *     → (3프레임 연속 검출 = 1.5초)     END 전송 (occurredAt = 첫 재검출 시각)
 *
 * 순수 객체다. 네트워크도 DOM도 모른다. 입력은 프레임별 3값 판정과 그 프레임의 시각뿐이고
 * 출력은 Effect 목록이다. 하네스가 녹화 로그를 그대로 흘려넣어 같은 판정을 재현할 수 있는
 * 것이 이 순수성 덕이다.
 *
 * ## 타이머를 프레임 도착 시점에만 평가하는 이유
 * setTimeout 을 쓰지 않고 onFrame 안에서만 시각을 비교한다. 프레임이 끊기면 타이머도 같이
 * 멈춰야 하기 때문이다 — 탭이 백그라운드로 가서 추론이 멈춘 구간에 사건을 만들면
 * "관측하지 않은 시간에는 사건을 만들지 않는다"(ai-pipeline.md §7-3)를 어긴다.
 *
 * ## 복귀 판정이 상태에 따라 다른 이유 (설계 문서에 명시되지 않은 해석)
 * 프롬프트가 뜨기 전(OBSERVING·CANDIDATE)에는 HIT 한 장이면 즉시 PRESENT 로 돌린다.
 * 사용자에게 보인 것도 서버로 나간 것도 없어서 되돌리는 비용이 0이고, 미검출 카운터가
 * "연속"이라는 정의와도 맞는다. 프롬프트가 떴거나 START 를 보낸 뒤(PROMPTING·DEFERRED·
 * ABSENT_SENT)에는 1.5초 연속 검출을 요구한다. 여기서 한 장에 반응하면 프롬프트가 깜빡이고,
 * START/END 가 5초 간격 제한에 걸려 되레 자리비움이 길게 남는다(open-decisions C-2 ③).
 */
export class AbsenceStateMachine {
  private state: DetectionState = "PRESENT";

  /** 연속 미검출 프레임 수 */
  private missRun = 0;
  /** 연속 검출 프레임 수 */
  private hitRun = 0;
  /** 지금 이어지고 있는 미검출 구간의 첫 프레임 시각 */
  private t0Ms: number | null = null;
  /** 지금 이어지고 있는 검출 구간의 첫 프레임 시각. END 의 occurredAt 이 된다 */
  private firstHitAtMs: number | null = null;
  /** 지금 이어지고 있는 UNCERTAIN 구간의 시작. 동결 상한 계산용 */
  private freezeStartedAtMs: number | null = null;

  private promptShownAtMs: number | null = null;
  private deferUntilMs: number | null = null;
  private tapCount = 0;
  private lastTapAtMs: number | null = null;

  private lastFrameAtMs: number | null = null;

  constructor(private readonly config: StateMachineConfig) {}

  get currentState(): DetectionState {
    return this.state;
  }

  /** START 를 보내고 아직 END 로 닫지 않았는가 */
  get hasOpenAbsence(): boolean {
    return this.state === "ABSENT_SENT";
  }

  /**
   * 새로고침 복구용. 세션 저장소에 열린 START 가 남아 있으면 이 상태로 복원해야
   * 복귀 시 END 가 나간다. 짝이 없으면 세션 종료 시각까지 자리비움으로 정산된다
   * (ai-pipeline.md §7-3, api-spec SS-4 서버 판정 4).
   */
  restoreOpenAbsence(): void {
    this.state = "ABSENT_SENT";
    this.missRun = 0;
    this.hitRun = 0;
    this.firstHitAtMs = null;
    this.lastFrameAtMs = null;
  }

  /**
   * 서버가 구간을 대신 닫은 경우(SS-5 화장실 모드 시작). END 를 보내면 짝 없는 END 가 되므로
   * 보내지 않고 상태만 정리한다.
   */
  absorbServerSideClose(): void {
    this.resetToPresent();
  }

  /** 프레임 하나를 넣는다 */
  onFrame(tMs: number, verdict: Verdict, lowLight: boolean): Effect[] {
    const effects: Effect[] = [];

    // 프레임 공백 처리가 카운터 갱신보다 먼저다. 공백 뒤 첫 프레임을 공백 이전의 연속으로
    // 이어붙이면 관측하지 않은 시간이 판정에 들어간다.
    if (
      this.lastFrameAtMs !== null &&
      tMs - this.lastFrameAtMs > this.config.observationGapMs
    ) {
      effects.push(...this.onObservationGap(tMs));
    }
    this.lastFrameAtMs = tMs;

    const effective = this.applyFreeze(tMs, verdict, lowLight);

    if (effective === "HIT") {
      if (this.hitRun === 0) this.firstHitAtMs = tMs;
      this.hitRun += 1;
      this.missRun = 0;
      this.t0Ms = null;
    } else if (effective === "MISS") {
      if (this.missRun === 0) this.t0Ms = tMs;
      this.missRun += 1;
      this.hitRun = 0;
      this.firstHitAtMs = null;
    }
    // UNCERTAIN(동결)은 카운터를 그대로 둔다. 판단이 애매한 구간을 "없음"으로 세지 않는
    // 것이 3값 논리의 요점이다(ai-pipeline.md §6).

    effects.push(...this.advance(tMs));
    return effects;
  }

  /** 프롬프트를 탭했다 */
  onPromptTap(tMs: number): Effect[] {
    if (this.state !== "PROMPTING") return [];

    this.tapCount += 1;
    this.lastTapAtMs = tMs;
    const graces = this.config.deferralGraceMs;
    const idx = Math.min(this.tapCount - 1, graces.length - 1);
    const grace = graces[idx] ?? graces[graces.length - 1] ?? 120_000;
    this.deferUntilMs = tMs + grace;
    this.promptShownAtMs = null;

    // 탭한 뒤 얼굴이 계속 안 보여도 무효화하지 않는다. 그 조건이 정확히 고개 숙임 오탐이라
    // 무효화하면 이 장치의 목적이 죽는다(ai-pipeline.md §5).
    return [{ kind: "HIDE_PROMPT" }, ...this.transit("DEFERRED", tMs)];
  }

  /**
   * 관측이 끊긴 구간. 이미 START 를 보냈으면 상태를 유지한다 — 복귀 후 END 를 반드시
   * 보내야 하기 때문이다. 아직 아무것도 보내지 않았으면 통째로 버린다.
   */
  private onObservationGap(tMs: number): Effect[] {
    if (this.state === "ABSENT_SENT") {
      this.missRun = 0;
      this.hitRun = 0;
      this.firstHitAtMs = null;
      this.freezeStartedAtMs = null;
      return [];
    }
    const hadPrompt = this.state === "PROMPTING";
    const effects: Effect[] = hadPrompt ? [{ kind: "HIDE_PROMPT" }] : [];
    if (this.state !== "PRESENT") effects.push(...this.transit("PRESENT", tMs));
    this.resetCounters();
    return effects;
  }

  /** UNCERTAIN 동결과 상한 초과 시 MISS 격하 */
  private applyFreeze(tMs: number, verdict: Verdict, lowLight: boolean): Verdict {
    if (verdict !== "UNCERTAIN") {
      this.freezeStartedAtMs = null;
      return verdict;
    }
    if (this.freezeStartedAtMs === null) this.freezeStartedAtMs = tMs;
    const cap = lowLight
      ? this.config.uncertainFreezeLowLightMs
      : this.config.uncertainFreezeMs;
    // 무한 보류를 막는다. 상한을 넘긴 뒤의 UNCERTAIN 은 MISS 로 센다.
    return tMs - this.freezeStartedAtMs > cap ? "MISS" : "UNCERTAIN";
  }

  private advance(tMs: number): Effect[] {
    const { missFramesToCandidate, returnFramesToPresent } = this.config;

    switch (this.state) {
      case "PRESENT":
        if (this.missRun >= 1) return this.transit("OBSERVING", tMs);
        return [];

      case "OBSERVING":
        if (this.hitRun >= 1) return this.toPresent(tMs);
        if (this.missRun >= missFramesToCandidate) return this.transit("CANDIDATE", tMs);
        return [];

      case "CANDIDATE": {
        if (this.hitRun >= 1) return this.toPresent(tMs);
        if (this.t0Ms !== null && tMs - this.t0Ms >= this.config.promptDelayMs) {
          return this.showPrompt(tMs);
        }
        return [];
      }

      case "PROMPTING": {
        if (this.hitRun >= returnFramesToPresent) {
          return [{ kind: "HIDE_PROMPT" }, ...this.toPresent(tMs)];
        }
        if (
          this.promptShownAtMs !== null &&
          tMs - this.promptShownAtMs >= this.config.promptAnswerWindowMs
        ) {
          return this.sendStart(tMs);
        }
        return [];
      }

      case "DEFERRED": {
        if (this.hitRun >= returnFramesToPresent) return this.toPresent(tMs);
        if (this.deferUntilMs !== null && tMs >= this.deferUntilMs) {
          return this.showPrompt(tMs);
        }
        return [];
      }

      case "ABSENT_SENT": {
        if (this.hitRun >= returnFramesToPresent && this.firstHitAtMs !== null) {
          const occurredAtMs = this.firstHitAtMs;
          const effects = this.toPresent(tMs);
          // END 의 시각은 3프레임째가 아니라 첫 재검출 프레임이다. 판단이 끝난 시각이 아니라
          // 실제 전이가 일어난 프레임의 시각을 적는 것이 서버와의 약속이다(§7-1).
          return [{ kind: "ABSENCE_END", occurredAtMs }, ...effects];
        }
        return [];
      }
    }
  }

  private showPrompt(tMs: number): Effect[] {
    this.promptShownAtMs = tMs;
    this.deferUntilMs = null;
    const effects: Effect[] = [...this.transit("PROMPTING", tMs), { kind: "SHOW_PROMPT" }];
    if (this.tapCount >= this.config.adjustHintAfterTaps) {
      effects.push({ kind: "SHOW_ADJUST_HINT" });
    }
    return effects;
  }

  private sendStart(tMs: number): Effect[] {
    // 탭이 있었으면 그 시각을 재실의 증거로 인정한다. 없었으면 첫 미검출 프레임 t0 다.
    const occurredAtMs = this.lastTapAtMs ?? this.t0Ms ?? tMs;
    this.promptShownAtMs = null;
    return [
      { kind: "HIDE_PROMPT" },
      { kind: "ABSENCE_START", occurredAtMs },
      ...this.transit("ABSENT_SENT", tMs),
    ];
  }

  private toPresent(tMs: number): Effect[] {
    const effects = this.transit("PRESENT", tMs);
    this.resetCounters();
    return effects;
  }

  private resetToPresent(): void {
    this.state = "PRESENT";
    this.resetCounters();
    this.lastFrameAtMs = null;
  }

  private resetCounters(): void {
    this.missRun = 0;
    this.hitRun = 0;
    this.t0Ms = null;
    this.firstHitAtMs = null;
    this.freezeStartedAtMs = null;
    this.promptShownAtMs = null;
    this.deferUntilMs = null;
    // 탭 횟수는 "연속으로 해소되지 않은" 횟수라 복귀하면 처음부터다.
    this.tapCount = 0;
    this.lastTapAtMs = null;
  }

  private transit(to: DetectionState, tMs: number): Effect[] {
    const from = this.state;
    if (from === to) return [];
    this.state = to;
    return [{ kind: "STATE", from, to, tMs }];
  }
}
