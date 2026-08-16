import { useState } from "react";
import { useNavigate } from "react-router-dom";

import { api } from "../api/client";

/**
 * S3. mediaConsented=false 일 때.
 *
 * 동의 없이는 세션 접속 토큰(SS-2)이 403 CONSENT_REQUIRED 로 막힌다. 즉 매칭은 되는데
 * 세션에 못 들어가는 상태가 만들어지므로 **매칭 신청 전에 받아 둔다.**
 *
 * 문구에 들어가야 하는 것(ai-pipeline.md §7): 자동 판정 사실, 기준 수치, 이의 경로,
 * 다른 참가자 5인에게 영상이 송출된다는 사실.
 *
 * 아래 수치는 아직 팀이 확정하지 않은 잠정값이다(frontend-guide §8 1·2번).
 * **바뀌면 재동의가 필요하다.** 그래서 화면 여기저기에 흩뿌리지 않고 이 상수 한 곳에 뒀다.
 */
const TERMS = {
  absenceThresholdSeconds: 60,
  evictionWarnings: 3,
  evictionPenaltyPoints: 300,
};

export default function MediaConsentScreen() {
  const navigate = useNavigate();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function agree() {
    setBusy(true);
    try {
      // true 만 받는다. 미동의는 저장하지 않고 400 이며 철회 API 는 v1 에 없다.
      await api.member.agreeMediaConsent();
      navigate("/", { replace: true });
    } catch (e) {
      setError(String(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="screen">
      <h1>캠 분석 동의</h1>
      <div className="card">
        <p>
          세션 중 카메라 영상은 <b>내 기기 안에서만</b> 분석된다. 영상은 서버로 보내지 않고
          자리를 비웠는지 여부와 그 시각만 기록된다.
        </p>
        <p>
          자리를 <b>{TERMS.absenceThresholdSeconds}초</b> 이상 비우면 경고가 하나 붙고,
          경고가 <b>{TERMS.evictionWarnings}회</b>가 되면 세션에서 나가게 되며{" "}
          <b>{TERMS.evictionPenaltyPoints}포인트</b>가 차감된다.
        </p>
        <p>
          판정이 잘못됐다고 생각하면 결과 화면에서 <b>이의를 신청</b>할 수 있다. 기한은 퇴출
          시각으로부터 3일이다.
        </p>
        <p>
          세션 중 내 카메라 영상은 <b>같은 세션의 다른 참가자 5명에게 실시간으로 송출된다.</b>{" "}
          녹화되지는 않는다.
        </p>
      </div>
      <p className="muted" style={{ marginTop: 12 }}>
        동의하지 않으면 세션에 들어갈 수 없다. 동의 철회 기능은 아직 없다.
      </p>
      <button className="primary" onClick={agree} disabled={busy}>
        동의하고 계속
      </button>
      {error && <p className="error">{error}</p>}
    </div>
  );
}
