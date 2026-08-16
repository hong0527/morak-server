import { useState } from "react";
import { useNavigate, useParams } from "react-router-dom";

import { api } from "../api/client";
import { ApiError } from "../api/http";

/**
 * S9. 퇴출 이의 신청. evictionId 가 있을 때만 들어온다.
 *
 * 제재 중에도 이 경로는 열려 있다(§2-7). 잘못된 퇴출의 구제 경로까지 닫으면 안 되기 때문이다.
 */
export default function AppealScreen() {
  const params = useParams();
  const navigate = useNavigate();
  const evictionId = Number(params.evictionId);

  const [reasonText, setReasonText] = useState("");
  const [done, setDone] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit() {
    setBusy(true);
    setError(null);
    try {
      // 필드명이 reason 이 아니라 reasonText 다. 비워 보내면 400.
      await api.appeal.create(evictionId, reasonText.trim());
      setDone(true);
    } catch (e) {
      if (e instanceof ApiError) {
        if (e.code === "APPEAL_DEADLINE_PASSED") setError("이의 기한 3일이 지났다.");
        else if (e.code === "APPEAL_ALREADY_FILED") setError("이미 이의를 냈다.");
        else setError(`${e.message} (${e.code})`);
      } else {
        setError(String(e));
      }
    } finally {
      setBusy(false);
    }
  }

  if (done) {
    return (
      <div className="screen">
        <h1>이의를 접수했다</h1>
        <p className="muted">
          검토 결과는 내 기록에서 확인할 수 있다. 인용되면 차감된 포인트가 돌아오고 완주가
          복구된다.
        </p>
        <button onClick={() => navigate("/")}>홈으로</button>
      </div>
    );
  }

  return (
    <div className="screen">
      <h1>퇴출 이의 신청</h1>
      <p className="muted">
        무슨 일이 있었는지 적는다. 최대 200자, 한 퇴출에 한 번만 낼 수 있다.
      </p>
      <textarea
        rows={5}
        maxLength={200}
        value={reasonText}
        onChange={(e) => setReasonText(e.target.value)}
      />
      <div className="row" style={{ marginTop: 12 }}>
        <button className="primary" disabled={!reasonText.trim() || busy} onClick={submit}>
          제출
        </button>
        <button onClick={() => navigate(-1)}>취소</button>
      </div>
      {error && <p className="error">{error}</p>}
    </div>
  );
}
