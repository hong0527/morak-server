# 프론트엔드 연동 가이드 (2026-08-15)

프론트가 이 문서 하나로 화면을 만들 수 있게 쓴 문서다. 계약의 세부는 `api-spec.md`가
정본이고, 이 문서는 **어느 화면에서 무엇을 언제 부르는지**와 **처음 붙일 때 걸려 넘어지는
것들**을 모았다.

이 문서에 실린 응답은 명세의 예시가 아니라 **실제로 서버를 띄워 받은 응답**이다
(2026-08-15, dev 프로필, 포트 8120).

## 0. 문서 관계

| 문서 | 무엇 |
|---|---|
| `api-spec.md` | 계약 정본. 번호가 붙은 오퍼레이션 42개의 요청·응답·에러 전부 |
| `openapi.yaml` | 위와 같은 내용의 기계 판독본. 코드 생성·목 서버에 쓴다. 개발 전용 4개를 더해 46개 |
| `db-schema.md` | 테이블 24개. 프론트가 직접 볼 일은 없지만 필드 의미가 궁금할 때 |
| `frontend-guide.md` | **이 문서.** 화면 단위 사용법 |
| `frontend-walkthrough.sh` | 로그인부터 세션 완주까지 순서대로 부르는 실행 스크립트 |
| `ai-pipeline.md` | 자리비움 감지. 카메라 담당자용 |

**`screen-api-map.md`와 `frontend-change-requests.md`는 보지 않는다.** 사진 인증 기획
시절의 구판이고 문서 안에 무효 표시가 붙어 있다. 화면 관련 내용은 이 문서로 대체한다.

## 1. 5분 안에 서버 확인하기

```bash
./gradlew bootRun --args='--server.port=8120'   # 터미널 1
./docs/frontend-walkthrough.sh                  # 터미널 2
```

스크립트가 계정 6개를 만들어 매칭을 성사시키고, 세션을 진행했다가 시계를 앞으로 당겨
완주 결과까지 받아 온다. 각 단계의 요청과 응답이 그대로 출력되므로, 화면에서 쓸 필드
이름과 형태를 여기서 가져가면 된다.

dev 프로필에서만 동작한다. 개발용 소셜 로그인과 `/api/dev/**`(시계 조작·배치 트리거)가
운영에서는 아예 등록되지 않는다.

## 2. 붙이기 전에 알아야 할 여덟 가지

여기저기 흩어져 있는 것 중 모르면 반드시 한 번은 삽질하는 것들이다.

### 2-1. 서버는 아무것도 밀어 주지 않는다

SSE도 WebSocket도 없다. 서버가 먼저 말을 거는 경로는 존재하지 않는다.
상태 변화를 아는 방법은 두 가지뿐이다.

- **매칭 성사** — `GET /api/match-requests/me` 폴링. 앱을 껐다 켠 경우라면 홈의
  `GET /api/members/me`가 주는 `activeSession`으로도 세션 번호를 되찾을 수 있다(§S4).
- **내 경고·퇴출** — **세션이 진행되는 동안에는** 내가 부른 요청의 응답에 실려 온다.
  진행 중 경고를 만드는 경로는 SS-4(자리비움 보고)·SS-5(화장실 모드 시작)·SS-6(복귀)
  셋뿐이고, 셋 다 그 호출자에게 동기 응답으로 돌려준다. 따로 물어볼 API가 없다.

**단, 네 번째 경로가 있다.** 세션 종료 정산(B1 배치)이 미결 상태를 마저 판정한다 —
돌아오지 않은 화장실 모드와 짝이 없는 `START`를 종료 시각 기준으로 닫으면서 경고를 붙이고,
그것이 3회째면 그 자리에서 퇴출·포인트 차감까지 한다.

**이 경고와 퇴출은 어떤 요청의 응답에도 실리지 않는다.** 요청이 없는 시점에 서버 혼자
일어난 일이기 때문이다. 사용자는 **결과 화면(SS-8)에서 처음 알게 된다.** 그래서 결과 화면은
"완주 축하"만 있는 화면이 아니라 퇴출 통지도 겸해야 하고, `my.evictionId`가 채워져 있으면
이의 신청 경로를 함께 띄워야 한다. 이의 기한 3일은 그 시점부터 이미 흐르고 있다.

LiveKit 연결은 별개다. 다른 참가자의 캠 영상과 스티커는 LiveKit 데이터 채널로 오간다.
서버는 스티커를 저장하지도 중계하지도 않는다.

### 2-2. 세션이 정시에 끝나도 결과는 최대 1분 뒤에 나온다

정산은 매분 :00에 도는 배치가 한다. `endsAt`이 지나도 배치가 돌기 전까지 세션은 `LIVE`로
남고, 그동안 `GET /api/sessions/{id}/result`는 **409 `SESSION_NOT_ENDED`**를 돌려준다.

**이 409는 오류가 아니다.** "아직 정산 전"이라는 뜻이므로 오류 화면을 띄우면 안 된다.
몇 초 간격으로 다시 부르고, 90초를 넘겨도 계속 409면 그때가 이상 상황이다.

실측(스크립트 9단계)에서 `endsAt`을 65분 넘긴 시점에도 배치 전에는 409였고, 배치가 돈
직후 200이 나왔다.

`CANCELLED` 세션도 같은 409를 돌려준다. 메시지가 "아직 진행 중인 세션입니다"라 오해하기
쉬운데, 코드가 `ENDED`가 아닌 모든 상태를 한 갈래로 묶기 때문이다.

### 2-3. 시각은 언제나 초 단위 고정폭이다

응답의 모든 시각은 `yyyy-MM-dd'T'HH:mm:ssXXX`다.

```
2026-08-15T18:34:37+09:00
2026-08-14T00:00:00+09:00     자정도 줄이지 않는다
```

소수점 이하 초는 항상 잘라낸다. 자릿수가 값마다 달라지지 않으므로 **이 패턴 하나로 전
응답을 파싱해도 된다.** 일자만 있는 값은 `YYYY-MM-DD`다.

요청으로 보낼 때는 오프셋을 포함해 보낸다(SS-4의 `occurredAt`). 서버가 자기 타임존으로
환산해 저장한다.

### 2-4. 에러는 항상 같은 모양이다

```json
{"error": {"code": "SESSION_NOT_FOUND", "message": "존재하지 않는 세션입니다.", "details": null}}
```

세 필드가 전부다. `timestamp`·`path`·`status` 같은 필드는 없고, `details` 키는 값이 없어도
`null`로 항상 붙는다.

`code`로 분기하고 `message`는 그대로 보여줘도 되도록 한국어로 쓰여 있다.

**`details`가 채워지는 경우** — 400 `VALIDATION_FAILED`는 `{필드명: 메시지}` 평면 맵을
싣는다. 폼 검증 오류를 필드에 그대로 붙일 수 있다.

```json
{"error": {"code": "VALIDATION_FAILED", "message": "요청 값이 올바르지 않습니다.",
           "details": {"periodDays": "허용값은 7, 14, 30입니다."}}}
```

키는 요청 필드명이다. 본문 자체를 못 읽으면 `{"body": ...}`, 경로·쿼리 파라미터 형변환
실패면 그 파라미터명이 키가 된다.

그 밖에 `details`를 쓰는 곳:

| 코드 | details | 화면이 할 일 |
|---|---|---|
| `MEMBER_SANCTIONED` | `{"endsAt": "..."}` (영구 제재는 `null`) | 제재 해제 시각 안내 |
| `REMATCH_COOLDOWN` | `{"availableAt": "..."}` | 언제부터 다시 매칭 가능한지 |
| `ALREADY_IN_ACTIVE_SESSION` | `{"sessionId": 12}` | 재시도가 아니라 그 세션 화면으로 이동 |
| `DUPLICATE_ORDER` | `{"orderId": 3}` | 새 주문이 아니라 기존 주문 상세로 |

### 2-5. 재시도해도 되는 것과 안 되는 것

| 상태 | 코드 | 재시도 |
|---|---|---|
| 503 | `LOCK_ACQUISITION_FAILED` | **해도 된다.** 경합으로 밀린 것이고 트랜잭션이 통째로 롤백돼 아무것도 바뀌지 않았다. 짧게 쉬고 그대로 다시 |
| 429 | `ABSENCE_RATE_LIMITED` | **해도 된다.** 단 간격을 두고 **같은 `clientSeq`로**. §5 참조 |
| 500 | `INTERNAL_SERVER_ERROR` | **하면 안 된다.** 어디까지 처리됐는지 알 수 없다 |
| 409 | 대부분 | 재시도 의미 없음. 이미 끝난 일이라는 뜻이다 |

`Retry-After` 헤더는 보내지 않는다. 429의 대기 시간은 아래 §5에 적힌 값을 쓴다.

409 중 둘은 "실패"가 아니다. `DUPLICATE_ABSENCE_EVENT`는 재전송이 중복 집계되지 않았다는
뜻이라 정상 완료로 처리하고, `DUPLICATE_ORDER`는 `details.orderId`로 이동하면 된다.

### 2-6. 인증은 Bearer 토큰 하나, 갱신 수단은 없다

```
Authorization: Bearer {accessToken}
```

AU-1 로그인 응답의 `accessToken`을 그대로 쓴다. JWT(HS256), **24시간** 유효하고
**리프레시 토큰이 없다.** 만료되면 401 `TOKEN_EXPIRED`이고 재로그인 말고는 방법이 없다.

401이 왔을 때 `TOKEN_EXPIRED`면 재로그인, 그냥 `UNAUTHORIZED`면 토큰이 없거나 깨진
것이다. 탈퇴 파기가 끝난 계정도 `UNAUTHORIZED`로 떨어진다.

### 2-7. 전역 검문소가 다섯 개 있다

개별 API가 아니라 인터셉터 한 곳에서 순서대로 검사한다. 어떤 화면에서든 아래가 뜰 수 있다.

| 순서 | 검사 | 실패 | 화면이 보낼 곳 |
|---|---|---|---|
| ① | JWT 유효성 | 401 `UNAUTHORIZED` / `TOKEN_EXPIRED` | 로그인 |
| ② | 회원 상태 | 403 `WITHDRAWAL_PENDING` | 탈퇴 철회 안내 |
| ③ | 관리자 역할 (`/api/admin/**`) | 403 `FORBIDDEN_ROLE` | — |
| ④ | 유효 제재 | 403 `MEMBER_SANCTIONED` | 제재 안내(`details.endsAt`) |
| ⑤ | 연령 확인 | 403 `AGE_NOT_VERIFIED` | 생년월일 입력 |

중요한 예외 둘.

- **탈퇴 유예 중(②)에는 조회 API까지 전부 막힌다.** 남는 건 내 정보(AU-2)와 탈퇴 철회뿐이다.
  "조회는 되겠지" 하고 짜면 안 된다.
- **제재 중(④)에도 퇴출 이의(AP-1·AP-2)는 열려 있다.** 잘못된 퇴출의 구제 경로까지 닫으면
  안 되기 때문이다.

### 2-8. 목록 응답은 한 가지 모양이다

```json
{"content": [...], "page": 0, "size": 20, "totalElements": 2, "totalPages": 1}
```

`page`는 0부터. `size`는 기본 20, **최대 50**이고 넘기면 400이다.

단 하나 예외가 있다. PT-1 포인트 조회는 잔액과 내역을 함께 주느라 이 객체가 `ledger` 키
안에 들어간다(`{"balance": ..., "ledger": {"content": [...], ...}}`).

## 3. 화면별 호출 사슬

와이어프레임이 없어 요구사항정의서(`functional-spec.md`)의 영역 구분과 `api-spec.md`의
오퍼레이션 목록을 근거로 그렸다. 화면 분할은 프론트가 조정해도 되고, **호출 순서와 화면
사이에 들고 가는 값**이 이 절의 내용이다.

```
로그인 ─→ [생년월일] ─→ [캠 동의] ─→ 홈 ─→ 매칭 대기 ─→ 세션 ─→ 결과 ─→ 홈
                                      │                      │
                                      ├→ 목표 설정            └→ [이의 신청]
                                      ├→ 스토어 · 충전
                                      └→ 내 기록
```

대괄호는 조건부 화면이다.

### S1. 로그인

`POST /api/auth/login` — 토큰 없이 부르는 유일한 일반 API다.

약관 동의가 별도 화면이 아니라 이 요청에 함께 실린다. 가입 폼이 없기 때문이다.
기존 회원이 다시 로그인할 때도 **필드 자체는 필수**라 빈 배열이라도 보내야 한다.

```json
{"provider": "KAKAO", "authorizationCode": "...",
 "agreements": [{"type": "TOS", "agreed": true},
                {"type": "PRIVACY", "agreed": true},
                {"type": "MARKETING", "agreed": false}]}
```

`TOS`·`PRIVACY`는 필수고 빠지면 400 `AGREEMENT_REQUIRED`, `MARKETING`은 선택이다.
**필수 약관 검사는 신규 가입 경로에서만 돈다.** 기존 회원의 재로그인은 `agreements`가 빈
배열이어도 통과한다 — 그래도 필드 자체는 빼면 안 된다(`@NotNull`).

실측 응답:

```json
{"accessToken": "eyJhbGciOiJIUzI1NiJ9...", "memberId": 1, "isNewMember": true,
 "needsBirthdate": true, "ageVerification": "REQUIRED", "loginResult": "NORMAL"}
```

**다음 화면으로 들고 갈 것** — `accessToken`(이후 전부), `needsBirthdate`.

`needsBirthdate`가 `true`면 S2로, 아니면 홈으로. `ageVerification` enum을 프론트가 해석하지
않아도 되도록 서버가 계산해 내려주는 값이니 이쪽을 쓴다.

`loginResult`는 `NORMAL` 또는 `RESTORED`다. `RESTORED`는 탈퇴 유예 중이던 계정이 로그인으로
되살아난 경우라, "돌아온 것을 환영한다" 류의 안내를 띄울 자리다.

거절 경로: 401 `INVALID_SOCIAL_TOKEN`(소셜 인증 실패) / 403 `REJOIN_BLOCKED`(재가입 차단) /
403 `UNDER_AGE_SIGNUP_BLOCKED`(만 14세 미만) / 401 `UNAUTHORIZED`(파기 시각이 지난 탈퇴 계정).

### S2. 생년월일 — `needsBirthdate=true`일 때만

`POST /api/members/me/birthdate` — `{"birthDate": "2000-03-15"}`

**되돌릴 수 없는 화면이다.** 만 14세 미만으로 판정되면 403 `UNDER_AGE_SIGNUP_BLOCKED`이고
계정이 영구히 잠긴다(★D7). 확인 단계를 두는 편이 좋다. 미래 날짜는 400으로 걸러진다.

이걸 통과하지 않으면 이후 대부분의 API가 게이트 ⑤에서 403 `AGE_NOT_VERIFIED`로 막힌다.
이미 확인된 계정이 다시 부르면 409 `ALREADY_VERIFIED`.

### S3. 캠 분석 동의 — `mediaConsented=false`일 때

`POST /api/members/me/media-consent` — `{"agreed": true}`. 성공은 **204**라 본문이 없다.

`true`만 받는다. 미동의는 저장하지 않고 400으로 거절하며, **철회 API는 v1에 없다.**

동의 없이는 세션 접속 토큰(SS-2)이 403 `CONSENT_REQUIRED`로 막힌다. 즉 매칭은 되는데
세션에 못 들어가는 상태가 만들어지므로, **매칭 신청 전에 받아 두는 편이 낫다.**

동의 문구에는 자동 판정 사실과 기준 수치(60초/3회/-300P), 이의 경로, 다른 참가자 5인에게
영상이 송출된다는 사실이 들어가야 한다(`ai-pipeline.md` §7).

### S4. 홈

`GET /api/members/me` 하나로 화면 전체가 채워진다.

```json
{"memberId": 2, "nickname": "익명 거북이319", "role": "PARTICIPANT",
 "ageVerification": "VERIFIED", "memberStatus": "ACTIVE", "mediaConsented": true,
 "pointBalance": 1100,
 "goal": {"goalId": 1, "periodDays": 30, "startedOn": "2026-08-15",
          "progressDays": 1, "status": "ACTIVE", "achievedAt": null},
 "streak": {"current": 1, "lastCompletedOn": "2026-08-15"},
 "activeSession": null, "sanction": null, "badges": []}
```

**`activeSession`을 반드시 본다.** 값이 있으면 진행 중인 세션이 있다는 뜻이고, 앱을 껐다
켠 사용자를 곧장 세션 화면(S7)으로 되돌려 보내야 한다. 이 확인을 빠뜨리면 사용자가
자기가 참여 중인 세션을 못 찾는다. 모양은
`{sessionId, participantStatus, targetMinutes, startedAt, endsAt}`이라, 복귀에 필요한 값이
여기 다 들어 있다.

`goal`·`sanction`은 없으면 `null`, `badges`는 없으면 빈 배열이다.

### S5. 목표 설정

`PUT /api/members/me/goal` — `{"periodDays": 30}`

허용값은 **7 / 14 / 30**뿐이고 그 외는 400이며 `details.periodDays`에 허용값이 실린다.
이미 진행 중인 목표가 있으면 409 `GOAL_ALREADY_ACTIVE`.

503 `LOCK_ACQUISITION_FAILED`가 날 수 있다(§2-5대로 재시도).

### S6. 매칭 대기

세 개를 쓴다.

1. `POST /api/match-requests` — `{"targetMinutes": 60}`. 허용값 **60 / 120 / 180 / 240**.
2. `GET /api/match-requests/me` — **폴링.** 이것 말고 성사를 아는 방법이 없다.
3. `DELETE /api/match-requests/{matchRequestId}` — 대기 취소.

실측 — 신청 직후:

```json
{"matchRequestId": 7, "status": "WAITING", "targetMinutes": 60,
 "requestedAt": "2026-08-15T18:34:37+09:00", "expiresAt": "2026-08-15T18:44:37+09:00",
 "waitingCount": 1, "requiredCount": 6, "sessionId": null}
```

6명이 모인 뒤:

```json
{"matchRequestId": 7, "status": "MATCHED", "targetMinutes": 60,
 "requestedAt": "2026-08-15T18:34:37+09:00", "expiresAt": "2026-08-15T18:44:37+09:00",
 "waitingCount": 6, "requiredCount": 6, "sessionId": 2}
```

화면에 쓸 것:

- `waitingCount` / `requiredCount` — "6명 중 3명" 진행 표시.
- `expiresAt` — 대기 만료 시각(신청 +10분). 카운트다운.
- `status`가 `MATCHED`가 되고 **`sessionId`가 채워지면 세션 화면으로.** 이 값이 S6→S7로
  들고 가는 유일한 값이다.

`status`는 `WAITING` / `MATCHED` / `CANCELLED` / `EXPIRED`. `CANCELLED`·`EXPIRED`에서는
`waitingCount`가 `0`으로 내려온다 — 더 셀 대기열이 없기 때문이다. `MATCHED`는 정원이 그대로
들어온다(위 예시의 `6`).

**만료도 배치가 매분 처리한다.** `expiresAt`이 지나도 최대 1분간 `WAITING`으로 보일 수 있다.
결과 조회와 같은 성질이다(§2-2).

거절 경로: 409 `DUPLICATE_MATCH_REQUEST`(이미 대기 중) / 409 `ALREADY_IN_ACTIVE_SESSION`
(`details.sessionId`로 이동) / 409 `REMATCH_COOLDOWN`(퇴출 후 30분, `details.availableAt`).

폴링 간격은 서버가 강제하지 않는다. 2~3초면 충분하다.

### S7. 세션

가장 복잡한 화면이다. 순서대로.

**① 입장할 때 두 번 부른다**

`GET /api/sessions/{sessionId}` — 참가자 목록과 시각.

```json
{"sessionId": 2, "status": "LIVE", "targetMinutes": 60,
 "startedAt": "2026-08-15T18:34:37+09:00", "endsAt": "2026-08-15T19:34:37+09:00",
 "endedAt": null, "endReason": null, "roomName": "molock-2",
 "participants": [
   {"memberId": 8, "nickname": "익명 사슴892", "isMe": true, "status": "ACTIVE",
    "warningCount": 0, "paused": false, "pauseUsed": false,
    "joinedAt": null, "goalText": null, "evictionId": null}
 ]}
```

`POST /api/sessions/{sessionId}/token` — LiveKit 접속 정보.

```json
{"url": "ws://localhost:7880", "roomName": "molock-2", "token": "eyJhbGciOiJIUzI1NiIs...",
 "identity": "8", "canPublishAudio": false, "expiresInSeconds": 3600}
```

`url` + `token`으로 LiveKit SDK를 붙인다. `identity`는 `memberId`의 문자열이라, LiveKit
참가자와 서버 응답의 참가자를 이 값으로 맞춘다.

**`canPublishAudio`는 항상 `false`다.** 이 서비스는 영상만 쓴다. 마이크 UI를 만들지 않는다.

**② 진행 중에 쓰는 것**

| 무엇 | API | 비고 |
|---|---|---|
| 오늘 할 일 | `PUT /api/sessions/{id}/goal` | 최대 50자, 빈 문자열 400 |
| 자리비움 보고 | `POST /api/sessions/{id}/absence-events` | 카메라 담당자 몫. §5 |
| 화장실 모드 시작 | `POST /api/sessions/{id}/pause` | **세션당 한 번** |
| 화장실 모드 복귀 | `DELETE /api/sessions/{id}/pause` | |
| 자율 퇴장 | `DELETE /api/sessions/{id}/participation` | 사유 필수 |
| 스티커 목록 | `GET /api/stickers` | 3종. 전송은 LiveKit 데이터 채널 |

**③ 경고는 이 응답들에 실려 온다**

화장실 모드 시작:

```json
{"status": "PAUSED", "pausedAt": "2026-08-15T18:34:43+09:00",
 "pauseLimitSeconds": 600, "resumeDueAt": "2026-08-15T18:44:43+09:00",
 "warningIssued": false, "warningCount": 0, "closedAbsenceSeconds": null}
```

시작 응답에 `warningIssued`가 있는 이유는, **화장실 모드를 시작하면 진행 중이던 자리비움이
먼저 닫히기 때문**이다. 그 구간이 임계를 넘었으면 여기서 경고가 붙고, 그게 3회째면 시작
자체가 409 `ALREADY_EVICTED`로 떨어진다.

복귀:

```json
{"status": "ACTIVE", "elapsedSeconds": 0,
 "warningIssued": false, "warningCount": 0, "evicted": false}
```

상한(`pauseLimitSeconds`, 600초)을 넘겨 복귀하면 `warningIssued: true`가 되고, 그것이
3회째면 **`status`가 `EVICTED`, `evicted`가 `true`로 온다.** `ACTIVE`만 온다고 가정하면 안 된다.

두 번째 화장실 모드는 409 `PAUSE_ALREADY_USED`다.

**④ 자율 퇴장**

`DELETE /api/sessions/{sessionId}/participation` — 본문에 사유가 **필수**다.

```json
{"reason": "PERSONAL"}
```

허용값은 `PERSONAL` / `DEVICE_ISSUE` / `UNPLEASANT` / `ETC` 넷뿐이다. `WITHDRAWAL`·`SANCTION`은
서버가 쓰는 값이라 보내면 400. 본문을 통째로 빠뜨려도 400 `REASON_REQUIRED`다
(`DELETE`라고 본문을 생략하기 쉬우니 주의).

성공은 **204**이고 본문이 없다.

**⑤ 참가자 상태 필드 이름이 화면마다 다르다**

주의할 점 하나. SS-1(세션 조회)의 참가자는 `status`, SS-8(결과)의 참가자는
`participantStatus`다. 값 집합은 같다(`ACTIVE`/`PAUSED`/`LEFT`/`EVICTED`). 공용 타입을
쓸 거면 매핑이 필요하다.

### S8. 세션 결과

`GET /api/sessions/{sessionId}/result`

**§2-2를 반드시 먼저 읽는다.** 정시 종료 직후 최대 1분은 409다.

```json
{"sessionId": 2, "status": "ENDED", "targetMinutes": 60,
 "startedAt": "2026-08-15T18:34:37+09:00", "endedAt": "2026-08-15T19:34:37+09:00",
 "endReason": "NORMAL",
 "my": {"completed": true, "participantStatus": "ACTIVE", "leftReason": null,
        "warningCount": 0, "pointAwarded": 100,
        "streak": {"before": 0, "after": 1, "countedToday": true},
        "goalAchieved": false, "badgeCode": null, "evictionId": null, "warnings": []},
 "participants": [
   {"memberId": 8, "nickname": "익명 사슴892", "isMe": true,
    "participantStatus": "ACTIVE", "completed": true, "warningCount": 0}
 ]}
```

- `my.streak.before` / `after` — 연속 일수가 오르는 연출에 그대로 쓴다.
- `my.goalAchieved`가 `true`면 목표 달성이고 `badgeCode`에 `GOAL_ACHIEVED`가 붙는다.
- `my.warnings[]` — 경고 한 건마다
  `{seq, basis, issuedAt, absenceStartedAt, absenceEndedAt, absentSeconds}`가 들어 있다.
  영상을 저장하지 않기 때문에 본인이 다툴 수 있는 유일한 근거가 이 시각 기록이다.
  **화면에 반드시 보여준다.** `basis`가 `ABSENCE`면 자리비움이고 뒤의 세 값이 채워지며,
  `PAUSE_OVERRUN`(화장실 모드 초과)이면 `absenceStartedAt`·`absenceEndedAt`·`absentSeconds`가
  모두 `null`이다.
- **`my.evictionId`가 있으면 퇴출된 것이다.** 이 값을 들고 S9(이의 신청)로 갈 수 있다.

### S9. 퇴출 이의 신청 — `evictionId`가 있을 때만

`POST /api/evictions/{evictionId}/appeals` — `{"reasonText": "..."}` 최대 200자, 성공 **201**.
필드명이 `reason`이 아니라 `reasonText`다. 비워 보내면 400이다.

**퇴출 시각으로부터 3일**이 기한이고 지나면 409 `APPEAL_DEADLINE_PASSED`. 한 퇴출에 한 번만
낼 수 있다(409 `APPEAL_ALREADY_FILED`). 남의 퇴출이면 403.

`GET /api/members/me/appeals` — 내가 낸 이의 목록. `status`는 `PENDING` / `ACCEPTED` /
`REJECTED` / `CLOSED`다.

- `ACCEPTED` — 인용. `pointRefunded`와 `sessionCompletedRestored`가 채워진다.
- `CLOSED` — 심사되지 못하고 종결된 것. 탈퇴 파기가 근거 기록을 지웠을 때만 생긴다.
  "기각"이 아니므로 문구를 구분해야 한다.
- 관리자가 남긴 `note`는 이 목록에 내려가지 않는다.

**이 두 API는 제재 중에도 열려 있다**(게이트 ④ 예외). 제재 때문에 이의를 못 내는 상황을
만들지 않기 위해서다.

### S10. 내 기록

`GET /api/members/me/sessions?page=0&size=5`

```json
{"content": [{"sessionId": 1, "targetMinutes": 60,
              "startedAt": "2026-08-15T18:33:52+09:00", "endedAt": "2026-08-15T19:33:52+09:00",
              "status": "ENDED", "participantStatus": "ACTIVE",
              "completed": true, "pointAwarded": 100, "warningCount": 0}],
 "page": 0, "size": 5, "totalElements": 1, "totalPages": 1}
```

이 조회는 연령 게이트(⑤)를 받지 않는다. 면제되는 것은 이것 말고도 내 정보·생년월일 등록·
탈퇴 신청과 철회·캠 동의·신고(RP-1)·관리자 API다. 나머지는 전부 생년월일을 넣어야 열린다.

### S11. 포인트

`GET /api/members/me/points` — 잔액과 원장을 함께 준다.

```json
{"balance": 1100,
 "ledger": {"content": [
   {"ledgerId": 8, "delta": 100, "reason": "SESSION_COMPLETE", "reasonLabel": "세션 완주",
    "refType": "SESSION_PARTICIPANT", "refId": 2, "balanceAfter": 1100,
    "createdAt": "2026-08-15T19:38:58+09:00"},
   {"ledgerId": 2, "delta": 1000, "reason": "WELCOME", "reasonLabel": "웰컴 포인트",
    "refType": "MEMBER", "refId": 2, "balanceAfter": 1000,
    "createdAt": "2026-08-15T18:33:51+09:00"}],
  "page": 0, "size": 20, "totalElements": 2, "totalPages": 1}}
```

`reasonLabel`이 한국어로 함께 오므로 프론트가 enum을 번역할 필요가 없다. `delta`는 부호가
있는 값이고 `balanceAfter`는 그 시점 잔액이라 그대로 표시하면 된다.

정렬은 `ledgerId` 내림차순이다.

**충전(PY-1 → PY-2)** — 두 단계다.

```
POST /api/points/charges                    {"amountKrw": 10000}
  → 201 {"chargeId": 1, "pgOrderId": "molock-chg-20260815-000001",
         "amountKrw": 10000, "pointAmount": 10000, "status": "READY", "provider": "toss-test"}

  (여기서 provider에 맞는 PG SDK로 결제창을 띄운다. 아직 포인트는 늘지 않았다)

POST /api/points/charges/1/confirm          {"pgOrderId": "...", "pgTid": "...", "amountKrw": 10000}
  → 200 {"chargeId": 1, "status": "APPROVED", "amountKrw": 10000, "pointAmount": 10000,
         "pointBalance": 11100, "approvedAt": "2026-08-15T19:39:43+09:00"}
```

`pointAmount`를 1단계에서 미리 주는 것은 "얼마 결제하면 얼마 들어오는지"를 결제창 전에
확정해 보여주기 위해서다.

**승인 확인은 멱등이다.** 같은 건을 다시 확인해도 같은 응답이 나온다(실측 확인). 네트워크가
끊겨 응답을 놓쳤을 때 그냥 다시 부르면 된다.

금액은 1,000원 이상 1,000,000원 이하이고, 벗어나면 400에 `details.amountKrw`로 허용
범위가 실린다. `pgOrderId`·`pgTid`는 각각 최대 64자.

### S12. 스토어

`GET /api/store/products?page=0&size=20` → `GET /api/store/products/{productId}` →
`POST /api/orders` → `GET /api/orders`

주문 요청:

```json
{"productId": 1, "quantity": 1, "idempotencyKey": "임의의-고유-문자열"}
```

**`idempotencyKey`는 클라이언트가 만든다.** 최대 64자. 같은 키로 다시 보내면 새 주문이
생기지 않고 409 `DUPLICATE_ORDER` + `details.orderId`가 온다. 결제 버튼 중복 탭이나 재전송
때문에 포인트가 두 번 빠지는 것을 막는 장치이므로, **주문 화면 진입 시 키를 하나 만들어
그 주문이 끝날 때까지 유지한다.** 재시도마다 새로 만들면 장치가 무력해진다.

실측 — 성공(201):

```json
{"orderId": 3, "productId": 1, "productName": "편의점 5,000원 금액권", "quantity": 1,
 "pointAmount": 5500, "status": "ORDERED", "orderedAt": "2026-08-15T19:39:44+09:00",
 "pointBalance": 5600}
```

같은 키로 재전송(409):

```json
{"error": {"code": "DUPLICATE_ORDER", "message": "이미 접수된 주문입니다.",
           "details": {"orderId": 3}}}
```

거절: 409 `INSUFFICIENT_POINT` / 409 `OUT_OF_STOCK` / 404 `PRODUCT_NOT_FOUND`.
`HIDDEN` 상품은 목록·상세 모두에서 안 보인다.

### S13. 신고

`POST /api/reports` — 세션 중 다른 참가자나 세션 자체를 신고한다. 연령 게이트가 없다.
사유 코드는 `SEXUAL_CONTENT` / `VIOLENT_THREAT` / `AD_SPAM` / `INAPPROPRIATE_SCREEN` / `ETC`.
같은 대상을 두 번 신고하면 409 `DUPLICATE_REPORT`.

## 4. 관리자 화면

별도 앱으로 볼지 같은 앱의 숨은 화면으로 볼지는 정해지지 않았다(§8). API는 9개 있다.

| 화면 | API |
|---|---|
| 신고 큐 | `GET /api/admin/reports`, `GET /api/admin/reports/{caseId}`, `PATCH /api/admin/reports/{caseId}` |
| 제재 부여 | `POST /api/admin/members/{memberId}/sanctions` |
| 이의 큐 | `GET /api/admin/appeals`, `GET /api/admin/appeals/{appealId}`, `PATCH /api/admin/appeals/{appealId}` |
| 진행 세션 모니터 | `GET /api/admin/sessions` |
| 탈퇴 처리 결과 | `GET /api/admin/withdrawals` |

전부 `role=ADMIN`이라야 하고 아니면 403 `FORBIDDEN_ROLE`이다. 신고·이의 큐는 SLA 기한
순으로 정렬돼 오고 `overdue` 플래그가 함께 온다.

## 5. 카메라 감지 담당자에게

설계와 근거는 **`docs/ai-pipeline.md`**, 구현은 **`../ai-detection/`** 폴더에 있다.
여기서는 서버와의 계약만 요약한다. 세부는 `api-spec.md`의 SS-4 절이 정본이다.

### 보내는 것 — SS-4 하나뿐

```
POST /api/sessions/{sessionId}/absence-events
{"type": "START", "clientSeq": 1, "occurredAt": "2026-08-15T18:34:37+09:00"}
```

| 필드 | 규칙 |
|---|---|
| `type` | `START`(얼굴 사라짐) / `END`(다시 나타남) |
| `clientSeq` | **0 이상**, 단조 증가. 음수는 400 |
| `occurredAt` | 오프셋 포함 ISO-8601. 세션 시작 −5초 ~ 현재 +5초를 벗어나면 400 |

대상은 언제나 **본인**이다. `memberId` 필드가 없는 것은 의도된 설계로, 남을 신고하는
구조를 만들지 않기 위해서다.

응답:

```json
{"accepted": true, "warningCount": 0, "evicted": false,
 "evictionId": null, "pointDelta": 0, "closedAbsenceSeconds": null}
```

`warningCount`·`evicted`·`pointDelta`가 여기 실려 오는 것이 §2-1에서 말한 "경고는 내 응답에
실린다"의 실체다. `closedAbsenceSeconds`는 이 보고로 닫힌 구간의 지속 초다. 위 예시처럼
`START` 보고는 닫는 구간이 없으므로 언제나 `null`이고, 짝이 맞는 `END`에서 초가 채워진다
(짝 없는 `END`도 `null`).

### 지켜야 하는 규약 셋

`ai-pipeline.md` §7에서 못 박은 것이다.

1. **보내는 시각은 판단이 끝난 시각이 아니라 얼굴이 실제로 사라진 프레임의 시각이다.**
   서버가 그 간격으로 60초 임계를 계산하기 때문에, 판단 지연이 시각에 섞이면 임계가 밀린다.

2. **429를 받으면 같은 `clientSeq`로 다시 보낸다.** 새 번호를 매기면 중복 제거가 깨진다.
   순번은 브라우저 세션 저장소에 보존한다 — 유실 후 0부터 다시 시작하면 서버가 중복으로
   보고 새 이벤트가 조용히 사라진다.

3. **관측하지 않은 시간에는 사건을 만들지 않는다.** 탭이 백그라운드로 가 추론이 멈춘 구간은
   보고하지 않는다. 다만 이미 `START`를 보낸 상태라면 복귀 후 `END`를 **반드시** 보낸다.
   짝이 없으면 세션 종료 시각까지 자리비움으로 정산된다.

### 레이트리밋과 429

최소 간격은 설정값 `morak.session.absence-min-interval-seconds`이고 **현재 5초**다.
미만이면 429 `ABSENCE_RATE_LIMITED`.

`Retry-After` 헤더는 없다. 위 간격을 클라이언트가 알고 있어야 한다.

실측:

```
POST .../absence-events  {"type":"END","clientSeq":2,...}   → 429 ABSENCE_RATE_LIMITED
  (5초 대기)
POST .../absence-events  {"type":"END","clientSeq":2,...}   → 200 accepted:true
```

같은 `clientSeq`를 유지한 채 재전송해 성공한 것이다.

**놓치기 쉬운 것 둘.**

- 간격은 클라이언트가 보낸 `occurredAt`이 아니라 **직전 이벤트의 서버 기록 시각**을 기준으로
  잰다.
- 그 "직전 이벤트"가 내가 보낸 것이 아닐 수 있다. 화장실 모드를 시작하면 서버가 진행 중이던
  자리비움을 닫는 `END`를 스스로 만들어 넣는다. 그래서 **화장실 모드 시작 직후 5초 안에
  보낸 SS-4는 429가 난다.** 정상 동작이다.

### 중복 재전송

같은 `clientSeq`가 이미 처리됐으면 409 `DUPLICATE_ABSENCE_EVENT`다. 서버 상태는 바뀌지
않았다는 뜻이므로 **정상 완료로 처리한다.** 오류로 올리면 안 된다.

검사 순서는 재전송(409)이 레이트리밋(429)보다 앞이다.

## 6. 아직 안 된 것

프론트가 붙기 전에 알고 있어야 손해를 안 본다.

### 6-1. 로그인이 진짜 소셜 로그인이 아니다

카카오·애플 키를 아직 못 받았다. dev 프로필에서는 `DevSocialClient`가 **`authorizationCode`를
그대로 소셜 사용자 식별자로 취급한다.** 같은 문자열 = 같은 회원이다.

프론트는 이걸 이용해 원하는 만큼 테스트 계정을 만들 수 있다. 실제 SDK를 붙이는 것은
12단계이고, 그때 바뀌는 것은 `authorizationCode`에 들어가는 값뿐이라 **화면 코드는 그대로
간다.** dev가 아닌 프로필에서는 어떤 로그인도 승인되지 않는다.

### 6-2. 결제가 진짜 결제가 아니다

PG 테스트 키가 없어 `DevPgClient`가 받은 값을 그대로 승인 처리한다. 결제창이 없으므로
PY-1 → PY-2를 바로 이어 부르면 포인트가 들어온다.

실패 경로도 재현할 수 있게 `pgTid` 접두사로 가른다.

| `pgTid` | 결과 |
|---|---|
| `fail-...` | PG 거절 |
| `mismatch-...` | 승인은 됐지만 금액 불일치 |
| 그 외 | 정상 승인 |

실패 화면을 만들 때 이걸 쓴다.

### 6-3. LiveKit 강제 퇴장이 구현되지 않았다

서버가 퇴출을 판정하면 DB의 참가자 상태는 `EVICTED`가 되고 응답에도 그렇게 나가지만,
**LiveKit 룸에서 실제로 끊어내는 호출이 아직 없다.** 즉 퇴출된 참가자의 영상이 룸에 그대로
남아 있을 수 있다.

당분간은 **클라이언트가 스스로 끊어야 한다.** 자기 응답에 `evicted: true`나
`status: EVICTED`가 오면 LiveKit 연결을 직접 종료하고 퇴출 화면으로 전환한다.
다른 참가자 화면에서 퇴출자를 지우는 것도 지금은 SS-1 재조회로 맞춰야 한다.

세션 종료 정산에서 일어나는 퇴출(§2-1)은 이 처리가 필요 없다. 세션 자체가 이미 끝나 모두가
룸을 떠나는 시점이라, 남는 일은 결과 화면에서 퇴출 사실을 알리는 것뿐이다.

### 6-4. LiveKit 서버가 로컬에 없다

dev 설정의 `livekit.host`는 `ws://localhost:7880`이라 실제로 붙으려면 LiveKit을 따로
띄워야 한다. SS-2가 돌려주는 토큰은 서명 형식까지는 진짜이므로, 토큰 발급 흐름 자체는
지금도 검증할 수 있다.

### 6-5. 나머지

- 인스타그램 스토리 공유(FR-502)는 클라이언트 단독 기능이라 서버 API가 없다.
- 장바구니(FR-504)·환불(FR-506)·공석 충원(FR-306)·스마트 대기열(FR-202)은 v1 보류다.
- 스티커 전송은 서버를 거치지 않는다. `GET /api/stickers`는 목록만 준다.

## 7. 화면을 만들며 자주 틀리는 것

한 줄로 모았다.

- 매칭 성사를 푸시로 기다리지 않는다. **폴링뿐이다.**
- 세션 종료 직후의 409를 오류로 표시하지 않는다. **최대 1분 기다린다.**
- 결과 화면을 완주 축하 전용으로 만들지 않는다. **종료 정산 퇴출은 여기서만 통지된다.**
- 이의 신청 본문 필드는 `reason`이 아니라 `reasonText`다.
- 화장실 모드 복귀 응답의 `status`가 `ACTIVE`라고 단정하지 않는다. `EVICTED`가 온다.
- 자율 퇴장에 본문을 빼먹지 않는다. `DELETE`에도 사유가 필수다.
- `idempotencyKey`를 재시도마다 새로 만들지 않는다.
- 429에 새 `clientSeq`를 매기지 않는다.
- 홈에서 `activeSession`을 확인하지 않고 지나가지 않는다.
- 탈퇴 유예 중에는 조회 API도 막힌다.
- SS-1의 `status`와 SS-8의 `participantStatus`는 이름만 다르고 같은 값이다.
- 목록 `size`는 50을 넘길 수 없다.

## 8. 프론트 시작 전에 팀이 정해야 하는 것

지금 서버에 잠정값으로 들어가 있어 **화면 문구와 레이아웃이 그 값에 묶이는** 것들이다.
값 자체는 설정만 바꾸면 되지만, 문구를 하드코딩해 두면 나중에 전부 찾아 고쳐야 한다.

| # | 정할 것 | 현재 잠정값 | 화면에 미치는 영향 |
|---|---|---|---|
| 1 | 자리비움 경고 임계 (★D4) | 60초 | 캠 동의 문구에 수치가 들어간다. 바꾸면 **재동의가 필요하다** |
| 2 | 퇴출 경고 횟수 / 패널티 | 3회 / -300P | 동의 문구·세션 화면 경고 표시 |
| 3 | 포인트 지급액 (D15) | 완주 100P/시간, 웰컴 1000P, 목표 달성 1000P | 결과 화면 연출, 스토어 가격 체감 |
| 4 | 매칭 시간 선택지 (D8) | 60/120/180/240분 | 매칭 화면 버튼 개수 |
| 5 | 대기 만료 / 재매칭 쿨다운 | 10분 / 30분 | 대기 화면 카운트다운, 쿨다운 안내 |
| 6 | 화장실 모드 상한 (D9) | 10분 | 세션 화면 타이머 |
| 7 | 목표 기간 선택지 | 7/14/30일 | 목표 설정 화면 |
| 8 | 충전 금액 하한·상한 | 1,000원 ~ 1,000,000원 | 충전 화면 입력 검증 |

이와 별개로 **결정이 필요한 것** 넷.

1. **관리자 화면을 어디에 두는가.** 같은 앱의 숨은 화면인지 별도 웹인지에 따라 AD-1~AD-9를
   프론트가 만들지 말지가 갈린다.
2. **퇴출자의 LiveKit 연결을 누가 끊는가.** §6-3대로 지금은 클라이언트가 스스로 끊어야
   한다. 서버가 LiveKit 강제 퇴장을 구현하면 그 코드는 불필요해지므로, 임시 대응으로
   둘지 서버 구현을 기다릴지 정해야 한다.
3. **폴링 간격.** 서버가 강제하지 않아 클라이언트가 정한다. 매칭 대기와 결과 대기 두 곳이다.
4. **커머스 기록 보존 연한.** 법무 확인 대기 중이고 `open-decisions.md`에 있다. 탈퇴 화면의
   안내 문구가 여기 묶인다.

---

문서에 없거나 어긋나는 것을 발견하면 코드가 아니라 `api-spec.md`를 기준으로 확인하고,
그래도 어긋나면 알려 주기 바란다. 계약 문서와 코드를 맞추는 것은 서버 쪽 일이다.
