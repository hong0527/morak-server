import { useCallback, useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";

import { api } from "../api/client";
import { ApiError } from "../api/http";
import type { MyAppealPage, MySessionPage, StreakSummary } from "../api/types";

const PAGE_SIZE = 20;

const APPEAL_STATUS_LABEL: Record<string, string> = {
  PENDING: "검토 중",
  ACCEPTED: "인용",
  REJECTED: "기각",
  CLOSED: "종결",
};

/**
 * S10. 내 기록. 세션 기록(MS-1)과 Streak, 퇴출 이의 목록(AP-2)을 함께 보여준다.
 *
 * Streak 를 주는 API 가 따로 없어 AU-2(me)에서 읽는다. 세 조회는 서로 독립이라
 * 한 번에 병렬로 부른다.
 */
export default function RecordsScreen() {
  const navigate = useNavigate();
  const [streak, setStreak] = useState<StreakSummary | null>(null);
  const [sessions, setSessions] = useState<MySessionPage | null>(null);
  const [appeals, setAppeals] = useState<MyAppealPage | null>(null);
  const [page, setPage] = useState(0);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async (p: number) => {
    try {
      const [me, sessionPage, appealPage] = await Promise.all([
        api.member.me(),
        api.member.mySessions({ page: p, size: PAGE_SIZE }),
        api.member.myAppeals({ page: 0, size: PAGE_SIZE }),
      ]);
      setStreak(me.streak);
      setSessions(sessionPage);
      setAppeals(appealPage);
      setError(null);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : String(e));
    }
  }, []);

  useEffect(() => {
    void load(page);
  }, [load, page]);

  if (error) {
    return (
      <div className="screen">
        <p className="error">{error}</p>
        <button onClick={() => navigate("/")}>홈으로</button>
      </div>
    );
  }
  if (!sessions) {
    return (
      <div className="screen">
        <p className="muted loading">불러오는 중이에요</p>
      </div>
    );
  }

  return (
    <div className="screen">
      <div className="row" style={{ justifyContent: "space-between" }}>
        <h1>내 기록</h1>
        <button onClick={() => navigate("/")}>홈으로</button>
      </div>

      <div className="card">
        <div className="row" style={{ justifyContent: "space-between" }}>
          <span>연속 완주</span>
          <b>{streak?.current ?? 0}일</b>
        </div>
        {streak?.lastCompletedOn && (
          <div className="row" style={{ justifyContent: "space-between" }}>
            <span>마지막 완주일</span>
            <b>{streak.lastCompletedOn}</b>
          </div>
        )}
      </div>

      <h2>세션 기록</h2>
      {sessions.content.length === 0 ? (
        <p className="empty">아직 참여한 세션이 없어요. 첫 매칭을 시작해 보세요.</p>
      ) : (
        <table>
          <thead>
            <tr>
              <th>시작</th>
              <th>시간</th>
              <th>결과</th>
              <th>포인트</th>
              <th>경고</th>
            </tr>
          </thead>
          <tbody>
            {sessions.content.map((s) => (
              <tr key={s.sessionId}>
                <td>{s.startedAt.slice(0, 16).replace("T", " ")}</td>
                <td>{s.targetMinutes / 60}시간</td>
                <td>
                  {s.status === "LIVE"
                    ? "진행 중"
                    : s.completed
                      ? "완주"
                      : s.participantStatus === "EVICTED"
                        ? "퇴출"
                        : s.participantStatus === "LEFT"
                          ? "중도 퇴장"
                          : "미완주"}
                </td>
                <td>{s.pointAwarded > 0 ? `+${s.pointAwarded}P` : "-"}</td>
                <td>{s.warningCount > 0 ? s.warningCount : "-"}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      {sessions.totalPages > 1 && (
        <div className="row" style={{ marginTop: 12 }}>
          <button disabled={page === 0} onClick={() => setPage(page - 1)}>
            이전
          </button>
          <span className="muted">
            {page + 1} / {sessions.totalPages}
          </span>
          <button disabled={page + 1 >= sessions.totalPages} onClick={() => setPage(page + 1)}>
            다음
          </button>
        </div>
      )}

      <h2>퇴출 이의</h2>
      {!appeals || appeals.content.length === 0 ? (
        <p className="empty">신청한 이의가 없어요.</p>
      ) : (
        appeals.content.map((a) => (
          <div className="card" key={a.appealId} style={{ marginBottom: 8 }}>
            <div className="row" style={{ justifyContent: "space-between" }}>
              <span>
                세션 {a.sessionId} · {a.evictedAt.slice(0, 16).replace("T", " ")} 퇴출
              </span>
              <b>{APPEAL_STATUS_LABEL[a.status] ?? a.status}</b>
            </div>
            <p className="muted">{a.reasonText}</p>
            {/* 처리 사유(note)는 내려오지 않는다. 결과는 원복 사실로만 설명한다(스키마 주석) */}
            {a.status === "ACCEPTED" && (
              <p className="muted">
                포인트 {a.pointRefunded ?? 0}P가 반환됐어요
                {a.sessionCompletedRestored && ". 완주 기록도 복구됐어요"}.
              </p>
            )}
            {a.status === "PENDING" && (
              <p className="muted">
                {a.slaDueAt.slice(0, 16).replace("T", " ")}까지 검토될 예정이에요.
              </p>
            )}
          </div>
        ))
      )}
    </div>
  );
}
