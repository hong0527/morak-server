import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";

import { api } from "../api/client";
import { ApiError } from "../api/http";
import { usePolling } from "../hooks/usePolling";
import { prefetchDetectionAssets } from "../detection/assets";
import type { MatchRequest, TargetMinutes } from "../api/types";

const CHOICES: TargetMinutes[] = [60, 120, 180, 240];

/** 서버가 강제하지 않는다. 팀이 정할 값이다(frontend-guide §8 3번) */
const POLL_MS = 2500;

/**
 * S6. 매칭 대기.
 *
 * **성사를 아는 방법이 폴링뿐이다**(§2-1). 푸시가 없다.
 *
 * 이 화면에서 감지 자산(모델 3.76MB · wasm)을 미리 받아 둔다. 세션에 들어간 뒤에 받기
 * 시작하면 그 시간만큼 입장이 밀린다(ai-detection/README 7-3).
 */
export default function MatchWaitScreen() {
  const navigate = useNavigate();
  const [request, setRequest] = useState<MatchRequest | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    void prefetchDetectionAssets();
  }, []);

  // 신청해 둔 것이 이미 있을 수 있다(새로고침·앱 재시작). 폴링이 그것도 함께 찾아 준다.
  const { data: polled } = usePolling(
    (signal) => api.match.mine(signal),
    POLL_MS,
    { enabled: true },
  );

  const current = polled ?? request;

  useEffect(() => {
    // status 가 MATCHED 이고 sessionId 가 채워지면 세션 화면으로.
    // 이 값이 대기 화면에서 세션 화면으로 들고 가는 유일한 값이다.
    if (current?.status === "MATCHED" && current.sessionId) {
      navigate(`/sessions/${current.sessionId}`, { replace: true });
    }
  }, [current, navigate]);

  async function apply(targetMinutes: TargetMinutes) {
    setBusy(true);
    setError(null);
    try {
      setRequest(await api.match.create(targetMinutes));
    } catch (e) {
      if (e instanceof ApiError) {
        // 이미 진행 중인 세션이 있으면 재시도가 아니라 그 세션 화면으로 보낸다.
        if (e.code === "ALREADY_IN_ACTIVE_SESSION" && e.details?.sessionId) {
          navigate(`/sessions/${e.details.sessionId}`, { replace: true });
          return;
        }
        if (e.code === "REMATCH_COOLDOWN") {
          setError(`퇴출 후 재매칭 대기 중이다. ${e.details?.availableAt ?? ""} 부터 가능하다.`);
        } else if (e.code === "CONSENT_REQUIRED") {
          navigate("/media-consent");
          return;
        } else {
          setError(`${e.message} (${e.code})`);
        }
      } else {
        setError(String(e));
      }
    } finally {
      setBusy(false);
    }
  }

  async function cancel(matchRequestId: number) {
    setBusy(true);
    try {
      await api.match.cancel(matchRequestId);
      setRequest(null);
      navigate("/", { replace: true });
    } catch (e) {
      setError(String(e));
    } finally {
      setBusy(false);
    }
  }

  const waiting = current?.status === "WAITING";

  return (
    <div className="screen">
      <h1>매칭</h1>

      {!waiting && (
        <>
          <p className="muted">공부할 시간을 고르면 같은 시간을 고른 6명이 자동으로 묶인다.</p>
          <div className="row">
            {CHOICES.map((m) => (
              <button key={m} className="primary" disabled={busy} onClick={() => apply(m)}>
                {m / 60}시간
              </button>
            ))}
          </div>
        </>
      )}

      {waiting && current && (
        <div className="card">
          <p>
            <b>
              {current.waitingCount} / {current.requiredCount}명
            </b>{" "}
            모였다. {current.targetMinutes / 60}시간 세션이다.
          </p>
          <Countdown until={current.expiresAt} />
          <p className="muted">
            대기가 만료돼도 최대 1분간 대기 중으로 보일 수 있다. 만료 처리를 매분 도는 배치가
            하기 때문이다.
          </p>
          <button disabled={busy} onClick={() => cancel(current.matchRequestId)}>
            대기 취소
          </button>
        </div>
      )}

      {(current?.status === "EXPIRED" || current?.status === "CANCELLED") && (
        <p className="muted">
          {current.status === "EXPIRED" ? "대기가 만료됐다." : "대기를 취소했다."} 다시 신청할 수 있다.
        </p>
      )}

      {error && <p className="error">{error}</p>}
    </div>
  );
}

/** expiresAt 은 신청 +10분이다. 잠정값이라 서버가 준 시각을 그대로 센다 */
function Countdown({ until }: { until: string }) {
  const [now, setNow] = useState(Date.now());
  useEffect(() => {
    const id = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(id);
  }, []);
  const left = Math.max(0, Math.floor((Date.parse(until) - now) / 1000));
  return (
    <p className="muted">
      남은 대기 시간 {String(Math.floor(left / 60)).padStart(2, "0")}:
      {String(left % 60).padStart(2, "0")}
    </p>
  );
}
