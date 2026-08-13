# MoLock 구현 파이프라인 v4 (2026-08-12)

기획이 사진 인증 챌린지에서 실시간 캠 스터디(MoLock)로 바뀌었다. 하루 목표 시간 하나로 6인을 자동 매칭해 LiveKit 세션에 넣고, 온디바이스 AI가 자리비움을 감지해 3회 경고 시 퇴출하며, 완주하면 Streak와 포인트가 쌓이는 구조다.
0·1단계에서 만든 코드(member·auth·common)는 그대로 유효하다. proof·ai·post 계열은 엔티티와 enum만 있고 서비스 로직이 0줄이라 폐기 비용이 없다 — 지금이 갈아엎기에 가장 싼 시점이다.

> 자기완결 문서. 계약 정본은 `docs/openapi.yaml`(엔드포인트)·`docs/api-spec.md`(처리 절차)·`docs/db-schema.md`(컬럼·제약).
> 전제: 화수 혼자 백엔드 전체 구현. Spring Boot 4.1 · Java 21 · Gradle · JPA · H2(개발) / MySQL(운영).
> 진행 원칙: **게이트(curl 실측) 통과 없이 다음 단계 금지.** 게이트는 그 단계 산출물만으로 실행 가능해야 한다.
> 강의 진행: ①왜 필요한가 → ②새 개념 → ③전체 코드 → ④줄별 설명 → ⑤화수 타이핑 후 리뷰 → ⑥curl 게이트.

## 진행 현황판

전 단계 구현이 끝났다. 남은 것은 실서비스 연동(카카오·토스페이먼츠)과 강의 자료 정리다.

| 단계 | 이름 | 상태 |
|---|---|---|
| 0 | 프로젝트 골격·전역 예외 | 완료 |
| 0.5 | enum·ErrorCode·정책값 교체 | 완료 |
| E | 엔티티 재편 | 완료 |
| O | OpenAPI 재생성 | 완료 |
| 1 | 회원·인증·JWT | 완료 |
| 1.5 | 약관·목표·Streak 골격 | 완료 |
| 2 | 매칭 엔진 | 완료 |
| 3 | 라이브 세션 골격 | 완료 |
| 4 | 경고·퇴출·Pause | 완료 |
| 5 | 세션 종료·완주·포인트 지급 | 완료 |
| 6 | 포인트 원장·조회 | 완료 |
| 7 | 스토어·주문 | 완료 |
| 8 | PG 테스트 결제 | 완료 (개발용 스텁 기준) |
| 9 | 신고·차단·제재 | 완료 |
| 10 | 관리자 콘솔·이의 | 완료 |
| 11 | 탈퇴 완결 | 완료 |
| 12 | 운영 준비 | 진행 중 — 실 연동 2건이 남았다 |

12단계에 남은 것: 카카오 `SocialClient`·토스페이먼츠 `PgClient` 실구현. 그 자리는 지금 dev가 아닌 프로필에서
전부 거절하는 기본 구현(`RejectingSocialClient`·`RejectingPgClient`)이 채우고 있어, 운영 프로필로도 기동은 된다 —
AU-1은 401 `INVALID_SOCIAL_TOKEN`, PY-2는 409 `PAYMENT_NOT_APPROVED`가 정상 동작이다.
그 밖에 LiveKit `RemoveParticipant` 호출(`EvictionService`)과 신고 서술 텍스트 검열(`Report`)이 TODO로 남아 있다.
여기에 3차 검증 B가 올린 **재기동 내성 이월 3건**(유예 창 DB 영속화 · 스케줄러 락 · `point_charge` 시각 정합)이 더해진다. 셋 다 12단계 절에 상세를 적었고, 1번이 끝나기 전까지 **v1 배포는 단일 인스턴스 전제**다.

**테스트 39건 / 16클래스**(`./gradlew test` 실측 — `build/test-results/test/*.xml`의 `tests` 합계, 실패·에러 0). 단위 테스트가 아니라 H2를 띄우는 통합 테스트이며, 단계별 게이트가 curl로 보는 것과 달리 동시성·멱등·경계값처럼 손으로 재현하기 어려운 불변식을 덮는다. 이 수치가 줄면 무엇을 지웠는지 먼저 확인한다.

수를 셀 때 `grep -c "@Test"`를 그대로 쓰면 `TestSupportConfig`의 `@TestConfiguration`이 함께 잡혀 40이 나온다. 실측 기준은 Gradle 리포트다.

---

## 완료 단계 요약

### 0단계 — 프로젝트 골격 (완료)
Spring Boot 4.1 골격, `GlobalExceptionHandler` + 공통 에러 포맷(`{"error":{code,message,details}}`), `ErrorCode` 49종.
게이트 실측 완료: 없는 URL → 404 `{"error":{"code":"ENDPOINT_NOT_FOUND",...}}`.
**피벗 영향 없음.** 예외 처리 골격은 도메인과 무관하다. ErrorCode의 내용만 0.5단계에서 갈아낀다.

### 1단계 — 회원·인증·JWT (완료, 수정 3건 대기)
구현 완료: `JwtProvider`(jjwt-gson, HS256) / `AuthInterceptor` 5게이트(JWT→회원상태→관리자→제재→연령, SKIP_RULES 표 방식) /
`@LoginMember` / `Clock` 빈 / AU-1 로그인 / AU-2 내 정보 / AU-3 연령 검증 / AU-4·AU-5 탈퇴 신청·철회 /
`NicknameGenerator` / `SocialHasher` + `blocked_social_hash` 조회(재가입 차단) / 가입 시 `match_lock('member:{id}')` 동반 INSERT.

계획 대비 이탈 2건 — 둘 다 채택 확정이고 피벗 후에도 유지된다:
- **DEV-1 별도 엔드포인트 폐기.** dev 로그인은 AU-1 `POST /api/auth/login` + `DevSocialClient`(`@Profile("dev")` AND `morak.dev.enabled` 이중 스위치)로 처리한다. authorizationCode가 곧 providerUserId라 같은 코드 재호출 = 같은 회원.
- **AU-4·AU-5를 앞당겨 구현.** 회원 상태 머신(ACTIVE↔WITHDRAW_PENDING)이 인터셉터 ②검사와 한 몸이라 분리 비용이 더 컸다. 11단계에는 배치(B4)와 AD-8만 남는다.

설정은 3파일로 분리돼 있다(`application.yml` 공통 / `application-dev.yml` H2·dev 시크릿·`morak.dev.enabled=true` / `application-prod.yml`).
base yml의 시크릿 폴백을 제거해 운영에서 환경변수가 없으면 기동 자체가 실패한다.

**피벗에 따른 수정 3건** — 1.5·6단계에서 각각 해소한다:

| # | 대상 | 수정 내용 | 해소 단계 |
|---|---|---|---|
| M-1 | `MemberService.verifyAge` | ★D7 반영. 만 14세 미만은 `AgeVerification.UNDER_AGE`로 두는 것이 아니라 **계정을 삭제하고 403 `UNDER_AGE_SIGNUP_BLOCKED`**. 미만 판정자는 계정 자체가 남지 않는다 | 1.5 |
| M-2 | `AuthInterceptor.SKIP_RULES` | `GET /api/proofs/*/media/raw` 행 삭제(폐기 API). `POST /api/webhooks/livekit`·`POST /api/webhooks/payment` 2행 추가 — 전 게이트 skip + 각 컨트롤러가 서명 검증. `GET /api/members/me/groups` → `GET /api/members/me/sessions`, `GET /api/posts` 계열 2행 삭제. `POST /api/members/me/media-consent`(⑤ skip)·`POST /api/evictions/*/appeals`(④ skip) 2행 추가 | 3(웹훅·동의), 1.5(정리), 10(AP-1) |
| M-3 | `AuthService.login` | 최초 가입 시 웰컴 +1,000p `point_ledger` INSERT. **6단계에서 추가한다** — point_ledger 테이블과 지급 서비스가 그때 생긴다. 지금 넣으면 의존이 거꾸로 선다 | 6 |

**`AgeVerification` enum은 `REQUIRED`/`VERIFIED` 2값으로 줄인다.** D7이 가입 자체를 차단하므로 `UNDER_AGE` 상태인 계정은 존재할 수 없다 — 값을 남겨 두면 인터셉터 ⑤가 영원히 도달하지 않는 분기를 검사하게 되고, 읽는 사람은 그 상태가 실재한다고 오해한다. 0.5단계에서 값을 지우고 1.5단계에서 M-1을 반영한다.

### 미커밋 작업물 처리 방침
현재 워킹트리에 `src/main/java/com/morak/dev/` 4파일(`AdjustableClock`·`DevClockController`·request/response record)과 `AppConfig`·`Sanction` 수정이 커밋되지 않은 상태다.
**DEV-2(시각 조작)는 피벗 후에도 그대로 유효하다** — Streak 일자 경계·매칭 만료·Pause 10분·세션 종료 예정 시각이 전부 시각 판정이고, 이것 없이는 2·4·5·11단계 게이트를 재현할 수 없다. 폐기하지 않고 2단계에서 DEV-4와 함께 커밋한다.
패키지 위치는 현행 `com.morak.dev`를 유지한다(E단계 '패키지 배치'·CLAUDE.md §4의 확정 도메인 목록에 포함돼 있다).

---

## 단계 순서 근거

- **0.5 → E → O가 선행**: 계약(enum·ErrorCode·엔티티·OpenAPI)이 먼저 서야 이후 단계가 컴파일된다. 이 셋은 기능이 아니라 정지 작업이라 게이트도 "기동 성공 + diff 0"이다.
- **1.5 → 2**: MT-1의 게이트에 연령 검증이 들어가고, 세션 완주가 Streak를 갱신한다. 약관·목표 테이블이 먼저 있어야 매칭 이후를 붙일 수 있다.
- **2가 데이터 원천**: 3단계 이후 전부가 `live_session`의 존재를 전제한다. 세션을 만드는 경로는 매칭뿐이다.
- **3 → 4**: SS-4(자리비움)의 첫 검증이 "세션 참가자인가"다. 참가자 조회·입퇴장 웹훅이 먼저 있어야 경고 게이트를 실측한다.
- **4 → 5**: B1의 완주 판정(D1)이 `session_participant.status`를 본다. EVICTED·LEFT를 만드는 경로가 4단계다.
- **5 → 6**: point_ledger의 첫 쓰기가 5단계(완주 지급)다. 6단계는 조회(PT-1)와 웰컴 지급(M-3)을 얹는다. 원장 UNIQUE 제약이 5단계에서 실측된 뒤 다른 지급 사유가 붙는 순서다.
- **6 → 7 → 8**: 스토어 주문은 포인트 차감이고, 충전은 그 반대 방향이다. 원장이 서 있어야 둘 다 붙는다.
- **9 → 10**: AD-5·AD-6(이의 큐)이 신고 인프라의 케이스·이력·제재 위에 얹힌다. AD-6 인용 시 포인트 원복이 6단계 원장을 쓴다.
- **10 → 11**: B4의 파기 예외 대상에 커머스 기록(7·8단계)과 제재 이력(9단계)이 들어간다.
- **11 → 12**: 운영 전환은 기능 완결 후. LiveKit 실연동·MySQL 동시성 재실측은 전 기능이 있어야 의미가 있다.

## 팀 미확정 항목 (T1) — 단계별 대기표

**구 T1 4건(T1-3 자격증 시험날짜 / T1-5 매칭 완화 / T1-4 완주 이중 기준 / T1-6 인원 미달 별도 기준)은 전부 소멸했다.**
매칭 키가 시간 단일로 바뀌었고(T1-3·T1-5 소멸), 완주 판정이 인증률 계산에서 "세션 종료까지 잔류"로 바뀌었다(T1-4·T1-6 소멸).

새 대기 항목은 `docs/open-decisions.md`의 Q1~Q8이다. **착수를 차단하는 항목은 없다** — 전부 잠정값이 적용돼 있고, 확정이 오면 코드가 아니라 값이나 판정 메서드 1곳을 고친다.

| 항목 | 영향 단계 | 잠정값과 변경 반경 |
|---|---|---|
| Q1 세션 완주 정의(D1) | 5 | 종료 시각까지 LEFT·EVICTED 아님. 재실 비율 기준이 들어오면 `SessionCompletionJudge` 한 메서드 |
| Q2 Streak 단위(D2) | 1.5·5 | 일 단위 + `UNIQUE(member, completed_on)`. 주 단위로 바뀌면 UNIQUE 컬럼과 갱신 쿼리 |
| Q3 AI 신뢰 모델(D4) | 4 | 클라이언트는 자기 이벤트만 보고, 판정은 서버. 상호 보고 방식이 되면 SS-4 요청 스키마와 멱등키 |
| Q4 스파크 포인트(D5) | 6 | 일반 포인트와 동일 통화, `PointReason.GOAL_ACHIEVED` 라벨로만 구분. 별도 통화면 원장 분리 |
| Q5 신고 시 세션 처리(D6) | 9 | 아무도 나가지 않음 + match_block 양방향. 퇴장 정책이 되살아나면 RP-1에 전이 1스텝 |
| Q6 만14세 가입 차단(D7) | 1.5 | 계정 미생성. 유지+차단으로 돌아가면 M-1 되돌리기 |
| Q7 커머스 범위 승인 | 7·8 | 절단선대로 진행(장바구니·환불·배송지·IAP 보류) |
| Q8 우선순위 축(D19) | 전체 | 분장표 MoSCoW 기준 |
| 값 확정 대기 | 4·5·6 | 포인트 4종(D15)·매칭 시간 옵션(D8)·경고 임계 60초 — 전부 `application.yml` 값 교체로 흡수 |

가정으로 진행한 단계는 게이트 통과와 별개로 **해당 항목 확정 시 재실측 항목**을 남긴다(각 단계 게이트에 표기).

---

## 공통 규약

### Spring Boot 4.1 실측 주의 (인터넷의 3.x 자료와 다름)
1. **Jackson 3가 기본** — `com.fasterxml.jackson.*` 없음(`tools.jackson.*`). 3.x 강의의 import는 컴파일 불가
2. **JWT는 `jjwt-gson`** — `jjwt-jackson`은 Jackson 2를 끌고 와 충돌 (1단계에서 실측 완료)
3. `@MockBean` 삭제 → `@MockitoBean`
4. 스타터 명칭 `web`→`webmvc`, H2 콘솔 별도 모듈. 인터셉터·`@Scheduled`·multipart·JPA는 3.x와 동일(`@EnableScheduling` 필요)
5. LiveKit server SDK는 Jackson 2를 전이 의존으로 끌고 온다(`livekit-server → java-jwt → jackson-databind 2.x`) — **exclude를 걸면 안 된다**(0.5단계 실측: exclude 시 `AccessToken.toJwt()`가 `NoClassDefFoundError`로 죽는다. java-jwt가 토큰 서명에 Jackson 2를 실제로 쓴다). Jackson 3는 `tools.jackson`, 2는 `com.fasterxml.jackson`으로 패키지가 갈리고 Spring 메시지 컨버터는 3만 쓰므로 공존해도 섞이지 않는다. 근거는 build.gradle 주석 참조

### 개발 전용 엔드포인트
활성 조건은 **`@Profile("dev")` AND `morak.dev.enabled=true` 이중 스위치**(1단계 `DevSocialClient`와 동일 패턴).
하나만 믿지 않는다 — 프로필을 잘못 켠 배포와 스위치를 잘못 켠 배포를 서로가 막는다.
`Clock` 빈 자체는 dev 프로필이면 무조건 `AdjustableClock`으로 뜬다(스위치를 걸면 dev에서 스위치를 끌 때 빈이 사라져 기동이 깨진다). **조작 경로(`DevClockController`)만 이중 스위치로 잠근다.**
DEV-2(시각)·DEV-4(배치)는 2단계, DEV-3(세션 시드)는 5단계에서 만든다. 패키지는 `com.morak.dev`.
12단계 게이트에서 운영 프로필 기동 후 전 경로 404를 실측한다.

### 배치 실행 규약
배치는 **4종(B1·B2·B4·B5)**이다. 전부 `@Scheduled` + DEV-4 수동 트리거(`POST /api/dev/batches/{name}`) 쌍으로 구현한다.

| 배치 | 하는 일 | 단계 |
|---|---|---|
| B1 | 세션 종료 처리 + 미지급 완주 흡수 + 퇴출 패널티 소급 | 5 |
| B2 | 매칭 대기 만료(10분) | 2 |
| B4 | 탈퇴 계정 파기(30일) | 11 |
| B5 | 충전 READY 만료 스윕(`pg.ready-expire-minutes` 30분) | 8 |

게이트의 "배치 트리거"는 이 엔드포인트 호출을 뜻한다. **모든 배치는 멱등(재실행 안전)해야 하고, 멱등의 근거는 코드가 아니라 UNIQUE 제약이다.**
패키지는 도메인 안에 둔다(CLAUDE.md §4): B1→session, B2→match, B4→member, B5→point.
**주기가 같다고 배치를 합치지 않는다.** B2와 B5는 둘 다 매분 도는 만료 스윕이지만 대상도 도메인도 다르다 — 한 배치가 두 도메인을 훑기 시작하면 배치 표가 그 배치를 더 이상 설명하지 못하고, 한쪽 정책을 고칠 때 다른 쪽 대상까지 함께 읽어야 한다. `DevBatch` 인터페이스는 도메인마다 배치를 따로 두고 **트리거만 공유**하라고 만든 자리다.
구 B3(SLA overdue 마킹)는 폐지됐다 — 신고·이의 모두 `overdue`가 저장 컬럼이 아니라 조회 시점 파생이라 마킹할 대상이 없다.

### 게이트 실행 준비 (모든 단계 공통)
`jq` 필요. 아래 helper를 셸에 정의해 두고 게이트에서 재사용한다. 1단계에서 검증된 것 그대로다.

```bash
BASE=http://localhost:8080
login() {  # login user1 → 토큰 출력. 같은 코드 재호출 = 같은 회원
  curl -s -X POST $BASE/api/auth/login -H 'Content-Type: application/json' \
    -d "{\"provider\":\"KAKAO\",\"authorizationCode\":\"$1\"}" | jq -r .accessToken
}
adult() {  # 성인 검증까지 마친 토큰
  local T=$(login "$1")
  curl -s -o /dev/null -X POST $BASE/api/members/me/birthdate \
    -H "Authorization: Bearer $T" -H 'Content-Type: application/json' \
    -d '{"birthDate":"2000-01-01"}'
  echo "$T"
}
```

1.5단계부터 약관 동의가 AU-1 body에 들어가므로 `login()`의 요청 본문에 `agreements`가 추가된다 — 그 단계에서 helper를 갱신한다.

### 커밋 단위 규약
- **커밋은 사전 승인**(CLAUDE.md §1). 무엇을 왜 커밋하는지 먼저 말하고 허락을 받는다.
- 단계마다 "커밋 N개 제안"을 적어 둔다. 기준은 **되돌릴 수 있는 최소 단위** — 한 커밋을 되돌렸을 때 기동이 깨지지 않아야 한다.
- 인프라(시더·dev 도구·배치)와 기능(서비스·컨트롤러)은 커밋을 나눈다. 인프라만 먼저 머지해도 안전하다.
- AI 흔적을 남기지 않는다(CLAUDE.md §2). `Co-Authored-By`·"Generated with"·이모지 금지.
- 코드가 명세와 어긋나면 `docs/api-spec.md`·`docs/db-schema.md`를 함께 고친다(CLAUDE.md §5).

---

## 0.5단계 — enum·ErrorCode·정책값 교체

**목표**: 사진 인증 챌린지의 어휘를 코드에서 걷어내고 캠 세션의 어휘를 심는다. 이 단계는 기능을 만들지 않는다 — 이후 모든 단계가 참조할 타입과 값만 정지시킨다. 지금 이름을 잘못 잡으면 12단계까지 그 이름을 끌고 간다.

### 만들 것

**폐기 enum** (파일 삭제)

| 패키지 | 파일 |
|---|---|
| `common/type` | `GoalCategory` |
| `group/type` | `GroupStatus`, `GroupMemberStatus` |
| `proof/type` | `ProofMethod`, `ProofAiStatus` |
| `post/type` | `PostStatus` |
| `ai/type` | `AiJudgmentType`, `AiVerdict`, `AiReviewType`, `AiTargetType`, `AiReviewStatus` |
| `report/type` | `AccessReason` |

**수정 enum**

- `common/type/BadgeCode` → `GOAL_ACHIEVED` 단일 재정의(D3의 목표 달성 뱃지)
- `group/type/LeftReason` → `session/type/LeftReason`으로 이동. 값 `PERSONAL, DEVICE_ISSUE, UNPLEASANT, ETC, WITHDRAWAL, SANCTION`. **EVICTED는 여기 없다** — 퇴출은 `ParticipantStatus`로 표현한다
- `report/type/ReportTargetType` → `MEMBER, SESSION`
- `report/type/ReportReasonCode` → `SEXUAL_CONTENT, VIOLENT_THREAT, AD_SPAM, INAPPROPRIATE_SCREEN, ETC`
- `member/type/AgeVerification` → **`REQUIRED, VERIFIED` 2값**. `UNDER_AGE` 삭제(D7 가입 차단이라 그 상태의 계정이 존재할 수 없다). 인터셉터 ⑤의 검사는 `!= VERIFIED`로 단순해진다
- `reaction/type/StickerType` → `session/type/StickerType`으로 이동. 값 `CLAP, MUSCLE, FIRE`

**신설 enum 12종**

| enum | 패키지 | 값 |
|---|---|---|
| `SessionStatus` | session/type | LIVE, ENDED, CANCELLED |
| `SessionEndReason` | session/type | NORMAL, EARLY_UNDER_MIN |
| `ParticipantStatus` | session/type | ACTIVE, PAUSED, LEFT, EVICTED |
| `AbsenceEventType` | session/type | START, END |
| `AgreementType` | member/type | TOS, PRIVACY, MARKETING |
| `GoalStatus` | member/type | ACTIVE, ACHIEVED, CANCELLED |
| `AppealStatus` | session/type | PENDING, ACCEPTED, REJECTED |
| `PointReason` | point/type | WELCOME, SESSION_COMPLETE, GOAL_ACHIEVED, EVICTION_PENALTY, ORDER_SPEND, ORDER_CANCEL, CHARGE, APPEAL_REFUND |
| `ProductType` | store/type | GIFTICON, BOOK |
| `ProductStatus` | store/type | ON_SALE, SOLD_OUT, HIDDEN |
| `OrderStatus` | store/type | ORDERED, CANCELLED |
| `ChargeStatus` | point/type | READY, APPROVED, FAILED |

승계(수정 없음): `MemberRole`, `MemberStatus`, `SocialProvider`, `MatchRequestStatus`, `MatchEventType`, `ReportSeverity`, `ReportStatus`, `SanctionType`.
`DecidedBy`는 값은 승계하되 위치가 report/type → **session/type**으로 이동(유일 사용처가 `appeal_case.decided_by`라 패키지 규칙상 session 소속).

**폐기 ErrorCode** (인증·미디어·게시판 계열)

```
OUT_OF_CHALLENGE_PERIOD  PROOF_DEADLINE_PASSED  DUPLICATE_DAILY_PROOF  PROOF_HIDDEN_BY_ADMIN
FACE_DETECTED_RETRY      FACE_RETRY_EXCEEDED    CONTENT_BLOCKED        AI_SCREENING_UNAVAILABLE
MEDIA_UPLOAD_FAILED      INVALID_FILE           VIEW_BLOCKED_GROUP_ENDED  INVALID_MEDIA_TOKEN
MEDIA_DELETED            PROOF_NOT_FOUND        NOT_REPORTED_PROOF     REPORT_NOT_READY
POST_REPORT_REQUIRED     DUPLICATE_POST         POST_NOT_FOUND
```

**이름 교체 ErrorCode** (status·메시지 톤 유지)

| 구 | 신 |
|---|---|
| `GROUP_NOT_FOUND` | `SESSION_NOT_FOUND` |
| `NOT_GROUP_MEMBER` | `NOT_SESSION_PARTICIPANT` |
| `GROUP_ENDED` | `SESSION_ENDED` |
| `GROUP_NOT_ENDED` | `SESSION_NOT_ENDED` |
| `ALREADY_IN_ACTIVE_GROUP` | `ALREADY_IN_ACTIVE_SESSION` |

`CONSENT_REQUIRED`는 유지하되 메시지를 캠 영상 온디바이스 분석 동의 문구로 바꾼다. `ALREADY_LEFT`·`REASON_REQUIRED`는 자율 퇴장(SS-7)에서 그대로 쓴다.

**신설 ErrorCode**

```
METHOD_NOT_ALLOWED        UNSUPPORTED_MEDIA_TYPE   UNDER_AGE_SIGNUP_BLOCKED AGREEMENT_REQUIRED
GOAL_ALREADY_ACTIVE       REMATCH_COOLDOWN         DUPLICATE_ABSENCE_EVENT  ABSENCE_RATE_LIMITED
ALREADY_EVICTED           PAUSE_ALREADY_USED       PAUSE_NOT_ACTIVE         INSUFFICIENT_POINT
OUT_OF_STOCK              PRODUCT_NOT_FOUND        DUPLICATE_ORDER          ORDER_NOT_FOUND
CHARGE_NOT_FOUND          PAYMENT_AMOUNT_MISMATCH  PAYMENT_NOT_APPROVED     APPEAL_ALREADY_FILED
APPEAL_NOT_FOUND          INVALID_WEBHOOK_SIGNATURE
```

`METHOD_NOT_ALLOWED`·`UNSUPPORTED_MEDIA_TYPE`은 개별 엔드포인트가 아니라 전 경로 공통이다. 잡지 않으면 전역 `Exception` 핸들러가 500으로 덮어 프론트가 잘못 부른 요청을 서버 장애로 읽고 재시도한다. 목록 정본은 api-spec §6-2다.

코드당 status 1개 원칙을 유지한다. 같은 코드가 상황에 따라 400도 되고 409도 되면 프론트가 코드로 분기할 수 없다.

**application.yml 정책값 교체**

폐기: `morak.proof.*`, `morak.ai.*`, `morak.completion.*`, `morak.media.retention-days`, `morak.storage.local-path`, `spring.servlet.multipart.*`
유지: `timezone`, `jwt.*`, `security.social-hash-pepper`, `dev.enabled`, `report.sla-hours(24/72)`, `withdrawal.grace-days(30)`
`security.media-token-secret`도 폐기한다 — 미디어 열람 경로가 사라졌다. 다만 **필수 환경변수는 줄지 않고 늘어난다**: 폐기 1종(media-token) 대신 LiveKit 3종·PG 1종이 새로 필수가 되어 **운영 필수 환경변수는 6종**이다(`MORAK_JWT_SECRET`·`MORAK_SOCIAL_HASH_PEPPER`·`MORAK_LIVEKIT_HOST`·`MORAK_LIVEKIT_API_KEY`·`MORAK_LIVEKIT_API_SECRET`·`MORAK_PG_SECRET_KEY`). 12단계 기동 실패 게이트가 이 6종을 기준으로 한다.

신설:
```yaml
morak:
  match:
    wait-expire-minutes: 10        # 구 expire-hours 24 대체
    rematch-cooldown-minutes: 30   # D14 퇴출자 쿨다운
  session:
    absence-threshold-seconds: 60  # [팀 확정 대기] 경고 임계
    absence-min-interval-seconds: 5  # [팀 확정 대기] SS-4 레이트리밋 최소 간격
    evict-warning-count: 3
    reconnect-grace-seconds: 90    # D13
    pause-limit-minutes: 10        # D9
    min-participants: 2            # D12 조기 종료 임계
  point:                           # [팀 확정 대기] D15 잠정값
    welcome: 1000
    session-complete-per-hour: 100
    eviction-penalty: 300
    goal-achieved: 1000
  livekit:
    host: ${MORAK_LIVEKIT_HOST}
    api-key: ${MORAK_LIVEKIT_API_KEY}
    api-secret: ${MORAK_LIVEKIT_API_SECRET}
  pg:
    provider: toss-test
    secret-key: ${MORAK_PG_SECRET_KEY}
```

위 6종(JWT·pepper·LiveKit 3·PG)은 base yml에 폴백을 두지 않는다(1단계 결정 승계) — `${...}` 기본값 없는 참조만 쓴다. dev 값은 `application-dev.yml`에만 둔다. `morak.livekit.host`는 비밀값은 아니지만 환경별로 갈리므로 같은 방식으로 주입한다.

**코드 주석 잔존 2건** — 폐기·수정 목록 어디에도 안 걸리는데 파일은 살아남는 케이스라 따로 적는다.

| 파일 | 잔존 | 고칠 내용 |
|---|---|---|
| `common/config/AppConfig` | javadoc "챌린지 시작일·**인증 마감**·매칭 만료가 전부 시각 판정" | "Streak 일자 경계·세션 종료 예정·Pause 10분·매칭 만료"로 교체 |
| `common/config/WebConfig` | 인터셉터 제외 목록 주석의 `GET /api/proofs/*/media/raw` 언급 | M-2로 그 행이 사라지므로 주석도 함께 삭제(웹훅 2경로로 교체) |

**build.gradle**

- 삭제: `implementation 'com.google.genai:google-genai:1.65.0'` — AI 판정이 서버에서 사라졌다(온디바이스로 이동)
- 추가: LiveKit server SDK(토큰 서명·웹훅 검증) `io.livekit:livekit-server:0.15.0`. Jackson 2가 전이 의존으로 오지만 exclude 금지 — 공통 규약 5번(실측 근거) 참조
- 유지: jjwt 3종, lombok, h2, validation/webmvc/jpa 스타터

### 완료 게이트
- `./gradlew build` 성공. 폐기 enum·ErrorCode를 참조하는 컴파일 에러 0
- 애플리케이션 기동 성공 후 없는 URL → 404 `ENDPOINT_NOT_FOUND` (0단계 게이트 회귀)
- `ErrorCode` ↔ api-spec §6-1 대조 diff 0. `application.yml` ↔ api-spec §0-5 대조 diff 0
- **잔존 어휘 검사는 `src` 전체를 훑는다**: `grep -rniE "proof|challenge|genai|인증 마감|촬영물|사진" src/` → 0건. `resources`만 보면 `AppConfig`의 javadoc("인증 마감" 등) 같은 **코드 주석 잔존**을 놓친다 — 주석은 컴파일을 막지 않으므로 빌드 게이트로도 안 잡힌다

### 강의 포인트
1. **enum 하나가 곧 계약이다.** `LeftReason`에서 EVICTED를 빼고 `ParticipantStatus`로 옮긴 것이 왜 단순 이름 바꾸기가 아닌지 — "스스로 나갔다"와 "쫓겨났다"는 사유가 아니라 상태다. 요청 본문으로 받을 수 있는 값과 서버만 쓸 수 있는 값이 갈린다.
2. **코드당 status 1개 원칙.** 프론트가 HTTP status가 아니라 `error.code`로 분기하는 구조에서, 같은 코드가 두 status를 가지면 계약이 깨진다.
3. **정책값을 yml로 격리하는 이유.** 팀 확정이 늦은 값(경고 임계 60초, 포인트 4종)이 코드 리터럴이면 확정 때마다 재컴파일·재배포다.

---

## E단계 — 엔티티 재편

**목표**: 24테이블 전부를 JPA 엔티티로 선작성한다. 이후 단계는 엔티티를 새로 만들지 않고 Repository·Service·Controller만 얹는다. 제약(UNIQUE)을 지금 전부 선언해 두는 것이 핵심이다 — 멱등·중복 방어가 서비스 코드가 아니라 DB에 있어야 동시 요청에 뚫리지 않는다.

### 만들 것

**폐기 엔티티** (파일 삭제, 14종)
`ChallengeGroup`, `GroupMember`, `Proof`, `ProofMedia`, `AiJudgment`, `AiReviewQueue`, `StickerReaction`, `MediaAccessLog`, `FinalReport`, `CompletionStats`, `ChallengePost`, `PostLike` — 그리고 db-schema에만 있던 `KickHistory`·`FillInHistory`.
`group/`·`proof/`·`post/`·`reaction/`·`ai/` 패키지가 통째로 사라진다.

**승계 엔티티** (10종)

| 엔티티 | 수정 |
|---|---|
| `Member` | `point_balance`(캐시, 진실은 원장), `current_streak`, `last_completed_on` 추가. 익명화 규칙 승계 |
| `MediaConsent` | 동의 문구 대상만 캠 영상 온디바이스 분석으로 교체 |
| `BlockedSocialHash` | 수정 없음 |
| `MatchLock` | `lock_key` 시드가 `match:{minutes}` 4행 + 회원 행. 컬럼 변경 없음 |
| `MatchRequest` | `daily_target_minutes`·`period_days`·`category` → `target_minutes` 단일. `expires_at`(만료 예정 시각) 유지 + **`matched_session_id` 추가**. `uk_mr_active(active_member_id)` 유지 |
| `MatchEvent` | 타입 조정 + **그룹 참조 → `session_id` 개명** |
| `ReportCase` | 승계(`uk_rc_open`, `sla_due_at`, `restriction_review`) + **그룹 참조 → `session_id` 개명**. **`overdue` 저장 컬럼 폐지 — 파생**(미종결 AND `sla_due_at < now`) |
| `Report` | 승계(`uk_report(case_id, reporter_id)`) |
| `ReportHistory` | 승계 |
| `Sanction` | 승계(유효 제재식, 인터셉터 연동) |

**신설 엔티티** (14종)

| 엔티티 | 패키지 | 핵심 제약 |
|---|---|---|
| `MemberAgreement` | member | `UNIQUE(member_id, type)` |
| `MemberGoal` | member | `period_days`, `started_on`, `status`, `achieved_at`. 활성 1건은 조건부 로직 |
| `StreakDay` | member | **`UNIQUE(member_id, completed_on)`** — 하루 다회 완주 멱등(★D2) |
| `MatchBlock` | match | `UNIQUE(member_id, blocked_member_id)`. 양방향 2행 |
| `LiveSession` | session | `livekit_room_name` UNIQUE, `started_at`, `ends_at`, `ended_at`, `end_reason`(NORMAL/EARLY_UNDER_MIN, NULL=진행 중) |
| `SessionParticipant` | session | `UNIQUE(session_id, member_id)`. `status`, `warning_count`, `pause_used`, `pause_started_at`, `goal_text`, `completed`, `point_awarded`, `left_reason` |
| `AbsenceEvent` | session | **`UNIQUE(session_id, member_id, client_seq)`** — 재전송 멱등(★D4) |
| `Warning` | session | `UNIQUE(session_id, member_id, seq)` — seq 1~3. **`absence_event_id`는 NULL 허용** (D9 Pause 초과 경고는 근거 이벤트가 없다) |
| `Eviction` | session | `UNIQUE(session_id, member_id)`. `revoked_at`(이의 인용) |
| `AppealCase` | session | `eviction_id` UNIQUE, `sla_due_at`, `decided_by`, `decided_at`, **`reason_text`(200자)·`created_at`**. **`overdue`는 저장 컬럼이 아니라 파생**(미종결 AND `sla_due_at < now`) |
| `PointLedger` | point | **`UNIQUE(member_id, reason, ref_type, ref_id)`** — 중복 지급의 유일 방어선. `ref_type`·`ref_id` NOT NULL. `balance_after` |
| `Product` | store | `type`, `price_point`, `stock`, `status`, `description`·`image_url`(NULL 허용) |
| `StoreOrder` | store | `idempotency_key` UNIQUE |
| `PointCharge` | point | `pg_order_id` UNIQUE, `pg_tid` UNIQUE(NULL 허용), `created_at` |

세션 결과는 별도 테이블 없이 `session_participant`의 `completed`·`point_awarded`에서 파생한다.

**`point_ledger`의 reason별 ref 규약** — `ref_type`·`ref_id`가 NOT NULL이므로 모든 지급 사유에 참조 대상이 정해져 있어야 한다. 이 표가 곧 멱등키의 정의다.

| reason | ref 대상 |
|---|---|
| WELCOME | `member.id` |
| SESSION_COMPLETE | `session_participant.id` |
| EVICTION_PENALTY · APPEAL_REFUND | `eviction.id` |
| GOAL_ACHIEVED | `member_goal.id` |
| ORDER_SPEND · ORDER_CANCEL | `store_order.id` |
| CHARGE | `point_charge.id` |

### 패키지 배치 (확정 — CLAUDE.md §4의 도메인 목록과 같다)

```
com.morak
├── common
├── auth
├── member    (goal · streak 포함)
├── match
├── session   (warning · pause · eviction · appeal 포함)
├── point     (ledger · charge · PG 웹훅 포함)
├── store     (product · order)
├── report    (신고 · 제재)
└── dev
```

`payment`·`appeal`·`admin`·`batch`는 별도 패키지를 만들지 않는다(CLAUDE.md §4 규칙 승계). PG 충전과 웹훅은 point 소속이고, 퇴출 이의는 session 소속이다.

### 완료 게이트
- `./gradlew build` 성공, dev 프로필 기동 시 Hibernate가 24테이블 DDL 생성
- H2 콘솔에서 UNIQUE 제약 실존 확인: `streak_day`·`absence_event`·`point_ledger`(4컬럼 복합)·`store_order`
- `warning.absence_event_id`가 NULL 허용인지, **`report_case`·`appeal_case` 양쪽에 `overdue` 컬럼이 없는지** 확인(둘 다 파생)
- `db-schema.md`의 컬럼·제약 ↔ 엔티티 필드 대조 diff 0
- 폐기 패키지 5개 디렉터리 부재 확인

### 강의 포인트
1. **멱등을 서비스가 아니라 제약으로 보장하는 이유.** `point_ledger`의 `UNIQUE(member_id, reason, ref_type, ref_id)`가 없으면 B1이 두 번 돌 때 포인트가 두 번 들어간다. "먼저 조회하고 없으면 INSERT"는 동시 실행에서 둘 다 통과한다. member_id가 키에 들어가는 이유는 같은 참조 대상(예: 한 세션)에 대해 회원별로 각각 1건씩 지급되기 때문이다.
2. **캐시 컬럼과 진실의 분리.** `member.point_balance`는 조회 속도를 위한 캐시이고 진실은 원장의 합이다. 둘이 어긋날 수 있다는 전제로 설계하면 검증 쿼리를 미리 만들게 된다.
3. **엔티티를 선작성하는 이유.** 단계마다 엔티티를 조금씩 늘리면 `@ManyToOne` 대상이 없어 컴파일이 막히고, 이미 만든 테이블에 컬럼을 붙이는 마이그레이션이 계속 생긴다.

---

## O단계 — OpenAPI 재생성

**목표**: `docs/openapi.yaml`을 41 오퍼레이션으로 다시 쓴다. 프론트가 이 파일을 계약으로 보고 병렬 작업하므로, 코드보다 먼저 확정돼야 한다.

### 만들 것
api-spec §3 총람의 **45행**을 그대로 옮긴다. AU 7 · MT 3 · SS 11 · AP 1 · PT 1 · SR 5 · PY 3 · RP 1 · AD 9 · **DEV 4**. DEV가 4행인 것은 DEV-2가 시각 조작(POST)과 조회(GET) 두 행이기 때문이다 — 오퍼레이션 수와 총람 행 수를 같은 단위로 센다. (AD-9 이의 심사 상세는 10단계 이후 추가분이다.)
**DEV-1(`POST /api/auth/dev-login`)은 1단계에서 폐기가 확정됐다** — dev 로그인은 AU-1 + `DevSocialClient`가 처리하므로 별도 경로가 없다. 남은 개발 전용은 DEV-2·3·4 셋이다.
**DEV 계열은 프론트 계약이 아니다.** openapi.yaml에는 `개발 전용`으로 표시해 싣는다(api-spec DEV 절과 동일 결정) — 공개 41개 + DEV 4개로 파일 총계는 45다.
공통 스키마: `ErrorResponse`(`{"error":{code,message,details}}`), `PageResponse<T>`(Spring `Page` 직렬화 금지), Bearer 보안 스킴.
`docs/api-spec.md`(처리 절차)·`docs/screen-api-map.md`(화면-API 대응)도 함께 갱신한다.

### 완료 게이트
- openapi.yaml 파싱 성공(Swagger Editor 또는 `npx @redocly/cli lint`)
- 오퍼레이션 45개(공개 41 + 개발 전용 4), `operationId` 중복 0
- 응답에 등장하는 모든 `error.code`가 0.5단계 `ErrorCode`에 실존
- 프론트에 갱신 통지 + `docs/frontend-change-requests.md`에 변경분 기록

### 강의 포인트
1. **계약을 코드보다 먼저 고정하는 이유.** 프론트가 대기하지 않고 목 서버로 병렬 작업할 수 있다.
2. **개발 전용 엔드포인트를 계약에서 빼는 이유.** 계약에 있으면 프론트가 쓸 수 있다고 판단하고, 운영에서 404가 나는 순간 장애로 보고된다.

---

## 1.5단계 — 약관·목표·Streak 골격

**목표**: 매칭 이전에 필요한 회원 부속 정보를 세운다. 약관 동의 없이는 가입이 성립하지 않고, 목표 기간이 없으면 Streak가 무엇을 향해 쌓이는지 정의되지 않는다. 이 단계에서 D7 가입 차단(M-1)도 함께 해소한다.

### 만들 것
- **AU-1 확장**: 요청 body에 `agreements` 추가. 필수 2종(TOS·PRIVACY) 미동의 시 400 `AGREEMENT_REQUIRED`, 선택 1종(MARKETING)은 optional. `member_agreement` 3행 이하 INSERT
- **AU-3 수정(M-1)**: 만 14세 미만 판정 시 `member` 행 삭제 + 403 `UNDER_AGE_SIGNUP_BLOCKED`. `match_lock('member:{id}')` 동반 행도 함께 삭제한다 — 남으면 고아 행이 쌓인다. 0.5단계에서 `AgeVerification.UNDER_AGE`를 지웠으므로 `verifyAge`가 그 값을 쓰던 분기도 함께 사라진다
- **AU-7** `PUT /api/members/me/goal` — `{periodDays: 7|14|30}`. ACTIVE 목표가 있으면 409 `GOAL_ALREADY_ACTIVE`
- **AU-2 확장**: 응답에 `goal(periodDays, status)`·`streak(current)` 추가. `pointBalance`는 6단계에서 채운다(TODO 주석)
- **엔티티 쓰기 경로**: `member_agreement`, `member_goal`, `streak_day`(테이블·Repository만 — 갱신은 5단계 B1)
- **M-2 정리분**: `AuthInterceptor.SKIP_RULES`에서 폐기 API 3행 삭제(`proofs/*/media/raw`, `GET /api/posts`, `GET /api/posts/*`), `GET /api/members/me/groups` → `GET /api/members/me/sessions`

### 완료 게이트
- 약관 미동의 로그인 → 400 `AGREEMENT_REQUIRED` / 필수 2종 동의 → 200 + `member_agreement` 행 확인 (AU-1 성공은 계약상 200 — 가입과 로그인이 한 엔드포인트라 201을 쓰지 않는다)
- 2013년생 생년월일 → 403 `UNDER_AGE_SIGNUP_BLOCKED` + H2 콘솔에서 `member`·`match_lock` 해당 행 0건. 같은 authorizationCode 재로그인 시 신규 가입으로 처리되는지 확인
- AU-7 최초 설정 → 200, 재설정 → 409 `GOAL_ALREADY_ACTIVE`
- AU-2 응답에 `goal`·`streak` 필드 존재, 목표 미설정 회원은 `goal: null`
- helper `login()`에 `agreements` 반영 후 기존 게이트 회귀

Q2·Q6 확정 시 재실측: Streak 단위, 가입 차단 정책.

### 강의 포인트
1. **삭제가 상태 전이보다 나은 경우.** D7은 "가입시키고 막는" 방식을 버렸다 — 계정이 없으면 개인정보도 없고, 차단 로직을 인터셉터에 영구히 이고 갈 필요도 없다. 대신 동반 행(match_lock)을 빠뜨리면 고아가 남는다는 대가가 붙는다.
2. **동의 이력을 별도 테이블로 두는 이유.** `member`에 boolean 3개로 두면 "언제 동의했는지"가 사라진다. 개인정보 분쟁에서 필요한 것은 현재 상태가 아니라 시점이다.
3. **활성 1건 제약을 DB가 아니라 로직으로 두는 이유.** `UNIQUE(member_id, status)`로는 ACHIEVED가 여러 건 쌓이는 것을 막아버린다. 부분 인덱스를 쓸 수 없는 환경에서의 절충이다.

---

## 2단계 — 매칭 엔진

**목표**: 목표 시간 하나가 같은 6인을 선착순으로 묶어 세션을 만든다. 매칭 키가 시간 단일로 단순해졌지만 **동시성 문제는 그대로다** — 6명이 동시에 6번째로 들어오면 무엇이 정확히 6인 그룹 하나를 보장하는지가 이 단계의 전부다. 구 문서의 행 잠금·2단 잠금 순서·조건부 UPDATE 설계를 그대로 승계하고 매칭 키만 교체한다.

### 만들 것
- **엔드포인트**: MT-1 `POST /api/match-requests` · MT-2 `GET /api/match-requests/me` · MT-3 `DELETE /api/match-requests/{id}`
- **개발 전용**: DEV-2(시각 조작 — 미커밋분 커밋), DEV-4(배치 트리거)
- **테이블**: `match_request`(`target_minutes`·`expires_at`·`matched_session_id`), `match_lock`(시드 4행), `match_event`(`session_id`), `match_block`(읽기만 — 쓰기는 9단계), `live_session`(생성만)
- **배치**: B2 매칭 대기 만료(10분)
- **enum**: `MatchRequestStatus`, `MatchEventType`(승계)

작업 순서:
1. `com.morak.dev`의 `AdjustableClock`·`DevClockController` 커밋 + `DevBatchController`(DEV-4) + `@EnableScheduling`. **B2가 이 단계에서 처음 생기므로 배치 인프라를 함께 만든다**
   (강의 순서 주: 실습에서는 `AdjustableClock`이 1강 1부 — 2빈 `AppConfig`를 최종 상태로 가르치는 결정에 따라 — 에서 먼저 옮겨져 커밋되므로, 학습자 기준 이 항목은 `DevClockController`부터다)
2. `MatchLockRepository`에 `@Lock(PESSIMISTIC_WRITE) findByLockKey` + `MatchLockSeeder`(`ApplicationRunner`) — `match:60`·`match:120`·`match:180`·`match:240` 4행 시드
3. Repository 4종 → `MatchService`(MT-1 성사 절차, MT-3, MT-2) → `MatchController` + dto
4. `MatchExpireBatch`(B2)
5. AU-4 탈퇴 시 활성 매칭 요청 CANCELLED 연결(1단계 TODO 해소)

### 이 단계의 함정 (구 문서 승계)
- **잠금 행을 런타임에 만들지 않는다.** 미존재 행에 `FOR UPDATE`를 걸면 H2에 갭 락이 없어 동시 진입 시 INSERT 경합으로 복구 불가다. 시드가 선행인 이유다. 조합이 72개에서 4개로 줄었을 뿐 구조는 같다.
- **2단 잠금 순서는 "회원 행 → 조건 행" 고정.** 다른 경로가 역순으로 잡으면 교차 대기 데드락이다. MT-3·B2·AD-4·AU-4는 조건 행만 잡되, 회원 행을 잡아야 한다면 반드시 회원 행 먼저.
- **status를 바꾸는 모든 주체(MT-1·MT-3·B2, 이후 AD-4·AU-4)는 3종 세트를 함께 수행한다**: ①조건 행 잠금 ②조건부 UPDATE(`WHERE status='WAITING'`) ③`active_member_id=NULL`. ③이 빠지면 `uk_mr_active` 때문에 그 회원의 재요청이 영구 차단된다.
- **자기 포함 계산**: 대기 5명 + 나 = 6이 성사다. "6명 대기 중인지"를 세면 7명째에 성사된다.
- **성사 UPDATE의 영향 행 수 = 6 검증, 미달 시 전체 롤백.** 7건 이상 경합 시 정확히 6건만 선착순으로.
- **세션 생성의 트랜잭션 경계**: 6명째 요청의 단일 `@Transactional` 안에서 `live_session` INSERT + `session_participant` 6건 + 요청 6건 MATCHED 전이가 전부 일어난다. 잠금 해제는 커밋 시 자동 — 수동 해제 코드를 만들지 않는다.
- **매칭 키 교체분**: 구 `category × dailyTargetMinutes × periodDays` 72조합이 `targetMinutes` 4조합이 된다. `startDate` 자동 결정 로직은 폐기 — 세션은 매칭 즉시 시작하고(D21), `ends_at = started_at + targetMinutes`다.
- **성사 시 `match_request.matched_session_id`를 채운다.** MT-2가 MATCHED 응답에 `sessionId`를 실어야 하는데, 이 컬럼이 없으면 회원의 활성 세션을 역으로 뒤져야 하고 세션이 이미 끝난 뒤에는 그 경로가 사라진다. `expires_at`은 만료 "예정" 시각이라 요청 생성 시점에 `+10분`으로 미리 채운다 — B2는 이 값만 보고 판정한다.
- **MT-1 신설 게이트 2종**: 퇴출 쿨다운 30분(D14) → 409 `REMATCH_COOLDOWN`(api-spec §6-1 정본 기준 — 구판의 403 표기는 오기), 활성 세션 보유 → 409 `ALREADY_IN_ACTIVE_SESSION`. 상호 `match_block` 배제는 9단계에서 데이터가 생기므로 코드는 지금 넣고 실측은 9단계 게이트에서.
- H2 `LOCK_TIMEOUT=3000` → `PessimisticLockingFailureException`을 503 `LOCK_ACQUISITION_FAILED`로 매핑.
- H2 6스레드 통과는 MySQL 통과를 보장하지 않는다 — MySQL 재실측은 12단계 게이트에 예약.

### 완료 게이트
1. 6명 순차 요청(`targetMinutes: 60`) → 1~5번째 WAITING, 6번째 MATCHED + `sessionId`
2. **동시성**: 새 조건(`targetMinutes: 120`)으로 6명 동시 요청 → 정확히 6인 세션 1개. H2 콘솔에서 `session_participant` COUNT=6, `live_session` 1행
3. 대기 중 재요청 → 409 `DUPLICATE_MATCH_REQUEST`
4. MT-3 취소 → 204, 같은 회원 즉시 재요청 → 201 (`active_member_id` 해제 검증)
5. 만료: DEV-2로 +11분 → B2 트리거 → MT-2가 EXPIRED, 재요청 201
6. 연령 미검증 회원 → 403 `AGE_NOT_VERIFIED`
7. 활성 세션 보유자 재요청 → 409 `ALREADY_IN_ACTIVE_SESSION`

D8(시간 옵션 4종) 확정 시 재실측: 시드 행과 요청 검증 범위.

### 강의 포인트
1. **행 잠금과 동시성 제어** — 잠금 순서가 데드락을, 조건부 UPDATE가 경합 손실을 막는 이유. 이 단계가 프로젝트 전체에서 난도가 가장 높다.
2. **트랜잭션 경계를 어디에 긋는가.** 6인 확정과 세션 생성이 한 트랜잭션인 것이 왜 필수인지 — 중간에 끊기면 매칭됐는데 들어갈 방이 없는 회원이 생긴다.
3. **UNIQUE 제약을 방어선으로 쓰기.** `uk_mr_active`가 이중 배정을 막는 마지막 선이고, 그래서 해제(③)를 빠뜨리면 그 방어선이 사용자를 가둔다.

---

## 3단계 — 라이브 세션 골격

**목표**: 매칭으로 만들어진 세션에 실제로 들어가고 나오는 경로를 세운다. LiveKit 토큰 발급과 입퇴장 웹훅이 핵심이고, **누가 지금 방에 있는지의 진실 원천은 서버 DB가 아니라 LiveKit 웹훅**이라는 점이 이 단계의 설계 기준이다.

### 만들 것
- **엔드포인트**: SS-1 세션 조회 · SS-2 토큰 발급 · SS-3 오늘의 목표 · SS-9 내 세션 이력 · SS-10 LiveKit 웹훅 · SS-11 스티커 목록 · AU-6 캠 영상 분석 동의
- **테이블**: `live_session`(조회·상태 전이), `session_participant`(joined_at·goal_text), `media_consent`(쓰기 경로)
- **enum**: `SessionStatus`, `ParticipantStatus`, `StickerType`, `LeftReason`(웹훅이 쓰는 `DEVICE_ISSUE`만 — 나머지 값은 4단계)
- **설정**: `morak.livekit.*`, `session.reconnect-grace-seconds`, LiveKit server SDK 토큰 서명·웹훅 서명 검증

**LiveKit identity 규약 (api-spec SS-2·SS-10 확정)**: **`identity` = `member_id`를 문자열로 변환한 값**이다. SS-2가 토큰에 그 값을 심고, SS-10 웹훅이 받은 `identity`를 그대로 파싱해 회원을 찾는다. 별도 매핑 컬럼을 두지 않는다 — 컬럼을 두면 토큰 발급과 웹훅 수신 사이에 갱신 순서 문제가 생기고, 매핑이 유실되면 웹훅이 누구의 것인지 알 수 없어진다. `room` 이름은 `live_session.livekit_room_name`(UNIQUE)이다.

작업 순서:
1. AU-6 `media_consent` 쓰기 + `MemberService` — SS-2의 선행 검증이라 먼저 만든다
2. `LiveSessionRepository`·`SessionParticipantRepository` + `SessionService`(SS-1, SS-3, SS-9)
3. `LiveKitTokenProvider` + SS-2 — 참가자만, `status=LIVE`인 동안만
4. SS-10 웹훅 컨트롤러 — 서명 검증 후 `participant_joined`/`participant_left`/`room_finished` 처리(LiveKit에 `disconnected` 이벤트는 없다 — 구판 문구 정정). `AuthInterceptor.SKIP_RULES`에 전 게이트 skip 행 추가(M-2). 함정: LiveKit SDK `AccessToken.ttl`의 단위는 초가 아니라 **밀리초**다 — 초로 넣으면 3.6초짜리 토큰이 나온다(3단계 실측)
5. SS-11 스티커 목록(정적 enum 반환)

### 이 단계의 함정
- **웹훅은 JWT를 못 싣는다.** 전 게이트를 skip하되 **반드시 서명 검증을 컨트롤러 첫 줄에 둔다** — 실패 시 401 `INVALID_WEBHOOK_SIGNATURE`. skip만 하고 검증을 빠뜨리면 누구나 남을 세션에서 퇴장시킬 수 있는 공개 엔드포인트가 된다.
- **재접속 유예 90초(D13 확정 해석)**: `participant_left` 웹훅이 왔다고 즉시 전이하지 않는다. 유예 안에 `participant_joined`가 오면 없던 일로 본다. **90초를 넘기면 `LEFT` + `left_reason=DEVICE_ISSUE`로 자동 처리한다 — 경고를 주지 않고 포인트도 차감하지 않는다.** 대가는 그 세션의 미완주뿐이다.
- **연결 끊김과 자리비움은 별개 축이다(D13 확정).** 자리비움 경고는 **캠이 연결된 상태에서 얼굴이 60초 넘게 안 잡힐 때만** 생긴다. 연결이 끊긴 사람은 애초에 얼굴 이벤트를 보낼 수 없으므로 4단계 경고 카운터와 무관하다. 둘을 같은 카운터로 세면 지하철에서 끊긴 사람이 퇴출되고, 반대로 별개로 두지 않으면 "연결을 끊어 두면 경고를 피할 수 있다"가 성립한다 — 그래서 끊김은 경고가 아니라 즉시 미완주(LEFT)로 정산한다.
- **웹훅은 순서를 보장하지 않고 중복 수신된다.** `joined`가 `left` 뒤에 도착하는 경우를 상태 전이표로 방어한다.
- **`room_finished`가 세션을 직접 닫지 않는다.** 5단계의 공통 종료 루틴(`SessionClosingService.closeSession`)을 부른다. 이 단계에서 자체 종료 로직을 만들면 5단계에서 지워야 하고, 그 사이에 **미결 정산과 지급이 빠진 종료 경로**가 남는다 — 실제로 그렇게 만들었다가 조기 종료·`room_finished`로 끝난 세션의 완주자가 최대 1분간 `pointAwarded=0`을 받았고, 10분 넘게 자리를 비운 사람이 경고 없이 완주했다. 3단계에서는 종료 루틴 호출부만 만들고 본체는 5단계에서 채운다.
- **`identity`를 닉네임이나 UUID로 잡지 않는다.** 닉네임은 바뀌고 UUID는 조회를 한 번 더 태운다. `member_id` 문자열은 파싱 실패가 곧 위조 신호라 검증도 단순하다 — 숫자가 아니거나 그 세션의 참가자가 아니면 웹훅을 무시한다(200으로 응답하되 처리는 하지 않는다. 4xx를 주면 LiveKit이 재시도를 반복한다).
- **SS 계열의 검사 순서는 세션 존재(404) → 참가 자격(403) → 세션 상태(409)로 고정한다**(§0-3). **자격이 상태보다 먼저다** — 뒤집으면 참가자가 아닌 사람이 세션 번호를 훑어 "그 세션이 존재하고 끝났다"를 알아낼 수 있다. `SESSION_ENDED`·`SESSION_NOT_ENDED`는 참가자에게만 보이는 정보다. SS-1~SS-8 전부 같은 순서이고, SS-4는 뒤에 참가자 상태(409 `ALREADY_EVICTED`) → 시각 검증(400) → 재전송(409) → 레이트리밋(429)이 이어진다.
- **SS-2는 세션 LIVE 중에만.** ENDED 세션 토큰을 발급하면 종료된 방에 계속 접속한다 — 409 `SESSION_ENDED`.
- **참가자 목록은 익명 닉네임만.** 실명·소셜 ID는 응답에 넣지 않는다.
- **SS-3의 goal_text는 50자 제한.** 세션 중 수정 가능하고, 종료 후에는 409.
- **스티커는 서버에 저장하지 않는다**(D17). SS-11은 종류 목록만 주고 전송은 LiveKit 데이터 채널이 담당한다. `sticker_reaction` 테이블을 부활시키지 않는다.
- AU-6 동의 문구는 "영상은 기기에서만 분석하고 서버에 저장하지 않는다"를 명시한다. 미동의 시 SS-2 → 403 `CONSENT_REQUIRED`.

### 완료 게이트
1. 매칭 성사 후 SS-1 → 200, 참가자 6인·`status=LIVE`·`endsAt` = `startedAt + targetMinutes`
2. 비참가자 SS-1 → 403 `NOT_SESSION_PARTICIPANT`
3. AU-6 미동의 상태 SS-2 → 403 `CONSENT_REQUIRED` / 동의 후 → 200 + 토큰. 토큰을 jwt.io로 디코드해 **`identity` == 내 memberId 문자열**, `room` == `livekit_room_name` 확인
4. SS-3 목표 등록 → 200, SS-1 응답에 반영 / 51자 → 400 `VALIDATION_FAILED`
5. SS-10: 서명 없는 요청 → 401 `INVALID_WEBHOOK_SIGNATURE` / 정상 `participant_joined` → `session_participant.joined_at` 기록. `identity`가 숫자가 아니거나 비참가자면 200 + 무처리
6. SS-10 `participant_left` 후 90초 이내 `joined` → 상태 ACTIVE 유지(H2 콘솔) / 90초 초과 → `LEFT` + `left_reason=DEVICE_ISSUE`, `warning_count` 불변·`point_ledger` 차감 행 0건
7. SS-9 → 내 세션 이력 Page 응답, `PageResponse` 형식 확인
8. SS-11 → 스티커 3종 목록

### 강의 포인트
1. **외부 시스템이 진실 원천일 때의 설계.** 서버는 LiveKit이 알려주는 것을 받아 적을 뿐이고, 그래서 중복·순서 뒤바뀜·유실을 전제로 상태 전이를 짜야 한다.
2. **인증을 못 거는 엔드포인트를 안전하게 만드는 법.** JWT skip과 무인증은 다르다 — 서명 검증이 그 자리를 대신한다.
3. **유예 시간이라는 장치.** 즉시 판정이 옳아 보여도 네트워크는 순간적으로 끊긴다. 90초는 사용자를 억울하게 만들지 않기 위한 비용이고, 그 뒤의 처리를 "퇴출"이 아니라 "미완주"로 정한 것은 사고와 위반을 구분하는 결정이다.

---

## 4단계 — 경고·퇴출·Pause

**목표**: 서비스의 판정 엔진. 클라이언트는 자기 얼굴이 안 보인다는 사실만 보고하고, **60초 초과 판정과 3회 누적 퇴출은 전적으로 서버가 계산한다**(★D4). 신뢰할 수 없는 입력으로 신뢰할 수 있는 판정을 만드는 것이 이 단계의 주제다.

**경고의 성립 조건은 "캠이 연결된 상태에서 얼굴 미검출 60초 초과"다**(D13 확정). 연결이 끊긴 구간은 이 단계가 다루지 않는다 — 3단계의 재접속 유예가 `LEFT(DEVICE_ISSUE)`로 정산하고 끝난다. 두 축을 섞지 않는 것이 이 단계 설계의 전제다.

### 만들 것
- **엔드포인트**: SS-4 자리비움 이벤트 · SS-5 Pause 시작 · SS-6 Pause 종료 · SS-7 자율 퇴장
- **테이블**: `absence_event`, `warning`, `eviction`, `session_participant`(warning_count·pause_*·status 전이)
- **enum**: `AbsenceEventType`, `LeftReason`
- **정책값**: `session.absence-threshold-seconds`, `absence-min-interval-seconds`(레이트리밋 최소 간격), `evict-warning-count`, `pause-limit-minutes`

작업 순서:
1. `AbsenceEventRepository`·`WarningRepository`·`EvictionRepository`
2. `AbsenceJudgeService` — SS-4 절차: 멱등 확인(`client_seq`) → 레이트리밋 → START/END 짝 맞춤 → 지속 60초 초과 시 `warning` INSERT → seq가 3이면 `EvictionService` 호출
3. `EvictionService` — **퇴출 단일 경로**. 경고를 만드는 자리는 셋이지만(SS-4 자리비움 판정·SS-6 Pause 초과·5단계 종료 정산) 퇴출 절차는 여기 하나뿐이다 — 나뉘면 `eviction` 행 없이 상태만 EVICTED가 되거나 차감이 빠지는 경로가 생긴다. 절차: `eviction` INSERT → 참가자 EVICTED 전이 → **포인트 -300 즉시 차감** → LiveKit 강제 퇴장. **임계 판정("3회인가")도 호출자가 아니라 이 클래스가 한다** — 호출자는 경고를 부여한 뒤 무조건 물어보고, 정책값을 쥔 쪽이 답한다
4. `PauseService` — SS-5(세션당 1회, 조건부 UPDATE) · SS-6(10분 초과 시 경고 1회 후 자동 종료)
5. SS-7 자율 퇴장 — 참가자 LEFT + `left_reason`

### 이 단계의 함정
- **정책값을 `application.yml`에 먼저 넣어야 한다.** 위 네 키가 없으면 `@Value` 주입이 실패해 서비스 빈이 만들어지지 않고, 컴파일은 통과하는데 **기동 자체가 안 된다**. 코드를 다 옮기고 나서 왜 안 뜨는지 찾게 되는 자리다. 3단계의 `reconnect-grace-seconds`·LiveKit 키도 같다.
- **client_seq 멱등키가 없으면 같은 이벤트를 반복 전송해 남을 퇴출시킬 수 있다.** `UNIQUE(session_id, member_id, client_seq)` 위반은 500이 아니라 409 `DUPLICATE_ABSENCE_EVENT`로 잡는다.
- **자기 자신의 이벤트만 받는다.** 요청에 `targetMemberId`를 두지 않는다 — 두는 순간 상호 신고 구조가 되고 D4가 무너진다.
- **레이트리밋은 위조 방어의 두 번째 선.** 같은 회원의 직전 이벤트로부터 `absence-min-interval-seconds`(잠정 5초) 안에 다시 오면 429 `ABSENCE_RATE_LIMITED`. 값이 코드가 아니라 yml에 있는 이유는 정상 클라이언트의 실제 보고 주기를 실기기에서 재본 뒤 조정해야 하기 때문이다 — 너무 좁히면 정상 사용자가 막히고, 너무 넓히면 위조 방어가 무의미해진다.
- **경고 부여의 기준은 `occurredAt` 간격이지 서버 수신 시각이 아니다.** 네트워크 지연으로 END가 늦게 오면 없던 자리비움이 생긴다. 다만 `occurredAt`은 클라이언트 값이라 미래 시각·과도한 과거는 거부한다.
- **경고 카운터는 세션 스코프**(D11). 세션이 끝나면 소멸한다. 계정 누적은 만들지 않는다.
- **연결이 끊긴 참가자에게는 경고를 주지 않는다**(D13 확정). 끊긴 사람은 얼굴 이벤트를 보낼 수 없으니 자연히 카운터가 멈추는데, 여기에 "이벤트가 안 오면 자리비움" 같은 서버측 추정을 얹으면 안 된다. 그 추정이 3단계의 90초 유예와 이중으로 걸려 재접속한 사람이 경고를 안고 돌아온다. 미보고는 판정하지 않는 것이 규칙이다.
- **연결 끊김으로 LEFT된 참가자의 뒤늦은 SS-4는 무시한다.** 유예 초과 판정과 웹훅·API 도착 순서는 보장되지 않는다 — LEFT·EVICTED 상태면 이벤트를 받아도 경고를 만들지 않는다.
- **Pause 10분 초과는 경고 1회 + 강제 종료**(D9). 그 경고가 3회째면 그대로 퇴출된다 — Pause가 퇴출 회피 수단이 되지 않게 하는 장치다.
- **SS-5는 조건부 UPDATE로 방어한다**(`WHERE pause_used = false`). 동시 2회 요청에 서비스 레벨 if는 뚫린다 → 409 `PAUSE_ALREADY_USED`.
- **이미 EVICTED인 참가자의 SS-4·SS-5·SS-7은 409 `ALREADY_EVICTED`.** 퇴출은 종점이다.
- **SS-7의 reason은 요청 전용 값만 받는다** — `WITHDRAWAL`·`SANCTION`은 서버 전용이다. `LeftReason`을 그대로 바인딩하면 클라이언트가 서버 전용 값을 보낼 수 있다.
- **필드 하나짜리 request record는 `@JsonCreator` 필수**(CLAUDE.md §4-2). `LeaveSessionRequest(reason)`가 그 케이스다.
- **퇴출 패널티는 퇴출 트랜잭션이 즉시 차감한다.** SS-4 응답이 `pointDelta=-300`을 싣는데 원장 기록을 B1에 미루면 최대 1분 동안 그 값이 사실이 아니다 — 사용자는 화면에서 -300을 보고 PT-1을 열면 아직 없는 상태를 만난다. 지급 주체가 둘(퇴출 트랜잭션·B1)이 되는 문제는 **원장 멱등키 `(memberId, EVICTION_PENALTY, EVICTION, evictionId)`가 막는다** — 두 자리가 같은 키로 쓰므로 이미 있으면 조용히 무시된다. B1의 `settleEvictionPenalty`는 **이 트랜잭션이 어떤 이유로 원장을 남기지 못했을 때만 걸리는 소급 안전망**으로 남는다.
- **차감은 절차의 마지막에 둔다.** `PointService.award`가 잔액 캐시를 벌크 UPDATE로 갱신하며 영속성 컨텍스트를 비우므로, 그 뒤에 엔티티에 쓴 값은 사라진다. 퇴출 행 생성과 참가자 상태 전이가 먼저다.
- **`EvictionService`는 잔여 인원 검사를 하지 않는다.** 퇴출이 사람을 줄이니 이어서 해야 할 일이 맞지만, 그 검사가 5단계 종료 루틴을 부르고 종료 루틴은 미결 정산에서 다시 경고를 만들어 이 클래스로 돌아온다 — 순환이다. 검사는 **호출자가 이어서** 부른다(5단계 함정 참조).

### 완료 게이트
1. SS-4 START 후 61초(DEV-2 조작) 뒤 END → `warning` 1행, `warning_count=1`
2. 같은 `clientSeq` 재전송 → 409 `DUPLICATE_ABSENCE_EVENT`, `absence_event` 행 수 불변
3. 경고 3회 누적 → 참가자 EVICTED + `eviction` 1행 + **그 자리에서 `point_ledger` `EVICTION_PENALTY` -300 1행**(B1을 기다리지 않는다) + SS-4 응답 `pointDelta=-300`이 PT-1 잔액과 즉시 일치. SS-1 응답에 반영. 이후 SS-4 → 409 `ALREADY_EVICTED`
4. SS-4 연속 호출(레이트 초과) → 429 `ABSENCE_RATE_LIMITED`
5. SS-5 → 200, 재요청 → 409 `PAUSE_ALREADY_USED` / SS-6 정상 복귀 → 200, 경고 미부여
6. Pause 후 DEV-2 +11분 → SS-6 → 경고 1회 부여 + Pause 종료
7. SS-7 → 204 + `left_reason` 기록 / `reason: "SANCTION"` 요청 → 400 `VALIDATION_FAILED`
8. 경고 2회 누적 상태에서 연결 끊김 90초 초과 → `LEFT(DEVICE_ISSUE)`, `warning_count`는 2 그대로(3회로 올라가 퇴출되지 않음). 그 뒤 도착한 SS-4는 무시
9. 퇴출자 MT-1 재요청 → 409 `REMATCH_COOLDOWN`, DEV-2 +31분 후 → 201

Q3·경고 임계 확정 시 재실측: 이벤트 스키마, 60초 값.

### 강의 포인트
1. **신뢰할 수 없는 클라이언트 입력으로 판정하기.** 멱등키·레이트리밋·시각 검증 3종이 각각 무엇을 막는지 — 하나만 빼도 뚫린다.
2. **판정을 서버가 하는 이유.** 클라이언트가 "경고 1회 부여해줘"를 보내는 설계와 "얼굴이 안 보이기 시작했다"를 보내는 설계의 차이. 후자는 위조해도 서버 계산을 통과해야 한다.
3. **조건부 UPDATE가 if문을 대신하는 자리.** `pause_used` 검사를 서비스에서 하면 동시 2요청이 둘 다 통과한다.

---

## 5단계 — 세션 종료·완주·포인트 지급

**목표**: 세션을 닫고 누가 완주했는지 판정해 보상을 확정한다. 배치 하나가 종료·완주·Streak·목표 달성·포인트를 연쇄로 처리하므로, **재실행해도 같은 결과**가 이 단계의 유일한 합격 기준이다.

### 만들 것
- **엔드포인트**: SS-8 세션 결과 조회
- **개발 전용**: DEV-3 과거 세션 완주 이력 시드
- **배치**: B1 세션 종료 처리
- **테이블**: `session_participant`(completed·point_awarded), `live_session`(ended_at·end_reason), `streak_day`, `member_goal`(ACHIEVED 전이), `point_ledger`(첫 쓰기), `member`(current_streak·last_completed_on)
- **enum**: `PointReason`(SESSION_COMPLETE·GOAL_ACHIEVED·EVICTION_PENALTY), `GoalStatus`

작업 순서:
1. `DevSessionSeedController`(DEV-3) — 과거 일자 완주 이력 시드. **Streak 연속 판정과 목표 달성 게이트를 이것 없이는 재현할 수 없다**(D3의 30일 목표를 실시간으로 채울 수 없다). 정식 경로로 INSERT해 `UNIQUE(member_id, completed_on)`을 그대로 통과시킨다
2. `PointLedgerRepository` + `PointService.award()` — 단일 지급 메서드. `UNIQUE(member_id, reason, ref_type, ref_id)` 위반은 중복 지급 시도로 보고 조용히 무시(멱등). ref 대상은 E단계의 reason별 규약표를 따른다
3. `SessionCompletionJudge` — D1 판정: 종료 시각까지 LEFT·EVICTED 아니면 완주. Pause 10분은 재실 인정
4. **`SessionClosingService` — 공통 종료 루틴**. 세션이 어떤 이유로 끝나든 여기를 지난다: 예정 시각 도래(B1) · 잔여 인원 미달 조기 종료(D12) · `room_finished` 웹훅(3단계). 절차는 ①세션 ENDED 전이 ②**미결 상태 사후 정산** ③남은 사람 완주 판정 ④지급 `+100×(targetMinutes/60)` + `streak_day` INSERT + `current_streak` 갱신 + 목표 달성 검사(D3).
5. `SessionClosingBatch`(B1) — **대상 선별만** 하고 처리는 위 서비스에 맡긴다(같은 빈 안에서 부르면 프록시를 타지 않아 대상별 트랜잭션 경계가 서지 않는다). 세 갈래를 순서대로: ⓐ`ends_at` 지난 LIVE 종료 ⓑ이미 ENDED인데 미지급인 완주자 흡수 ⓒ원장에 안 남은 퇴출 패널티 소급

   **② 사후 정산이 필요한 이유**: 세션이 끝나는 순간 두 종류의 미결 상태가 남는다 — **복귀하지 않은 PAUSED 참가자**(SS-6를 안 부르고 세션이 끝남)와 **END가 오지 않은 자리비움 START**(SS-4 START만 있고 END가 없음). 둘 다 판정 주체가 사용자 요청이라, 요청이 영영 안 오면 경고가 부여되지 않는다. 세션 중에 10분 넘게 자리를 비우고 그대로 이탈한 사람이 아무 경고 없이 끝나는 셈이고, 조기 종료까지 겹치면 **"남이 먼저 나가면 내 경고가 사라진다"**는 회피 경로가 성립한다.
   정산 규칙은 진행 중 판정과 동일하게 적용한다: PAUSED 시작 시각부터 세션 종료까지가 `pause-limit-minutes` 초과면 경고 1회(D9), 자리비움 START부터 세션 종료까지가 `absence-threshold-seconds` 초과면 경고 1회. **부여 결과가 3회째면 그 시점에 퇴출 처리**하고 완주 판정(③)은 그 결과를 본다.
6. `closeIfUnderMinimum`(D12 조기 종료) — 잔여 인원이 `min-participants` 미만이면 같은 종료 루틴을 `EARLY_UNDER_MIN`으로 탄다. **PAUSED도 재실로 세어 인원에 포함한다**(★D1) — 빼면 화장실 간 사람 때문에 세션이 끝난다. 사람이 빠지는 경로마다 이 검사를 뒤에 붙인다(SS-4 퇴출·SS-6 Pause 초과·SS-7 퇴장)
7. `SessionService`에 SS-8 추가
8. **AD-6 소급용 `restoreCompletion`** — 10단계가 쓴다. 지급 경로를 따로 만들지 않고 같은 자리를 지나게 한다(멱등키가 겹쳐야 B1 흡수와 이중 지급이 안 난다)

### 이 단계의 함정
- **멱등의 근거는 코드가 아니라 제약이다.** B1 재실행 시 `point_ledger`의 `UNIQUE(member_id, reason, ref_type, ref_id)`와 `streak_day`의 `UNIQUE(member_id, completed_on)`이 중복을 막는다. "이미 처리했나 조회 후 INSERT"는 동시 실행에서 뚫린다.
- **사후 정산(③)의 멱등 근거는 `warning`의 `UNIQUE(session_id, member_id, seq)`다.** B1이 두 번 돌면 같은 미결 건으로 경고를 또 만들려 하는데, seq가 이미 점유돼 있어 막힌다. 정산 전에 "이 참가자에게 미결 건이 남아 있는가"를 상태로 판단하지 말고(ENDED 세션의 PAUSED는 재실행 때도 그대로다) 제약에 맡긴다.
- **완주 지급의 ref는 `session_participant.id`이지 `session.id`가 아니다.** 세션 하나에 6명이 각각 지급받으므로 세션 id를 쓰면 첫 사람만 지급되고 나머지 5명이 UNIQUE에 막힌다. member_id가 키에 함께 들어가도 ref는 규약표대로 잡는다.
- **하루 2세션 완주는 Streak +1이지 +2가 아니다**(D2). `streak_day` UNIQUE가 이것을 보장하고, `member.current_streak`는 그 결과를 반영할 뿐이다.
- **Streak 연속 판정은 `last_completed_on`과의 차이로 한다.** 1일 차이면 +1, 2일 이상이면 1로 리셋. 목표 자체는 유지된다(D3) — 리셋과 목표 취소는 다르다.
- **목표 달성 시 목표는 ACHIEVED로 닫고 재설정 가능하게 둔다.** 계속 ACTIVE로 두면 같은 목표에 포인트가 반복 지급된다.
- **판정식은 조건 둘이다. 연속 캐시만 보면 뚫린다(치명 결함 A).** `current_streak >= period_days` 하나로 판정하면, 7일 목표를 막 달성한 회원이 곧바로 7일 목표를 다시 걸고 **다음 날 한 번만 완주해도 또 1,000p를 받는다** — `current_streak`가 이미 8이기 때문이다. 같은 연속을 몇 번이고 다시 팔 수 있다. 그래서 **`started_on` 이후 완주일 수 >= `period_days`**를 함께 본다. 목표는 "지금까지 며칠 했는가"가 아니라 **"여기서부터 며칠 더 하는가"**다.
- **두 조건은 서로를 대체하지 못한다.** 시작일 이후 완주일이 7일이어도 중간에 하루 끊겼다면 연속이 아니라 달성이 아니므로 연속 캐시 조건도 남긴다. 반대로 연속만 보면 위 구멍이 열린다.
- **집계 전에 `flush()`가 필요하다.** 방금 INSERT한 완주일이 아직 영속성 컨텍스트에만 있으면 `started_on` 이후 집계가 그 하루를 빼고 세어, 마지막 날 달성이 판정되지 않는다.
- **완주 포인트는 `targetMinutes` 기준이지 실제 재실 시간이 아니다**(D15). 60분 세션 완주 = 100p, 240분 = 400p.
- **조기 종료(D12)와 정상 종료의 완주 판정이 같다**는 점에 주의. 2인 미만으로 떨어져 30분 만에 끝난 세션도 잔류자는 완주다 — 남의 이탈로 내 Streak가 끊기지 않게 하는 결정이다.
- **B1은 정시 종료 + 흡수 안전망이다.** 종료 경로가 지급까지 함께 하도록 통일된 뒤로, 흡수 갈래(ⓑⓒ)는 **종료 트랜잭션이 지급을 남기지 못하고 끊긴 경우에만** 대상이 생긴다. 그래도 지우지 않는 이유는 그 경우에 완주자가 영구 미지급으로 남기 때문이다. 통일 전에는 조기 종료·`room_finished`가 완주 마킹까지만 하고 지급을 B1에 미뤄 그 사이 SS-8이 `pointAwarded=0`을 내렸다.
- **순서가 곧 명세다: ①닫기 → ②정산 → ③판정 → ④지급.** ②가 ③보다 먼저인 것이 핵심이다 — 뒤집으면 정산으로 퇴출될 사람이 이미 완주로 집계된 뒤라 되돌릴 자리가 없다.
- **①이 맨 앞인 것은 순환 차단이다.** ②의 퇴출이 잔여 인원을 최소 미만으로 떨어뜨려 조기 종료 검사를 다시 부르는데, 세션을 먼저 ENDED로 찍어 뒀으므로 그 호출이 곧바로 되돌아 나간다. 같은 이유로 **`EvictionService`는 잔여 인원 검사를 스스로 하지 않는다** — 퇴출이 사람을 줄이니 이어서 해야 할 일이 맞지만, 그 검사가 종료 루틴을 부르고 종료 루틴은 정산에서 다시 경고를 만들어 `EvictionService`로 돌아온다. 검사를 **호출자로 옮겨** 순환을 끊었고, 호출자 셋(SS-4 판정·SS-6 Pause 초과·SS-7 퇴장)이 각각 이어서 부른다. 종료 루틴 자신만 예외다(이미 닫은 세션을 다시 닫을 이유가 없다).
- **지급 호출이 영속성 컨텍스트를 비운다.** `PointService.award`가 잔액 캐시를 벌크 UPDATE로 갱신하므로(원장의 `balance_after`가 증감 후 값이려면 그래야 한다), 지급 이전에 잡아 둔 엔티티 참조는 그 뒤로 준영속이다. **엔티티 목록이 아니라 id로 루프를 돌고 쓸 행은 그때그때 다시 읽는다** — 목록을 미리 읽어 돌면 첫 지급 이후의 참가자가 전부 준영속이 되어 완주 표시와 경고가 조용히 사라지고, 완주 표시가 없으면 흡수 대상 조회에도 안 잡혀 영구 미표시가 된다.
- **종료 시각은 배치가 도는 시각이 아니라 `ends_at`이다.** 배치가 몇 분 늦게 돌았다는 이유로 자리비움 구간이 길어져 경고가 붙으면, 같은 세션을 언제 처리했느냐에 따라 결과가 달라진다. 정시 종료의 사유는 정산 중 퇴출로 인원이 미달해도 `NORMAL`로 고정한다 — 예정된 시간을 다 채웠으므로 조기 종료가 아니다.
- **한 참가자에게 미결 정산 두 규칙을 함께 적용하지 않는다.** PAUSED는 자리를 비우라고 만든 상태라 진행 중 판정도 자리비움 경고를 주지 않는데, 종료 정산에서만 둘 다 걸면 화장실 모드를 쓴 사람이 경고를 두 배로 받는다.
- **포인트 지급과 잔액 캐시 갱신은 같은 트랜잭션.** `member.point_balance` 갱신을 빠뜨리면 원장과 캐시가 갈린다.

### 완료 게이트
1. 6인 세션 → DEV-2로 `ends_at` 경과 → B1 트리거 → 세션 ENDED + `end_reason=NORMAL`, 완주자 `completed=true`, `point_ledger`에 `SESSION_COMPLETE` 6행(각 행의 `ref_id`가 서로 다른 `session_participant.id`인지 확인)
2. **멱등**: B1 재트리거 → `point_ledger`·`streak_day` 행 수 불변(H2 콘솔 COUNT 대조)
3. 퇴출자·자율 퇴장자·연결 끊김 이탈자(`DEVICE_ISSUE`) → `completed=false`, 지급 0. **퇴출자만** `EVICTION_PENALTY` -300 1행 — 나머지 둘은 차감 없이 미완주로 끝난다(D10·D13)
   **소급 규칙(4단계 인계)**: 4단계는 퇴출 시 원장을 만들지 않고 `eviction.point_penalty=300`만 남겼다. 원장 지급 주체는 이 단계로 일원화한다 — B1이 `revoked_at IS NULL`인 eviction 중 원장에 `(EVICTION_PENALTY, EVICTION, eviction.id)` 행이 없는 것을 소급 차감한다. 멱등키(4열 UNIQUE)가 이중 차감을 막으므로 재실행 안전. 퇴출 트랜잭션이 직접 차감하도록 되돌리면 B1과 이중 주체가 되니 금지
4. 같은 날 2세션 완주 → `streak_day` 1행, `current_streak` +1
5. DEV-3로 6일 시드 + 7일 목표 회원이 7일차 완주 → `member_goal` ACHIEVED + `GOAL_ACHIEVED` 1,000p
5-1. **재달성 구멍 검증(치명 결함 A 회귀)**: 위 회원이 곧바로 7일 목표를 다시 설정하고 **다음 날 1회만 완주** → `current_streak`는 8이지만 새 목표의 `started_on` 이후 완주일이 1일이므로 **ACHIEVED 아님, 지급 0**. 이후 6일 더 완주해야 달성
6. 하루 건너뛴 회원 → `current_streak` 1로 리셋, 목표는 ACTIVE 유지
7. SS-8 → 본인 완주 여부·지급 포인트·Streak 반영·참가자별 요약. 종료 전 호출 → 409 `SESSION_NOT_ENDED`
8. 참가자 4명 퇴장으로 2인 미만 → 세션 즉시 ENDED + `end_reason=EARLY_UNDER_MIN`, 잔류 2인 완주 인정
9. **사후 정산**: ⓐPause 시작 후 SS-6 없이 세션 종료(경과 11분) → B1 후 경고 1행 추가 ⓑSS-4 START만 보내고 END 없이 종료(경과 61초 이상) → B1 후 경고 1행 추가 ⓒ경고 2회 보유자가 ⓐ 또는 ⓑ에 걸림 → 3회째로 퇴출 처리 + `completed=false` + `EVICTION_PENALTY` -300. B1 재트리거 시 경고·퇴출·원장 행 수 불변(멱등)

Q1·Q2·D15 확정 시 재실측: 완주 판정식, Streak 단위, 포인트 값.

### 강의 포인트
1. **배치 멱등성** — "다시 실행해도 같은 결과"를 UNIQUE 제약으로 강제하는 법. 크론이 두 번 뜨거나 수동 트리거가 겹치는 일은 반드시 일어난다.
2. **원장(ledger) 패턴.** 잔액을 직접 더하고 빼지 않고 사건을 append하는 이유 — 어떤 지급이 왜 일어났는지 사후에 추적할 수 있고, 이의 인용 시 역분개로 되돌릴 수 있다.
3. **연쇄 판정의 순서.** 완주 → Streak → 목표 달성이 한 방향이라 중간에 실패하면 무엇까지 커밋됐는지 정의돼야 한다.

---

## 6단계 — 포인트 원장·조회

**목표**: 5단계에서 생긴 원장에 조회 경로와 나머지 지급 사유를 붙인다. 이 단계가 끝나면 포인트의 모든 유입·유출 사유가 한 테이블에 모인다.

### 만들 것
- **엔드포인트**: PT-1 `GET /api/members/me/points` — 잔액 + 원장 내역(Page)
- **M-3 해소**: `AuthService.login` 최초 가입 시 웰컴 +1,000p(`PointReason.WELCOME`, `ref_type=MEMBER`, `ref_id=memberId`)
- **AU-2 확장**: `pointBalance` 필드 채우기(1.5단계 TODO 해소)
- **테이블**: `point_ledger`(조회 인덱스), `member.point_balance`

### 이 단계의 함정
- **웰컴 포인트의 멱등키는 `(memberId, WELCOME, MEMBER, memberId)`다.** ref 대상이 회원 자신이라 member_id가 두 자리에 들어간다 — 규약표대로다. 탈퇴 후 재로그인 복구(RESTORED) 시 다시 지급되면 안 되고, UNIQUE가 그것을 막는다.
- **잔액은 캐시에서 읽고 내역은 원장에서 읽는다.** 둘이 어긋나면 원장이 진실이다. 검증 쿼리(`SUM(delta)` vs `point_balance`)를 게이트에 넣는다.
- 원장 내역은 최신순 Page. `PageResponse<T>`를 쓴다(Spring `Page` 직렬화 금지).

### 완료 게이트
1. 신규 가입 → PT-1 잔액 1,000, 내역 1행(`WELCOME`)
2. 세션 완주 후 → 잔액 1,100, 내역 2행. `balance_after`가 누적과 일치
3. `SUM(delta)` = `member.point_balance` (H2 콘솔 대조)
4. 탈퇴 → 재로그인 복구 → 웰컴 재지급 없음(내역 행 수 불변)
5. AU-2 응답 `pointBalance` = PT-1 잔액

Q4 확정 시 재실측: 스파크 포인트 분리 여부.

### 강의 포인트
1. **파생값을 저장할 때의 규칙.** 캐시 컬럼은 성능을 위한 것이고 진실이 아니다 — 어긋남을 검출할 쿼리를 함께 만들어 둔다.
2. **멱등키 설계.** `(reason, ref_type, ref_id)` 3튜플이 왜 모든 지급 사유를 커버하는지, 웰컴처럼 참조 대상이 회원 자신인 경우를 어떻게 표현하는지.

---

## 7단계 — 스토어·주문

**목표**: 쌓인 포인트를 쓰는 경로. 재고 차감과 포인트 차감이 한 트랜잭션 안에서 함께 성공하거나 함께 실패해야 하고, 사용자의 중복 탭이 주문을 두 건 만들면 안 된다.

### 만들 것
- **엔드포인트**: SR-1 상품 목록 · SR-2 상품 상세 · SR-3 주문 생성 · SR-4 내 주문 목록 · SR-5 주문 상세
- **테이블**: `product`, `store_order`
- **enum**: `ProductType`, `ProductStatus`, `OrderStatus`, `PointReason.ORDER_SPEND`/`ORDER_CANCEL`
- 상품 시드는 `ApplicationRunner` 또는 dev 프로필 SQL. 실제 상품 목록·가격은 팀 확정 대기 — 잠정 데이터로 진행

### 이 단계의 함정
- **포인트 차감은 조건부 UPDATE**(`WHERE point_balance >= ?`). 조회 후 검사하면 동시 주문 2건이 잔액을 넘겨 쓴다 → 영향 행 0이면 409 `INSUFFICIENT_POINT`.
- **재고 차감도 같은 방식**(`WHERE stock >= ?`) → 409 `OUT_OF_STOCK`.
- **`idempotency_key` UNIQUE가 중복 주문의 방어선.** 클라이언트가 생성해 보내고, 위반 시 500이 아니라 409 `DUPLICATE_ORDER` 또는 기존 주문 반환. 서비스의 사전 조회는 순차 재전송용 지름길이라 동시 요청 둘은 함께 통과한다 — 제약 위반을 잡아 기존 주문 번호를 돌려주려면 **잡는 자리가 트랜잭션 밖**이어야 한다(`@Transactional` 메서드 안에서는 이미 롤백된 뒤라 조회할 수 없다).
- **주문·재고·원장이 한 트랜잭션.** 포인트만 빠지고 주문이 없는 상태를 만들지 않는다.
- **`HIDDEN` 상품은 SR-1 목록에서 제외하고 SR-2 직접 호출도 404** `PRODUCT_NOT_FOUND`. 목록 제외만으로는 부족하다.
- **장바구니·환불·배송지는 보류**(v2). SR-3은 단일 상품 주문 1건이고 `OrderStatus.CANCELLED`는 enum에만 두고 전이 경로를 만들지 않는다 — 환불이 들어올 자리를 비워 두는 것이지 지금 구현하는 것이 아니다.
- **주문 이행은 v1 범위 밖이다**(api-spec SR-3·§7 FR-506). `store_order`는 **주문 접수까지**만 책임진다. 기프티콘 발송 코드·수령 연락처·배송 상태 컬럼을 만들지 않는다. 실제 전달은 운영자가 수동으로 처리하는 것을 전제하고, 그 전제를 팀과 프론트에 명시한다 — 스토어 화면이 "발송 완료" 같은 상태를 기대하면 계약이 어긋난다.

### 완료 게이트
1. SR-1 → 상품 목록, `SOLD_OUT` 상태 표시. `HIDDEN` 상품 미노출 + SR-2 직접 호출 404
2. SR-3 정상 주문 → 201 + 포인트 차감 + 재고 -1 + `point_ledger` `ORDER_SPEND` 1행
3. 잔액 부족 주문 → 409 `INSUFFICIENT_POINT`, 재고·원장 불변
4. 같은 `idempotencyKey` 재요청 → 409 `DUPLICATE_ORDER` 또는 기존 주문. `store_order` 행 수 불변
5. 재고 1개 상품에 동시 2주문 → 정확히 1건 성공, 나머지 409 `OUT_OF_STOCK`
6. SR-4·SR-5 → 본인 주문만. 타인 주문 ID 조회 → 403 `FORBIDDEN`, 없는 주문 → 404 `ORDER_NOT_FOUND`

Q7·상품 목록 확정 시 재실측: 상품 데이터, 커머스 범위.

### 강의 포인트
1. **멱등키를 클라이언트가 만드는 이유.** 네트워크 재시도와 중복 탭을 서버가 구분할 방법이 없다 — 같은 의도의 요청임을 클라이언트만 안다.
2. **404와 403의 선택.** 타인 자원에 403을 주면 "그 ID는 존재한다"를 알려주는 셈이다. 주문은 번호를 아는 것만으로 새어 나갈 사실이 없어 403으로 통일했지만(API명세서 SR-5), 퇴출(10단계 AP-1)처럼 존재 자체가 민감한 자원은 반대로 통일한다. 기준은 "그 자원의 기본 응답을 따르되 응답이 갈리는 지점을 만들지 않는다"이다.
3. **조건부 UPDATE의 반복 등장.** 매칭(2단계)·Pause(4단계)·잔액(7단계)이 전부 같은 패턴이다.

---

## 8단계 — PG 테스트 결제

**목표**: 포인트를 사서 채우는 경로. 외부 PG와의 왕복에서 **돈은 빠졌는데 포인트가 없거나 그 반대**가 되지 않게 하는 것이 전부다. 테스트 키로 1회 왕복만 완성한다.

### 만들 것
- **엔드포인트**: PY-1 충전 생성 · PY-2 승인 확인 · PY-3 PG 웹훅
- **테이블**: `point_charge`
- **enum**: `ChargeStatus`, `PointReason.CHARGE`
- **설정**: `morak.pg.provider(toss-test)`, `secret-key`, `point-per-krw(1)`, `min-amount-krw(1,000)`, `max-amount-krw(1,000,000)`, `ready-expire-minutes(30)`
- **배치**: **B5 충전 승인 기한 만료** — 승인도 실패도 오지 않은 채 `ready-expire-minutes`를 넘긴 READY를 FAILED로 닫는다. `@Scheduled`(매분) + DEV-4 트리거, 패키지는 point
- **PG 클라이언트**: `PgClient` 인터페이스 + `DevPgClient`(1단계 `SocialClient` 패턴). 실 키 연동은 12단계
- `AuthInterceptor.SKIP_RULES`에 `POST /api/webhooks/payment` 행(M-2, 3단계에서 함께 추가했으면 확인만)

### 이 단계의 함정
- **금액 검증은 서버가 한다.** PY-2에서 PG가 알려준 승인 금액과 `point_charge.amount_krw`가 다르면 400 `PAYMENT_AMOUNT_MISMATCH`. 클라이언트가 보낸 금액을 믿으면 1원 결제로 10만 포인트가 들어온다.
- **PY-2와 PY-3은 같은 결과를 만들어야 하고 둘 다 와도 한 번만 적립된다.** 원장 UNIQUE `(memberId, CHARGE, CHARGE, chargeId)`와 `pg_tid` UNIQUE가 이중 방어선이다.
- **웹훅 서명 검증 필수.** JWT skip과 무인증은 다르다(3단계와 같은 규칙) → 401 `INVALID_WEBHOOK_SIGNATURE`. 테스트 모드 PG가 서명을 붙여 주지 않아 규약을 우리가 정했다 — `X-Morak-Signature`에 본문 바이트의 HMAC-SHA256(hex), 키는 `pg.secret-key`.
- **지급 경로의 잔액 캐시가 상대 UPDATE인지 확인한다.** 여기서 처음으로 같은 회원에게 지급 두 건(PY-2·PY-3)이 동시에 들어온다. 엔티티를 읽어 더하면 한쪽이 사라져 "잔액 = 원장 합"이 깨진다 — 원장 UNIQUE는 서로 다른 충전 건을 막지 않으므로 이 결함을 가려 주지 않는다.
- **`pg_tid`는 NULL 허용 UNIQUE.** READY 상태에서는 아직 없다.
- **승인도 실패도 오지 않는 READY가 반드시 생긴다**(결제창을 열고 닫은 경우). 닫아 주지 않으면 영구 잔존하면서 사용자의 충전 이력을 "진행 중"으로 오염시킨다. B5의 기한은 **PG 승인 API가 결제창 생성 후 받아 주는 시간의 배**로 잡는다(토스 10분 → 30분) — 짧으면 정상 결제가 닫히고, 길면 잔존 시간이 늘어난다.
- **B5는 조회 시점과 전이 시점 사이에 승인이 도착할 수 있다.** 전이 직전에 상태를 다시 확인해 READY가 아니면 건너뛴다. 재실행이 안전한 근거는 두 번째 실행의 대상 조회가 `status='READY'`에 걸려 비어 있다는 점이다.
- **승인되지 않은 충전에 적립 요청** → 409 `PAYMENT_NOT_APPROVED`.
- **IAP는 보류**(v2). 웹 PG 테스트 모드 1종만 구현한다. FR-505의 "실물+인앱결제 혼합"은 스토어 정책 위반이라 구현하지 않는다(팀 전달 사항).

### 완료 게이트
1. PY-1 → 201 + `pgOrderId`, `point_charge` READY 1행. **DEV-2 +31분 → B5 트리거 → FAILED 전이**, 그 뒤 PY-2 → 409 `PAYMENT_NOT_APPROVED`. B5 재트리거 시 행 상태 불변
2. PG 테스트 결제 후 PY-2 → 200 + 포인트 적립 + `point_ledger` `CHARGE` 1행 + 상태 APPROVED
3. PY-2 재호출 → 원장 행 수 불변(멱등)
4. PY-3 웹훅이 뒤늦게 도착 → 원장 행 수 불변
5. 금액 조작 요청 → 400 `PAYMENT_AMOUNT_MISMATCH`, 적립 없음
6. 서명 없는 웹훅 → 401 `INVALID_WEBHOOK_SIGNATURE`
7. 없는 충전 ID → 404 `CHARGE_NOT_FOUND`

### 강의 포인트
1. **결제에서 서버가 클라이언트를 믿지 않는 지점들.** 금액·상태·완료 여부 셋 다 PG에 직접 물어 확인한다.
2. **같은 결과를 만드는 두 경로(폴링 확인·웹훅)를 동시에 두는 이유.** 웹훅은 유실되고 폴링은 늦는다 — 둘 다 있어야 하고, 그래서 멱등이 필수다.

---

## 9단계 — 신고·차단·제재

**목표**: 안전 도구. 구 기획과 가장 크게 갈라지는 지점이 여기다 — **신고해도 아무도 세션에서 나가지 않는다**(★D6). 대신 서로 다시 만나지 않게 하고, 실제 조치는 관리자 판단 뒤에 온다.

### 만들 것
- **엔드포인트**: RP-1 신고 생성 · AD-1 신고 목록 · AD-2 신고 상세 · AD-3 신고 처리 · AD-4 제재 적용
- **배치 없음**: 구 B3(SLA overdue 마킹)는 폐지됐다. `overdue`가 조회 시점 파생이라 마킹할 컬럼이 없다
- **테이블**: `report_case`, `report`, `report_history`, `match_block`(쓰기 경로), `sanction`(쓰기 경로 — 조회는 1단계 완료)
- **enum**: `ReportTargetType`(MEMBER/SESSION), `ReportReasonCode` 5종, `ReportSeverity`, `ReportStatus`, `SanctionType`
- 관리자 계정은 DB 수동 UPDATE로 생성(1단계 결정 승계)

작업 순서:
1. Repository 4종 + `ReportService`에 RP-1 — 자격 검사 → 케이스 병합 또는 생성 → **`match_block` 양방향 2행 INSERT**
2. `SanctionService` — 제재 적용 단일 메서드: ①`sanction` INSERT ②진행 세션 있으면 강제 퇴장(LiveKit 포함) ③활성 매칭 요청 CANCELLED(2단계 3종 세트 재사용). AD-3와 AD-4가 같은 메서드를 호출해야 하므로 콘솔보다 먼저
3. `ReportAdminController` — AD-1(필터·페이징)·AD-2(대상 도출)·AD-3(PENDING만, SANCTIONED면 2 호출, REJECTED면 신고자 `restriction_review`)·AD-4. AD-1의 `?overdue=true`는 배치 결과를 읽는 것이 아니라 `status` 미종결 AND `sla_due_at < now`를 조회 조건으로 건다

### 이 단계의 함정
- **신고 시 아무도 나가지 않는다**(★D6 — 구 기획의 "신고자 즉시 퇴장"은 폐기). 상호 비노출은 클라이언트가 처리하고, 서버는 `match_block` 양방향 등재로 재매칭을 영구 차단한다. 신고 즉시 누군가를 내보내는 설계는 "아무나 신고해서 남을 쫓아내는" 악용이 성립한다.
- **`match_block`은 반드시 2행이다.** 한 방향만 넣으면 신고자가 대기열에 먼저 서 있을 때 피신고자가 그 방에 들어간다. 배제는 대기열 조회 양쪽에서 걸려야 한다.
- **2단계에 넣어 둔 `match_block` 배제 코드를 여기서 처음 실측한다.** 코드 추가 없이 게이트만.
- **병합 시 severity 상향이면 `sla_due_at`을 재계산한다.** NORMAL 케이스에 HIGH 신고가 합류했는데 72h SLA가 그대로면 고위험 24h 약속이 깨진다. 병합 감지는 `uk_rc_open`(대상당 PENDING 1건)으로.
- **AD-3는 PENDING만, 재오픈 불가.** 종결 시 `open_target_id=NULL` — 재오픈을 허용하면 `uk_rc_open` 충돌 경로가 되살아난다. 재검토는 새 케이스다.
- **RP-1은 연령 게이트를 건너뛴다**(인터셉터 SKIP_RULES에 이미 있음). 미성년이 유해물을 보고도 신고 못 하는 상태를 만들지 않는다.
- **제재의 세션 강제 퇴장은 LiveKit API 호출까지 포함한다.** DB만 EVICTED로 바꾸면 방에는 그대로 남아 있다.
- `targetType=SESSION` 신고는 특정 대상자 없이 세션 전체를 대상으로 한다 — `match_block` 등재 없음.
- **`overdue`는 컬럼이 아니라 조회 조건이다.** 배치가 상태를 미리 찍어 두는 방식은 배치가 늦거나 죽으면 SLA를 넘긴 케이스가 큐에서 안 보인다 — 안전 도구에서 가장 위험한 실패 방식이다. 파생 계산은 조회할 때마다 현재 시각으로 판정하므로 그 구멍이 없다. 이의(10단계)도 같은 규칙이다.
- **`restriction_review`는 파생이 아니라 저장 컬럼이다.** 관리자가 AD-3에서 기각할 때 찍는 값이라 시각 계산으로 도출할 수 없다. `overdue`와 헷갈리지 않는다 — 구 B3가 이 둘을 함께 건드려서 한 덩어리로 보이지만, 성격이 다르다.

### 완료 게이트
1. A가 B를 신고 → 201 `caseId`. **A·B 모두 세션에 그대로 있음**(SS-1 확인) + `match_block` 2행
2. C가 같은 대상 신고 → 같은 caseId 병합. HIGH 사유면 severity 상향 + `slaDueAt` 단축(AD-2)
3. A 재신고 → 409 `DUPLICATE_REPORT`
4. **A와 B가 같은 조건으로 매칭 대기 → 서로 같은 세션에 묶이지 않음**(2단계 배제 코드 실측). 6인이 안 차면 만료되는지 확인
5. DEV-2로 SLA 경과 → **배치 없이 곧바로** AD-1 `?overdue=true`에 노출(파생 계산 확인). 시각을 되돌리면 목록에서 빠지는지도 확인 — 저장 컬럼이 남아 있으면 이 왕복에서 드러난다
6. AD-3 SANCTIONED(TEMP 7일) → 대상 로그인 API 403 `MEMBER_SANCTIONED` + 진행 세션 강제 퇴장 + 활성 매칭 요청 CANCELLED
7. AD-3 REJECTED → 신고자 `restriction_review=1`
8. 참여자 토큰으로 AD-1~AD-4 각각 → 403 `FORBIDDEN_ROLE`
9. 미성년 토큰으로 RP-1 → 201(연령 게이트 skip 확인)

Q5 확정 시 재실측: 신고 시 세션 처리 정책.

### 강의 포인트
1. **악용 방지 설계.** "누구를 보호하는 장치인가"를 상태 머신에 새기는 법 — 신고가 즉시 조치로 이어지면 신고 자체가 무기가 된다.
2. **양방향 데이터의 필요성.** 차단이 왜 대칭이어야 하는지 — 조회하는 쪽이 둘이기 때문이다.
3. **저장할 값과 계산할 값의 구분.** `sla_due_at`(마감 시각)은 저장하고 `overdue`(넘겼는가)는 계산한다 — 전자는 접수 시점에 결정돼 변하지 않고, 후자는 시각이 흐르면 저절로 바뀐다. 시간이 지나면 답이 바뀌는 값을 컬럼에 박아 두면 그 값을 갱신할 배치가 필요해지고, 그 배치가 멈추면 값이 조용히 틀린다.

---

## 10단계 — 관리자 콘솔·이의

**목표**: 퇴출당한 사람이 이의를 제기하고 관리자가 되돌릴 수 있는 경로. **인용 시 포인트 원복(역분개)과 완주 소급 재판정**이 이 단계의 난도다 — 이미 확정된 결과를 되돌리는 유일한 지점이다.

### 만들 것
- **엔드포인트**: AP-1 이의 신청 · AD-5 이의 큐 · AD-6 이의 처리 · AD-7 진행 중 세션 모니터 · AD-8 탈퇴 처리 결과 · AD-9 이의 심사 상세
  (AD-8은 조회 전용이라 파기 배치 B4보다 먼저 놓을 수 있다. 오히려 순서가 이쪽이 맞다 —
   **B4가 조용히 멈춘 것을 확인할 창구 없이 파기 배치부터 켜면 늦은 파기가 드러나지 않는다.**
   11단계에서 B4가 붙으면 이 화면의 `deletedAt`이 채워지기 시작한다)
- **테이블**: `appeal_case`
- **enum**: `AppealStatus`, `PointReason.APPEAL_REFUND`
- **배치 없음**: 이의의 `overdue`도 조회 시점 파생(미종결 AND `sla_due_at < now`)이다. AD-5의 `?overdue=true`는 9단계 AD-1과 같은 방식의 조회 조건으로 처리한다

### 이 단계의 함정
- **AP-1은 본인·1회만.** `eviction_id` UNIQUE가 방어선 → 409 `APPEAL_ALREADY_FILED`. 요청 본문의 `reason_text`는 200자 제한이고 필수다 — 사유 없는 이의는 관리자가 판단할 근거가 없다.
- **AP-1은 게이트 ④(제재)를 건너뛴다**(§0-3 SKIP_RULES). 퇴출 이의는 잘못된 퇴출을 되돌리는 유일한 수단인데(NFR-402), 별개의 제재가 걸려 있으면 구제 경로까지 함께 닫혀 3일짜리 이의 기한이 그대로 지나간다. AU-5(탈퇴 철회)를 제재 중에도 열어 두는 것과 같은 논리다. **연령 게이트 ⑤는 그대로 건다** — 퇴출당했다는 것은 이미 세션에 들어갔다는 뜻이라 연령 확인을 통과한 회원이다.
- **타인의 퇴출과 존재하지 않는 퇴출을 같은 403 `FORBIDDEN`으로 응답한다.** 404를 주면 evictionId를 훑어 "그 번호의 퇴출이 실재한다"를 알아낼 수 있고, 퇴출 사실은 그 자체로 민감하다. 7단계 SR-5(없는 주문 404·타인 주문 403)와 방향이 반대로 보이지만 원칙은 같다 — **응답 두 종류가 갈리는 지점을 만들지 않는다**는 것이 핵심이고, 어느 쪽으로 통일하느냐는 그 자원의 기본 응답을 따른다. `APPEAL_NOT_FOUND`는 AD-6(관리자가 없는 이의를 처리)에서만 쓴다.
- **`overdue`는 컬럼이 아니라 조회 조건이다**(api-spec AD-1·§5 '구 B3 폐지'). 미종결 AND `sla_due_at < now`를 그때그때 계산한다. **신고(9단계)와 같은 규칙이므로 판정식을 공용 헬퍼로 뽑아 두 콘솔이 같은 코드를 쓰게 한다** — 한쪽만 고쳐 두 큐의 SLA 판정이 갈리는 것이 이 구조에서 가장 흔한 사고다.
- **인용 시 원복은 3종 세트다**: ①`eviction.revoked_at` 기록(행을 지우지 않는다 — 감사 기록이다) ②포인트 역분개(`APPEAL_REFUND` +300, 기존 -300 행은 그대로 둔다) ③해당일 완주 소급 재판정(D1 기준으로 다시 판정 → 완주면 `streak_day` INSERT + 완주 포인트 지급).
- **역분개이지 삭제가 아니다.** 원장에서 행을 지우면 `balance_after` 연쇄가 깨지고 무슨 일이 있었는지 사라진다.
- **소급 완주가 Streak 연속을 되살릴 수 있다.** 그날이 채워지면 이후 날짜의 연속 계산이 바뀐다 — `current_streak` 재계산이 필요하다. 이 재계산을 빠뜨리면 이의가 인용돼도 Streak는 끊긴 채로 남는다.
- **소급 지급도 멱등해야 한다.** `(memberId, SESSION_COMPLETE, PARTICIPANT, participantId)` UNIQUE가 이미 있으므로 B1이 나중에 다시 와도 이중 지급되지 않는다. 원복분 `APPEAL_REFUND`의 ref는 `eviction.id`라 패널티 행과 키가 겹치지 않는다.
- **되돌릴 차감이 아직 없을 수 있다.** 퇴출 -300을 원장에 넣는 주체는 5단계에서 B1로 일원화했으므로 퇴출과 차감 사이에 최대 1분의 틈이 있다. 그 틈에 인용하면 빠져나간 적 없는 300이 들어오고, `revoked_at`이 채워진 뒤로는 B1이 그 퇴출을 차감 후보에서 빼므로 차감은 영영 오지 않는다 — **역분개는 기존 기록을 뒤집는 것이지 새 지급이 아니다.** 원장에 패널티 행이 있는지를 보고 환급 여부를 정한다.
- **Streak 재계산은 증분이 아니다.** 되살린 날이 `last_completed_on`보다 과거이면(8일을 되살렸는데 9·10일 완주가 이미 있는 경우) 증분 갱신은 그 날짜를 무시하고 끝나 `streak_day`에는 행이 생겼는데 연속은 끊긴 채로 남는다. 그렇다고 오늘부터 다시 세면 `last_completed_on`이 과거로 밀려 **되살린 하루가 오히려 연속을 깎는다.** 완주일 전체를 읽어 가장 최근 완주일부터 거꾸로 세는 것이 유일하게 맞는 계산이다.
- **AD-7 세션 모니터는 조회 전용.** 관리자가 세션에 개입하는 경로(강제 종료)는 v1 범위 밖이다.
- **SLA 24h/72h는 신고와 같은 규칙**을 쓴다(`morak.report.sla-hours`).

### 완료 게이트
1. 퇴출자 AP-1 → 201 PENDING + `reason_text`·`created_at` 기록, 재신청 → 409 `APPEAL_ALREADY_FILED`
2. **타인의 퇴출·없는 evictionId 모두 403 `FORBIDDEN`**(존재 비노출) / 201자 사유 → 400 `VALIDATION_FAILED`
3. AD-5 → 이의 큐 목록 + `slaDueAt`. DEV-2로 SLA 경과 → **배치 없이 곧바로** `?overdue=true` 필터에 노출(파생 계산 확인)
4. AD-6 인용 → `eviction.revoked_at` 기록 + `point_ledger` `APPEAL_REFUND` +300 + 완주 소급 시 `streak_day` INSERT + `SESSION_COMPLETE` 지급
5. 인용 후 PT-1 잔액 = 퇴출 전 잔액. `SUM(delta)` = `point_balance`
6. 인용 후 AU-2의 `streak.current`가 재계산 반영
7. AD-6 기각 → 상태 REJECTED, 포인트·Streak 불변
8. AD-7 → 진행 중 세션 목록 + 참가자·경고 현황
9. 참여자 토큰으로 AD-5~AD-7·AD-9 → 403 `FORBIDDEN_ROLE`
10. AD-9 → `reasonText` + 경고별 근거 구간·보고 지연·동시 보고 집계. 타 참가자 식별자 비노출

### 강의 포인트
1. **역분개(reversing entry).** 회계에서 온 개념 — 잘못된 기록을 지우지 않고 반대 기록을 더한다. 감사 가능성과 정정을 동시에 얻는 방법이다.
2. **소급 처리의 연쇄.** 과거 하루를 바꾸면 그 이후 계산이 전부 흔들린다. 어디까지 다시 계산해야 하는지를 정하는 것이 설계다.
3. **이미 멱등한 시스템의 이점.** 소급 지급을 특별 취급하지 않아도 되는 이유 — 5단계에서 제약을 제대로 걸어 뒀기 때문이다.

---

## 11단계 — 탈퇴 완결

**목표**: 유예 만료 회원의 익명화 삭제와 관리자 탈퇴 콘솔. AU-4·AU-5는 1단계에서 이미 구현됐고, 여기서는 배치와 동반 처리 완결·재실측만 한다.

### 만들 것
- **엔드포인트**: AD-8 탈퇴 처리 결과 목록
- **배치**: B4 탈퇴 계정 파기(30일)
- **테이블**: `blocked_social_hash` 쓰기 경로(조회는 1단계 완료)

B4 대상: `delete_scheduled_at < now`인 WITHDRAW_PENDING. 처리 내용은 익명화 + DELETED 전이 + 동반 3종:
①PERMANENT 제재 이력자만 `blocked_social_hash` 등재 ②`media_consent` 삭제 ③진행 중 세션 참가 LEFT(WITHDRAWAL).

동반 삭제 대상은 이 셋이 전부가 아니다. **지우는 테이블과 남기는 테이블의 목록은 db-schema '파기·보존 정책' 표가 정본이고**(`member_agreement`·`member_goal`·`streak_day`·`match_request`·`match_block`·`match_lock`·`absence_event`·`warning` 삭제, `session_participant.goal_text`만 비움), 여기 셋은 그 표에서 다른 단계와 얽히는 항목만 따로 적은 것이다.

### 이 단계의 함정
- **익명화는 NULL 세팅이 아니라 치환이다**: `provider_user_id='deleted:{id}'`, 닉네임 '탈퇴회원', `birth_date`만 NULL. NOT NULL 제약과 `uk_member_provider`(같은 소셜 계정 재가입) 때문에 값 치환이어야 한다.
- **`blocked_social_hash` 등재는 PERMANENT 제재 이력자만.** TEMP까지 등재하면 일시 제재가 영구 재가입 차단으로 승격된다. 해시는 HMAC(pepper=환경변수) — pepper 없는 sha256은 역산 가능하다.
- **커머스 기록은 파기 예외 대상이다**(전자상거래법 보존 의무). `store_order`·`point_charge`는 회원이 익명화돼도 남긴다. 보존 기간 값은 팀 확정 대기 — 우선 삭제하지 않는 것으로 진행하고 확정 시 별도 파기 배치를 검토한다.
- **`point_ledger`는 사유를 가리지 않고 통째로 남긴다.** 커머스 기록과 연결된 원장을 지우면 주문의 근거가 사라지고, 일부만 남기면 남은 줄의 `balance_after`가 실제 잔액과 어긋난다. 이 결정 때문에 `member.point_balance`도 0으로 덮지 않는다(db-schema '파기·보존 정책' 개정 근거).
- **재로그인 복구(RESTORED)는 1단계 AU-1에 이미 있다.** B4가 먼저 지나갔으면 복구 불가(DELETED는 종점)라는 경계 실측이 이 단계 몫이다.
- **세션 영상은 저장하지 않으므로**(D17) 미디어 파기 배치가 없다. 구 B5는 소멸했다.

### 완료 게이트
1. AU-4 → 202 + MT-1 → 403 `WITHDRAWAL_PENDING` / 재로그인 → `loginResult=RESTORED` + 시각 컬럼 NULL
2. 신청 후 DEV-2 +31일 → B4 트리거 → H2 콘솔: `provider_user_id='deleted:{id}'`·`birth_date` NULL·`media_consent` 0행
3. 같은 회원의 `store_order`·`point_charge`·`point_ledger` 행 잔존 확인(파기 예외)
4. PERMANENT 제재 이력자 탈퇴·B4 후 같은 계정 로그인 → 403 `REJOIN_BLOCKED` / 제재 없던 회원은 신규 가입 성공
5. B4 재트리거 → 이미 DELETED인 회원 재처리 없음(멱등)
6. AD-8 → 처리 이력 목록 200(관리자), 참여자 403
7. 진행 중 세션이 있는 회원 탈퇴 → 참가 LEFT(WITHDRAWAL)

커머스 보존 기간 확정 시 재실측: 파기 예외 범위.

### 강의 포인트
1. **개인정보 삭제 설계** — "지운다"가 컬럼 NULL이 아닌 이유(제약·재가입·감사가 얽힌다).
2. **법적 보존 의무와 삭제 요구의 충돌.** 둘 다 법이라 한쪽을 고르는 것이 아니라 대상을 나눠야 한다.
3. **소급 불가능한 작업의 게이트.** 삭제 배치는 되돌릴 수 없으므로 dev에서 조건을 전부 실측한 뒤 운영에 올린다.

---

## 12단계 — 운영 준비

**목표**: dev 대체물(소셜·DB·LiveKit 테스트 환경)을 실물로 교체하고 배포한다. 응답 계약은 하나도 바뀌지 않는다.

### 만들 것
- **엔드포인트**: 신규 없음. AU-1 카카오 실연동
- **외부 의존**: 카카오 앱 키, LiveKit 운영 프로젝트, MySQL 인스턴스, PG 운영 키(또는 테스트 유지), 배포 대상

작업 순서:
1. `auth/client/KakaoSocialClient` — `SocialClient`의 두 번째 구현. `DevSocialClient`는 dev 프로필에 남는다
2. LiveKit 운영 환경 전환 + 웹훅 URL 등록 + 실기기 e2e
3. MySQL 전환: 운영 프로필 datasource + **`ddl-auto=update` 금지, `db-schema.md`의 DDL 스크립트로 생성** + UNIQUE 제약 실생성 확인
4. **동시성 재실측**: 2단계 게이트 2(6스레드 동시 매칭)를 MySQL에서 재실행
5. CORS 설정, 운영 프로필 검증, 배포

**재기동 내성 이월 3건** (3차 검증 B의 실측 발견. 전부 12단계 몫으로 미룬 것이라 여기 등재한다)

1. **재접속 유예 창을 DB로 내린다.** `session_participant`에 끊긴 시각 컬럼(`disconnected_at` 등)을 추가하고 `ReconnectGraceRegistry`의 메모리 맵을 대체한다. 지금 구조의 두 결함을 한 번에 없앤다 — ① 유예 도중 재기동하면 창이 사라져 끊긴 참가자가 `ACTIVE`로 남고 세션 종료 시 완주로 계산된다(실측: 5명이 유령으로 남아 예정 종료까지 유지되고 전원 완주 지급, 조기 종료 판정도 인원이 남은 것으로 보여 작동하지 않았다) ② 인스턴스가 둘이면 웹훅을 받은 쪽만 타이머를 쥐어 다른 쪽이 받은 재접속이 유예를 닫지 못한다. **`db-schema.md` 개정이 동반된다** — 컬럼 추가이므로 테이블 수는 그대로지만 `session_participant` 정의와 개발 주의 절의 실측 수치가 함께 바뀐다. 이 작업 전까지 v1은 단일 인스턴스 전제다.
2. **종료 루틴의 다중 인스턴스 중복 실행을 막는다.** B1을 비롯한 `@Scheduled` 배치가 인스턴스마다 돌면 같은 세션의 종료 루틴이 동시에 두 번 들어온다. 지급·완주일·사후 정산 경고는 UNIQUE(`uk_pl_dedup`·`uk_streak_day`·`uk_warning`)가 막아 결과는 어긋나지 않지만, 매분 제약 위반 예외가 쌓여 진짜 장애를 덮는다. 스케줄러 락(ShedLock 류 또는 DB 기반 리더 선출)을 넣어 한 인스턴스만 돌게 한다. 1번과 함께 해야 수평 확장이 열린다.
3. **`point_charge`의 시각 정합을 방어한다.** `approved_at`이 `created_at`보다 앞서는 행을 막는 장치가 없다. 승인 시각은 PG가 준 값을 그대로 쓰는데, 외부 시계가 우리보다 뒤처져 있으면 "승인이 생성보다 먼저"인 행이 원장에 남아 정산 조회의 기간 필터가 그 건을 놓친다. `approved_at >= created_at` 검증을 승인 경로에 넣고, MySQL 전환 시 CHECK 제약으로도 받친다.

### 이 단계의 함정
- **시크릿 폴백 금지는 1단계에서 확보됐다.** 여기서는 실측만 한다: 운영 프로필 + 환경변수 미설정 → **기동 실패**가 정답이다. **필수 6종**: `MORAK_JWT_SECRET`·`MORAK_SOCIAL_HASH_PEPPER`·`MORAK_LIVEKIT_HOST`·`MORAK_LIVEKIT_API_KEY`·`MORAK_LIVEKIT_API_SECRET`·`MORAK_PG_SECRET_KEY`. 1단계의 3종에서 media-token 1종이 빠지고 LiveKit·PG 4종이 들어온 결과다 — 피벗으로 외부 의존이 늘었으니 기동 전제도 늘었다.
- **dev 이중 스위치**: 운영 프로필은 `@Profile("dev")` 빈 자체가 없고 `morak.dev.enabled=false`(base 기본값). 하나만 믿지 않는다.
- **MySQL `DECIMAL` 반올림·`utf8mb4` 인덱스 상한(191)은 db-schema.md에 반영돼 있다** — ddl-auto가 아니라 스크립트를 쓰는 이유다.
- **H2와 MySQL의 잠금 구현이 다르다.** 2단계 동시성 게이트를 반드시 다시 돌린다.
- 카카오 검증 실패는 401 `INVALID_SOCIAL_TOKEN` 그대로 — `DevSocialClient`가 빈 코드로 이 경로를 재현하게 만들어 둔 것이 여기서 회귀 테스트가 된다.
- **지금 운영 프로필은 뜨지 않는다.** `SocialClient`·`PgClient`의 구현이 `DevSocialClient`·`DevPgClient`뿐이고 둘 다 `@Profile("dev")`라, prod에서는 `AuthService`가 `No qualifying bean of type 'SocialClient'`로 죽는다(11단계 시점 실측). 작업 순서 1(`KakaoSocialClient`)과 PG 실구현이 들어와야 비로소 기동 자체가 가능하다 — 위 안전장치 실측은 이 두 자리에 임시 스텁을 끼워 측정한 것이고, 그 스텁은 저장소에 없다.
- **MySQL 드라이버 의존성이 `build.gradle`에 없다.** `runtimeOnly 'com.mysql:mysql-connector-j'`를 넣기 전에는 `MORAK_DB_URL`을 MySQL로 바꿔도 드라이버를 못 찾는다. H2는 `runtimeOnly`로 들어가 있어 개발에서는 드러나지 않는다.

### 완료 게이트
1. 카카오 실계정 e2e: 로그인 → 목표 설정 → 매칭 → 세션 입장 → 완주 → 포인트 1회전
2. 실기기 2대 이상으로 LiveKit 세션 입장·자리비움 경고·Pause 실측
3. 운영 프로필 기동: 환경변수 미설정 → 기동 실패 실측 / 설정 후 기동 → `/api/dev/clock`·`/api/dev/batches/B1`·`/api/dev/sessions/seed` 각각 404 — **아래 실측표로 통과**(단, 실구현이 아닌 스텁 위에서 측정했으므로 `KakaoSocialClient` 투입 후 1회 재확인)
4. MySQL 6스레드 동시 매칭 → 정확 6인(2단계 게이트 재실행)
5. 전 배치(B1·B2·B4·B5) MySQL에서 1회씩 트리거 → 멱등 확인
6. MySQL 스키마 생성 후 테이블 24·UNIQUE 17을 이름으로 대조 — H2 실측치와 같은지 확인한다. 다르면 그 자리가 곧 개발/운영이 갈리는 지점이다

### 강의 포인트
1. **개발/운영 환경 차이** — H2 통과가 MySQL 통과를 보장하지 않는 이유(잠금·격리 수준 구현 차이).
2. **인터페이스를 만들어 둔 자리가 회수되는 순간.** `SocialClient`가 왜 CLAUDE.md §4-1의 "구현이 실제로 2개인 경우"에 해당하는지가 여기서 증명된다.
3. **폴백을 두지 않은 값만 기동을 막는다.** `@Value("${...}")`는 값이 없으면 컨텍스트가 죽지만, `@ConfigurationProperties`로 바인딩되는 `spring.datasource.*`는 미해석 플레이스홀더를 문자열 그대로 통과시킨다. 같은 `${...}` 문법인데 실패 시점과 메시지가 다르다 — 아래 실측표가 그 차이다.
4. **`ddl-auto=validate`가 지켜 주지 않는 것.** 컬럼 유무는 잡지만 UNIQUE 제약과 인덱스는 보지 않는다. 멱등과 중복 지급 방어를 UNIQUE에 걸어 둔 설계에서는, validate 통과가 곧 안전이 아니다.

### 운영 프로필 안전장치 실측 (2026-08-13, 로컬)

운영 MySQL이 아직 없어 **파일 H2를 운영 데이터소스로 가장해** 측정했다. 환경변수 해석·프로필별 빈 등록·404 응답은 DB 종류와 무관하므로 그대로 유효하다. 반면 마지막 두 줄(`ddl-auto=validate` 동작)과 스키마 수치는 H2 기준이라 MySQL 전환 때 다시 확인해야 한다.

| 확인 | 결과 |
| --- | --- |
| 환경변수 6종 각각을 하나씩 제거 후 기동 | 6종 모두 기동 실패. `Could not resolve placeholder '<이름>'` — 폴백이 없음이 실측으로 확인됐다 |
| 6종 전부 채우고 기동 | 정상 기동 |
| `POST /api/dev/clock`·`GET /api/dev/clock` | 404 `ENDPOINT_NOT_FOUND` |
| `POST /api/dev/batches/B1`·`B2`·`B4` | 각각 404 `ENDPOINT_NOT_FOUND` |
| `POST /api/dev/sessions/seed` | 404 `ENDPOINT_NOT_FOUND` |
| `GET /h2-console`·`/h2-console/` | 404. 콘솔이 매핑 자체를 갖지 않는다 |
| 404 응답 본문 | 전부 우리 규격 — `{"error":{"code":"ENDPOINT_NOT_FOUND","message":"존재하지 않는 경로입니다.","details":null}}` |
| 대조: `GET /api/members/me` | 401 `UNAUTHORIZED`. 전 경로가 404인 게 아니라 dev 경로만 사라진 것이다 |
| `ddl-auto=validate` + 컬럼 1개 제거 | 기동 실패. `Schema validation: missing column [session_id] in table [streak_day]` |
| `ddl-auto=validate` + `uk_streak_day` 제거 | **정상 기동.** validate는 UNIQUE 제약을 보지 않는다 |

스키마 실측(H2, `ddl-auto=update` 생성): 테이블 24 · PK 24 · UNIQUE 17 · **FK 0** · 인덱스 항목 60(그중 명명 인덱스 `idx_*` 19종). DEFAULT 절이 붙은 컬럼 0개, enum 컬럼은 네이티브 `ENUM`. `db-schema.md` 개발 주의 절의 서술과 일치한다.

인덱스 항목이 57에서 60으로 는 것은 `e6c8e99`에서 배치 스캔용 3종(`idx_sp_award`·`idx_ev_revoked`·`idx_pc_expire`)을 추가했기 때문이다.

`MORAK_DB_URL`·`MORAK_DB_USERNAME`·`MORAK_DB_PASSWORD`도 없으면 기동은 실패하지만 **원인 메시지가 다르다** — 각각 `'url' must start with "jdbc"`, `Wrong user name or password`다. 미해석 플레이스홀더가 문자열로 흘러 들어가 뒤늦게 터지기 때문이다. 배포 중 이 메시지를 보면 DB 문제가 아니라 환경변수 누락을 먼저 의심한다.

### 배포 전 확인표

> **예행 권고(최종검증 리서치)**: 이 표를 12단계 당일에 처음 여는 것이 최악이다. MySQL 8 컨테이너로
> `ddl-auto=validate` 기동을 **미리 한 번** 해 보라 — 네이티브 enum 생성·FK 0개·DEFAULT 절 부재
> 3건은 붙여 봐야만 어긋남이 드러나고, validate는 UNIQUE를 검사하지 않으므로 제약 이름 대조도 그때 함께.

1. **환경변수 6종**을 배포 환경에 등록했는가 — `MORAK_JWT_SECRET`(32자 이상) · `MORAK_SOCIAL_HASH_PEPPER` · `MORAK_LIVEKIT_HOST` · `MORAK_LIVEKIT_API_KEY` · `MORAK_LIVEKIT_API_SECRET` · `MORAK_PG_SECRET_KEY`. DB 3종(`MORAK_DB_URL`·`MORAK_DB_USERNAME`·`MORAK_DB_PASSWORD`)까지 합쳐 9종이다
2. **`MORAK_SOCIAL_HASH_PEPPER`는 한 번 정하면 바꿀 수 없다.** `blocked_social_hash`가 이 pepper로 HMAC을 걸어 저장돼 있어, 값을 바꾸면 기존 차단 해시가 전부 매칭되지 않는다 — 재가입 차단이 조용히 풀린다. 교체하려면 원본 소셜 ID가 필요한데 그건 파기 대상이라 남아 있지 않다
3. **LiveKit 운영 키 발급** + 웹훅 URL 등록. 웹훅은 인증 제외 경로(`POST /api/webhooks/livekit`)이므로 서명 검증이 유일한 방어선이다
4. **PG 키**: 토스 테스트 유지인지 운영 전환인지 확정. 운영 전환 시 웹훅 서명 규약이 우리가 임시로 정한 것과 달라지므로 `PaymentWebhookService`와 `api-spec.md` PY-3 절을 함께 고친다
5. **MySQL 스키마는 `db-schema.md`의 DDL 스크립트로 만든다**(`ddl-auto=update` 금지). 생성 후 UNIQUE 17개를 이름으로 직접 확인한다 — validate는 이걸 잡아 주지 않는다. 특히 `uk_streak_day`(B1 멱등) · `uk_pl_dedup`(중복 지급) · `uk_pc_tid`(충전 중복) · `uk_mr_active`(대기 1건)
6. **FK를 실제로 걸지 결정한다.** 개발 스키마에는 0개이고 참조 무결성은 애플리케이션이 지킨다. 운영에 거는 쪽을 택하면 11단계 파기 배치의 삭제 순서가 FK 순서에 묶인다
7. **`RemoveParticipant` 미구현 2곳**: `EvictionService`(TODO 주석 있음)와 `SessionExitService`의 LEFT 경로(주석 없음). 서버는 상태만 바꾸고 LiveKit 방에서 실제로 내보내지 않는다 — 퇴출된 참가자의 미디어가 계속 흐른다
8. **커머스 데이터 보존 연한을 확정한다.** 11단계 파기 배치가 이 값을 기준으로 지운다. 전자상거래법 기준을 팀이 확인해 넣는다
9. `SPRING_PROFILES_ACTIVE=prod`가 실제로 적용됐는지 기동 로그로 확인 — 프로필이 빠지면 base의 `default: dev`가 먹어 dev로 뜬다

---

## 컷 라인 (일정 압박 시 — 명세 위반 표기 필수)

버리는 순서: ①AD-7 세션 모니터 → ②AD-8 → ③카카오 외 소셜 3종 → ④8단계 PG 결제 전체(포인트 유입을 완주 보상만으로 한정 — 스토어는 남는다).

**못 버리는 것**: 2단계 매칭 / 3·4단계 세션·경고·퇴출 / 5단계 완주·포인트 / 9단계 신고·제재 / 11단계 B4(법적) / 1.5단계 연령 차단·약관(법적).

## 우선순위

①매칭 엔진(2) — 동시성 최고 난도, 모든 단계의 데이터 원천
②세션·경고·퇴출(3·4) — 서비스 신뢰의 심장. 판정을 서버가 한다는 원칙이 여기서 지켜지거나 무너진다
③완주·포인트(5·6) — 원장 제약이 이후 모든 지급의 방어선
나머지는 표준 CRUD + 배치다.
