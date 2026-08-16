import { DEFAULT_CONFIG } from "../src/config.js";
import { AbsenceSeqStore } from "../src/client/seq-store.js";
import {
  AbsenceEventSender,
  FetchAbsenceTransport,
  toIsoWithOffset,
  type AbsenceTransport,
  type TransportOutcome,
} from "../src/client/sender.js";
import type { AbsenceEventBody } from "../src/types.js";

/**
 * 서버 계약 정합 확인. **실제로 서버를 띄워 놓고** 이 클라이언트가 만드는 요청을 그대로 보낸다.
 *
 *   ./gradlew bootRun --args='--server.port=8130'    (morak-server 에서)
 *   npm run contract                                  (여기서)
 *
 * selftest 는 판정 로직이 설계대로 도는지를 본다. 이 스크립트가 보는 것은 다른 것이다 —
 * **그렇게 만든 요청을 서버가 실제로 받아들이는가.** 본문 필드 이름 하나, 시각 표기 하나가
 * 어긋나면 셀프테스트는 전부 통과하면서 실서비스에서는 400 만 돌아온다.
 *
 * 브라우저 없이 돈다. 카메라·모델 추론을 뺀 나머지(전송 계층과 서버 계약)만 확인한다.
 */

const BASE = process.env.MORAK_BASE_URL ?? "http://localhost:8130";
const TARGET_MINUTES = 60;
const REQUIRED_PARTICIPANTS = 6;

let failures = 0;

function check(name: string, actual: unknown, expected: unknown): void {
  const a = JSON.stringify(actual);
  const e = JSON.stringify(expected);
  if (a === e) {
    console.log(`  통과  ${name}`);
  } else {
    failures += 1;
    console.log(`  실패  ${name}\n        기대: ${e}\n        실제: ${a}`);
  }
}

function checkTrue(name: string, actual: boolean, detail = ""): void {
  if (actual) console.log(`  통과  ${name}`);
  else {
    failures += 1;
    console.log(`  실패  ${name}${detail ? `\n        ${detail}` : ""}`);
  }
}

async function api(
  method: string,
  path: string,
  token: string | null,
  body?: unknown,
): Promise<{ status: number; json: any }> {
  const res = await fetch(`${BASE}${path}`, {
    method,
    headers: {
      "Content-Type": "application/json",
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  });
  const text = await res.text();
  let json: any = null;
  try {
    json = text ? JSON.parse(text) : null;
  } catch {
    json = { raw: text };
  }
  return { status: res.status, json };
}

/** 가입 → 생년월일까지. 매칭(MT-1)이 연령 게이트를 지나려면 여기까지 필요하다 */
async function onboard(code: string): Promise<{ memberId: number; token: string }> {
  const login = await api("POST", "/api/auth/login", null, {
    provider: "KAKAO",
    authorizationCode: code,
    agreements: [
      { type: "TOS", agreed: true },
      { type: "PRIVACY", agreed: true },
    ],
  });
  if (login.status !== 200) throw new Error(`로그인 실패 ${login.status}: ${JSON.stringify(login.json)}`);
  const token = login.json.accessToken as string;
  const birth = await api("POST", "/api/members/me/birthdate", token, {
    birthDate: "1998-04-11",
  });
  if (birth.status !== 200) throw new Error(`생년월일 실패 ${birth.status}`);
  return { memberId: login.json.memberId as number, token };
}

/** 전송 시도를 전부 기록하는 래퍼. 429 재전송이 같은 번호였는지를 여기서 본다 */
class RecordingTransport implements AbsenceTransport {
  readonly attempts: { clientSeq: number; type: string; occurredAt: string; kind: string }[] = [];

  constructor(private readonly inner: AbsenceTransport) {}

  async post(body: AbsenceEventBody): Promise<TransportOutcome> {
    const outcome = await this.inner.post(body);
    this.attempts.push({
      clientSeq: body.clientSeq,
      type: body.type,
      occurredAt: body.occurredAt,
      kind: outcome.kind,
    });
    return outcome;
  }
}

function sleep(ms: number): Promise<void> {
  return new Promise((r) => setTimeout(r, ms));
}

async function main(): Promise<void> {
  console.log(`서버: ${BASE}`);

  const health = await fetch(`${BASE}/api/stickers`).catch(() => null);
  if (health === null) {
    console.error(
      `서버에 닿지 못했다. morak-server 에서 다음을 먼저 실행하라:\n` +
        `  ./gradlew bootRun --args='--server.port=8130'`,
    );
    process.exit(2);
  }

  // ── 준비: 6인 매칭으로 LIVE 세션 하나 ──
  const stamp = Date.now();
  const me = await onboard(`contract-${stamp}-0`);
  await api("POST", "/api/match-requests", me.token, { targetMinutes: TARGET_MINUTES });
  const others: { memberId: number; token: string }[] = [];
  for (let i = 1; i < REQUIRED_PARTICIPANTS; i += 1) {
    const other = await onboard(`contract-${stamp}-${i}`);
    others.push(other);
    await api("POST", "/api/match-requests", other.token, { targetMinutes: TARGET_MINUTES });
  }
  // 429 확인은 다른 참가자로 한다. 멱등키가 (세션, 회원, clientSeq) 라서 같은 사람으로
  // 하면 앞에서 쓴 번호와 겹쳐 429 보다 409(중복)가 먼저 나온다 — 서버의 검사 순서다.
  const rateLimitProbe = others[0];
  if (!rateLimitProbe) throw new Error("참가자 준비 실패");
  const poll = await api("GET", "/api/match-requests/me", me.token);
  const sessionId = poll.json?.sessionId as number | null;
  if (!sessionId) throw new Error(`세션이 열리지 않았다: ${JSON.stringify(poll.json)}`);
  console.log(`세션 ${sessionId} 준비 완료 (참가자 ${REQUIRED_PARTICIPANTS}인)\n`);

  const sessionStartMs = Date.now();
  // 서버 시각을 앞으로 밀어 과거 시각(t0)을 보낼 여지를 만든다. 실제 사용자는 그냥
  // 시간이 흐르기를 기다리지만, 확인 스크립트가 90초를 자고 있을 이유는 없다.
  await api("POST", "/api/dev/clock", null, { offsetMinutes: 5 });

  const url = `${BASE}/api/sessions/${sessionId}/absence-events`;
  const path = "/api/sessions/" + sessionId + "/absence-events";

  console.log("본문 형식과 과거 시각");
  {
    // §7-1 — 보내는 시각은 판단이 끝난 시각이 아니라 실제로 얼굴이 사라진 프레임의 시각이다.
    const t0 = sessionStartMs + 10_000;
    const res = await api("POST", path, me.token, {
      type: "START",
      clientSeq: 1,
      occurredAt: toIsoWithOffset(t0),
    });
    check("START 200", res.status, 200);
    checkTrue(
      "응답이 SS-4 계약 필드를 전부 가진다",
      res.json !== null &&
        "accepted" in res.json &&
        "warningCount" in res.json &&
        "evicted" in res.json &&
        "evictionId" in res.json &&
        "pointDelta" in res.json &&
        "closedAbsenceSeconds" in res.json,
      JSON.stringify(res.json),
    );
    check("아직 경고 없음", res.json?.warningCount, 0);

    // 70초 뒤 END → 서버가 60초 초과로 판정해 경고를 준다
    const endAt = t0 + 70_000;
    await sleep(DEFAULT_CONFIG.transport.minIntervalMs + 500);
    const end = await api("POST", path, me.token, {
      type: "END",
      clientSeq: 2,
      occurredAt: toIsoWithOffset(endAt),
    });
    check("END 200", end.status, 200);
    check("서버가 닫힌 구간을 70초로 계산", end.json?.closedAbsenceSeconds, 70);
    check("60초 초과라 경고 1회", end.json?.warningCount, 1);
  }

  console.log("\n시각 검증(서버가 거절하는 자리)");
  {
    await sleep(DEFAULT_CONFIG.transport.minIntervalMs + 500);
    // 세션 시작 이전 시각은 없던 자리비움을 만들 수 있어 400 이다
    const past = await api("POST", path, me.token, {
      type: "START",
      clientSeq: 90,
      occurredAt: toIsoWithOffset(sessionStartMs - 60_000),
    });
    check("세션 시작 이전 시각은 400", past.status, 400);
    check("코드는 VALIDATION_FAILED", past.json?.error?.code, "VALIDATION_FAILED");
  }

  console.log("\n429 재전송 — 같은 clientSeq 로 다시 보내는가");
  {
    // 페이싱을 0 으로 두어 일부러 429 를 만든다. 실제 클라이언트는 5초를 지켜 이 상황을
    // 잘 만들지 않지만, 만들어졌을 때 같은 번호로 재전송하는 것이 §7-2 의 계약이다.
    const store = new AbsenceSeqStore(`contract-${sessionId}`);
    const recording = new RecordingTransport(
      new FetchAbsenceTransport(url, () => ({
        Authorization: `Bearer ${rateLimitProbe.token}`,
      })),
    );
    const sender = new AbsenceEventSender(store, recording, {
      ...DEFAULT_CONFIG.transport,
      minIntervalMs: 0,
    });

    const base = sessionStartMs + 120_000;
    sender.enqueue("START", base);
    sender.enqueue("END", base + 3000);
    // 429 → 5초 대기 → 재전송까지 기다린다
    await sleep(DEFAULT_CONFIG.transport.retryAfterRateLimitMs + 3000);
    sender.stop();

    const rateLimited = recording.attempts.filter((a) => a.kind === "RATE_LIMITED");
    checkTrue(
      "페이싱을 지키지 않으면 서버가 429 로 답한다",
      rateLimited.length >= 1,
      JSON.stringify(recording.attempts),
    );
    const endAttempts = recording.attempts.filter((a) => a.type === "END");
    const seqs = new Set(endAttempts.map((a) => a.clientSeq));
    checkTrue(
      "429 뒤 재전송의 clientSeq 가 같다",
      endAttempts.length >= 2 && seqs.size === 1,
      `END 시도: ${JSON.stringify(endAttempts)}`,
    );
    checkTrue(
      "재전송이 결국 받아들여진다",
      endAttempts.some((a) => a.kind === "OK" || a.kind === "DUPLICATE"),
      `END 시도: ${JSON.stringify(endAttempts)}`,
    );
    check("재전송 뒤 큐가 비었다", sender.pendingCount, 0);
    check("열린 START 가 닫혔다", sender.hasOpenStart, false);
  }

  console.log("\n중복 재전송(409)은 성공으로 취급한다");
  {
    await sleep(DEFAULT_CONFIG.transport.minIntervalMs + 500);
    const dup = await api("POST", path, me.token, {
      type: "START",
      clientSeq: 1,
      occurredAt: toIsoWithOffset(sessionStartMs + 10_000),
    });
    check("같은 clientSeq 재전송은 409", dup.status, 409);
    check("코드는 DUPLICATE_ABSENCE_EVENT", dup.json?.error?.code, "DUPLICATE_ABSENCE_EVENT");
  }

  await api("POST", "/api/dev/clock", null, { reset: true });
  console.log(failures === 0 ? "\n전부 통과" : `\n실패 ${failures}건`);
  if (failures > 0) process.exit(1);
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
