import { useEffect, useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";

import { api } from "../api/client";
import { ApiError } from "../api/http";
import type { MySessionPage, ReportCreateResponse, ReportReasonCode } from "../api/types";

const REASON_LABEL: Record<ReportReasonCode, string> = {
  SEXUAL_CONTENT: "성적 콘텐츠",
  VIOLENT_THREAT: "폭력·위협",
  AD_SPAM: "광고·스팸",
  INAPPROPRIATE_SCREEN: "부적절한 화면",
  ETC: "기타",
};

/** 신고 대상 후보. SS-1 과 SS-8 의 참가자 모양이 달라 여기서 공통분모로 줄인다 */
interface Candidate {
  memberId: number;
  nickname: string;
}

/**
 * S13. 신고. 같은 세션에 참가했던 회원이나 세션 자체를 신고한다(RP-1).
 * 마크업은 프로토타입 data-screen="report"(form-card 선택지)와
 * data-screen="reportDone"(접수 완료)을 옮겼다.
 *
 * 세션 화면에서 ?sessionId=&memberId= 로 넘어오는 흐름을 받되, 직접 들어와도
 * 쓸 수 있게 내 세션 기록(MS-1)에서 세션을 고르게 했다. 신고 자격이 같은 세션
 * 참가 이력이라 후보가 그 목록과 정확히 일치한다.
 *
 * 접수되면 대상 회원과의 재매칭이 영구 차단된다. 세션에서 나가지는 않는다(★D6).
 */
export default function ReportScreen() {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();

  const [sessions, setSessions] = useState<MySessionPage | null>(null);
  const [sessionId, setSessionId] = useState<string>(searchParams.get("sessionId") ?? "");
  const [targetType, setTargetType] = useState<"MEMBER" | "SESSION">("MEMBER");
  const [candidates, setCandidates] = useState<Candidate[] | null>(null);
  const [targetMemberId, setTargetMemberId] = useState<string>(searchParams.get("memberId") ?? "");
  const [reasonCode, setReasonCode] = useState<ReportReasonCode>("INAPPROPRIATE_SCREEN");
  const [detail, setDetail] = useState("");

  const [done, setDone] = useState<ReportCreateResponse | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    let cancelled = false;
    void (async () => {
      try {
        const page = await api.member.mySessions({ page: 0, size: 20 });
        if (cancelled) return;
        setSessions(page);
        // 쿼리로 세션이 지정되지 않았으면 가장 최근 세션을 고른다.
        const latest = page.content[0];
        if (!searchParams.get("sessionId") && latest) {
          setSessionId(String(latest.sessionId));
        }
      } catch (e) {
        if (cancelled) return;
        setError(e instanceof ApiError ? `${e.message} (${e.code})` : String(e));
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [searchParams]);

  // 대상 회원 후보는 그 세션의 참가자다. 진행 중이면 SS-1, 끝났으면 SS-8 에 목록이
  // 있는데 어느 쪽인지 확정할 수 없어(쿼리로 온 세션은 기록 목록에 없을 수 있다)
  // SS-1 을 먼저 묻고 안 되면 SS-8 로 넘어간다.
  useEffect(() => {
    if (!sessionId || targetType !== "MEMBER") return;
    let cancelled = false;
    setCandidates(null);
    void (async () => {
      const id = Number(sessionId);
      let list: Candidate[];
      try {
        const live = await api.session.get(id);
        list = live.participants
          .filter((p) => !p.isMe)
          .map((p) => ({ memberId: p.memberId, nickname: p.nickname }));
      } catch {
        try {
          const result = await api.session.result(id);
          list = result.participants
            .filter((p) => !p.isMe)
            .map((p) => ({ memberId: p.memberId, nickname: p.nickname }));
        } catch (e) {
          if (!cancelled) {
            setError(e instanceof ApiError ? `${e.message} (${e.code})` : String(e));
          }
          return;
        }
      }
      if (cancelled) return;
      setCandidates(list);
      const first = list[0];
      setTargetMemberId((prev) =>
        prev && list.some((c) => String(c.memberId) === prev)
          ? prev
          : first
            ? String(first.memberId)
            : "",
      );
      setError(null);
    })();
    return () => {
      cancelled = true;
    };
  }, [sessionId, targetType]);

  async function submit() {
    setBusy(true);
    setError(null);
    try {
      const created = await api.report.create({
        targetType,
        // SESSION 신고에서는 targetMemberId 를 보내지 않는다(RP-1)
        targetMemberId: targetType === "MEMBER" ? Number(targetMemberId) : undefined,
        sessionId: Number(sessionId),
        reasonCode,
        detail: detail.trim() || undefined,
      });
      setDone(created);
    } catch (e) {
      if (e instanceof ApiError && e.code === "DUPLICATE_REPORT") {
        setError("같은 대상을 이미 신고했다.");
      } else if (e instanceof ApiError && e.code === "NOT_SESSION_PARTICIPANT") {
        setError("같은 세션에 참가한 대상만 신고할 수 있다.");
      } else if (e instanceof ApiError && e.code === "TARGET_NOT_FOUND") {
        setError("신고 대상을 찾을 수 없다.");
      } else {
        setError(e instanceof ApiError ? `${e.message} (${e.code})` : String(e));
      }
    } finally {
      setBusy(false);
    }
  }

  if (done) {
    return (
      <div className="screen center-title done">
        <h1>신고가 접수되었습니다.</h1>
        <p className="muted">운영자가 검토한 뒤 조치합니다.</p>
        <div className="card info">
          <h3>접수 내용</h3>
          <p>
            접수 번호 <b>{done.caseId}</b>
          </p>
          <p>
            신고 사유 <b>{REASON_LABEL[reasonCode]}</b>
          </p>
          {done.blockedMemberId !== null && (
            <p>
              재매칭 <b>영구 차단됨</b>
            </p>
          )}
        </div>
        <button className="black-button" onClick={() => navigate("/")}>
          홈으로 돌아가기
        </button>
      </div>
    );
  }

  if (!sessions) {
    return (
      <div className="screen">
        {error ? <p className="error">{error}</p> : <p className="muted">불러오는 중...</p>}
      </div>
    );
  }

  if (sessions.content.length === 0 && !sessionId) {
    return (
      <div className="screen center-title done">
        <h1>신고</h1>
        <p className="muted">참여한 세션이 없다. 신고는 같이 세션을 한 대상에게만 할 수 있다.</p>
        <button className="black-button" onClick={() => navigate("/")}>
          홈으로 돌아가기
        </button>
      </div>
    );
  }

  const canSubmit =
    !!sessionId && (targetType === "SESSION" || !!targetMemberId) && !busy;

  return (
    <div className="screen center-title">
      <button className="back" onClick={() => navigate(-1)}>‹</button>
      <h1>신고하기</h1>

      <div className="card form-card">
        <label>
          세션
          <select value={sessionId} onChange={(e) => setSessionId(e.target.value)}>
            {/* 쿼리로 온 세션이 최근 20건 밖이면 목록에 없다. 그때도 선택이 비지 않게 넣어 둔다 */}
            {sessionId && !sessions.content.some((s) => String(s.sessionId) === sessionId) && (
              <option value={sessionId}>세션 {sessionId}</option>
            )}
            {sessions.content.map((s) => (
              <option key={s.sessionId} value={String(s.sessionId)}>
                {s.startedAt.slice(0, 16).replace("T", " ")} · {s.targetMinutes / 60}시간
                {s.status === "LIVE" && " (진행 중)"}
              </option>
            ))}
          </select>
        </label>
        <label>
          신고 대상
          <select
            value={targetType}
            onChange={(e) => setTargetType(e.target.value as "MEMBER" | "SESSION")}
          >
            <option value="MEMBER">참가자</option>
            <option value="SESSION">세션 전체</option>
          </select>
        </label>
        {targetType === "MEMBER" &&
          (candidates === null ? (
            <p className="muted" style={{ marginTop: 12 }}>참가자를 불러오는 중...</p>
          ) : candidates.length === 0 ? (
            <p className="muted" style={{ marginTop: 12 }}>
              이 세션에는 신고할 수 있는 다른 참가자가 없다.
            </p>
          ) : (
            <label>
              대상 참가자
              <select value={targetMemberId} onChange={(e) => setTargetMemberId(e.target.value)}>
                {candidates.map((c) => (
                  <option key={c.memberId} value={String(c.memberId)}>
                    {c.nickname}
                  </option>
                ))}
              </select>
            </label>
          ))}
        <label>
          신고 사유 선택
          <select
            value={reasonCode}
            onChange={(e) => setReasonCode(e.target.value as ReportReasonCode)}
          >
            {(Object.keys(REASON_LABEL) as ReportReasonCode[]).map((code) => (
              <option key={code} value={code}>
                {REASON_LABEL[code]}
              </option>
            ))}
          </select>
        </label>
        <label>
          상세 내용 (선택)
          <textarea
            rows={4}
            maxLength={500}
            value={detail}
            onChange={(e) => setDetail(e.target.value)}
            placeholder="무슨 일이 있었는지 적는다. 최대 500자."
          />
        </label>
      </div>

      <div className="card small-text">
        <h3>처리 안내</h3>
        <p>참가자를 신고하면 그 상대와는 영구히 다시 매칭되지 않습니다.</p>
      </div>

      <button className="black-button" disabled={!canSubmit} onClick={submit}>
        신고 접수하기
      </button>

      {error && <p className="error">{error}</p>}
    </div>
  );
}
