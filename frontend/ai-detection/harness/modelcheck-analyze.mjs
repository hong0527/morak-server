/**
 * modelcheck.mjs 가 남긴 로그를 읽는다. **서비스 코드가 아니다.**
 *
 *   node harness/modelcheck-analyze.mjs /tmp/molock-modelcheck/run-2hz.json
 */
import { readFileSync } from "node:fs";

const files = process.argv.slice(2);
if (!files.length) { console.error("사용: node harness/modelcheck-analyze.mjs <로그.json>..."); process.exit(1); }

const DEG = 180 / Math.PI;

/** detector.ts 의 eulerFromColumnMajor 를 그대로 옮긴 것. 비교 기준이다. */
function eulerColumnMajor(data) {
  const at = (r, c) => data[c * 4 + r] ?? 0;
  return decompose(at);
}
/** 같은 식을 row-major 로 읽었을 때. 전치 행렬을 분해하는 것과 같다. */
function eulerRowMajor(data) {
  const at = (r, c) => data[r * 4 + c] ?? 0;
  return decompose(at);
}
function decompose(at) {
  const r00 = at(0, 0), r10 = at(1, 0), r20 = at(2, 0);
  const r21 = at(2, 1), r22 = at(2, 2), r11 = at(1, 1), r12 = at(1, 2);
  const sy = Math.sqrt(r00 * r00 + r10 * r10);
  const singular = sy < 1e-6;
  const x = singular ? Math.atan2(-r12, r11) : Math.atan2(r21, r22);
  const y = Math.atan2(-r20, sy);
  const z = singular ? 0 : Math.atan2(r10, r00);
  return { pitchDeg: x * DEG, yawDeg: y * DEG, rollDeg: z * DEG };
}

/** 각 열의 노름. 1 에서 벗어나면 회전행렬에 스케일이 섞여 있다는 뜻이다. */
function colNorms(d) {
  const n = (c) => Math.hypot(d[c * 4], d[c * 4 + 1], d[c * 4 + 2]);
  return [n(0), n(1), n(2)];
}
/** 회전행렬로서 얼마나 정직한가. R^T R = I 에서 벗어난 정도 */
function orthoError(d) {
  const col = (c) => [d[c * 4], d[c * 4 + 1], d[c * 4 + 2]];
  const [a, b, c] = [col(0), col(1), col(2)];
  const dot = (u, v) => u[0] * v[0] + u[1] * v[1] + u[2] * v[2];
  return Math.max(Math.abs(dot(a, b)), Math.abs(dot(a, c)), Math.abs(dot(b, c)));
}
/** det < 0 이면 반사가 섞였다는 뜻 — 회전이 아니다 */
function det3(d) {
  const m = (r, c) => d[c * 4 + r];
  return m(0,0)*(m(1,1)*m(2,2)-m(1,2)*m(2,1))
       - m(0,1)*(m(1,0)*m(2,2)-m(1,2)*m(2,0))
       + m(0,2)*(m(1,0)*m(2,1)-m(1,1)*m(2,0));
}

const med = (a) => { if (!a.length) return null; const s = [...a].sort((x, y) => x - y); return s[s.length >> 1]; };
const pct = (a, p) => { if (!a.length) return null; const s = [...a].sort((x, y) => x - y); return s[Math.min(s.length - 1, Math.floor(s.length * p))]; };
const f = (v, d = 1) => (typeof v === "number" && Number.isFinite(v) ? v.toFixed(d) : "—");

for (const file of files) {
  const { hz, log } = JSON.parse(readFileSync(file, "utf8"));
  const N = log.length;
  console.log(`\n${"=".repeat(78)}\n${file}   hz=${hz}  프레임 ${N}\n${"=".repeat(78)}`);

  // ── 1. 세 모델 검출률 ────────────────────────────────────────────────
  const flOn = log.filter((r) => r.fl).length;
  const fdOn = log.filter((r) => r.fd).length;
  const plOn = log.filter((r) => r.pl).length;
  console.log(`\n[1] 같은 프레임에서의 검출률`);
  console.log(`  FaceLandmarker  ${flOn}/${N}  ${f((flOn / N) * 100)}%   추론 중앙 ${f(med(log.map((r) => r.flMs)))}ms  p90 ${f(pct(log.map((r) => r.flMs), 0.9))}ms`);
  console.log(`  FaceDetector    ${fdOn}/${N}  ${f((fdOn / N) * 100)}%   추론 중앙 ${f(med(log.map((r) => r.fdMs)))}ms  p90 ${f(pct(log.map((r) => r.fdMs), 0.9))}ms`);
  console.log(`  PoseLandmarker  ${plOn}/${N}  ${f((plOn / N) * 100)}%   추론 중앙 ${f(med(log.map((r) => r.plMs)))}ms  p90 ${f(pct(log.map((r) => r.plMs), 0.9))}ms`);

  // 엇갈리는 프레임 — 한쪽만 놓치는 곳이 어디인가
  const flMissFdHit = log.filter((r) => !r.fl && r.fd).length;
  const fdMissFlHit = log.filter((r) => r.fl && !r.fd).length;
  const flMissPlHit = log.filter((r) => !r.fl && r.pl).length;
  console.log(`  FL 놓치고 FD 잡음 ${flMissFdHit}   FD 놓치고 FL 잡음 ${fdMissFlHit}   FL 놓치고 PL 잡음 ${flMissPlHit}`);

  // ── 2. 10초 구간별 ──────────────────────────────────────────────────
  console.log(`\n[2] 10초 구간별 검출률 (%)`);
  console.log(`  ${"구간".padEnd(10)}${"n".padStart(4)}${"FL".padStart(7)}${"FD".padStart(7)}${"PL".padStart(7)}   ${"FD score중앙".padStart(12)}`);
  const last = Math.ceil(log.at(-1).tMs / 1000);
  for (let t = 0; t < last; t += 10) {
    const b = log.filter((r) => r.tMs / 1000 >= t && r.tMs / 1000 < t + 10);
    if (!b.length) continue;
    const s = b.filter((r) => r.fd).map((r) => r.fd.score);
    console.log(`  ${`${t}-${t + 10}s`.padEnd(10)}${String(b.length).padStart(4)}${f((b.filter((r) => r.fl).length / b.length) * 100, 0).padStart(7)}${f((b.filter((r) => r.fd).length / b.length) * 100, 0).padStart(7)}${f((b.filter((r) => r.pl).length / b.length) * 100, 0).padStart(7)}   ${f(med(s), 2).padStart(12)}`);
  }

  // ── 3. 변환행렬의 성질 ──────────────────────────────────────────────
  const withMat = log.filter((r) => r.fl?.mat);
  const norms = withMat.flatMap((r) => colNorms(r.fl.mat));
  const orth = withMat.map((r) => orthoError(r.fl.mat));
  const dets = withMat.map((r) => det3(r.fl.mat));
  console.log(`\n[3] facialTransformationMatrixes 의 성질  (n=${withMat.length})`);
  console.log(`  열 노름(=스케일)  최소 ${f(Math.min(...norms), 4)}  중앙 ${f(med(norms), 4)}  최대 ${f(Math.max(...norms), 4)}`);
  console.log(`  직교 오차          최대 ${f(Math.max(...orth), 5)}`);
  console.log(`  행렬식             최소 ${f(Math.min(...dets), 4)}  최대 ${f(Math.max(...dets), 4)}`);
  const t0 = withMat[0]?.fl.mat;
  if (t0) console.log(`  마지막 열(=평행이동) 예시  [${t0.slice(12).map((v) => f(v, 2)).join(", ")}]  ← (0,0,0,1)이 아니면 열우선이 맞다`);

  // ── 4. 각도 분포와 이상치 ───────────────────────────────────────────
  const eul = withMat.map((r) => ({ r, e: eulerColumnMajor(r.fl.mat), er: eulerRowMajor(r.fl.mat) }));
  const P = eul.map((x) => x.e.pitchDeg), Y = eul.map((x) => x.e.yawDeg), R = eul.map((x) => x.e.rollDeg);
  console.log(`\n[4] 각도 (열우선 = 지금 코드)`);
  const line = (name, a) => console.log(`  ${name.padEnd(6)} 최소 ${f(Math.min(...a)).padStart(8)}  중앙 ${f(med(a)).padStart(8)}  최대 ${f(Math.max(...a)).padStart(8)}   |v|>30 인 프레임 ${a.filter((v) => Math.abs(v) > 30).length}`);
  line("pitch", P); line("yaw", Y); line("roll", R);

  const OUT = 30;
  const outliers = eul.filter((x) => Math.abs(x.e.pitchDeg) > OUT);
  console.log(`\n[5] pitch 이상치 (|pitch| > ${OUT}°) — ${outliers.length}건`);
  for (const x of outliers.slice(0, 12)) {
    const m = x.r.fl.mat;
    console.log(`  t=${f(x.r.tMs / 1000, 1)}s  pitch ${f(x.e.pitchDeg)}  yaw ${f(x.e.yawDeg)}  roll ${f(x.e.rollDeg)}`);
    console.log(`      행우선으로 읽었다면  pitch ${f(x.er.pitchDeg)}  yaw ${f(x.er.yawDeg)}  roll ${f(x.er.rollDeg)}`);
    console.log(`      얼굴 diag ${f(x.r.fl.diag, 3)}  중심 (${f(x.r.fl.cx, 2)}, ${f(x.r.fl.cy, 2)})  열노름 [${colNorms(m).map((v) => f(v, 3)).join(", ")}]  det ${f(det3(m), 3)}`);
    console.log(`      R = [${[0, 1, 2].map((r) => [0, 1, 2].map((c) => f(m[c * 4 + r], 3)).join(" ")).join(" | ")}]`);
    console.log(`      평행이동 [${m.slice(12, 15).map((v) => f(v, 1)).join(", ")}]   FD ${x.r.fd ? `score ${f(x.r.fd.score, 2)}` : "미검출"}  PL ${x.r.pl ? "검출" : "미검출"}`);
  }

  // 이상치 앞뒤 흐름
  if (outliers.length) {
    const idx = eul.indexOf(outliers[0]);
    console.log(`\n  이상치 전후 흐름 (첫 건 기준)`);
    for (let i = Math.max(0, idx - 3); i <= Math.min(eul.length - 1, idx + 3); i += 1) {
      const x = eul[i];
      console.log(`    ${i === idx ? "→" : " "} t=${f(x.r.tMs / 1000, 1)}s  pitch ${f(x.e.pitchDeg).padStart(8)}  yaw ${f(x.e.yawDeg).padStart(7)}  roll ${f(x.e.rollDeg).padStart(7)}  diag ${f(x.r.fl.diag, 3)}  cy ${f(x.r.fl.cy, 2)}`);
    }
  }

  // ── 6. 우리가 안 쓰는 신호 ──────────────────────────────────────────
  const vis = withMat.map((r) => r.fl.p0?.vis).filter((v) => v !== null && v !== undefined);
  console.log(`\n[6] 지금 안 쓰는 신호`);
  console.log(`  FaceLandmarker 랜드마크 visibility  최소 ${f(Math.min(...vis), 3)} 최대 ${f(Math.max(...vis), 3)}  ← 0 이면 값이 안 실린다`);
  const bsDown = withMat.map((r) => r.fl.bs?.eyeLookDownLeft).filter(Number.isFinite);
  const bsBlink = withMat.map((r) => r.fl.bs?.eyeBlinkLeft).filter(Number.isFinite);
  console.log(`  blendshape eyeLookDownLeft  중앙 ${f(med(bsDown), 2)}  최대 ${f(Math.max(...bsDown), 2)}`);
  console.log(`  blendshape eyeBlinkLeft     중앙 ${f(med(bsBlink), 2)}  최대 ${f(Math.max(...bsBlink), 2)}`);

  const pl = log.filter((r) => r.pl);
  if (pl.length) {
    const g = (k) => pl.map((r) => r.pl[k]?.vis).filter(Number.isFinite);
    console.log(`  PoseLandmarker visibility 중앙 — 코 ${f(med(g("nose")), 3)}  어깨L ${f(med(g("shL")), 3)}  어깨R ${f(med(g("shR")), 3)}  엉덩이L ${f(med(g("hipL")), 3)}  엉덩이R ${f(med(g("hipR")), 3)}`);
    const noseLo = g("nose").filter((v) => v < 0.5).length;
    console.log(`  코 visibility < 0.5 인 프레임 ${noseLo}/${pl.length}  ← 자리비움 신호로 쓸 수 있는지의 척도`);
  }
}
