import { useState } from "react";
import { useNavigate } from "react-router-dom";

import { api } from "../api/client";
import { ApiError } from "../api/http";
import { authStore } from "../auth/session";

/**
 * 계정 탈퇴. 마크업은 프로토타입 data-screen="leave"(안내 카드 3장 + 동의 스위치 +
 * 검정 CTA)와 data-screen="leaveDone"(유예 안내 + 철회)을 한 화면의 두 상태로 옮겼다.
 *
 * AU-4 신청 → 30일 유예. 유예 안에 AU-5 철회나 재로그인으로 되살릴 수 있다.
 * 이미 유예 중인 계정이 다시 신청하면 서버가 WITHDRAWAL_PENDING 을 내므로
 * 그때는 신청 화면 대신 철회 화면을 보여준다.
 */
export default function LeaveScreen() {
  const navigate = useNavigate();
  const [checked, setChecked] = useState(false);
  const [deleteScheduledAt, setDeleteScheduledAt] = useState<string | null>(null);
  const [done, setDone] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function withdraw() {
    setBusy(true);
    setError(null);
    try {
      const res = await api.member.withdraw();
      setDeleteScheduledAt(res.deleteScheduledAt);
      setDone(true);
    } catch (e) {
      if (e instanceof ApiError && e.code === "WITHDRAWAL_PENDING") {
        // 이미 신청돼 있다. 완료 상태로 보여주고 철회 경로를 연다.
        setDone(true);
      } else {
        setError(e instanceof ApiError ? `${e.message} (${e.code})` : String(e));
      }
    } finally {
      setBusy(false);
    }
  }

  async function revoke() {
    setBusy(true);
    setError(null);
    try {
      await api.member.cancelWithdrawal();
      navigate("/", { replace: true });
    } catch (e) {
      setError(e instanceof ApiError ? `${e.message} (${e.code})` : String(e));
      setBusy(false);
    }
  }

  if (done) {
    return (
      <div className="screen center-title done">
        <h1>탈퇴 신청 완료</h1>
        <p className="muted">30일 유예 기간 동안 탈퇴를 철회할 수 있습니다.</p>
        {deleteScheduledAt && (
          <div className="card info">
            <h3>유예 기간</h3>
            <p>
              <b>{deleteScheduledAt.slice(0, 10)} 까지</b>
            </p>
          </div>
        )}
        <div className="card small-text">
          <h3>삭제될 정보</h3>
          <p>
            SNS 연동 정보, 생년월일·연령 검증 정보, 프로필, 동의 이력이 유예 기간이 지나면
            삭제됩니다. 유예 안에 다시 로그인하거나 철회하면 계정이 되살아납니다.
          </p>
        </div>
        <button className="black-button" disabled={busy} onClick={revoke}>
          탈퇴 철회
        </button>
        <button className="black-button" disabled={busy} onClick={() => authStore.signOut()}>
          로그아웃
        </button>
        {error && <p className="error">{error}</p>}
      </div>
    );
  }

  return (
    <div className="screen">
      <header className="topbar">
        <button onClick={() => navigate(-1)}>‹</button>
        <b>계정 탈퇴</b>
        <span></span>
      </header>
      <h1>계정 탈퇴 신청</h1>
      <div className="card small-text">
        <h3>탈퇴 즉시 적용되는 이용 제한</h3>
        <p>
          탈퇴 신청 즉시 서비스 기능 이용이 제한됩니다. 진행 중인 세션이 있다면 퇴장
          처리됩니다.
        </p>
      </div>
      <div className="card small-text">
        <h3>30일 유예 후 자동 삭제되는 정보</h3>
        <p>
          SNS 제공자 식별자, 생년월일 및 연령 검증 정보, 프로필 정보, 동의 이력이
          삭제됩니다.
        </p>
      </div>
      <div className="card small-text">
        <h3>되살리는 방법</h3>
        <p>
          유예 기간 안에 다시 로그인하거나 탈퇴를 철회하면 계정이 되살아납니다. 유예가
          지나면 되돌릴 수 없습니다.
        </p>
      </div>
      <p className="hint">
        <span>위 내용을 모두 확인하였으며 탈퇴에 동의합니다.</span>
        <span
          className={checked ? "switch" : "switch off"}
          role="switch"
          aria-checked={checked}
          onClick={() => setChecked(!checked)}
        ></span>
      </p>
      <button className="cta black-cta" disabled={busy || !checked} onClick={withdraw}>
        탈퇴하기
      </button>
      {error && <p className="error">{error}</p>}
    </div>
  );
}
