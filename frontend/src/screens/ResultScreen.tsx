import { useEffect, useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";

import { api } from "../api/client";
import { ApiError } from "../api/http";
import type { SessionResult } from "../api/types";

const RETRY_MS = 3000;
/** 90초를 넘겨도 계속 409 면 그때가 이상 상황이다 */
const GIVE_UP_MS = 90_000;

/**
 * S8. 세션 결과.
 *
 * **이 화면은 완주 축하 전용이 아니다.** 세션 종료 정산(B1 배치)이 붙인 경고와 퇴출은
 * 어떤 요청의 응답에도 실리지 않아서, 사용자가 그것을 처음 아는 자리가 여기다.
 *
 * 그리고 정시 종료 직후 최대 1분간 409 SESSION_NOT_ENDED 가 온다. **그 409 는 오류가 아니다** —
 * 정산이 매분 :00 에 도는 배치라 아직 안 돈 것뿐이다. 오류 화면을 띄우면 정시 종료
 * 직후에는 항상 그것이 보인다.
 */
export default function ResultScreen() {
  const params = useParams();
  const navigate = useNavigate();
  const sessionId = Number(params.sessionId);

  const [result, setResult] = useState<SessionResult | null>(null);
  const [settling, setSettling] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    const startedAt = Date.now();

    const attempt = async () => {
      if (cancelled) return;
      try {
        const data = await api.session.result(sessionId);
        if (cancelled) return;
        setResult(data);
        setSettling(false);
      } catch (e) {
        if (cancelled) return;
        if (e instanceof ApiError && e.code === "SESSION_NOT_ENDED") {
          if (Date.now() - startedAt > GIVE_UP_MS) {
            setSettling(false);
            setError("정산이 90초를 넘겨도 끝나지 않는다. 서버 상태를 확인해야 한다.");
            return;
          }
          window.setTimeout(attempt, RETRY_MS);
          return;
        }
        setSettling(false);
        setError(e instanceof ApiError ? `${e.message} (${e.code})` : String(e));
      }
    };

    void attempt();
    return () => {
      cancelled = true;
    };
  }, [sessionId]);

  if (settling) {
    return (
      <div className="screen">
        <h1>정산 중</h1>
        <p className="muted">
          세션이 끝났다. 결과가 나오기까지 최대 1분 걸린다.
        </p>
      </div>
    );
  }

  if (error || !result) {
    return (
      <div className="screen">
        <p className="error">{error ?? "결과를 불러오지 못했다."}</p>
        <button onClick={() => navigate("/")}>홈으로</button>
      </div>
    );
  }

  const my = result.my;

  return (
    <div className="screen">
      <h1>{my.completed ? "완주" : "미완주"}</h1>

      {/* 조기 종료여도 남아 있던 사람은 완주다. "중단됨"으로 그리면 사실과 다르다 */}
      {result.endReason === "EARLY_UNDER_MIN" && (
        <p className="muted">
          인원이 부족해 세션이 일찍 끝났다. 남아 있었다면 완주로 인정된다.
        </p>
      )}

      {/* 퇴출은 여기서 처음 알게 될 수 있다. 이의 기한 3일은 이미 흐르고 있다 */}
      {my.evictionId !== null && (
        <div className="notice">
          <p>
            경고 {my.warningCount}회로 세션에서 퇴출됐다. 판정이 잘못됐다고 생각하면 이의를
            신청할 수 있다. <b>기한은 퇴출 시각으로부터 3일이다.</b>
          </p>
          <Link to={`/appeals/${my.evictionId}`}>
            <button className="primary">이의 신청</button>
          </Link>
        </div>
      )}

      <div className="card">
        <div className="row" style={{ justifyContent: "space-between" }}>
          <span>받은 포인트</span>
          <b>{my.pointAwarded >= 0 ? "+" : ""}{my.pointAwarded}P</b>
        </div>
        <div className="row" style={{ justifyContent: "space-between" }}>
          <span>연속 완주</span>
          <b>
            {my.streak.before}일 → {my.streak.after}일
          </b>
        </div>
        {my.goalAchieved && (
          <div className="row" style={{ justifyContent: "space-between" }}>
            <span>목표 달성</span>
            <b>{my.badgeCode}</b>
          </div>
        )}
      </div>

      {/* 영상을 저장하지 않으므로 본인이 다툴 수 있는 유일한 근거가 이 시각 기록이다.
          반드시 보여준다. */}
      {my.warnings.length > 0 && (
        <>
          <h2>경고 기록</h2>
          <table>
            <thead>
              <tr>
                <th>#</th>
                <th>사유</th>
                <th>구간</th>
                <th>시간</th>
              </tr>
            </thead>
            <tbody>
              {my.warnings.map((w) => (
                <tr key={w.seq}>
                  <td>{w.seq}</td>
                  <td>{w.basis === "ABSENCE" ? "자리비움" : "화장실 모드 초과"}</td>
                  <td>
                    {w.absenceStartedAt
                      ? `${w.absenceStartedAt.slice(11, 19)} ~ ${w.absenceEndedAt?.slice(11, 19)}`
                      : "-"}
                  </td>
                  <td>{w.absentSeconds !== null ? `${w.absentSeconds}초` : "-"}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </>
      )}

      <h2>같이 한 사람들</h2>
      <table>
        <tbody>
          {result.participants.map((p) => (
            <tr key={p.memberId}>
              <td>{p.nickname}{p.isMe && " (나)"}</td>
              <td>{p.completed ? "완주" : p.participantStatus}</td>
              <td>경고 {p.warningCount}</td>
            </tr>
          ))}
        </tbody>
      </table>

      <div style={{ marginTop: 24 }}>
        <button className="primary" onClick={() => navigate("/", { replace: true })}>
          홈으로
        </button>
      </div>
    </div>
  );
}
