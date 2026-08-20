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
  const [sharing, setSharing] = useState(false);
  const [shareMessage, setShareMessage] = useState<string | null>(null);

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
            setError("결과 정리가 예상보다 오래 걸리고 있어요. 잠시 뒤 다시 확인해 주세요.");
            return;
          }
          window.setTimeout(attempt, RETRY_MS);
          return;
        }
        setSettling(false);
        setError(e instanceof ApiError ? e.message : String(e));
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
        <h1>결과 정리 중</h1>
        <p className="muted loading">
          세션이 끝났어요. 결과가 나오기까지 최대 1분 정도 걸려요.
        </p>
      </div>
    );
  }

  if (error || !result) {
    return (
      <div className="screen">
        <p className="error">{error ?? "결과를 불러오지 못했어요."}</p>
        <button onClick={() => navigate("/")}>홈으로</button>
      </div>
    );
  }

  const report = result;
  const my = report.my;

  async function shareReport() {
    setSharing(true);
    setShareMessage(null);
    try {
      const blob = await createReportCard(report);
      const file = new File([blob], `morak-session-${report.sessionId}.png`, { type: "image/png" });
      const shareData = {
        title: "모락 완주 리포트",
        text: `${report.targetMinutes / 60}시간 목표 ${my.completed ? "완주" : "도전"} 기록`,
        files: [file],
      };

      if (navigator.share && navigator.canShare?.({ files: [file] })) {
        await navigator.share(shareData);
        setShareMessage("공유 화면을 열었어요.");
      } else {
        downloadBlob(blob, file.name);
        setShareMessage("공유 카드를 저장했어요. 인스타그램 스토리 등에 올려 보세요.");
      }
    } catch (e) {
      if (e instanceof DOMException && e.name === "AbortError") return;
      setShareMessage("공유 카드를 만들지 못했어요. 잠시 후 다시 시도해 주세요.");
    } finally {
      setSharing(false);
    }
  }

  return (
    <div className="screen">
      <h1>{my.completed ? "완주했어요" : "완주하지 못했어요"}</h1>

      {/* 조기 종료여도 남아 있던 사람은 완주다. "중단됨"으로 그리면 사실과 다르다 */}
      {result.endReason === "EARLY_UNDER_MIN" && (
        <p className="muted">
          인원이 부족해 세션이 일찍 끝났어요. 끝까지 남아 있었다면 완주로 인정돼요.
        </p>
      )}

      {/* 퇴출은 여기서 처음 알게 될 수 있다. 이의 기한 3일은 이미 흐르고 있다 */}
      {my.evictionId !== null && (
        <div className="notice">
          <p>
            경고가 {my.warningCount}회 누적되어 세션에서 퇴출됐어요. 판정이 잘못됐다고
            생각하시면 이의를 신청할 수 있어요. <b>기한은 퇴출 시각부터 3일이에요.</b>
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

      <section className="result-share">
        <div>
          <h2>오늘의 기록 공유</h2>
          <p>완주 리포트를 이미지로 만들어 인스타그램 스토리 등에 공유할 수 있어요.</p>
        </div>
        <button className="cta" disabled={sharing} onClick={shareReport}>
          {sharing ? "카드 만드는 중..." : "완주 리포트 공유"}
        </button>
        {shareMessage && <p className="share-message" role="status">{shareMessage}</p>}
      </section>

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
              <td>
                {p.completed
                  ? "완주"
                  : p.participantStatus === "EVICTED"
                    ? "퇴출"
                    : p.participantStatus === "LEFT"
                      ? "중도 퇴장"
                      : "미완주"}
              </td>
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

async function createReportCard(result: SessionResult): Promise<Blob> {
  const canvas = document.createElement("canvas");
  canvas.width = 1080;
  canvas.height = 1920;
  const ctx = canvas.getContext("2d");
  if (!ctx) throw new Error("Canvas is unavailable");

  const my = result.my;
  const gradient = ctx.createLinearGradient(0, 0, 1080, 1920);
  gradient.addColorStop(0, "#fffdfb");
  gradient.addColorStop(1, "#fff0e9");
  ctx.fillStyle = gradient;
  ctx.fillRect(0, 0, 1080, 1920);

  ctx.fillStyle = "#ff532f";
  ctx.fillRect(0, 0, 1080, 28);
  ctx.textAlign = "center";
  ctx.fillStyle = "#ff532f";
  ctx.font = '900 92px Arial, "Noto Sans KR", sans-serif';
  ctx.fillText("모락", 540, 190);
  ctx.fillStyle = "#8c4a34";
  ctx.font = '700 32px Arial, "Noto Sans KR", sans-serif';
  ctx.fillText("함께 시작하는 목표 달성", 540, 250);

  roundedRect(ctx, 90, 360, 900, 920, 40, "#ffffff");
  ctx.fillStyle = my.completed ? "#ff532f" : "#766a62";
  ctx.font = '900 52px Arial, "Noto Sans KR", sans-serif';
  ctx.fillText(my.completed ? "오늘도 완주했어요" : "오늘의 도전을 기록했어요", 540, 485);
  ctx.fillStyle = "#171717";
  ctx.font = '900 170px Arial, "Noto Sans KR", sans-serif';
  ctx.fillText(`${result.targetMinutes / 60}`, 540, 720);
  ctx.font = '800 42px Arial, "Noto Sans KR", sans-serif';
  ctx.fillText("시간 몰입", 540, 790);

  const stats: Array<[string, string]> = [
    ["연속 완주", `${my.streak.after}일`],
    ["받은 포인트", `${my.pointAwarded >= 0 ? "+" : ""}${my.pointAwarded}P`],
    ["함께한 인원", `${result.participants.length}명`],
  ];
  stats.forEach(([label, value], index) => {
    const x = 130 + index * 285;
    roundedRect(ctx, x, 890, 250, 230, 24, "#fff5ef");
    ctx.fillStyle = "#766a62";
    ctx.font = '700 28px Arial, "Noto Sans KR", sans-serif';
    ctx.fillText(label, x + 125, 960);
    ctx.fillStyle = "#171717";
    ctx.font = '900 46px Arial, "Noto Sans KR", sans-serif';
    ctx.fillText(value, x + 125, 1045);
  });

  if (my.goalAchieved) {
    roundedRect(ctx, 190, 1170, 700, 74, 37, "#ffc928");
    ctx.fillStyle = "#6e3b2d";
    ctx.font = '900 28px Arial, "Noto Sans KR", sans-serif';
    ctx.fillText("개인 목표 달성", 540, 1218);
  }

  ctx.fillStyle = "#171717";
  ctx.font = '900 42px Arial, "Noto Sans KR", sans-serif';
  ctx.fillText("작은 몰입이 모여 오늘을 만들었어요", 540, 1450);
  ctx.fillStyle = "#766a62";
  ctx.font = '600 28px Arial, "Noto Sans KR", sans-serif';
  ctx.fillText(new Intl.DateTimeFormat("ko-KR", { dateStyle: "long" }).format(new Date(result.endedAt)), 540, 1510);
  ctx.fillText("morak.duckdns.org", 540, 1780);

  return new Promise((resolve, reject) => canvas.toBlob((blob) => blob ? resolve(blob) : reject(new Error("PNG encoding failed")), "image/png"));
}

function roundedRect(ctx: CanvasRenderingContext2D, x: number, y: number, width: number, height: number, radius: number, fill: string) {
  ctx.beginPath();
  ctx.roundRect(x, y, width, height, radius);
  ctx.fillStyle = fill;
  ctx.fill();
}

function downloadBlob(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = filename;
  link.click();
  window.setTimeout(() => URL.revokeObjectURL(url), 1000);
}
