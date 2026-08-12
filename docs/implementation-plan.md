# 모락 백엔드 구현 커리큘럼 v4 — 통합 정본 (2026-08-12)

> 자기완결 문서. 계약 정본은 `docs/openapi.yaml`(엔드포인트)·`docs/api-spec.md`(처리 절차)·`docs/db-schema.md`(컬럼·제약).
> 전제: 화수 혼자 백엔드 전체 구현. Spring Boot 4.1 · Java 21 · Gradle · JPA · H2(개발) / MySQL(운영).
> 진행 원칙: **게이트(curl 실측) 통과 없이 다음 단계 금지.** 게이트는 그 단계 산출물만으로 실행 가능해야 한다.
> 강의 진행: ①왜 필요한가 → ②새 개념 → ③전체 코드 → ④줄별 설명 → ⑤화수 타이핑 후 리뷰 → ⑥curl 게이트.

## 진행 현황판

| 단계 | 이름 | 상태 |
|---|---|---|
| 0 | 프로젝트 골격·전역 예외 | 완료 |
| 0.5 | enum·정책값 격리 | 완료 |
| E | JPA 엔티티 | 완료 (PR #1 머지) |
| O | OpenAPI 계약 | 완료 (PR #2 머지) |
| 1 | 회원·인증·JWT | 코드 완성, 검수 중, PR 예정 |
| 2 | 매칭 엔진 | 미착수 — **다음 작업** |
| 3 | 그룹 조회·자율 퇴장 | 미착수 |
| 4 | 인증 제출 (AI 스텁) | 미착수 |
| 5 | 인증 열람·스티커 | 미착수 |
| 6 | 대시보드 | 미착수 |
| 7 | AI 실물 | 미착수 |
| 8 | 종료·완주 판정 | 미착수 |
| 9 | 신고·제재 | 미착수 |
| 9.5 | 완주 게시판 | 미착수 |
| 10 | 탈퇴 완결 (배치) | 미착수 |
| 11 | 운영 준비 | 미착수 |

---

## 완료 단계 요약

### 0단계 — 프로젝트 골격 (완료)
Spring Boot 4.1 골격, `GlobalExceptionHandler` + 공통 에러 포맷, `ErrorCode` 49종.
게이트 실측 완료: 없는 URL → 404 `{"error":{"code":"ENDPOINT_NOT_FOUND",...}}`.

### 0.5단계 — enum·정책값 격리 (완료)
enum 26종을 API 명세서 §0-4와 일치시켰고, `application.yml`의 `morak.*`에 §0-6 정책값 전부를 격리했다.
`ErrorCode` ↔ 명세서 §6 diff 통과(`REPORT_NOT_READY` 1건 제외 — 202 정상 응답이라 예외로 던지지 않음).

### 엔티티 단계 — JPA 엔티티 22개 (완료, PR #1)
기존 계획에 없던 단계. db-schema.md의 22테이블 전부를 엔티티로 선작성했다(이력 2테이블 제외).
UNIQUE 제약을 `@Table(uniqueConstraints=…)`에 선언, `proof.daily_slot`은 생성 컬럼으로 매핑
(`insertable=false, updatable=false`). 이후 단계는 엔티티를 새로 만들지 않고 **Repository·Service·Controller만 얹는다.**

### OpenAPI 단계 — 계약 확정 (완료, PR #2)
기존 계획에 없던 단계. `docs/openapi.yaml`에 API 38개(operationId 기준)를 확정했다.
DEV-2~4 개발 전용 엔드포인트는 프론트 계약이 아니므로 openapi.yaml에 없다 — 이 문서가 정의처다.

### 1단계 — 회원·인증·JWT (코드 완성, 검수 중)
구현 완료: `JwtProvider`(jjwt-gson, HS256) / `AuthInterceptor` 5게이트(§0-2, SKIP_RULES 표 방식 —
**미래 단계의 예외 행까지 이미 등록됨**: RP-1·PB-2·PB-3·PF-4r·admin) / `@LoginMember` /
`Clock` 빈 / AU-1 로그인 / AU-2 내 정보 / AU-3 연령 검증 / AU-4·AU-5 탈퇴 신청·철회 /
`NicknameGenerator` / `SocialHasher` + `blocked_social_hash` 조회(재가입 차단) /
가입 시 `match_lock('member:{id}')` 동반 INSERT.

계획(v3) 대비 이탈 2건 — 둘 다 채택 확정:
- **DEV-1 별도 엔드포인트 폐기.** dev 로그인은 AU-1 `POST /api/auth/login` + `DevSocialClient`
  (`@Profile("dev")` AND `morak.dev.enabled` 이중 스위치)로 처리한다. authorizationCode가 곧
  providerUserId라 같은 코드 재호출 = 같은 회원. 별도 경로가 없으니 운영 노출면도 줄어든다.
- **AU-4·AU-5를 10단계에서 앞당겨 구현.** 회원 상태 머신(ACTIVE↔WITHDRAW_PENDING)이 인터셉터 ②검사와
  한 몸이라 분리 비용이 더 컸다. 10단계에는 배치(B4·B5)와 AD-8만 남는다.

검수 중 반영된 결정: 설정을 3파일로 분리했다(`application.yml` 공통 / `application-dev.yml` H2·dev 시크릿·
`morak.dev.enabled=true` / `application-prod.yml`). **base yml의 시크릿 폴백을 제거**해 운영에서 환경변수
3종(JWT·media-token·pepper)이 없으면 기동 자체가 실패한다 — 로컬 기본값으로 조용히 뜨는 사고를 막는다.

잔여 TODO (코드에 주석으로 표시됨):
- `MemberService` AU-2 응답의 `sanction` 필드 — `SanctionRepository`는 인터셉터에 이미 연결돼 있으므로 검수 중 해소
- AU-2 `mediaConsented` — 4단계(PF-1)에서 해소
- AU-4 동반 처리(매칭 요청 CANCELLED, 그룹 LEFT(WITHDRAWAL)) — 2·3단계에서 해소

---

## 단계 순서 재검토 결론

기존 순서(2→3→4→5→6→7→8→9→9.5→10→11)를 **유지한다.** 근거:

- **2가 최우선**: 이후 모든 단계가 그룹의 존재를 전제한다. 데이터 생산 경로가 매칭뿐이다.
- **3 → 4**: PF-2의 첫 검증이 "멤버십 ACTIVE"다. 멤버십 조회·전이(GR-3)가 먼저 있어야 인증 게이트를 실측할 수 있다.
- **6 → 7·8**: DEV-3(과거 일자 인증 시드)가 6단계 산출물인데, 7단계 게이트(같은 사진 2일 연속)와
  8단계 게이트(완주율 손계산)의 데이터 공급원이 전부 DEV-3다.
- **7 → 8 유지, 단 교환 가능**: 8은 7에 의존하지 않는다(완주 판정은 proof 상태만 본다). 그런데 8은
  T1-4·T1-6(팀 확정)에 막혀 있고 7은 아무것도 기다리지 않는다 — 팀 답변 대기 시간을 7이 흡수하는 배치가
  자연스럽다. 반대로 **AI 벤더 키 발급이 늦어지면 7↔8을 교환한다**(8 게이트는 4단계 스텁 조작으로 전부 수행 가능).
- **9 → 9.5**: AD-6 게시글 hide와 `ReportTargetType.POST` 분기가 신고 인프라(케이스·이력·제재) 위에 얹힌다.
- **9.5 → 10**: B4의 동반 처리에 `challenge_post → DELETED`가 있다. 게시판이 있어야 B4를 온전히 실측한다.
  또한 B4의 `blocked_social_hash` 등재 대상이 "PERMANENT 제재 이력자"라 9단계 산출물(제재)이 필요하다.
- **10 → 11**: 운영 전환은 기능 완결 후. MySQL 동시성 재실측은 전 기능이 있어야 의미가 있다.

## 팀 미확정 항목 (T1) — 단계별 대기표

T1-1(신고자 즉시 퇴장)·T1-2(startDate 자동 결정)·T1-7(얼굴 거부)은 8/12 확정 — 본문에 반영됨.

| 항목 | 막는 단계 | 답이 없으면 이 가정으로 진행 |
|---|---|---|
| T1-3 자격증 시험날짜 방식 | 2 | **시험날짜는 v1 매칭 키에 없다**(category 6 × minutes 4 × days 3 = 72 조합 고정). (a) 프리셋 채택 시 match_request 스키마·매칭 키·잠금 행 시드가 전부 바뀌므로, 시드 로직을 `MatchLockSeeder` 한 클래스에 격리해 변경 반경을 좁혀 둔다 |
| T1-5 매칭 완화 정책 | 2 | **완화 없음**(24h 만료 + 재시도 안내가 전부). 완화가 v1에 들어오면 MT-1 5번 절차(대기열 조회)만 바뀐다 |
| T1-4 완주 이중 기준(개인 AND 그룹) | 8 | 현행 yml 값(개인 0.7 AND 그룹 0.6)으로 구현. 기준 변경은 `morak.completion.*` 값 교체로 흡수, AND→OR 등 구조 변경은 판정 메서드 1곳 수정 |
| T1-6 인원 미달 시 별도 완주 기준 | 8 | **별도 기준 없음**(현행 규칙 그대로). 확정 시 B1 판정 메서드에 분기 추가 — `final_report.criteria_*` 스냅샷 컬럼이 이미 있어 소급 오염은 없다 |

가정으로 진행한 단계는 게이트 통과와 별개로 **해당 T1 확정 시 재실측 항목**을 남긴다(각 단계 게이트에 표기).

## 공통 규약

### Spring Boot 4.1 실측 주의 (인터넷의 3.x 자료와 다름)
1. **Jackson 3가 기본** — `com.fasterxml.jackson.*` 없음(`tools.jackson.*`). 3.x 강의의 import는 컴파일 불가
2. **JWT는 `jjwt-gson`** — `jjwt-jackson`은 Jackson 2를 끌고 와 충돌 (1단계에서 실측 완료)
3. `@MockBean` 삭제 → `@MockitoBean`
4. 스타터 명칭 `web`→`webmvc`, H2 콘솔 별도 모듈. 인터셉터·`@Scheduled`·multipart·JPA는 3.x와 동일(`@EnableScheduling` 필요)

### 배치 실행 규약
B1~B7 전부 `@Scheduled` + DEV-4 수동 트리거(`POST /api/dev/batches/{name}`) 쌍으로 구현한다.
게이트의 "배치 트리거"는 이 엔드포인트 호출을 뜻한다. 모든 배치는 멱등(재실행 안전)해야 한다.
패키지는 도메인 안에 둔다(CLAUDE.md §4): B2→match, B1·B6→group, B7→proof, B3→report, B4→member, B5→proof.

### 개발 전용 엔드포인트
활성 조건은 **`@Profile("dev")` AND `morak.dev.enabled=true` 이중 스위치**(1단계 `DevSocialClient`와 동일 패턴).
DEV-2(시각 조작)·DEV-4(배치 트리거)는 2단계, DEV-3(인증 시드)는 6단계에서 만든다. `common/dev/` 패키지.
11단계 게이트에서 운영 프로필 기동 후 전 경로 404를 실측한다.

### 게이트 실행 준비 (모든 단계 공통)
`jq` 필요. 아래 helper를 셸에 정의해 두고 게이트에서 재사용한다.

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

---

## 2단계 — 매칭 엔진

**만드는 것**: 조건 완전 일치 6인 선착순 자동 매칭과 그 대기열의 생성·취소·만료.
**대상 API**: MT-1 `createMatchRequest` · MT-2 `getMyMatchRequest` · MT-3 `cancelMatchRequest` (+ 계약 밖: DEV-2 시각 조작, DEV-4 배치 트리거)
**대상 테이블**: match_request · challenge_group · group_member · match_event (+ match_lock 조건 행 72개 시드)
**선행 조건**: 1단계 (회원·인터셉터·match_lock 회원 행). T1-3·T1-5는 위 가정으로 진행.
**강의 포인트**: 행 잠금과 동시성 제어 — 잠금 순서가 데드락을, 조건부 UPDATE가 경합 손실을 막는 이유.

### 작업 순서
1. `common/dev/MutableClock` + `common/dev/DevClockController`(DEV-2) — 현재 `AppConfig`의 `Clock.system`을
   dev 프로필에서 오프셋 조작이 가능한 구현으로 교체한다. **1단계 잔여물이며 이 단계 게이트(만료)부터
   4·8·10단계 게이트 전부가 이것 없이는 재현 불가**라 최우선이다.
2. `common/dev/DevBatchController`(DEV-4) — 배치 빈을 이름으로 찾아 즉시 실행하는 디스패처.
   B2가 이 단계에서 처음 생기므로 함께 만든다. `@EnableScheduling`도 여기서 켠다.
3. `match/repository/MatchLockRepository`에 `@Lock(PESSIMISTIC_WRITE) findByLockKey` 추가 +
   `match/MatchLockSeeder`(`ApplicationRunner`) — 조건 행 72개 시드. 잠금 행을 런타임에 만들면
   미존재 행 `FOR UPDATE`에 갭 락이 없어(H2 실측) 동시 진입 시 INSERT 경합으로 복구 불가 — 그래서 시드가 선행이다.
4. `MatchRequestRepository` · `ChallengeGroupRepository` · `GroupMemberRepository` · `MatchEventRepository` —
   대기열 조회는 `idx_mr_queue`를 타는 메서드 이름 쿼리 + 성사 UPDATE는 `@Modifying @Query`(영향 행 수 반환 필요).
5. `match/service/MatchService` — MT-1 절차 9스텝(api-spec §4 그대로), MT-3, MT-2. 서비스가 완성돼야
   컨트롤러 계약을 붙일 수 있다.
6. `match/controller/MatchController` + request/response record.
7. `match/service/MatchExpireBatch`(B2) — `expires_at < now`인 WAITING → EXPIRED. **조건 행 잠금 후 조건부 UPDATE.**
8. 1단계 TODO 해소: `MemberService` 탈퇴 시 활성 매칭 요청 CANCELLED 연결(MatchService의 취소 메서드 재사용).

### 이 단계의 함정
- **2단 잠금 순서는 "회원 행 → 조건 행" 고정.** MT-1이 이 순서인데 다른 경로가 역순으로 잡으면 교차 대기
  데드락이 된다. MT-3·B2·AD-4·AU-4는 조건 행만 잡되, 회원 행을 잡아야 한다면 반드시 회원 행 먼저.
- **status를 바꾸는 모든 주체(MT-1·MT-3·B2, 이후 AD-4·AU-4)는 3종 세트를 함께 수행한다**:
  ①조건 행 잠금 ②조건부 UPDATE(`WHERE status='WAITING'`) ③`active_member_id=NULL`.
  ③이 빠지면 `uk_mr_active` 때문에 그 회원의 재요청이 영구 차단된다 — "조건 조정" 플로우가 여기서 죽는다.
- **자기 포함 계산**: 대기 5명 + 나 = 6이 성사다. "6명 대기 중인지"를 세면 7명째에 성사된다.
- **성사 UPDATE의 영향 행 수 = 6 검증, 미달 시 전체 롤백.** 7건 이상 경합 시 정확히 6건만 선착순으로.
- **그룹 생성의 트랜잭션 경계**: 6명째 요청의 단일 `@Transactional` 안에서 그룹 INSERT + 멤버십 6건 +
  요청 6건 MATCHED 전이가 전부 일어난다. 잠금 해제는 커밋 시 자동 — 수동 해제 코드를 만들지 않는다.
- **startDate 자동 결정(T1-2 확정)**: 매칭 시각이 그날 인증 마감(23:59) 이후면 다음날. `endDate = startDate + periodDays - 1`.
- H2 `LOCK_TIMEOUT=3000` → `PessimisticLockingFailureException`을 503 `LOCK_ACQUISITION_FAILED`로 매핑.
- H2 6스레드 통과는 MySQL 통과를 보장하지 않는다 — MySQL 재실측은 11단계 게이트에 예약돼 있다.

### 게이트 (전부 통과해야 다음 단계)
```bash
REQ='{"category":"STUDY","dailyTargetMinutes":60,"periodDays":30}'
mt1() { curl -s -X POST $BASE/api/match-requests -H "Authorization: Bearer $1" \
  -H 'Content-Type: application/json' -d "$REQ"; }

# 1) 6명 순차 → 1~5번째 WAITING, 6번째 MATCHED + groupId
for i in 1 2 3 4 5 6; do T=$(adult "u$i"); mt1 "$T" | jq '{status, groupId}'; done

# 2) 동시성: 새 조건으로 6명 동시 → 정확히 6인 그룹 1개 (H2 콘솔에서 group_member COUNT=6 확인)
REQ='{"category":"EXERCISE","dailyTargetMinutes":30,"periodDays":30}'
for i in 11 12 13 14 15 16; do adult "u$i" > /tmp/t$i & done; wait
for i in 11 12 13 14 15 16; do mt1 "$(cat /tmp/t$i)" & done; wait

# 3) 대기 중 재요청 → 409 DUPLICATE_MATCH_REQUEST
T=$(adult u21); mt1 "$T" | jq .; mt1 "$T" | jq .error.code

# 4) MT-3 취소 → 204, 같은 회원 즉시 재요청 → 201 (active_member_id 해제 검증)
ID=$(curl -s $BASE/api/match-requests/me -H "Authorization: Bearer $T" | jq .matchRequestId)
curl -s -o /dev/null -w '%{http_code}\n' -X DELETE $BASE/api/match-requests/$ID -H "Authorization: Bearer $T"
mt1 "$T" | jq .status

# 5) 만료: DEV-2로 +25h → B2 트리거 → EXPIRED + 재요청 201
curl -s -X POST $BASE/api/dev/clock -H 'Content-Type: application/json' -d '{"offsetMinutes":1500}'
curl -s -X POST $BASE/api/dev/batches/B2
mt1 "$T" | jq .status   # 201 WAITING (만료된 요청이 재차단하지 않음)

# 6) 미검증 회원 → 403 AGE_NOT_VERIFIED
T=$(login noage); mt1 "$T" | jq .error.code
```
T1-3·T1-5 확정 시 재실측: 매칭 키 조합·완화 경로.

### 예상 작업량
파일 약 15개(dev 인프라 3 · 시더 1 · repository 4 · service 2 · controller 1 · dto 4).
커밋 4개 제안: dev 시각·배치 인프라 / 매칭 요청·성사 / 취소·만료(B2) / 탈퇴 연동.

---

## 3단계 — 그룹 조회·자율 퇴장

**만드는 것**: 내 그룹 목록·그룹 상세 조회와 자율 퇴장.
**대상 API**: GR-1 `getMyGroups` · GR-2 `getGroup` · GR-3 `leaveGroup`
**대상 테이블**: 신규 없음 (challenge_group · group_member 재사용)
**선행 조건**: 2단계 (그룹이 있어야 조회할 대상이 있다)
**강의 포인트**: 조회 API의 자격 분기 — 같은 데이터라도 "누가, 언제 보느냐"로 응답 집합이 갈린다.

### 작업 순서
1. `group/service/GroupService` — GR-1(이력 전체), GR-2(자격 검사 + 상태별 members 분기), GR-3(ACTIVE→LEFT 전이).
   조회 2종을 먼저 만들어야 퇴장의 효과(403 전환)를 게이트에서 확인할 수 있다.
2. `group/controller/GroupController` + `MemberController`에 GR-1 추가(경로가 `/api/members/me/groups`) + dto record.
3. 1단계 TODO 해소: `MemberService` 탈퇴 시 진행 그룹 membership → `LEFT(WITHDRAWAL)` 연결.

### 이 단계의 함정
- **`memberCount`는 ACTIVE 수 단일 정의.** 퇴장자가 생기면 6 미만으로 내려가고, 충원은 없다.
- **members 집합이 그룹 상태로 갈린다**: 진행 중 = ACTIVE만 / 종료 = COMPLETED+LEFT+REPORT_EXIT 전원(membershipStatus 병기).
- **GR-2 자격: LEFT·REPORT_EXIT는 403** `NOT_GROUP_MEMBER`(그룹 접근 상실). GR-1에는 모든 이력이 나온다 —
  목록은 보여주되 진입을 GR-2가 막는 구조다.
- **GR-3의 reason은 4값만 받는다**(PERSONAL·SCHEDULE·HEALTH·ETC). enum `LeftReason`을 그대로 바인딩하면
  서버 전용 값(WITHDRAWAL·SANCTION)이 요청으로 들어온다 — 요청 전용 검증 필요.
- **필드 하나짜리 request record는 `@JsonCreator` 필수**(CLAUDE.md §4-2). `LeaveGroupRequest(reason)`가 정확히 그 케이스다.
- GR-2 응답의 `proofStatus`는 proof가 4단계 산출물이므로 **이 단계에서는 NONE 고정으로 두고 4단계에서 채운다**(TODO 주석 명시).

### 게이트 (전부 통과해야 다음 단계)
```bash
T1=$(adult u1); GID=$(curl -s $BASE/api/members/me/groups -H "Authorization: Bearer $T1" | jq '.groups[0].groupId')

# 1) 멤버 조회 → 200, memberCount=6 / 비멤버 → 403 NOT_GROUP_MEMBER
curl -s $BASE/api/groups/$GID -H "Authorization: Bearer $T1" | jq '{memberCount, status}'
TX=$(adult stranger); curl -s $BASE/api/groups/$GID -H "Authorization: Bearer $TX" | jq .error.code

# 2) 사유 없이 퇴장 → 400 REASON_REQUIRED
curl -s -X DELETE $BASE/api/groups/$GID/membership -H "Authorization: Bearer $T1" \
  -H 'Content-Type: application/json' -d '{}' | jq .error.code

# 3) 퇴장 204 → GR-2 403 (접근 상실) → 매칭 재요청 201 (활성 멤버십 소멸 확인)
curl -s -o /dev/null -w '%{http_code}\n' -X DELETE $BASE/api/groups/$GID/membership \
  -H "Authorization: Bearer $T1" -H 'Content-Type: application/json' -d '{"reason":"PERSONAL"}'
curl -s $BASE/api/groups/$GID -H "Authorization: Bearer $T1" | jq .error.code
mt1 "$T1" | jq .status

# 4) 재퇴장 → 409 ALREADY_LEFT / GR-1에는 LEFT로 여전히 표시
curl -s -X DELETE $BASE/api/groups/$GID/membership -H "Authorization: Bearer $T1" \
  -H 'Content-Type: application/json' -d '{"reason":"PERSONAL"}' | jq .error.code
curl -s $BASE/api/members/me/groups -H "Authorization: Bearer $T1" | jq '.groups[0].membershipStatus'
```

### 예상 작업량
파일 약 8개(service 1 · controller 1 · dto 5 · repository 메서드 추가 1). 커밋 2개 제안: 조회 2종 / 퇴장·탈퇴 연동.

---

## 4단계 — 인증 제출 (AI는 스텁)

**만드는 것**: 촬영물 동의와 하루 1건 인증 제출 — AI 4종 검사 자리를 스텁으로 잡고 트랜잭션 골격을 완성한다.
**대상 API**: PF-1 `agreeMediaConsent` · PF-2 `submitProof` (+ B7 회수 배치)
**대상 테이블**: media_consent · proof · proof_media · ai_judgment · ai_review_queue
**선행 조건**: 3단계 (멤버십 검증), 2단계 DEV-2 (마감 게이트 재현)
**강의 포인트**: 트랜잭션 경계 설계 — 외부 호출(AI)을 트랜잭션 밖으로 빼는 이유와 그 대가.

### 작업 순서
1. `ai/AiClient` 인터페이스 4메서드(얼굴·이미지 검열·텍스트 검열·진위) + `ai/StubAiClient` —
   전부 통과가 기본이되 **파일명 규약으로 실패를 재현**한다(파일명에 `face` → FACE_REJECT,
   `nsfw` → BLOCK, `dup` → HOLD). 게이트가 curl만으로 실패 경로를 밟게 하는 장치다. 인터페이스가
   먼저 있어야 ProofService가 AI 교체(7단계)와 무관하게 완성된다. 구현이 실제로 2개인 유일한
   인터페이스 허용 케이스다(CLAUDE.md §4-1).
2. `common/storage/MediaStorage` 인터페이스 + 로컬 구현 — `{groupId}/{proofId}/{uuid}.{ext}`.
   11단계 S3 교체 지점을 여기 한 곳으로 고정한다.
3. PF-1: `MediaConsentRepository` + `MemberService`에 동의 저장 + AU-2 `mediaConsented` TODO 해소.
   PF-2의 선행 검증이라 먼저 만든다.
4. `proof/repository` 3종(Proof·ProofMedia·AiReviewQueue) + `ai/repository/AiJudgmentRepository`.
5. `proof/service/ProofService` — PF-2 5스텝: 검증 → 얼굴 선검사(메모리 바이트) → tx1(proof SCREENING +
   flush로 id 확보 + 파일 쓰기 + proof_media) → tx 밖 AI(검열→진위) → tx2(조건부 UPDATE + ai_judgment +
   큐 적재). 트랜잭션 2개는 `TransactionTemplate`으로 긋는다 — 같은 빈 안 자기 호출은 `@Transactional`
   프록시를 타지 않는다(1단계 `AuthService`에서 배운 패턴 재사용).
6. `proof/controller/ProofController` + dto. **에러 응답 변환은 tx2 커밋 후 컨트롤러에서.**
7. `proof/service/ScreeningRecoverBatch`(B7) — `screening-timeout-minutes` 초과 SCREENING → HOLD.

### 이 단계의 함정
- **얼굴 선검사는 파일·DB 쓰기 이전.** 선저장 후 삭제 방식은 슬롯 점유·고아 파일·dangling 로그를 만든다.
  감지 시 바이트 폐기 + `ai_judgment(FACE, target_type=MEMBER)` 기록(proof 행이 없으므로 MEMBER 규약) + 422.
- **daily_slot이 SCREENING을 점유하지 않는 이유**: ①AI 처리 중 서버가 죽어도 그날 인증이 409로 잠기지 않고
  ②검열 오탐 1회가 그날 인증을 영구 상실시키지 않는다. 생성 컬럼이 이 규칙을 DB 차원에서 강제한다
  (`uk_proof_daily`). 중복 검증을 서비스에서만 하면 동시 제출 경합에 뚫린다.
- **마감 판정은 접수 시각.** AI가 5분 걸려 23:59를 넘겨도 인정 — tx2가 아니라 검증 시점의 clock을 쓴다.
- **재업로드는 UPDATE가 아니라 새 행 INSERT + 구 행에 `superseded_by_id`.** 차단 원본과 감사 연결을 보존한다.
  구 HOLD/BLOCKED 행의 상태는 유지된다.
- **tx2는 조건부 UPDATE**(`WHERE ai_status='SCREENING'`) — B7이 먼저 HOLD로 회수했으면 덮어쓰지 않는다.
- **PENDING_REVIEW의 AUTHENTICITY 큐 적재를 빠뜨리면 그날 인증이 영구 소실된다.** PENDING_REVIEW는 슬롯을
  점유해 재업로드가 409로 막히는데, 큐에 없으면 관리자가 처리할 경로도 없다(설계 검증에서 잡힌 치명 결함 1호).
- **422·502를 tx2 안에서 던지면 BLOCKED 갱신·큐 적재가 함께 롤백된다.** tx2를 커밋한 뒤 결과를 보고
  컨트롤러가 상태 코드를 정한다.
- 파일 검증은 확장자·Content-Type이 아니라 **매직바이트**로. multipart 한도(10MB)는 yml에 이미 있다.

### 게이트 (전부 통과해야 다음 단계)
```bash
# 준비: 6명 매칭으로 새 그룹, T=멤버 토큰, GID=그룹. 동의 먼저
curl -s -o /dev/null -X POST $BASE/api/members/me/media-consent \
  -H "Authorization: Bearer $T" -H 'Content-Type: application/json' -d '{"agreed":true}'
pf2() { curl -s -X POST $BASE/api/groups/$GID/proofs -H "Authorization: Bearer $T" \
  -F "file=@$1" -F method=PHOTO; }

# 1) 정상 제출 → 201 APPROVED / 재제출 → 409 DUPLICATE_DAILY_PROOF
pf2 ok.jpg | jq '{proofId, aiStatus}'; pf2 ok.jpg | jq .error.code

# 2) 미동의 회원 → 403 CONSENT_REQUIRED / DEV-2로 새벽 03:00 → 400 PROOF_DEADLINE_PASSED
curl -s -X POST $BASE/api/dev/clock -d '{"offsetMinutes":-COMPUTED}' ...  # 03시로 조정 후
pf2 ok.jpg | jq .error.code   # PROOF_DEADLINE_PASSED (확인 후 시각 복원)

# 3) 얼굴 감지(face.jpg) → 422 + DB에 proof 0건·storage에 파일 0건 + ai_judgment(MEMBER) 1건
pf2 face.jpg | jq .error.code; ls storage/$GID 2>/dev/null

# 4) 검열 위험(nsfw.jpg, 다음날로 시각 이동 후) → 422 CONTENT_BLOCKED + BLOCKED 행 + CENSORSHIP 큐 PENDING
#    이어서 재업로드 ok → 201, proof 2행·구 행 BLOCKED 유지·superseded_by_id 연결 (H2 콘솔 확인)
pf2 nsfw.jpg | jq .error.code; pf2 ok2.jpg | jq .aiStatus

# 5) B7: proof를 SCREENING으로 두고 DEV-2 +11분 → B7 트리거 → HOLD (H2 콘솔 확인)
curl -s -X POST $BASE/api/dev/batches/B7
```

### 예상 작업량
파일 약 15개(AI 2 · storage 2 · repository 4 · service 2 · controller 1 · dto 3 · batch 1).
커밋 4개 제안: AI 스텁·storage / PF-1 동의 / PF-2 제출 / B7.

---

## 5단계 — 인증 열람·스티커

**만드는 것**: 그룹원끼리 인증을 확인·열람하고 스티커로 응원하는 상호작용.
**대상 API**: PF-3 `getProofsByDate` · PF-4 `getProofMedia` · PF-4r `streamProofMedia` · ST-1 `toggleSticker` · ST-2 `getStickers`
**대상 테이블**: sticker_reaction
**선행 조건**: 4단계 (열람할 인증이 있어야 한다)
**강의 포인트**: 자원 접근 제어 — `<img src>`에 Bearer를 실을 수 없을 때 서명 토큰이 신원을 대신하는 구조.

### 작업 순서
1. PF-3: `ProofService`에 일자별 조회 추가 — 3단계에서 NONE 고정으로 미룬 GR-2의 `proofStatus`도 여기서 채운다.
2. PF-4: `common/security/MediaTokenProvider`(HMAC — `proofId|memberId|exp`, `morak.security.media-token-secret`) +
   열람 URL 발급. 토큰 발급이 있어야 3의 검증을 만들 수 있다.
3. PF-4r: 스트리밍 컨트롤러 — 토큰 파싱으로 신원 확정 후 **PF-4와 동일한 자격 검증 전체를 재수행**.
   인터셉터 SKIP_RULES에 이 경로의 JWT 제외가 1단계부터 등록돼 있다(확인만).
4. `reaction/` — Repository·Service·Controller. 토글은 행 삭제 방식(`uk_sr`).

### 이 단계의 함정
- **타인에게는 APPROVED만 노출.** SCREENING·PENDING_REVIEW·HOLD·BLOCKED는 `proofStatus="NONE"`으로 마스킹.
  본인에게는 5값 그대로 — 응답 조립 시 "요청자 == 대상자" 분기를 빠뜨리면 차단 사실이 그룹에 샌다.
- **raw 경로에서 자격 검증을 생략하지 않는다.** 토큰은 "누구인지"만 대신한다. URL이 유출돼도 같은 그룹
  ACTIVE가 아니면 403 — 서명 검증만 하고 자격을 건너뛰면 5분짜리 공개 링크가 된다.
- **열람 자격은 그룹 진행 중 + ACTIVE 멤버 + 대상 APPROVED** 3중이다. 종료 그룹은 403
  `VIEW_BLOCKED_GROUP_ENDED`(참여자 열람은 종료로 끝, 이후는 관리자 AD-5만).
- **ST-1 대상이 APPROVED가 아니면 403이 아니라 404** `PROOF_NOT_FOUND` — 타인의 차단 인증 존재 여부를 누설하지 않기 위해서다.
- 토글 동시 중복은 `uk_sr` 위반 → `DataIntegrityViolationException`을 잡아 현재 상태로 응답(500 금지, db-schema §UNIQUE 표).

### 게이트 (전부 통과해야 다음 단계)
```bash
# A가 인증(APPROVED) 상태. B는 같은 그룹 멤버, C는 타 그룹
# 1) B의 PF-3 → A의 proofStatus=APPROVED / A 본인 HOLD 건은 B에게 NONE
curl -s "$BASE/api/groups/$GID/proofs?date=$TODAY" -H "Authorization: Bearer $TB" | jq '.members[]|{memberId,proofStatus}'

# 2) 스티커 ON → 201, 재요청 OFF → 200 + ST-2 카운트 감소
curl -s -X POST $BASE/api/proofs/$PID/reactions -H "Authorization: Bearer $TB" \
  -H 'Content-Type: application/json' -d '{"stickerType":"CLAP"}' -o /dev/null -w '%{http_code}\n'  # 201, 재실행 200
curl -s $BASE/api/proofs/$PID/reactions -H "Authorization: Bearer $TB" | jq .total

# 3) PF-4 URL 발급 → raw 200 / 같은 URL을 타 그룹 토큰 자격으로 → 403 / DEV-2 +6분 → 403 INVALID_MEDIA_TOKEN
URL=$(curl -s $BASE/api/proofs/$PID/media -H "Authorization: Bearer $TB" | jq -r .url)
curl -s -o /dev/null -w '%{http_code}\n' "$BASE$URL"          # 200
curl -s -X POST $BASE/api/dev/clock -d '{"offsetMinutes":6}'; curl -s "$BASE$URL" | jq .error.code

# 4) HOLD 인증에 스티커 → 404 PROOF_NOT_FOUND (B 토큰)
```

### 예상 작업량
파일 약 10개(media token 1 · service 2 · controller 2 · repository 1 · dto 4). 커밋 3개 제안: PF-3 / PF-4·4r / 스티커.

---

## 6단계 — 대시보드

**만드는 것**: 그룹 진행 현황판 — 개인 인증률, 멤버별 일자 그리드, 일자별 인증 수.
**대상 API**: DB-1 `getDashboard` (+ 계약 밖: DEV-3 인증 시드)
**대상 테이블**: 신규 없음
**선행 조건**: 4단계. (5단계와 독립이지만 순서 유지 — 열람 마스킹 규칙을 그리드가 재사용한다)
**강의 포인트**: 집계 쿼리와 분모 정의 — "무엇으로 나누는가"가 지표의 의미를 결정한다.

### 작업 순서
1. `common/dev/DevProofSeedController`(DEV-3) — 과거 일자 APPROVED 인증 시드. **PF-2는 당일 1건만 받으므로
   이것 없이는 이 단계와 7·8단계의 게이트 데이터를 만들 수 없다.** 먼저 만들어야 DB-1을 검증할 수 있다.
2. `group/service/DashboardService` + `GroupController`에 DB-1 추가 + dto — 집계는 `idx_proof_date`를 타는
   조회 후 애플리케이션 조립(QueryDSL 금지, CLAUDE.md §4-1).

### 이 단계의 함정
- **분모는 `periodDays` 고정.** 경과 일수로 나누는 `achievementRate`는 폐기된 개념이다 — 15일차에 15일 전부
  인증해도 30일 챌린지면 0.5다. 순위 필드도 없다(경쟁 지표를 만들지 않는 서비스 결정).
- **DEV-3 시드는 정식 경로로 INSERT해야 한다** — `daily_slot` 생성 컬럼과 `uk_proof_daily`를 그대로 통과시킨다.
  제약을 우회한 시드는 8단계 판정 게이트를 오염시킨다.
- 인증으로 세는 상태는 APPROVED만(daily_slot 점유 중 PENDING_REVIEW는 미확정이므로 그리드에는 상태 그대로,
  인증률 분자에는 미포함 — 8단계 판정 규칙과 동일 기준).

### 게이트 (전부 통과해야 다음 단계)
```bash
# 1) 시작 직후 → proofRate 0.0
curl -s $BASE/api/groups/$GID/dashboard -H "Authorization: Bearer $T" | jq .my

# 2) DEV-3로 15일 시드한 30일 챌린지 → 0.5
curl -s -X POST $BASE/api/dev/proofs/seed -H 'Content-Type: application/json' \
  -d "{\"groupId\":$GID,\"memberId\":$MID,\"dates\":[...15개...]}"
curl -s $BASE/api/groups/$GID/dashboard -H "Authorization: Bearer $T" | jq .my.proofRate

# 3) 비멤버 → 403 / groupDaily의 provedCount가 시드와 일치
```

### 예상 작업량
파일 약 5개(dev 1 · service 1 · dto 3). 커밋 2개 제안: DEV-3 / DB-1.

---

## 7단계 — AI 실물

**만드는 것**: 4단계 스텁을 실물로 교체 — 얼굴 탐지, 이미지 검열, 진위(pHash+관련성), 텍스트 검열.
**대상 API**: 신규 없음 (PF-2 내부 교체. 텍스트 검열은 9·9.5단계의 RP-1·PB-1이 쓸 인터페이스만 실물화)
**대상 테이블**: 신규 없음 (ai_judgment · ai_review_queue 기록 본격화)
**선행 조건**: 4단계. **외부 의존: AI 벤더 선정·API 키 발급(팀 확정 대상)** — 늦어지면 8단계와 교환(순서 재검토 절 참조).
**강의 포인트**: 외부 API 장애 격리 — fail-closed의 비용(가용성)과 이득(신뢰)을 맞바꾸는 결정.

### 작업 순서
1. `ai/client/` 실물 구현 — 얼굴 탐지·이미지 검열(클라우드 moderation)·텍스트 검열. 스텁과 같은 인터페이스라
   `@Profile` 스위치로 교체된다(dev 게이트 재현성을 위해 스텁은 남긴다).
2. 진위 검사: `ai/PerceptualHash`(pHash 계산) + ProofService 대조 로직 — `idx_proof_phash`로 동일 회원 이력을
   좁힌 뒤 **해밍 거리를 애플리케이션에서 계산**(임계 `phash-hamming-threshold: 5`). DB 정확 일치가 아니다.
3. 관련성 판정 — shadow 모드로 시작(`relevance-shadow: true`, 점수 로깅만). 임계값이 잡히면 yml만 바꿔 차단 전환.
4. BLOCKED → CENSORSHIP 큐 적재 경로 실측(4단계에 이미 있음 — 실물 판정으로 재검증).

### 이 단계의 함정
- **모든 AI 실패는 fail-closed(502).** 타임아웃·5xx에서 몰래 통과시키면 검열이 뚫린 것을 아무도 모른다.
  대신 502는 재제출 가능해야 하고, tx2에서 HOLD 강등 후 반환한다(4단계 규칙 유지 — 그날이 잠기지 않는다).
- **pHash를 정확 일치로 구현하면 게이트는 녹색인데 기능은 무력하다.** 리사이즈·재압축한 같은 사진이
  통과한다 — 해밍 거리 임계 비교가 본체다.
- **관련성은 차단이 최종 목표다**(api-spec §7 — AI가 서비스 핵심 엔진). shadow는 튜닝 기간 한정이며,
  전환은 코드가 아니라 `ai.relevance-shadow: false`로 한다.
- 벤더 응답 스키마를 `AiClient` 반환 타입 안쪽에 가둔다 — ProofService가 벤더 DTO를 알면 교체 비용이 서비스 전체로 번진다.

### 게이트 (전부 통과해야 다음 단계)
```bash
# 1) 유해 샘플 → 422 CONTENT_BLOCKED + BLOCKED 행 + ai_review_queue(CENSORSHIP, PENDING) 생성 (H2 콘솔)
# 2) 같은 사진 2일 연속 (DEV-2로 다음날 이동) → 2번째 200 HOLD + guide
# 3) 같은 사진을 리사이즈해서 제출 → HOLD (해밍 거리 검증 — 이 게이트가 pHash 구현의 진짜 판정)
# 4) 얼굴 사진 → 422 + proof 0건·파일 0건 (실물 탐지로 재실측)
# 5) 관련성 낮은 사진 → 통과 + 로그에 점수 기록 (shadow 확인)
# 6) AI 벤더 키를 무효값으로 바꿔 기동 → 제출 502 AI_SCREENING_UNAVAILABLE (fail-closed 실측)
```

### 예상 작업량
파일 약 8개(client 3 · pHash 1 · 설정 2 · 서비스 수정 2). 커밋 3개 제안: 얼굴·검열 실물 / 진위 pHash / 관련성 shadow.

---

## 8단계 — 종료·완주 판정

**만드는 것**: 그룹 종료 배치(B1)와 이중 기준 완주 판정, 완주 리포트, AI 검토 큐 콘솔.
**대상 API**: FR-1 `getFinalReport` · AD-7 `getAiReviews`/`decideAiReview` (+ B1 · B6)
**대상 테이블**: final_report · completion_stats
**선행 조건**: 6단계 (DEV-3 시드가 판정 데이터 공급원). **T1-4·T1-6은 위 가정으로 진행 — 확정 시 재실측.**
**강의 포인트**: 배치 멱등성 — "다시 실행해도 같은 결과"를 UNIQUE 제약으로 강제하는 법. BigDecimal 판정.

### 작업 순서
1. `group/service/GroupClosingBatch`(B1, 매일 00:05) — 절차: ①B7 선행 실행 ②`end_date < 오늘`인 ACTIVE 선정
   ③ENDED 전이 ④잔류 ACTIVE→COMPLETED ⑤`proof_media.participant_view_end_at` 기록 ⑥그룹 평균 계산 —
   **경계면 그룹 단위 1건만 큐 적재(`member_id=NULL`)하고 그 그룹의 개인 판정 중단** ⑦명확하면 개인 판정
   ⑧`ai_judgment(COMPLETION)` INSERT 후 `final_report`·`completion_stats` 생성. 판정 로직은 별도
   `report/service/CompletionJudge`로 분리 — AD-7 재개 경로가 같은 코드를 호출해야 한다.
2. FR-1: `report/service/ReportService` + controller + dto — 검사 순서 4단 고정(함정 참조).
3. AD-7: `ai/controller/AiReviewAdminController` + service — CONFIRM/OVERRIDE 분기표(api-spec §AD-7).
   **COMPLETION 그룹 경계 확정 트랜잭션에서 `CompletionJudge`를 이어 실행해 리포트를 생성한다.**
4. `group/service/ReportRecalcBatch`(B6, 이벤트 구동) — 종료 그룹 proof가 APPROVED로 바뀔 때 리포트·통계 재계산.

### 이 단계의 함정
- **B1은 `end_date < 오늘`만 본다** — 종료일 당일이 아니라 다음날 00:05에 닫는다. 종료일 23:59 인증을
  놓치지 않기 위해서다. B7 선행은 SCREENING 잔류분을 HOLD로 정리해 분자 집합을 확정하는 절차다.
- **B1이 이미 ENDED인 그룹에 다시 오지 않으므로, AD-7 확정이 판정 재개 주체다.** 이 연결이 없으면
  경계 그룹의 리포트가 영구 202에 머문다(설계 검증에서 잡힌 치명 결함 2호).
- **멱등의 근거는 코드가 아니라 제약이다**: 큐는 `uk_arq`(member_key=COALESCE(member_id,0)),
  리포트는 `uk_fr` upsert. B1 재실행 게이트가 이를 실측한다.
- **BigDecimal 경계**: `proof_rate DECIMAL(5,4)` vs 기준 0.7 — double로 계산하면 0.6999…가 나온다.
  나눗셈은 `BigDecimal.divide(…, 4, RoundingMode.HALF_UP)`, 비교는 `compareTo`. 경계 구간(±0.05) 판정도 동일.
- **criteria 스냅샷**: 판정 당시의 `personal-proof-rate`·`group-avg-proof-rate`를 `criteria_*` 컬럼에 저장한다.
  T1-4로 기준이 나중에 바뀌어도 기존 리포트가 소급 오염되지 않는 장치다.
- **그룹 평균 분모 n = COMPLETED 수. n=0이면 0.0000·그룹 기준 미충족**(전원 이탈 그룹). 리포트 생성 대상은
  COMPLETED + REPORT_EXIT(T1-1 확정 — 정당한 신고자가 완주를 잃지 않게), 단 REPORT_EXIT는 평균 분모에서 제외.
  분모가 두 종류라 응답 필드명도 분리돼 있다(`avgDenominator` ≠ `memberCount`).
- **FR-1 검사 순서: ①그룹 존재 → ②ENDED(아니면 409) → ③멤버십 COMPLETED·REPORT_EXIT(아니면 403, LEFT 포함)
  → ④리포트 존재(없으면 202).** ②③을 바꾸면 진행 중 그룹의 참가자가 자기 그룹에서 403을 받는다.
- HOLD·PENDING_REVIEW는 미인증 취급. 사후 AD-7 승인으로 APPROVED가 되면 B6가 정정한다.
- `badgeCode`는 저장하지 않는 파생값. **`completed=false`면 무조건 NONE** — 전제 없이 인증률만 보면
  미완주자가 GOLD를 달고 게시판에 나간다.

### 게이트 (전부 통과해야 다음 단계)
```bash
# 준비: DEV-3로 멤버별 인증 시드(명확 완주/명확 미달/경계 케이스 조합) → DEV-2로 end_date+1 이동
curl -s -X POST $BASE/api/dev/batches/B1

# 1) 명확 그룹: FR-1 200 → provedDays·proofRate·completed를 손계산과 대조. completed=false면 badgeCode=NONE
curl -s $BASE/api/groups/$GID/report -H "Authorization: Bearer $T" | jq '{personal,group,completed,badgeCode}'

# 2) 경계 그룹: FR-1 → 202 REPORT_NOT_READY + ai_review_queue에 COMPLETION·member_id NULL 1건만
# 3) AD-7 확정 → 같은 그룹 final_report N건이 그 자리에서 생성 (재개 주체 검증)
curl -s -X PATCH $BASE/api/admin/ai-reviews/$RID -H "Authorization: Bearer $ADMIN" \
  -H 'Content-Type: application/json' -d '{"decision":"CONFIRM"}'
curl -s $BASE/api/groups/$GID/report -H "Authorization: Bearer $T" | jq .completed   # 200

# 4) 멱등: B1 재트리거 → final_report·큐 행 수 불변 (H2 콘솔 COUNT 대조)
# 5) LEFT 멤버 FR-1 → 403 / 완주자 매칭 재요청 → 201
# 6) 참여자 토큰으로 AD-7 → 403 FORBIDDEN_ROLE
```
T1-4·T1-6 확정 시 재실측: 판정식·인원 미달 분기. REPORT_EXIT 케이스는 9단계 후 재실측(신고 경로가 없어 아직 못 만든다).

### 예상 작업량
파일 약 12개(batch 2 · judge 1 · service 2 · controller 2 · repository 2 · dto 3).
커밋 4개 제안: B1·판정 / FR-1 / AD-7·재개 / B6.

---

## 9단계 — 신고·안전·제재

**만드는 것**: 신고 접수(신고자 즉시 퇴장), 관리자 신고 콘솔, 제재 적용, 관리자 열람 감사.
**대상 API**: RP-1 `createReport` · AD-1 `getAdminReports` · AD-2 `getAdminReportDetail` · AD-3 `processReport` · AD-4 `createSanction` · AD-5 `getAdminProofMedia` · AD-6 `hideProof`/`unhideProof` (+ B3 SLA 배치. AD-6 게시글 분기는 9.5)
**대상 테이블**: report_case · report · report_history · media_access_log (sanction은 1단계 조회 연결 완료 — 쓰기 경로가 여기)
**선행 조건**: 8단계 (REPORT_EXIT의 리포트 포함 규칙 실측에 B1 필요). 관리자 계정은 DB 수동 UPDATE로 생성(1단계 결정).
**강의 포인트**: 악용 방지 설계 — "누구를 보호하는 장치인가"를 상태 머신에 새기는 법.

### 작업 순서
1. `report/repository` 4종 + `report/service/ReportService`에 RP-1 — 접수 절차: detail 텍스트 검열(위험 시
   **detail만 제거하고 접수는 진행** — 신고를 막지 않는다) → 자격 검사 → 케이스 병합 or 생성 →
   **신고자 본인 membership → REPORT_EXIT**(T1-1 확정, `exit_case_id` 기록).
2. `report/service/SanctionService` — **제재 적용 단일 메서드**: ①sanction INSERT ②진행 그룹 LEFT(SANCTION)
   ③활성 매칭 요청 CANCELLED(2단계 3종 세트 재사용). AD-3와 AD-4가 같은 메서드를 호출해야 하므로 콘솔보다 먼저.
3. `report/controller/ReportAdminController` — AD-1(필터·페이징)·AD-2(대상 도출 분기)·AD-3(PENDING만,
   SANCTIONED면 2 호출, REJECTED면 신고자 `restriction_review` 플래그)·AD-4.
4. AD-5·AD-6: `proof/controller/ProofAdminController` — 열람 3조건 + `media_access_log` 기록, hide/unhide 전이.
5. `report/service/SlaOverdueBatch`(B3) — `sla_due_at < now`인 PENDING → `overdue=1`, HIGH는 `restriction_review=1`.

### 이 단계의 함정
- **즉시 조치 대상은 신고자다, 피신고자가 아니다**(T1-1 확정의 핵심). 피신고자는 AD-3 SANCTIONED 전까지
  아무 조치도 받지 않는다 — 신고 즉시 상대를 차단하면 "아무나 신고해서 남을 쫓아내는" 악용이 성립한다.
  `targetType=POST` 신고는 신고자 퇴장도 없다(그룹과 무관).
- **병합 시 severity 상향이면 `sla_due_at`을 재계산한다.** NORMAL 케이스에 HIGH 신고가 합류했는데 72h SLA가
  그대로면 고위험 24h 약속이 깨진다. 병합 감지는 `uk_rc_open`(대상당 PENDING 1건)으로.
- **AD-3는 PENDING만, 재오픈 불가.** 종결 시 `open_target_id=NULL` — 재오픈을 허용하면 uk_rc_open 충돌
  경로가 되살아난다. 재검토는 새 케이스다.
- **`targetType=POST`는 그룹 자격 검사 면제·`group_id` NULL 허용.** 게시판이 9.5 산출물이므로 이 단계에서
  POST 신고는 404 `TARGET_NOT_FOUND`가 정상이다 — 분기 코드는 지금 넣고 실측은 9.5 게이트에서.
- **AD-5 열람 3조건**(신고 대상 / BLOCKED+CENSORSHIP 큐 PENDING / AD-7 큐 대상) 밖이면 400. `media_access_log`의
  `case_id`는 NULL 허용(AI 자동 차단 건) — NOT NULL로 조이면 오탐 구제가 불가능해진다.
- 제재의 그룹 퇴장 LEFT(SANCTION)는 **그룹 평균 분모에서 제외**된다(COMPLETED가 아니므로 자동 성립) —
  8단계 판정 코드를 고칠 것은 없지만 게이트로 실측한다.

### 게이트 (전부 통과해야 다음 단계)
```bash
# 1) A가 B의 인증 신고 → 201 caseId + A의 GR-2 즉시 403 (REPORT_EXIT) / B는 인증 제출 정상 (무조치 확인)
curl -s -X POST $BASE/api/reports -H "Authorization: Bearer $TA" -H 'Content-Type: application/json' \
  -d "{\"targetType\":\"PROOF\",\"targetId\":$PID,\"reasonCode\":\"SPAM_PROOF\"}" | jq .caseId

# 2) C가 같은 대상 신고 → 같은 caseId에 병합 / HIGH 사유면 severity 상향 + slaDueAt 단축 확인 (AD-2)
# 3) A 재신고 → 409 DUPLICATE_REPORT
# 4) DEV-2로 SLA 경과 → B3 → AD-1 ?overdue=true에 노출
curl -s -X POST $BASE/api/dev/batches/B3
# 5) 참여자 토큰으로 AD-1~AD-7 각각 → 403 FORBIDDEN_ROLE
# 6) AD-3 SANCTIONED(TEMP 7일) → 대상 로그인 API 403 MEMBER_SANCTIONED + membership LEFT(SANCTION)
#    + 그 그룹 B1 판정에서 평균 분모 제외 실측 / AD-3 REJECTED → 케이스 restriction_review=1
# 7) AD-5: 조건 미충족 proof → 400 NOT_REPORTED_PROOF, 충족 → 200 + media_access_log 1행
# 8) 8단계 재실측: 신고자(REPORT_EXIT)가 있는 그룹 B1 → 그의 final_report 생성 + 평균 분모 제외
```

### 예상 작업량
파일 약 14개(repository 4 · service 3 · controller 2 · dto 4 · batch 1).
커밋 4개 제안: RP-1·신고자 퇴장 / 제재 서비스·AD-3·4 / AD-1·2·5·6 / B3.

---

## 9.5단계 — 완주 자랑 게시판·공유

**만드는 것**: 완주 리포트 보유자만 쓰는 전체 공개 게시판 — 수치는 서버가 보증하는 위조 불가 스냅샷.
**대상 API**: PB-1 `createPost` · PB-2 `getPosts` · PB-3 `getPost` · PB-4 `togglePostLike` · PB-5 `deletePost` · AD-6 `hidePost`/`unhidePost`
**대상 테이블**: challenge_post · post_like
**선행 조건**: 8단계 (final_report가 원천), 9단계 (AD-6·POST 신고 분기)
**강의 포인트**: 프라이버시를 고려한 스냅샷 — 무엇을 복사하고, 무엇을 새로 만들고, 무엇을 뭉개서 내보내는가.

### 작업 순서
1. `post/repository` 2종 + `post/service/PostService` — PB-1 검증 순서(그룹 존재→ENDED→본인 리포트→중복) +
   소감 2단 검열(①정규식 선차단: 전화번호·카톡ID·`@핸들`·URL·이메일 ②AI 텍스트 검열 — 7단계 실물) +
   스냅샷 복사(final_report에서 4컬럼, group에서 category) + `author_alias` 신규 생성.
2. PB-2~5 + `post/controller/PostController`. 목록은 `PageResponse<T>`(Spring `Page` 직렬화 금지, §0-1).
3. AD-6 게시글 분기: `post/controller/PostAdminController` — hide→HIDDEN, unhide→VISIBLE, DELETED는 불가.
4. 9단계에 넣어둔 `ReportTargetType.POST` 분기 실측(코드 추가 없음, 게이트만).

### 이 단계의 함정
- **수치는 클라이언트에서 받지 않는다.** proofRate·provedDays·completed는 서버가 `final_report`에서 복사 —
  요청 본문에 수치 필드가 있으면 그 자체가 결함이다. B6 정정 시 이 스냅샷 5컬럼도 함께 갱신된다.
- **`author_alias`는 게시 시점 신규 생성. `member.nickname` 재사용 금지** — 같은 분야·기간·작성일 글을 묶으면
  외부인이 6인 그룹 구성원과 각자의 인증률을 재구성할 수 있고, 신고 후 퇴장한 사람이 추적된다.
  같은 이유로 **PB-2의 시각은 일 단위만** 노출한다.
- **인증 사진은 게시 대상이 아니다.** 게시판에 미디어 경로를 만들지 않는다(공유 카드는 프론트가 PB-3 데이터로 생성 — 백엔드 작업 없음).
- **미성년 접근 분기가 인터셉터에 이미 있다**(1단계 SKIP_RULES): PB-2·3 열람 허용, PB-1·4 차단. 새 코드가 아니라 확인 항목이다.
- PB-5는 soft delete(DELETED) + `uk_post_member_group` 때문에 **삭제 후 같은 그룹 재작성 불가** — 사용자 고지 필요 사항.
- hide된 글은 목록 제외만으로 부족하다 — **PB-3 직접 호출도 404** `POST_NOT_FOUND`(VISIBLE 아니면 전부).

### 게이트 (전부 통과해야 다음 단계)
```bash
# 1) 리포트 보유자 작성 → 201 + DB의 proof_rate가 final_report와 일치 (H2 콘솔 대조)
curl -s -X POST $BASE/api/posts -H "Authorization: Bearer $T" -H 'Content-Type: application/json' \
  -d "{\"groupId\":$GID,\"comment\":\"완주했습니다\"}" | jq .postId
# 2) 재작성 → 409 DUPLICATE_POST / 진행 중 그룹 → 409 GROUP_NOT_ENDED / 리포트 없는 회원 → 409 POST_REPORT_REQUIRED
# 3) 소감에 "인스타 @abc 팔로우" → 422 CONTENT_BLOCKED (정규식 선차단 — AI 호출 없이)
# 4) 목록: authorAlias ≠ nickname, createdDate에 시각 없음 / 좋아요 ON 201 → OFF 200
# 5) AD-6 hide → PB-2에서 제외 + PB-3 직접 호출 404 / unhide → 복구
# 6) 미성년 토큰(2013년생): PB-2 200 · PB-3 200 · PB-1 403 AGE_NOT_VERIFIED · 게시글 신고 201 (9단계 POST 분기 실측)
```

### 예상 작업량
파일 약 10개(repository 2 · service 1 · controller 2 · dto 5). 커밋 3개 제안: PB-1 작성·검열 / PB-2~5 / AD-6 게시글.

---

## 10단계 — 탈퇴 완결 (배치·익명화)

**만드는 것**: 유예 만료 회원의 익명화 삭제(B4), 미디어 물리 삭제(B5), 관리자 탈퇴 콘솔.
**대상 API**: AD-8 `getWithdrawals` (+ B4 · B5. AU-4·AU-5는 1단계 구현 완료 — 여기서는 동반 처리 완결과 재실측만)
**대상 테이블**: blocked_social_hash 쓰기 경로 (조회는 1단계에 있음)
**선행 조건**: 9단계 (등재 대상 = PERMANENT 제재 이력자), 9.5단계 (B4의 challenge_post DELETED 전이)
**강의 포인트**: 개인정보 삭제 설계 — "지운다"가 컬럼 NULL이 아닌 이유(제약·재가입·감사가 얽힌다).

### 작업 순서
1. `member/service/WithdrawalBatch`(B4) — `delete_scheduled_at < now`인 WITHDRAW_PENDING 대상:
   익명화 + DELETED 전이 + 동반 4종(①PERMANENT 제재 이력자만 `blocked_social_hash` 등재
   ②`challenge_post` → DELETED ③`proof_media.delete_scheduled_at` 앞당김 ④`media_consent` 삭제).
2. `proof/service/MediaPurgeBatch`(B5) — 예정일 지난 미디어 삭제, legal hold 대상 보류.
3. `member/controller/MemberAdminController`(AD-8) — 탈퇴 처리 결과 목록.

### 이 단계의 함정
- **익명화는 NULL 세팅이 아니라 치환이다**: `provider_user_id='deleted:{id}'`, 닉네임 '탈퇴회원',
  `birth_date`만 NULL. NOT NULL 제약과 `uk_member_provider`(같은 소셜 계정 재가입) 때문에 값 치환이어야 한다.
- **`blocked_social_hash` 등재는 PERMANENT 제재 이력자만.** TEMP까지 등재하면 일시 제재가 영구 재가입
  차단으로 승격된다. 해시는 HMAC(pepper=환경변수) — pepper 없는 sha256은 역산 가능하다.
- **B5의 legal hold 해제 규칙**: 보류 = 미처리 신고 대상 또는 BLOCKED(미복구) / 해제 = 케이스 종결+30일,
  BLOCKED 확정+30일. **무기한 보존 금지** — 가장 민감한 이미지만 영구 잔존하는 역설을 막는다.
- 재로그인 복구(RESTORED)는 1단계 AU-1에 이미 있다 — B4가 먼저 지나갔으면 복구 불가(DELETED는 종점)라는
  경계 실측이 이 단계 몫이다.

### 게이트 (전부 통과해야 다음 단계)
```bash
# 1) AU-4 → 202 + 참여 API(MT-1) 403 WITHDRAWAL_PENDING / 재로그인 → loginResult=RESTORED + 시각 컬럼 NULL
# 2) 신청 후 DEV-2 +31일 → B4 트리거 → H2 콘솔: provider_user_id='deleted:{id}'·birth_date NULL·
#    media_consent 0행·해당 회원 challenge_post DELETED·proof_media.delete_scheduled_at 앞당겨짐
curl -s -X POST $BASE/api/dev/batches/B4
# 3) PERMANENT 제재 이력자 탈퇴·B4 후 같은 계정 로그인 → 403 REJOIN_BLOCKED / 제재 없던 회원은 신규 가입 성공
# 4) B5 트리거 → 예정일 지난 파일만 삭제, 미처리 신고 대상 파일은 잔존 → PF-4 410 MEDIA_DELETED
curl -s -X POST $BASE/api/dev/batches/B5
# 5) AD-8 → 처리 이력 목록 200 (관리자), 참여자 403
```

### 예상 작업량
파일 약 6개(batch 2 · controller 1 · dto 2 · repository 메서드 추가 1). 커밋 3개 제안: B4 / B5 / AD-8.

---

## 11단계 — 운영 준비

**만드는 것**: dev 대체물 3개(소셜·스토리지·DB)를 실물로 교체하고 배포한다. 응답 계약은 하나도 바뀌지 않는다.
**대상 API**: AU-1 `login` 카카오 실연동 (신규 엔드포인트 없음)
**대상 테이블**: 신규 없음
**선행 조건**: 10단계 (전 기능 완결 후 전환). 외부 의존: 카카오 앱 키, S3 버킷, MySQL 인스턴스, 배포 대상(Cloudtype → AWS).
**강의 포인트**: 개발/운영 환경 차이 — H2 통과가 MySQL 통과를 보장하지 않는 이유(잠금·격리 수준 구현 차이).

### 작업 순서
1. `auth/client/KakaoSocialClient` — `SocialClient`의 두 번째 구현(인터페이스를 만들어 둔 이유가 여기서 실현).
   `DevSocialClient`는 dev 프로필에 남는다.
2. `common/storage/S3MediaStorage` — `MediaStorage` 교체. PF-4 응답 계약(서명 URL 구조) 불변.
3. MySQL 전환: 운영 프로필 datasource + **`ddl-auto=update` 금지, db-schema.md의 DDL 스크립트로 생성** +
   생성 컬럼·UNIQUE 제약 실생성 확인.
4. **동시성 재실측**: 2단계 게이트 2(6스레드 동시 매칭)를 MySQL에서 재실행. H2와 잠금 구현이 다르다.
5. CORS 설정(프론트 도메인), 운영 프로필 검증, 배포.

### 이 단계의 함정
- **시크릿 폴백 금지는 1단계 검수에서 이미 확보됐다**(base yml 폴백 제거, dev 값은 `application-dev.yml`).
  이 단계에서는 실측만 한다: 운영 프로필 + 환경변수 3종 미설정 → **기동 실패**가 정답이다.
- dev 이중 스위치: 운영 프로필은 `@Profile("dev")` 빈 자체가 없고 `morak.dev.enabled=false`(base 기본값). 하나만 믿지 않는다.
- MySQL 생성 컬럼 문법·`DECIMAL` 반올림·`utf8mb4` 인덱스 상한(191)은 db-schema.md에 반영돼 있다 —
  ddl-auto가 아니라 스크립트를 쓰는 이유다.
- 카카오 검증 실패는 401 `INVALID_SOCIAL_TOKEN` 그대로 — DevSocialClient가 빈 코드로 이 경로를 재현하게
  만들어 둔 것이 여기서 회귀 테스트가 된다.

### 게이트 (전부 통과해야 배포)
```bash
# 1) 카카오 실계정 e2e: 로그인 → 매칭 → 인증 → 열람 1회전
# 2) 운영 프로필 기동: 환경변수 미설정 → 기동 실패 실측 / 설정 후 기동 → /api/auth/dev 계열·
#    /api/dev/clock·/api/dev/batches/B1·/api/dev/proofs/seed 각각 404
# 3) MySQL 6스레드 동시 매칭 → 정확 6인 (2단계 게이트 재실행)
# 4) S3: 제출 → PF-4 열람 200 (응답 스키마 diff 없음) / H2 콘솔 경로 운영 404
```

### 예상 작업량
파일 약 8개(client 1 · storage 1 · 운영 yml·DDL 3 · CORS 1 · 문서 2). 커밋 4개 제안: 카카오 / S3 / MySQL·DDL / 배포 설정.

---

## 컷 라인 (일정 압박 시 — 명세 위반 표기 필수)

버리는 순서: ①AI 관련성 차단(shadow 로깅만 남김) → ②AD-8 → ③카카오 외 소셜 3종 →
④9.5단계 게시판 전체(기능명세 영역 8 미구현 — 팀장 요구라 승인 필수).
**못 버리는 것**: 2단계 매칭 / 4·5·7단계 인증·AI / 8단계 완주 판정 / 9단계 신고·제재 / 10단계 B4·B5(법적) / 1단계 연령 검증(법적).

## 우선순위

①매칭 엔진(2) — 동시성 최고 난도, 모든 단계의 데이터 원천 ②인증+AI(4·5·7) — 서비스 신뢰의 심장
③완주 판정(8) — 지표 직결. 나머지는 표준 CRUD+배치다.
