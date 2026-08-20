import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";

import { api } from "../api/client";
import { ApiError } from "../api/http";
import type { Goal } from "../api/types";

/** 서버 허용값이지만 선택지 자체는 팀이 확정하지 않은 잠정값이다(frontend-guide §8 7번) */
const CHOICES: Goal["periodDays"][] = [7, 14, 30];

/**
 * S5. 목표 설정. 개인 목표 챌린지 기간을 정한다.
 *
 * 현재 목표를 따로 조회하는 API 가 없어 AU-2(me)의 goal 필드에서 읽는다.
 * 진행 중(ACTIVE)인 목표가 있으면 서버가 409 GOAL_ALREADY_ACTIVE 를 내므로
 * 그때는 선택 버튼을 아예 그리지 않는다.
 */
export default function GoalScreen() {
  const navigate = useNavigate();
  const [goal, setGoal] = useState<Goal | null>(null);
  const [loaded, setLoaded] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    let cancelled = false;
    void (async () => {
      try {
        const me = await api.member.me();
        if (cancelled) return;
        setGoal(me.goal ?? null);
        setLoaded(true);
      } catch (e) {
        if (cancelled) return;
        setError(e instanceof ApiError ? e.message : String(e));
        setLoaded(true);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  async function choose(periodDays: Goal["periodDays"]) {
    setBusy(true);
    setError(null);
    try {
      setGoal(await api.member.setGoal(periodDays));
    } catch (e) {
      if (e instanceof ApiError && e.code === "GOAL_ALREADY_ACTIVE") {
        setError("이미 진행 중인 목표가 있어요.");
      } else {
        setError(e instanceof ApiError ? e.message : String(e));
      }
    } finally {
      setBusy(false);
    }
  }

  if (!loaded) {
    return (
      <div className="screen">
        <p className="muted loading">불러오는 중이에요</p>
      </div>
    );
  }

  const active = goal?.status === "ACTIVE";

  return (
    <div className="screen">
      <div className="row" style={{ justifyContent: "space-between" }}>
        <h1>목표 설정</h1>
        <button onClick={() => navigate("/")}>홈으로</button>
      </div>

      {goal && (
        <div className="card">
          <div className="row" style={{ justifyContent: "space-between" }}>
            <span>{goal.status === "ACHIEVED" ? "달성한 목표" : "진행 중인 목표"}</span>
            <b>{goal.periodDays}일 연속 완주</b>
          </div>
          <div className="row" style={{ justifyContent: "space-between" }}>
            <span>진행도</span>
            {/* streak.current 가 아니라 progressDays 로 그린다. streak 로 그리면
                목표 시작 전의 연속이 진행으로 잘못 보인다(스키마 주석) */}
            <b>
              {goal.progressDays} / {goal.periodDays}일
            </b>
          </div>
          <div className="row" style={{ justifyContent: "space-between" }}>
            <span>시작일</span>
            <b>{goal.startedOn}</b>
          </div>
          {goal.status === "ACHIEVED" && goal.achievedAt && (
            <div className="row" style={{ justifyContent: "space-between" }}>
              <span>달성 시각</span>
              <b>{goal.achievedAt.slice(0, 19).replace("T", " ")}</b>
            </div>
          )}
        </div>
      )}

      {active ? (
        <p className="muted">진행 중인 목표를 마치면 새 목표를 시작할 수 있어요.</p>
      ) : (
        <>
          <h2>{goal ? "새 목표 시작" : "목표 시작"}</h2>
          <p className="muted">기간을 고르면 오늘부터 연속 완주에 도전해요.</p>
          <div className="row">
            {CHOICES.map((d) => (
              <button key={d} className="primary" disabled={busy} onClick={() => choose(d)}>
                {d}일
              </button>
            ))}
          </div>
        </>
      )}

      {error && <p className="error">{error}</p>}
    </div>
  );
}
