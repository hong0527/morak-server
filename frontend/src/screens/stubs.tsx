/**
 * 아직 만들지 않은 화면들. **뼈대와 할 일만 있고 동작하지 않는다.**
 *
 * 세션 화면(SessionScreen.tsx)이 가장 복잡하고 실시간 축이 걸린 화면이라 그것만
 * 끝까지 만들어 두었다. 나머지는 그 화면과 api/client.ts 를 보고 같은 방식으로 채우면 된다.
 *
 * 각 화면에 그 화면에서만 걸리는 함정을 적어 두었다. 근거는 morak-server/docs/frontend-guide.md 다.
 */
import { Link } from "react-router-dom";

function Stub({ title, api: apiCalls, notes }: { title: string; api: string[]; notes: string[] }) {
  return (
    <div className="screen">
      <div className="row" style={{ justifyContent: "space-between" }}>
        <h1>{title}</h1>
        <Link to="/">
          <button>홈으로</button>
        </Link>
      </div>
      <div className="todo">
        <p>
          <b>아직 만들지 않았다.</b>
        </p>
        <p>부를 것</p>
        <ul>
          {apiCalls.map((a) => (
            <li key={a}>
              <code>{a}</code>
            </li>
          ))}
        </ul>
        <p>이 화면에서 걸리는 것</p>
        <ul>
          {notes.map((n) => (
            <li key={n}>{n}</li>
          ))}
        </ul>
      </div>
    </div>
  );
}

/** S5. 목표 설정 */
export function GoalScreen() {
  return (
    <Stub
      title="목표 설정"
      api={["api.member.setGoal(periodDays)"]}
      notes={[
        "허용값은 7 / 14 / 30 뿐이다. 그 외는 400 이고 details.periodDays 에 허용값이 실려 온다.",
        "이미 진행 중인 목표가 있으면 409 GOAL_ALREADY_ACTIVE 다.",
        "503 LOCK_ACQUISITION_FAILED 가 날 수 있는데 http.ts 가 알아서 재시도한다.",
        "선택지 7/14/30 은 팀이 확정하지 않은 잠정값이다(frontend-guide §8 7번).",
      ]}
    />
  );
}

/** S10. 내 기록 */
export function RecordsScreen() {
  return (
    <Stub
      title="내 기록"
      api={["api.member.mySessions({ status, page, size })", "api.member.myAppeals({ page, size })"]}
      notes={[
        "status 는 세션 상태(LIVE·ENDED) 필터이고 내 참가 상태 필터가 아니다.",
        "목록 size 는 최대 50 이고 넘기면 400 이다.",
        "이의 status 는 PENDING / ACCEPTED / REJECTED / CLOSED 다. ACCEPTED 면 pointRefunded 와 sessionCompletedRestored 가 채워진다.",
      ]}
    />
  );
}

/** S11. 포인트 */
export function PointsScreen() {
  return (
    <Stub
      title="포인트"
      api={[
        "api.member.points({ page, size })",
        "api.payment.createCharge(...)",
        "api.payment.confirmCharge(chargeId, ...)",
      ]}
      notes={[
        "PT-1 만 목록 모양이 다르다. 잔액과 내역을 함께 주느라 목록이 ledger 키 안에 들어간다.",
        "결제가 진짜 결제가 아니다. PG 테스트 키가 없어 PY-1 → PY-2 를 바로 이어 부르면 포인트가 들어온다.",
        "실패 화면은 pgTid 접두사로 만든다. `fail-` 은 PG 거절, `mismatch-` 는 금액 불일치다.",
        "충전 금액 하한·상한(1,000원 ~ 1,000,000원)은 잠정값이다(frontend-guide §8 8번).",
      ]}
    />
  );
}

/** S12. 스토어 */
export function StoreScreen() {
  return (
    <Stub
      title="스토어"
      api={[
        "api.store.products({ type, page, size })",
        "api.store.product(productId)",
        "api.order.create({ ... })",
        "api.order.mine() / api.order.get(orderId)",
      ]}
      notes={[
        "idempotencyKey 를 재시도마다 새로 만들지 않는다. 새로 만들면 중복 주문이 된다.",
        "409 DUPLICATE_ORDER 는 실패가 아니다. details.orderId 로 기존 주문 상세에 보낸다.",
        "장바구니(FR-504)와 환불(FR-506)은 v1 보류라 서버 API 가 없다.",
      ]}
    />
  );
}

/** S13. 신고 */
export function ReportScreen() {
  return (
    <Stub
      title="신고"
      api={["api.report.create({ ... })"]}
      notes={[
        "세션 화면에서 참가자를 눌러 들어오는 흐름이 자연스럽다. 지금은 라우트만 있다.",
        "관리자 쪽 처리 화면(AD-1~AD-3)을 이 앱에 둘지 별도 웹으로 뺄지는 팀이 정하지 않았다.",
      ]}
    />
  );
}
