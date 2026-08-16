/**
 * W1 재현 — 탭을 닫았다 다시 들어오는 시나리오를 **실제 서버에 대고** 돌린다.
 *
 * 실제 `AbsenceSeqStore`·`AbsenceEventSender`·`FetchAbsenceTransport` 를 그대로 쓰고,
 * 브라우저 저장소만 대역으로 갈아끼워 "탭이 닫혀 저장소가 비었다"를 만든다.
 * 409 는 서버의 진짜 `uk_ae(session_id, member_id, client_seq)` 가 낸다.
 *
 *   ./gradlew bootRun --args='--spring.profiles.active=dev --server.port=8175'   (morak-server)
 *   npm run build && node harness/w1-check.mjs                                    (여기)
 *
 * 세 경로를 나란히 돌린다.
 *   A 고치기 전 — 저장소가 비고 바닥도 없고, 409 를 성공으로 취급한다
 *   B 고친 뒤(바닥 없음) — 409 재발급 안전망만으로 되살아나는가
 *   C 고친 뒤(바닥 있음) — 애초에 겹치지 않는가
 *
 * 판정 기준은 하나다: **그 자리비움이 서버에 기록됐는가**(warningCount 가 움직였는가).
 */
import { AbsenceSeqStore } from "../dist/src/client/seq-store.js";
import { AbsenceEventSender, FetchAbsenceTransport } from "../dist/src/client/sender.js";
import { DEFAULT_CONFIG } from "../dist/src/config.js";

const BASE = process.env.MORAK_BASE_URL ?? "http://localhost:8175";
const REQUIRED = 6;

/** 브라우저 저장소 대역. wipe() 가 "탭을 닫았다"이다 */
class FakeStorage {
  map = new Map();
  getItem(k) { return this.map.get(k) ?? null; }
  setItem(k, v) { this.map.set(k, v); }
  removeItem(k) { this.map.delete(k); }
  wipe() { this.map.clear(); }
}

async function api(method, path, token, body) {
  const res = await fetch(`${BASE}${path}`, {
    method,
    headers: { "Content-Type": "application/json", ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  });
  const text = await res.text();
  let json = null;
  try { json = text ? JSON.parse(text) : null; } catch { json = { raw: text }; }
  return { status: res.status, json };
}

async function onboard(code) {
  const r = await api("POST", "/api/auth/login", null, {
    provider: "KAKAO",
    authorizationCode: code,
    agreements: [{ type: "TOS", agreed: true }, { type: "PRIVACY", agreed: true }],
  });
  if (r.status !== 200) throw new Error(`로그인 실패 ${r.status}: ${JSON.stringify(r.json)}`);
  await api("POST", "/api/members/me/birthdate", r.json.accessToken, { birthDate: "1998-04-11" });
  return { memberId: r.json.memberId, token: r.json.accessToken };
}

async function openSession() {
  const stamp = Date.now();
  const me = await onboard(`w1-${stamp}-0`);
  await api("POST", "/api/match-requests", me.token, { targetMinutes: 60 });
  for (let i = 1; i < REQUIRED; i += 1) {
    const other = await onboard(`w1-${stamp}-${i}`);
    await api("POST", "/api/match-requests", other.token, { targetMinutes: 60 });
  }
  const poll = await api("GET", "/api/match-requests/me", me.token);
  const sessionId = poll.json?.sessionId;
  if (!sessionId) throw new Error(`세션이 열리지 않았다: ${JSON.stringify(poll.json)}`);
  return { sessionId, ...me };
}

async function warningCount(sessionId, token) {
  const r = await api("GET", `/api/sessions/${sessionId}`, token);
  const mine = (r.json?.participants ?? []).find((p) => p.isMe);
  return mine?.warningCount ?? null;
}

/** 자리비움 한 건(START + 71초 뒤 END)을 보내고 무슨 일이 났는지 돌려준다 */
async function sendAbsence(store, url, token, label) {
  const attempted = [];
  const duplicates = [];
  let accepted = 0;
  const transport = new FetchAbsenceTransport(url, () => ({ Authorization: `Bearer ${token}` }));
  const wrapped = {
    post: async (body) => {
      const out = await transport.post(body);
      attempted.push(`${body.type}#${body.clientSeq}:${out.kind}`);
      return out;
    },
  };
  const sender = new AbsenceEventSender(
    store,
    wrapped,
    // 서버의 5초 간격 제한을 미리 지킨다. 429 재전송은 이 검증의 대상이 아니다.
    { ...DEFAULT_CONFIG.transport, minIntervalMs: 5200, retryAfterRateLimitMs: 5200 },
    {
      onAccepted: () => (accepted += 1),
      onUnexpectedDuplicate: (e, reissuedAs) => duplicates.push(`${e.clientSeq}→${reissuedAs}`),
      onTerminal: (c) => console.log(`      onTerminal ${c}`),
    },
  );

  const now = Date.now();
  sender.enqueue("START", now - 71_000);
  sender.enqueue("END", now);
  // 재발급이 걸리면 5초 페이싱으로 몇 번 왕복한다. 넉넉히 기다린다.
  for (let i = 0; i < 240 && sender.pendingCount > 0; i += 1) {
    await new Promise((r) => setTimeout(r, 500));
  }
  sender.stop();
  return { label, attempted, duplicates, accepted, leftover: sender.pendingCount };
}

async function main() {
  console.log(`서버 ${BASE}\n`);
  const openedAtMs = Date.now();
  const { sessionId, token } = await openSession();
  const url = `${BASE}/api/sessions/${sessionId}/absence-events`;
  console.log(`세션 ${sessionId} 준비 완료 (${REQUIRED}인)`);

  // 서버는 세션 시작보다 이른 occurredAt 을 400 으로 거절한다. 71초짜리 자리비움을 보고하려면
  // 세션이 그만큼 묵어야 한다. 임계(60초)를 넘겨야 경고가 붙고, 경고가 이 검증의 판정 기준이다.
  const waitMs = 78_000 - (Date.now() - openedAtMs);
  if (waitMs > 0) {
    console.log(`세션이 묵기를 ${Math.round(waitMs / 1000)}초 기다린다 (과거 시각 허용 범위)\n`);
    await new Promise((r) => setTimeout(r, waitMs));
  }

  const storage = new FakeStorage();
  const sessionStartedAtMs = openedAtMs;

  // ── 첫 탭. 번호 1,2 를 쓴다 ──────────────────────────────────────────
  const first = new AbsenceSeqStore({ sessionId, memberId: 1, storage });
  const r0 = await sendAbsence(first, url, token, "첫 탭");
  const w0 = await warningCount(sessionId, token);
  console.log(`[0] 첫 탭          보낸 것 ${r0.attempted.join(" ")}  수락 ${r0.accepted}  경고 ${w0}`);

  // ── A0. 고치기 전 동작. 되돌아간 번호를 그대로 보내고 409 를 성공으로 취급한다 ──
  // sender 를 거치지 않고 원시 HTTP 로 보낸다. 옛 클라이언트는 이 409 둘을 성공으로 읽고
  // 큐에서 빼 버렸다 — 그래서 자리비움이 아무 소리 없이 사라졌다.
  {
    const now = Date.now();
    const raw = [];
    for (const [type, at, seq] of [["START", now - 71_000, 1], ["END", now, 2]]) {
      const r = await api("POST", `/api/sessions/${sessionId}/absence-events`, token, {
        type,
        clientSeq: seq,
        occurredAt: new Date(at).toISOString(),
      });
      raw.push(`${type}#${seq}:${r.status}${r.json?.error?.code ? `/${r.json.error.code}` : ""}`);
      await new Promise((r2) => setTimeout(r2, 5200));
    }
    const wRaw = await warningCount(sessionId, token);
    console.log(`\n[A0] 고치기 전 — 번호가 1 로 되돌아간 채 그대로 전송`);
    console.log(`    서버 응답 ${raw.join(" ")}`);
    console.log(`    경고 ${w0} → ${wRaw}  ${wRaw > w0 ? "기록됨" : "사라짐 ← 옛 클라이언트는 이것을 성공으로 읽었다"}`);
  }

  // ── A. 탭을 닫았다 다시 들어온다. 바닥 없음 = 고치기 전 번호 발급 ────
  storage.wipe();
  const reopened = new AbsenceSeqStore({ sessionId, memberId: 1, storage });
  console.log(`\n[A] 탭 닫고 재입장 — 바닥 없음. 첫 발급 번호 ${reopened.nextSeq}`);
  const rA = await sendAbsence(reopened, url, token, "재입장(바닥 없음)");
  const wA = await warningCount(sessionId, token);
  console.log(`    보낸 것 ${rA.attempted.join(" ")}`);
  console.log(`    예상 못 한 409 ${rA.duplicates.length}건 ${rA.duplicates.join(" ")}`);
  console.log(`    수락 ${rA.accepted}  큐 잔량 ${rA.leftover}  경고 ${w0} → ${wA}  ${wA > w0 ? "기록됨" : "사라짐"}`);

  // ── C. 같은 상황 + 세션 시작 시각을 준다 ────────────────────────────
  storage.wipe();
  const withFloor = new AbsenceSeqStore({ sessionId, memberId: 1, storage, sessionStartedAtMs });
  console.log(`\n[C] 탭 닫고 재입장 — 바닥 있음. 첫 발급 번호 ${withFloor.nextSeq}`);
  const rC = await sendAbsence(withFloor, url, token, "재입장(바닥 있음)");
  const wC = await warningCount(sessionId, token);
  console.log(`    보낸 것 ${rC.attempted.join(" ")}`);
  console.log(`    예상 못 한 409 ${rC.duplicates.length}건`);
  console.log(`    수락 ${rC.accepted}  큐 잔량 ${rC.leftover}  경고 ${wA} → ${wC}  ${wC > wA ? "기록됨" : "사라짐"}`);

  console.log("\n────────────────────────────────────────────────────────");
  const ok =
    wA > w0 && wC > wA && rC.duplicates.length === 0 && rA.duplicates.length > 0;
  console.log(ok ? "통과 — 두 경로 모두 자리비움이 서버에 기록됐다" : "실패 — 위 수치를 확인하라");
  console.log(`  A(바닥 없음): 409 를 ${rA.duplicates.length}번 맞고 번호를 다시 매겨 살아났다`);
  console.log(`  C(바닥 있음): 409 없이 한 번에 들어갔다`);
  process.exit(ok ? 0 : 1);
}

main().catch((e) => { console.error(e); process.exit(1); });
