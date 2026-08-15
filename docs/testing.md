# 테스트 가이드 (2026-08-15)

이 문서는 세 가지를 다룬다 — 무엇을 어떤 방식으로 검증하고 있는가(§1~§3),
어디가 덮여 있고 어디가 비어 있는가(§4), 새 기능에 어떤 테스트를 붙이는가(§5).

## §0. 실행

```bash
./gradlew test                        # 전건
./gradlew test --tests 'com.morak.store.*'              # 패키지
./gradlew test --tests 'com.morak.common.GateMatrixTest' # 한 클래스
./gradlew test --rerun-tasks          # 캐시 무시하고 다시
```

실패 상세는 `build/reports/tests/test/index.html`, 기계가 읽을 형식은
`build/test-results/test/*.xml`에 남는다.

현재 **41개 파일 163건, 전건 통과, 전체 9.2초**(캐시 무시 기준, 컴파일 포함).
클래스 실행 시간 합은 6.3초이고 그중 2.7초가 `MorakServerApplicationTests` 하나다(§3-4 참조).
나머지 40개 파일이 3.6초를 나눠 쓰므로 **테스트를 두세 배로 늘려도 실행 시간은 문제가 되지 않는다.**

## §1. 무엇을 검증하는가

이 프로젝트의 테스트는 전부 **통합 테스트**다. 단위 테스트가 없는 것은 누락이 아니라 판단이다 —
여기서 깨지는 것들이 클래스 하나 안에서 나는 논리 오류가 아니라 **경계에서 나는 오류**이기
때문이다. 동시 요청, 트랜잭션 경계, DB 제약, 배치와 API의 경합, 인터셉터와 컨트롤러 사이.
모킹으로 잘라 내면 그 자리들이 통째로 사라진다.

검증 대상을 성격으로 나누면 다섯이다.

| 성격 | 무엇을 확인하나 | 대표 파일 |
|---|---|---|
| 게이트 | 누가 어떤 API를 쓸 수 있는가 (§0-3 매트릭스) | `common/GateMatrixTest` |
| 흐름 | 기능이 처음부터 끝까지 도는가 | `auth/LoginFlowTest`, `store/StoreCatalogAndOrderTest`, `point/ChargeRoundTripTest`, `session/SessionEntryTest` |
| 경계 | 임계값의 이쪽과 저쪽 | `session/AppealDeadlineBoundaryTest`, `session/PauseAbsenceBoundaryTest`, `member/WithdrawalPurgeBoundaryTest` |
| 동시성 | 둘이 같은 자리를 동시에 두드릴 때 | `match/MatchConcurrencyTest`, `point/PointLedgerInvariantTest`, `report/ReportConcurrencyTest`, `session/MassEvictionClosingTest` |
| 멱등 | 같은 일이 두 번 일어나도 결과가 하나인가 | `session/SessionCloseIdempotencyTest`, `point/ChargeSettlementIdempotencyTest` |

## §2. 어떻게 검증하는가

### 2-1. 공통 바탕 — `support/IntegrationTest`

dev 프로필로 애플리케이션 전체를 띄운다(H2, 조작 가능한 시계, 개발용 소셜·PG 클라이언트).
모든 테스트 클래스가 이것을 상속하므로 **Spring 컨텍스트는 하나를 공유한다.** 새 테스트에
`@SpringBootTest`나 `@TestPropertySource`를 직접 붙이면 컨텍스트가 하나 더 생겨 실행 시간이
통째로 늘어난다 — 붙이지 않는다.

두 가지 규칙이 여기 걸려 있다.

- **`@Transactional`을 걸지 않는다.** 확인하려는 것의 상당수가 커밋된 뒤에야 성립한다.
  동시 요청은 남의 트랜잭션이 커밋한 결과를 봐야 하고, "완주 표시가 정말 DB에 닿았는가"는
  영속성 컨텍스트를 비운 뒤 다시 읽어야 안다. 정리는 `DatabaseCleaner`가 매 테스트 전에
  TRUNCATE로 한다.
- **시각은 `AdjustableClock`으로만 움직인다.** `clock.fixAt(...)`로 고정하고 `BASE_TIME`
  (2026-03-10 09:00)을 기준으로 삼는다. 자정·월말에 걸리지 않는 값이라 실행일에 따라 결과가
  달라지지 않는다. `Thread.sleep`이나 리플렉션으로 필드를 고치는 방식은 쓰지 않는다 —
  판정 기준이 코드가 아니라 테스트에 생긴다.

**배치는 스케줄러가 아니라 빈의 `run()`을 직접 불러 확인한다.** `morak.scheduling.enabled=false`가
`SchedulingConfig`를 통째로 빼서 테스트 도중 배치가 저절로 돌지 않게 한다(그 전제 자체를
`SchedulingDisabledTest`가 못 박는다). `run()`은 DEV-4 수동 트리거가 부르는 것과 같은
메서드라, 거기서 확인한 동작이 곧 스케줄 실행의 동작이다.

### 2-2. 서비스로 부를 것인가, HTTP로 부를 것인가

**기본은 서비스 직접 호출이다.** 확인하려는 것이 대개 비즈니스 규칙이고, MockMvc를 거치면
JSON 직렬화·역직렬화가 실패 원인에 한 겹 더 끼어든다.

**HTTP로 불러야 하는 경우는 셋뿐이다.**

1. **인터셉터 게이트** — 권한 판정이 컨트롤러 앞에 있어서 서비스를 직접 부르면 지나가지
   않는다. `GateMatrixTest`가 전부 MockMvc인 이유다.
2. **웹훅** — 서명 검증이 컨트롤러의 첫 줄이고, 그 사실 자체가 확인 대상이다.
   `support/LiveKitWebhookSigner`·`support/PaymentWebhookSigner`가 실제 서명을 만든다.
3. **직렬화 계약** — "이 필드가 응답 JSON에 없어야 한다" 같은 판정은 객체가 아니라 JSON이
   답해야 한다(`WarningTraceVisibilityTest`의 남의 행 구간 노출 검사).

### 2-3. 공용 픽스처 — `support/TestFixtures`

준비와 확인을 한곳에 모은다. **회원은 정식 가입 경로(AU-1)로 만든다** — 회원 행만 넣으면
매칭 잠금 행도 웰컴 원장도 없는 계정이 되어, 그 상태에서만 통과하는 게이트가 생긴다.

| 준비 | 쓰임 |
|---|---|
| `joinMember()` / `joinMember(code)` | 회원 가입. 같은 코드로 다시 부르면 재로그인이다 |
| `joinMembers(n)` | n명 |
| `joinVerifiedMember(code)` | 연령까지 확정된 회원. ⑤ 게이트 뒤의 API를 볼 때 |
| `joinAdmin(code)` | 관리자. `/api/admin/**`에 HTTP로 닿을 때만 필요하다 |
| `tokenOf(memberId)` | 인터셉터가 받는 JWT |
| `openSession(분, 시작시각, 회원들)` | 매칭이 만드는 것과 같은 모양의 진행 중 세션 |
| `createProduct` / `createHiddenProduct` / `createSoldOutProduct` | 상품 |

| 확인 | 쓰임 |
|---|---|
| `member` / `participant` / `session` / `evictionId` | 엔티티 |
| `ledgerSum(memberId)` | 원장 합계. 잔액 캐시와 비교하는 쪽은 언제나 이 값이다 |
| `count(table, where, args)` / `countAll(table)` | 행 수 |
| `queryString` / `queryLongs` | 컬럼 직접 읽기 |
| `execute(sql, args)` | 정상 경로로 만들 수 없는 중간 상태를 직접 만든다 |

**`execute`는 최후의 수단이다.** 트랜잭션이 끊겨 남은 미결처럼 서비스 호출로 재현할 수 없는
상태에만 쓴다. 편하다는 이유로 준비를 SQL로 하면 실제 경로가 만들지 않는 상태를 시험하게 된다.

## §3. 구조 판단

### 3-1. 도메인별 폴더를 유지한다

테스트는 `src/test/java/com/morak/{도메인}/`에 두고, 성격별(unit/integration/e2e)로 나누지
않는다. 다음 이유로 그렇게 판단했다.

- **나눌 축이 실제로 없다.** 41개 파일이 전부 같은 방식의 통합 테스트다. 성격 폴더를 만들면
  `integration/` 하나에 40개가 들어가고 나머지는 빈다. 폴더가 아무것도 가르지 못한다.
- **분리의 효용은 실행 시간을 가를 때 생기는데** 전체가 9초다. "빠른 것만 먼저 돌린다"는
  운영이 성립할 만한 비용 차이가 없다.
- **도메인 대응은 지금 잘 맞는다.** `src/main`이 도메인별 패키지 구조(CLAUDE.md §4)라
  테스트가 같은 축을 쓰면 짝을 찾기 쉽다.

바꾸는 비용(41개 파일 이동, import 정리, 아직 진행 중인 다른 작업과의 충돌)이 얻는 것보다
크다. **테스트가 수백 건이 되거나 느린 테스트가 생겨 실행을 가를 필요가 실제로 생기면 그때
다시 본다.**

예외는 `common/GateMatrixTest`다. 게이트는 어느 한 도메인의 것이 아니라 전 도메인에 걸리는
규칙이라 `common`에 둔다.

### 3-2. 이름 규칙

- **클래스**: `{무엇을}{어떤_성격}Test`. 성격 접미사를 그대로 쓴다 —
  `...ConcurrencyTest`(동시성), `...BoundaryTest`(경계), `...IdempotencyTest`(멱등),
  `...FlowTest`·`...RoundTripTest`(흐름). 파일 이름만 보고 무엇을 지키는지 알게 하는 것이 목적이다.
- **클래스 `@DisplayName`**: 명사구. "인터셉터 게이트 매트릭스", "포인트 충전 왕복".
- **메서드 `@DisplayName`**: **평서문 한 문장으로 지켜야 할 사실을 쓴다.**
  "제재 중에는 참여도 조회도 막히지만 구제 경로 넷은 열려 있다"처럼, 테스트 목록만 읽어도
  그것이 명세서가 되게 한다. "~을 테스트한다", "~이 정상 동작한다"는 쓰지 않는다.
- **메서드 이름**: 한글 스네이크. `제재_게이트의_예외_넷이_열려_있다`.

### 3-3. 주석 규칙 — "이 테스트가 죽으면"

모든 테스트 메서드는 첫 줄에 **이 테스트가 죽었을 때 무엇이 깨진 것인지**를 적는다.

```java
// 이 테스트가 죽으면: 제재당한 회원이 갇힌 것이다. AP-1·AP-2가 닫히면 잘못된 퇴출을
// 되돌릴 유일한 수단이 사라지고(NFR-402), AU-5가 닫히면 탈퇴 철회가 영구 불가해진다.
```

실패를 마주한 사람이 가장 먼저 알아야 하는 것은 "무엇이 틀렸나"가 아니라 **"이걸 그냥 고쳐도
되나, 아니면 심각한 일인가"**다. 어서션만 보면 그 판단에 코드를 거슬러 올라가야 한다.

### 3-4. 남은 정리거리

- **`MorakServerApplicationTests`** — Spring Initializr가 만든 `contextLoads()`가 그대로
  남아 있다. `@SpringBootTest`를 맨몸으로 붙여 **컨텍스트를 하나 더 만들고, 그 한 건이 전체
  실행 시간의 30%(2.7초)를 쓴다.** 컨텍스트가 뜨는지는 나머지 40개 파일이 매번 증명하므로
  얻는 것이 없다. 지우는 것을 권한다(이 문서를 쓴 시점에는 지우지 않았다).
- **`loginToken(...)` 중복** — `AppealDetailQueryTest`와 `MyAppealListTest`가 같은 헬퍼를
  각자 들고 있다. `fixtures.tokenOf(memberId)`로 대체 가능하다.

## §4. 커버리지 지도

`docs/api-spec.md` §3 총람의 46개 오퍼레이션과 배치 4종·웹훅 2종 기준이다.

기호: **O** 정상·실패 경로가 함께 있음 / **△** 일부만(비고에 무엇이 빠졌는지) / **X** 없음

### 4-1. 인증·회원 (AU)

| ID | 오퍼레이션 | | 어디서 | 비고 |
|---|---|---|---|---|
| AU-1 | 소셜 로그인·가입 | O | `LoginFlowTest`, `SocialBirthDateJoinTest`, `SocialValueOverflowJoinTest`, `UnderAgePurgeTest` | 신규·재로그인·복구·재가입 차단·약관 |
| AU-2 | 내 정보 | O | `ActiveSessionInMeTest`, `GoalProgressTest`, `GateMatrixTest` | 진행 중 세션·목표·뱃지·제재 |
| AU-3 | 생년월일·연령 검증 | △ | `GateMatrixTest`, `UnderAgePurgeTest` | 통과와 파기는 있으나 **만 14세 정확 경계(생일 당일 대 하루 전)가 없다** |
| AU-4 | 탈퇴 신청 | O | `GateMatrixTest`, `WithdrawalPurgeBoundaryTest`, `WithdrawalMatchRaceTest` | 세션 퇴장·대기 해제 동반 |
| AU-5 | 탈퇴 철회 | O | `GateMatrixTest`, `MatchConcurrencyTest`, `LoginFlowTest` | 기한 초과 거절 포함 |
| AU-6 | 캠 분석 동의 | O | `SessionEntryTest` | 재동의 멱등·미동의 거절 |
| AU-7 | 목표 기간 설정 | △ | `GoalProgressTest`, `GoalAchievementTest` | 정상 경로만. **활성 목표 중복(409)·허용값 밖(400)이 없다** |

### 4-2. 매칭 (MT)

| ID | 오퍼레이션 | | 어디서 | 비고 |
|---|---|---|---|---|
| MT-1 | 매칭 요청 | O | `MatchConcurrencyTest`, `RejoinHintTest`, `WithdrawalMatchRaceTest`, `MatchExpiryWindowTest` | 6인 확정 동시성까지 |
| MT-2 | 매칭 상태 폴링 | O | `MatchPollContractTest` | 종결 4상태 + 404 |
| MT-3 | 매칭 취소 | O | `MatchConcurrencyTest`, `MatchPollContractTest` | |

### 4-3. 세션 (SS)

| ID | 오퍼레이션 | | 어디서 | 비고 |
|---|---|---|---|---|
| SS-1 | 세션 상세 | △ | `WarningTraceVisibilityTest`, `AdminOperationsTest` | **참가자 아닌 사람의 404/403 검사 순서가 직접 확인되지 않는다**(SS-2에서는 확인함) |
| SS-2 | LiveKit 접속 토큰 | O | `SessionEntryTest` | 동의 게이트·검사 순서·재발급 |
| SS-3 | 오늘 할 일 등록 | O | `SessionEntryTest` | |
| SS-4 | 자리비움 이벤트 | O | `PauseAbsenceBoundaryTest`, `WarningAxisSeparationTest`, `MassEvictionClosingTest`, `WarningTraceVisibilityTest`, `AbsenceAfterScheduledEndTest` | 가장 촘촘한 자리 |
| SS-5 | 화장실 모드 시작 | O | `PauseAbsenceBoundaryTest` | |
| SS-6 | 화장실 모드 복귀 | O | `PauseAbsenceBoundaryTest`, `ActiveSessionInMeTest` | |
| SS-7 | 자율 퇴장 | △ | `ActiveSessionInMeTest`, `SessionEntryTest`, `SessionCloseIdempotencyTest` | 다른 테스트의 준비 단계로만 쓰인다. **사유별 검증·중복 퇴장(409)이 없다** |
| SS-8 | 세션 결과 | O | `WarningTraceVisibilityTest`, `SessionCompletionPersistenceTest`, `RetroactiveAcceptanceTest` | |
| SS-9 | 내 세션 이력 | △ | `GateMatrixTest` | **접근 가능 여부만 본다. 목록 내용·상태 필터·페이지가 비어 있다** |
| SS-10 | LiveKit 웹훅 | O | `SessionCloseIdempotencyTest`, `ReconnectGraceOrderingTest`, `GateMatrixTest` | 서명·순서 역전·재수신 |
| SS-11 | 스티커 목록 | O | `SessionEntryTest` | |

### 4-4. 이의·포인트 (AP·PT)

| ID | 오퍼레이션 | | 어디서 | 비고 |
|---|---|---|---|---|
| AP-1 | 퇴출 이의 신청 | O | `AppealDeadlineBoundaryTest`, `GateMatrixTest` | 3일 경계 |
| AP-2 | 내 이의 목록 | O | `MyAppealListTest` | 상태별 필드·본인 스코프·제재 예외 |
| PT-1 | 포인트 잔액·원장 | △ | `PointLedgerInvariantTest`, `RetroactiveAcceptanceTest`, `GateMatrixTest` | 원장 불변식은 촘촘하다. **조회 응답의 페이지·정렬은 확인되지 않는다** |

### 4-5. 스토어·결제 (SR·PY)

| ID | 오퍼레이션 | | 어디서 | 비고 |
|---|---|---|---|---|
| SR-1 | 상품 목록 | O | `StoreCatalogAndOrderTest` | HIDDEN 제외·종류 필터 |
| SR-2 | 상품 상세 | O | `StoreCatalogAndOrderTest` | 감춘 상품 = 없는 상품 |
| SR-3 | 주문 생성 | O | `StoreCatalogAndOrderTest` | 정상·잔액 부족·품절·멱등키 |
| SR-4 | 내 주문 목록 | O | `StoreCatalogAndOrderTest` | 최신순·본인 스코프 |
| SR-5 | 주문 상세 | O | `StoreCatalogAndOrderTest` | 타인 403 |
| PY-1 | 충전 생성 | O | `ChargeRoundTripTest` | 한도 경계 |
| PY-2 | 충전 승인 확인 | O | `ChargeRoundTripTest`, `ChargeSettlementIdempotencyTest` | 정상·거절·금액 불일치·기한 초과·타인 |
| PY-3 | PG 결제 웹훅 | O | `ChargeRoundTripTest`, `ChargeSettlementIdempotencyTest`, `GateMatrixTest` | 서명·승인·취소·모르는 주문번호 |

### 4-6. 신고·관리자 (RP·AD)

| ID | 오퍼레이션 | | 어디서 | 비고 |
|---|---|---|---|---|
| RP-1 | 신고 | O | `ReportConcurrencyTest`, `AdminOperationsTest`, `GateMatrixTest` | 병합·중복·연령 예외 |
| AD-1 | 신고 케이스 목록 | O | `AdminOperationsTest` | 상태·심각도·SLA 필터 |
| AD-2 | 신고 케이스 상세 | O | `AdminOperationsTest` | 신고자·이력 |
| AD-3 | 신고 처리 | O | `AdminOperationsTest`, `ReportConcurrencyTest` | 제재 유무 두 경로·중복 처리 |
| AD-4 | 제재 단독 적용 | O | `AdminOperationsTest` | TEMP·PERMANENT·없는 회원 |
| AD-5 | 이의 큐 | △ | `PurgedMemberAppealTest` | 다른 테스트의 조회 수단으로 쓰인다. **필터 조합 자체는 확인되지 않는다** |
| AD-6 | 이의 처리 | O | `AppealDuringLiveSessionTest`, `MyAppealListTest`, `RetroactiveAcceptanceTest` | 인용·기각·진행 중 거절 |
| AD-7 | 진행 중 세션 모니터 | O | `AdminOperationsTest` | 상태별 인원 집계 |
| AD-8 | 탈퇴 처리 결과 | O | `AdminOperationsTest` | 유예·파기 구분 |
| AD-9 | 이의 심사 상세 | O | `AppealDetailQueryTest`, `WarningTraceVisibilityTest` | 경고 근거 구간 |

### 4-7. 개발 전용 (DEV)

| ID | 오퍼레이션 | | 어디서 | 비고 |
|---|---|---|---|---|
| DEV-2 | 시각 조작·조회 | △ | 모든 테스트 | `AdjustableClock` 빈은 매 테스트가 쓰지만 **HTTP 엔드포인트 자체는 호출되지 않는다** |
| DEV-3 | 완주 이력 시드 | O | `DevSessionSeedServiceTest` | |
| DEV-4 | 배치 수동 트리거 | △ | 배치 테스트 전부 | 배치 빈의 `run()`은 부르지만 **HTTP 경로·이중 스위치는 확인되지 않는다** |

### 4-8. 배치와 전역 규약

| 대상 | | 어디서 |
|---|---|---|
| B1 세션 종료 | O | `SessionCloseIdempotencyTest`, `ClosingBatchIsolationTest`, `SessionCompletionPersistenceTest`, `MassEvictionClosingTest` |
| B2 매칭 만료 | O | `MatchPollContractTest`, `MatchConcurrencyTest`, `MatchExpiryWindowTest` |
| B4 회원 파기 | O | `WithdrawalPurgeBoundaryTest`, `LoginFlowTest`, `AdminOperationsTest` |
| B5 충전 만료 | O | `ChargeRoundTripTest` |
| 배치 공통(대상 격리) | O | `BatchGuardTest`, `ClosingBatchIsolationTest` |
| 게이트 매트릭스 §0-3 | O | `GateMatrixTest` |
| 시각 표기 §0-1 | O | `TimestampFormatTest` |

### 4-9. 남은 빈자리 정리

우선순위 순이다.

1. **AU-3 만 14세 정확 경계** — 생일 당일은 가입, 하루 전은 차단. 판정이 하루 어긋나면
   되돌릴 수 없는 계정 파기가 잘못 일어난다.
2. **SS-9 내 세션 이력의 내용** — 지금은 "열린다"만 안다. 남의 세션이 섞이지 않는지,
   상태 필터가 도는지 확인되지 않았다.
3. **AU-7 목표 설정의 실패 경로** — 활성 목표 중복(409), 허용값 밖(400).
4. **SS-7 자율 퇴장의 자체 계약** — 중복 퇴장(409), 사유 검증.
5. **PT-1 조회 응답의 페이지·정렬**, **AD-5 이의 큐 필터 조합**.
6. **DEV-2·DEV-4의 HTTP 경로와 이중 스위치** — 운영 프로필에서 정말 404인지.

## §5. 새 기능에 테스트를 붙이는 법

### 5-1. 최소 한 벌

새 오퍼레이션에는 **정상 경로 하나와 실패 경로 하나**를 반드시 붙인다. 정상만 있는 테스트는
"이 API가 존재한다"까지만 증명한다. 그 위에 해당하는 것을 더한다.

| 그 기능이 이렇다면 | 이것도 붙인다 |
|---|---|
| 게이트 매트릭스(§0-3)에 행이 늘었다 | `GateMatrixTest`에 그 행. **예외 열이 있으면 반드시** — 잘못 닫힌 예외는 사고 전까지 아무도 모른다 |
| 포인트가 오간다 | 원장 합과 잔액 캐시가 같은지. `fixtures.ledgerSum`이 진실이다 |
| 소유권이 있다(내 것/남의 것) | 남의 자원에 접근했을 때의 응답. 404인지 403인지는 그 자원의 기본 응답을 따른다 |
| 임계값·기한이 있다 | 경계의 양쪽. "정확히 3일"과 "3일 1초" |
| 두 요청이 같은 자리를 두드릴 수 있다 | `support/Concurrently.run(n, ...)`. 순차 재호출로는 사전 조회를 지나는 경합이 재현되지 않는다 |
| 재시도·재수신될 수 있다 | 두 번 불러도 결과가 하나인지 |
| 배치가 관여한다 | 재실행 안전성. 배치 빈의 `run()`을 두 번 부른다 |
| 상태를 바꾼다 | 서비스 응답이 아니라 **DB를 다시 읽어** 확인 |

### 5-2. 지켜야 할 것

- **통과하지만 아무것도 검사하지 않는 테스트를 만들지 않는다.** 새 테스트를 쓴 뒤
  **관련 코드를 잠깐 되돌려 실제로 실패하는지 확인하고 복원한다.** 이 문서를 쓰면서 추가한
  6개 파일은 전부 이 확인을 거쳤다(감춘 상품 차단, SS-2 동의 게이트, AD-8 상태 검증,
  PY-2 기한 초과, AU-1 재가입 차단, SR-1 목록 제외 — 6건 모두 되돌리자 실패했다).
- **어서션에 같은 값을 양쪽에 두지 않는다.** `assertThat(f(x)).isEqualTo(f(x))`는 언제나
  통과한다. 기대값은 상수나 계산식으로 쓴다.
- **준비는 `fixtures`로, 확인은 DB로.** 같은 준비 코드를 두 파일이 각자 들고 있으면
  `TestFixtures`로 올린다.
- **실패 메시지를 읽고 무엇이 깨졌는지 알 수 있게** `@DisplayName`과 "이 테스트가 죽으면"
  주석을 쓴다(§3-2, §3-3).

### 5-3. 새 파일을 만들 때

```java
@DisplayName("명사구")
class 무엇을어떤성격Test extends IntegrationTest {

    @Autowired
    private 필요한Service service;

    @Test
    @DisplayName("지켜야 할 사실을 평서문으로")
    void 한글_스네이크_메서드명() {
        // 이 테스트가 죽으면: 무엇이 깨진 것인지, 그래서 사용자에게 무슨 일이 일어나는지.
        Long memberId = fixtures.joinVerifiedMember("접두사-용도");

        ...

        assertThat(...).isEqualTo(...);
    }
}
```

`fixtures.joinMember(code)`의 코드는 그 파일 안에서 겹치지 않게 짓는다(`ap2-me`, `sr3-buyer`
처럼 오퍼레이션 접두사를 쓴다). 같은 코드는 같은 소셜 계정의 재로그인이라, 겹치면 의도치 않게
같은 회원을 공유한다.
