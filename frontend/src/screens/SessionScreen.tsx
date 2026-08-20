import { useCallback, useEffect, useMemo, useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";

import { api } from "../api/client";
import { ApiError } from "../api/http";
import { usePolling } from "../hooks/usePolling";
import { useSessionRoom } from "../livekit/useRoom";
import { useAbsenceDetection } from "../detection/useAbsenceDetection";
import type { LeaveReason, SessionDetail, SessionParticipant } from "../api/types";

/** 서버가 강제하지 않는다. 2~3초를 권한다 — 짧으면 서버가 먼저 지친다 */
const POLL_MS = 2500;

/**
 * S7. 세션 화면.
 *
 * 이 화면이 지키는 것들(frontend-guide §3 "실례"):
 *  - SS-1 을 계속 다시 묻는다. 남의 퇴장·퇴출·화장실 모드와 조기 종료를 아는 경로가 이것뿐이다
 *  - 입장 순서가 SS-1 → SS-2 → LiveKit 이다. SS-2 를 먼저 부르면 동의 미비 때 참가자 목록도 없다
 *  - 저장해 둔 LiveKit 토큰을 재사용하지 않는다. 수명 1시간, 세션 최대 4시간이다
 *  - LEFT·EVICTED 참가자 카드를 지우지 않는다. 빈자리가 드러나야 한다
 *  - 퇴출되면 LiveKit 을 직접 끊는다. 서버가 끊어 주지 않는다
 *  - 화장실 모드 버튼을 pauseUsed 로 미리 막는다. 두 번째는 409 다
 */
export default function SessionScreen() {
  const params = useParams();
  const navigate = useNavigate();
  const sessionId = Number(params.sessionId);

  const [entryError, setEntryError] = useState<string | null>(null);
  const [leaving, setLeaving] = useState(false);
  const [pauseEndsAt, setPauseEndsAt] = useState<string | null>(null);

  // ① SS-1 을 먼저, 그리고 계속. 입장할 때 한 번만 부르면 세션 내내 화면이 멈춰 있다.
  const { data: session, error: pollError } = usePolling<SessionDetail>(
    (signal) => api.session.get(sessionId, signal),
    POLL_MS,
    { deps: [sessionId] },
  );

  const me = useMemo(
    () => session?.participants.find((p) => p.isMe) ?? null,
    [session],
  );

  // ② SS-2 와 LiveKit 연결. 훅 안에서 토큰을 매번 새로 발급받는다.
  const room = useSessionRoom(session ? sessionId : null);

  const goToResult = useCallback(() => {
    room.disconnect();
    navigate(`/sessions/${sessionId}/result`, { replace: true });
  }, [room, navigate, sessionId]);

  // ③ 감지. 카메라가 준비된 뒤에 시작한다 — 새로 열지 않고 위 <video> 를 그대로 받는다.
  const detection = useAbsenceDetection({
    sessionId,
    sessionStartedAt: session?.startedAt ?? "",
    memberId: me?.memberId ?? 0,
    videoRef: room.localVideoRef,
    enabled: room.cameraReady && !!session?.startedAt && !!me,
    // 퇴출은 그 자리에서 세션이 끝난 것이다. 캠을 끊고 결과 화면으로 보낸다.
    onEvicted: goToResult,
  });

  // ④ 세션이 끝나면 결과 화면으로. 정시 종료·조기 종료 둘 다 이 경로로 안다.
  useEffect(() => {
    if (session?.status === "ENDED" || session?.status === "CANCELLED") goToResult();
  }, [session?.status, goToResult]);

  // 내가 퇴출·퇴장 상태로 폴링에 잡히는 경우(종료 정산 등)
  useEffect(() => {
    if (me && (me.status === "EVICTED" || me.status === "LEFT")) goToResult();
  }, [me, goToResult]);

  useEffect(() => {
    if (!(pollError instanceof ApiError)) return;
    if (pollError.code === "CONSENT_REQUIRED") navigate("/media-consent", { replace: true });
    else if (pollError.code === "NOT_SESSION_PARTICIPANT") navigate("/", { replace: true });
    else if (pollError.code === "SESSION_ENDED") goToResult();
    else setEntryError(`${pollError.message} (${pollError.code})`);
  }, [pollError, navigate, goToResult]);

  async function startRestroom() {
    try {
      const res = await detection.startRestroom();
      setPauseEndsAt(res.resumeDueAt);
    } catch (e) {
      if (e instanceof ApiError && e.code === "PAUSE_ALREADY_USED") {
        setEntryError("화장실 모드는 세션당 한 번만 쓸 수 있다.");
      } else if (e instanceof ApiError && e.code === "ALREADY_EVICTED") {
        goToResult();
      } else {
        setEntryError(String(e));
      }
    }
  }

  async function endRestroom() {
    try {
      await detection.endRestroom();
      setPauseEndsAt(null);
    } catch (e) {
      setEntryError(String(e));
    }
  }

  async function leave(reason: LeaveReason) {
    setLeaving(true);
    try {
      // DELETE 인데 본문이 필수다. 빠뜨리면 400 REASON_REQUIRED 다.
      await api.session.leave(sessionId, reason);
      room.disconnect();
      navigate("/", { replace: true });
    } catch (e) {
      setEntryError(String(e));
      setLeaving(false);
    }
  }

  if (!session) {
    return (
      <div className="screen wide">
        <p className="muted">세션을 불러오는 중...</p>
        {entryError && <p className="error">{entryError}</p>}
      </div>
    );
  }

  const paused = me?.paused ?? false;

  return (
    // 캠 6개가 들어가는 화면이라 이 화면만 wide 로 넓힌다
    <div className="screen wide">
      <div className="row" style={{ justifyContent: "space-between" }}>
        <h1>세션 #{session.sessionId}</h1>
        <Remaining endsAt={session.endsAt} />
      </div>

      <div className="row" style={{ gap: 16 }}>
        <span>
          내 경고 <b>{detection.warningCount} / 3</b>
        </span>
        <span className="muted">감지 {phaseLabel(detection.phase)}</span>
      </div>

      {room.error && <div className="notice">{room.error}</div>}
      {detection.error && <div className="notice">{detection.error}</div>}
      {detection.calibrationWarnings.includes("LOW_LIGHT") && (
        <div className="notice">조명이 어둡다. 밝게 하면 판정이 정확해진다.</div>
      )}
      {detection.adjustHint && (
        <div className="notice">카메라 각도나 조명을 조정해 주세요.</div>
      )}
      {entryError && <p className="error">{entryError}</p>}

      <h2>캠</h2>
      <div className="cam-grid">
        <div className="cam">
          {/* 감지 모듈이 받아 쓰는 video 다. 여기서만 카메라를 잡는다 */}
          <video ref={room.localVideoRef} playsInline muted autoPlay />
          <div className="label">나 {paused && "· 화장실 모드"}</div>
        </div>
        {session.participants
          .filter((p) => !p.isMe)
          .map((p) => (
            <ParticipantCam
              key={p.memberId}
              sessionId={sessionId}
              participant={p}
              track={room.remoteCams.find((c) => c.identity === String(p.memberId))?.track}
            />
          ))}
      </div>

      <h2>오늘 할 일</h2>
      <GoalText sessionId={sessionId} initial={me?.goalText ?? null} />

      <h2>세션</h2>
      <div className="row">
        {!paused ? (
          <button
            onClick={startRestroom}
            // 두 번째는 409 다. 미리 막는다.
            disabled={me?.pauseUsed || detection.phase === "PAUSED"}
          >
            화장실 모드 {me?.pauseUsed && "(사용함)"}
          </button>
        ) : (
          <button className="primary" onClick={endRestroom}>
            복귀하기 {pauseEndsAt && <PauseLeft until={pauseEndsAt} />}
          </button>
        )}
        <button disabled={leaving} onClick={() => leave("PERSONAL")}>
          나가기
        </button>
      </div>
      <p className="muted">
        나가면 미완주가 되고 다시 들어올 수 없다. 화장실 모드는 세션당 한 번, 10분까지다.
      </p>

      {/* 스티커 전송은 서버를 거치지 않고 LiveKit 데이터 채널로 간다.
          GET /api/stickers 는 목록만 준다. */}
      <h2>스티커</h2>
      <div className="todo">
        TODO. 목록은 <code>api.stickers.list()</code> 로 받고, 전송은 서버가 아니라 LiveKit
        데이터 채널(<code>room.localParticipant.publishData</code>)로 보낸다. 서버는 스티커를
        저장하지도 중계하지도 않는다.
      </div>

      {/* 프롬프트. 15초 안에 반응해야 하고, 탭하면 반드시 tapPrompt() 를 부른다 —
          그 시각이 재실 증거로 서버에 보낼 START 시각을 뒤로 민다. */}
      {detection.promptVisible && (
        <div className="prompt-overlay">
          <div className="box">
            <h2 style={{ color: "inherit", fontSize: 18 }}>자리에 계신가요?</h2>
            <p className="muted">15초 안에 눌러 주세요.</p>
            <button className="primary" onClick={detection.tapPrompt}>
              네, 있습니다
            </button>
          </div>
        </div>
      )}
    </div>
  );
}

function ParticipantCam({
  sessionId,
  participant,
  track,
}: {
  sessionId: number;
  participant: SessionParticipant;
  track?: { attach: (el: HTMLVideoElement) => void; detach: () => void } | undefined;
}) {
  const [el, setEl] = useState<HTMLVideoElement | null>(null);

  useEffect(() => {
    if (!track || !el) return;
    track.attach(el);
    return () => {
      track.detach();
    };
  }, [track, el]);

  // LEFT·EVICTED 도 목록에 그대로 둔다. 빈자리가 드러나야 하기 때문이다.
  const gone = participant.status === "LEFT" || participant.status === "EVICTED";

  return (
    <div className={`cam ${gone ? "left" : ""}`}>
      <video ref={setEl} playsInline autoPlay />
      <div className="label">
        {participant.nickname}
        {participant.status === "PAUSED" && " · 화장실"}
        {participant.status === "LEFT" && " · 나감"}
        {participant.status === "EVICTED" && " · 퇴출"}
        {participant.joinedAt === null && !gone && " · 접속 대기"}
        {participant.warningCount > 0 && ` · 경고 ${participant.warningCount}`}
        {participant.goalText && (
          <div className="muted" style={{ fontSize: 11 }}>
            {participant.goalText}
          </div>
        )}
        {/* 신고 화면이 쿼리로 세션·대상을 받으므로 여기서 채워 보낸다 */}
        {!gone && (
          <Link
            to={`/report?sessionId=${sessionId}&memberId=${participant.memberId}`}
            className="muted"
            style={{ fontSize: 11 }}
          >
            신고
          </Link>
        )}
      </div>
    </div>
  );
}

function GoalText({ sessionId, initial }: { sessionId: number; initial: string | null }) {
  const [text, setText] = useState(initial ?? "");
  const [saved, setSaved] = useState(false);

  useEffect(() => {
    if (initial !== null) setText(initial);
  }, [initial]);

  async function save() {
    // 최대 50자, 빈 문자열은 400 이다.
    if (!text.trim()) return;
    await api.session.setGoalText(sessionId, text.trim());
    setSaved(true);
  }

  return (
    <div className="row">
      <input
        value={text}
        maxLength={50}
        placeholder="같은 세션 사람들에게 보인다"
        onChange={(e) => {
          setText(e.target.value);
          setSaved(false);
        }}
        style={{ flex: 1, minWidth: 200 }}
      />
      <button onClick={save} disabled={!text.trim() || saved}>
        {saved ? "저장됨" : "저장"}
      </button>
    </div>
  );
}

function Remaining({ endsAt }: { endsAt: string }) {
  const [now, setNow] = useState(Date.now());
  useEffect(() => {
    const id = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(id);
  }, []);
  const left = Math.max(0, Math.floor((Date.parse(endsAt) - now) / 1000));
  const h = Math.floor(left / 3600);
  const m = Math.floor((left % 3600) / 60);
  const s = left % 60;
  return (
    <b>
      {/* 0 이 돼도 세션은 최대 1분간 LIVE 로 남는다. 정산이 매분 :00 에 돌기 때문이다 */}
      {h > 0 && `${h}:`}
      {String(m).padStart(2, "0")}:{String(s).padStart(2, "0")}
    </b>
  );
}

function PauseLeft({ until }: { until: string }) {
  const [now, setNow] = useState(Date.now());
  useEffect(() => {
    const id = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(id);
  }, []);
  const left = Math.floor((Date.parse(until) - now) / 1000);
  // 상한을 넘겨 복귀하면 경고가 붙고, 그것이 3회째면 퇴출이다.
  if (left <= 0) return <span> (초과)</span>;
  return (
    <span>
      {" "}
      ({Math.floor(left / 60)}:{String(left % 60).padStart(2, "0")})
    </span>
  );
}

function phaseLabel(phase: string): string {
  switch (phase) {
    case "LOADING_MODEL":
      return "모델 로딩 중";
    case "CALIBRATING":
      return "기준 잡는 중 (8초)";
    case "RUNNING":
      return "작동 중";
    case "PAUSED":
      return "멈춤 (화장실 모드)";
    case "STOPPED":
      return "종료됨";
    case "FAILED":
      return "실패";
    default:
      return "대기";
  }
}
