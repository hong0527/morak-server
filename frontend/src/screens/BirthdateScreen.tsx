import { useState } from "react";
import { useNavigate } from "react-router-dom";

import { api } from "../api/client";
import { ApiError } from "../api/http";

/**
 * S2. needsBirthdate=true 일 때만.
 *
 * **되돌릴 수 없다.** 만 14세 미만으로 판정되면 계정이 영구히 잠긴다(★D7).
 * 그래서 확인 단계를 한 번 둔다.
 */
export default function BirthdateScreen() {
  const navigate = useNavigate();
  const [birthDate, setBirthDate] = useState("");
  const [confirming, setConfirming] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit() {
    setBusy(true);
    setError(null);
    try {
      await api.member.verifyAge(birthDate);
      navigate("/", { replace: true });
    } catch (e) {
      if (e instanceof ApiError) {
        if (e.code === "UNDER_AGE_SIGNUP_BLOCKED") {
          setError("만 14세 미만은 가입할 수 없다. 이 계정은 더 이상 쓸 수 없다.");
        } else if (e.code === "ALREADY_VERIFIED") {
          navigate("/", { replace: true });
          return;
        } else {
          setError(`${e.message} (${e.code})`);
        }
      } else {
        setError(String(e));
      }
      setConfirming(false);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="screen">
      <h1>생년월일 확인</h1>
      <p className="muted">
        만 14세 미만은 가입할 수 없다. 한 번 저장하면 바꿀 수 없으니 확인하고 넣는다.
      </p>
      <input
        type="date"
        value={birthDate}
        onChange={(e) => setBirthDate(e.target.value)}
        max={new Date().toISOString().slice(0, 10)}
      />
      {!confirming ? (
        <button
          className="primary"
          style={{ marginTop: 12 }}
          disabled={!birthDate}
          onClick={() => setConfirming(true)}
        >
          다음
        </button>
      ) : (
        <div className="notice" style={{ marginTop: 12 }}>
          <p>{birthDate} 로 저장한다. 저장 후에는 바꿀 수 없다.</p>
          <div className="row">
            <button className="primary" onClick={submit} disabled={busy}>
              저장
            </button>
            <button onClick={() => setConfirming(false)} disabled={busy}>
              고치기
            </button>
          </div>
        </div>
      )}
      {error && <p className="error">{error}</p>}
    </div>
  );
}
