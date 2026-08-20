import { useState } from "react";
import { useNavigate } from "react-router-dom";

import { api } from "../api/client";
import { ApiError } from "../api/http";

/**
 * S2. needsBirthdate=true 일 때만. 마크업은 프로토타입 data-screen="birth" 를 옮겼다
 * (년·월·일 세 칸 → 서버가 받는 YYYY-MM-DD 하나로 합쳐 보낸다).
 *
 * **되돌릴 수 없다.** 만 14세 미만으로 판정되면 계정이 영구히 잠긴다(★D7).
 * 그래서 확인 단계를 한 번 둔다.
 */
export default function BirthdateScreen() {
  const navigate = useNavigate();
  const [year, setYear] = useState("");
  const [month, setMonth] = useState("");
  const [day, setDay] = useState("");
  const [confirming, setConfirming] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  // 실존하는 날짜일 때만 YYYY-MM-DD 를 만든다. 아니면 빈 문자열 → 버튼이 막힌다.
  const birthDate = (() => {
    const y = Number(year);
    const m = Number(month);
    const d = Number(day);
    if (!y || !m || !d || y < 1900) return "";
    const date = new Date(y, m - 1, d);
    if (date.getFullYear() !== y || date.getMonth() !== m - 1 || date.getDate() !== d) return "";
    if (date > new Date()) return "";
    return `${y}-${String(m).padStart(2, "0")}-${String(d).padStart(2, "0")}`;
  })();

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
      <header className="topbar">
        <span></span>
        <b>생년월일</b>
        <span></span>
      </header>
      <h1>생년월일 입력</h1>
      <p className="muted">
        서비스 이용 가능 여부를 확인하기 위해 생년월일을 입력해주세요. 만 14세 미만은 가입할
        수 없고, 한 번 저장하면 바꿀 수 없습니다.
      </p>
      <label>
        년도 (YYYY)
        <input
          inputMode="numeric"
          maxLength={4}
          placeholder="예) 2002"
          value={year}
          onChange={(e) => setYear(e.target.value.replace(/\D/g, ""))}
        />
      </label>
      <label>
        월 (MM)
        <input
          inputMode="numeric"
          maxLength={2}
          placeholder="예) 03"
          value={month}
          onChange={(e) => setMonth(e.target.value.replace(/\D/g, ""))}
        />
      </label>
      <label>
        일 (DD)
        <input
          inputMode="numeric"
          maxLength={2}
          placeholder="예) 15"
          value={day}
          onChange={(e) => setDay(e.target.value.replace(/\D/g, ""))}
        />
      </label>
      <p className="hint">만 14세 이상만 이용 가능합니다.</p>
      {!confirming ? (
        <button className="cta black-cta" disabled={!birthDate} onClick={() => setConfirming(true)}>
          확인
        </button>
      ) : (
        <div className="card small-text">
          <h3>저장 전 확인</h3>
          <p>{birthDate} 로 저장한다. 저장 후에는 바꿀 수 없다.</p>
          <button className="cta black-cta" style={{ marginTop: 12 }} onClick={submit} disabled={busy}>
            저장
          </button>
          <button className="ghost" onClick={() => setConfirming(false)} disabled={busy}>
            고치기
          </button>
        </div>
      )}
      {error && <p className="error">{error}</p>}
    </div>
  );
}
