# MoLock DB 스키마 v2.0 (2026-08-12)

기획이 사진 인증 챌린지에서 실시간 캠 스터디로 바뀌면서 스키마의 절반이 근거를 잃었다. 인증 사진과 AI 판정을 중심으로 짜여 있던 14개 테이블(challenge_group·group_member·proof·proof_media·ai_judgment·ai_review_queue·sticker_reaction·media_access_log·final_report·completion_stats·challenge_post·post_like·kick_history·fill_in_history)을 폐기하고, 라이브 세션·자리비움 경고·포인트 원장·스토어를 담는 16개 테이블을 새로 넣었다. 회원·매칭·신고 계열 10개는 정의를 승계하되 그룹(challenge_group) 참조를 세션(live_session) 참조로 바꾸고, 시간 단일 매칭에 맞춰 조건 컬럼을 줄였다. 총 24개 테이블이다.

> 자기완결 문서. 구 판본(v1.0)은 `docs/archive/`. 계약 정본은 `docs/api-spec.md`(enum·계산식·상태 전이는 그쪽이 유일 정의처, 여기 주석은 참조).
> MySQL 8 / InnoDB / utf8mb4. 개발은 H2 2.4.240(`MODE=MySQL;LOCK_TIMEOUT=3000`).
> **UNIQUE 제약은 엔티티 `@Table(uniqueConstraints=…)`에도 반드시 선언한다** — `ddl-auto=update`만 믿으면 개발 DB에 방어선이 생기지 않아 게이트가 무의미해진다.
> 개수는 §목록의 행 수로만 말한다.
> 잠정 결정에 걸린 테이블은 **(잠정 — 팀 확인 대기)** 로 표기했다. 표기된 테이블은 팀 회신에 따라 구조가 바뀔 수 있다.

## 실행 순서

DDL은 이 문서 순서대로 위→아래 실행 가능하다(FK 대상이 항상 선행). 단 회원 계열에서 세션을 참조하는 `streak_day → live_session`, 매칭에서 세션을 참조하는 `match_request → live_session` 두 FK만 말미 `ALTER`로 분리했다.

---

## 도메인 그룹핑

| 도메인 | 테이블 | 수 |
|---|---|---|
| member | member, member_agreement, member_goal, streak_day, media_consent, blocked_social_hash | 6 |
| match | match_lock, match_request, match_block, match_event | 4 |
| session | live_session, session_participant, absence_event, warning, eviction, appeal_case | 6 |
| point | point_ledger, point_charge | 2 |
| store | product, store_order | 2 |
| report | report_case, report, report_history, sanction | 4 |

패키지는 `com.morak.{member,match,session,point,store,report}` 에 대응한다(D22 — 코드 패키지명은 morak 유지).

## 관계도

```
member ─┬─< member_agreement          (약관 동의, 종류당 1건)
        ├─< member_goal               (목표 기간, 활성 1건)
        ├─< streak_day >── live_session   (완주한 날, 하루 1건)
        ├─1 media_consent             (캠 분석 동의, 회원당 1건)
        ├─< match_request >── live_session (성사 시 배정)
        ├─< match_block                (신고 부수효과, 양방향 2행)
        ├─< match_event                (지표 원천)
        ├─< point_ledger               (포인트 진실 원천)
        ├─< store_order >── product
        ├─< point_charge               (PG 충전)
        ├─< report (reporter)
        └─< sanction

live_session ─< session_participant    (6인, 세션당 회원 1행)
             ├─< absence_event         (얼굴 미검출 보고)
             ├─< warning >── absence_event  (경고 1~3회)
             └─< eviction ─1 appeal_case    (퇴출 · 이의 1회)

report_case ─┬─< report                (같은 대상 신고를 케이스로 병합)
             ├─< report_history        (처리 이력)
             └─< sanction              (근거 케이스, NULL 허용)

match_lock  (참조 없음 — 행 잠금 전용)
```

`session_participant`가 세션 도메인의 중심이다. 세션 결과(완주 여부·지급 포인트)는 별도 테이블 없이 이 테이블의 `completed`·`point_awarded`에서 파생한다.

---

## 1. 회원 (member)

```sql
CREATE TABLE member (
    id                    BIGINT       NOT NULL AUTO_INCREMENT,
    provider              VARCHAR(20)  NOT NULL,   -- SocialProvider
    provider_user_id      VARCHAR(191) NOT NULL,   -- 191 = utf8mb4 인덱스 안전 상한
    nickname              VARCHAR(30)  NOT NULL,   -- 표시용 익명 닉네임(서버 생성). 타인에게 보이는 화면은 전부 이 값만 사용
    sns_nickname          VARCHAR(50)  NULL,       -- SNS 원본. 본인 확인용 — 타인 노출 금지
    sns_profile_image_url VARCHAR(500) NULL,       -- SNS 원본. 동일 규칙. B4가 두 컬럼을 함께 삭제
    role                  VARCHAR(20)  NOT NULL DEFAULT 'PARTICIPANT',   -- MemberRole
    birth_date            DATE         NULL,
    age_verification      VARCHAR(20)  NOT NULL DEFAULT 'REQUIRED',      -- AgeVerification
    status                VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',        -- MemberStatus
    point_balance         INT          NOT NULL DEFAULT 0,   -- 캐시. 진실은 point_ledger
    current_streak        INT          NOT NULL DEFAULT 0,   -- 캐시. 진실은 streak_day
    last_completed_on     DATE         NULL,                 -- 마지막 완주일. 연속 판정 기준
    withdraw_requested_at DATETIME(6)  NULL,
    delete_scheduled_at   DATETIME(6)  NULL,       -- 신청 + withdrawal.grace-days(30)
    deleted_at            DATETIME(6)  NULL,
    created_at            DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_member_provider (provider, provider_user_id),
    KEY idx_member_withdraw (status, delete_scheduled_at)
);
```

| 컬럼 | 설명 |
|---|---|
| provider / provider_user_id | 소셜 계정 식별자. 이 쌍이 회원의 정체 |
| nickname | 서버 생성 익명 닉네임. 세션 참가자 목록·신고 화면 등 타인에게 보이는 곳은 예외 없이 이 값만 쓴다 |
| sns_nickname / sns_profile_image_url | SNS 원본. **v1은 저장만 하고 노출 API를 두지 않는다** — 어떤 응답에도 실리지 않는다. B4가 함께 지운다 |
| age_verification | REQUIRED → VERIFIED. 만 14세 미만은 값이 남지 않는다(계정 자체를 만들지 않음, ★D7) |
| point_balance | 잔액 캐시. 차감은 조건부 UPDATE(`WHERE point_balance >= ?`)로만 하고 원장을 함께 쓴다 |
| current_streak / last_completed_on | 연속 완주일 캐시(★D2). B1이 streak_day 기록과 함께 갱신 |
| withdraw_requested_at / delete_scheduled_at / deleted_at | 탈퇴 30일 유예 상태 3종 |

**불변식**
- 소셜 계정 하나에 회원 행은 최대 1개다(`uk_member_provider`).
- `point_balance`는 그 회원의 `point_ledger.delta` 합과 항상 같다. 어긋나면 원장이 옳다.
- `current_streak`는 `streak_day`에서 재계산 가능한 파생값이다. 조회 성능용 캐시일 뿐 판정 근거가 아니다.
- 만 14세 미만 회원 행은 존재하지 않는다. AU-3에서 미만 판정 시 계정을 삭제한다(★D7 — 구 코드의 `AgeVerification.UNDER_AGE` 유지 방식에서 변경. `Member.verifyAge()` 수정 대상).
- B4 삭제 방식은 익명화다: `provider_user_id='deleted:{id}'`, `nickname='탈퇴회원'`, `birth_date=NULL`. 컬럼을 NULL로 비우는 게 아니다(NOT NULL 제약 + `uk_member_provider` 재가입 충돌 방지).
- 익명화 후에도 `point_balance`는 위 불변식을 그대로 지킨다(원장을 남기므로). 반면 `current_streak`·`last_completed_on`은 진실인 `streak_day`가 함께 지워지므로 0·NULL로 비운다.

```sql
CREATE TABLE member_agreement (
    id        BIGINT      NOT NULL AUTO_INCREMENT,
    member_id BIGINT      NOT NULL,
    type      VARCHAR(20) NOT NULL,   -- AgreementType: TOS | PRIVACY | MARKETING
    agreed_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ma (member_id, type),
    CONSTRAINT fk_ma_member FOREIGN KEY (member_id) REFERENCES member(id)
);
```

| 컬럼 | 설명 |
|---|---|
| type | 필수 2종(TOS·PRIVACY) + 선택 1종(MARKETING). 위치정보 약관은 근거가 없어 두지 않는다(D20) |
| agreed_at | 동의 시각. 철회는 행 삭제로 표현 |

**불변식**
- 회원·종류당 동의 행은 최대 1개다(`uk_ma`). 재동의는 upsert.
- TOS·PRIVACY 두 행이 없는 회원은 서비스 API를 쓸 수 없다(AU-1 가입 트랜잭션에서 함께 INSERT, 누락 시 `AGREEMENT_REQUIRED`).
- MARKETING 행의 유무가 곧 마케팅 수신 동의 여부다. 별도 플래그를 두지 않는다.

```sql
CREATE TABLE member_goal (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    member_id   BIGINT      NOT NULL,
    period_days INT         NOT NULL,   -- 7 | 14 | 30
    started_on  DATE        NOT NULL,
    status      VARCHAR(20) NOT NULL,   -- GoalStatus: ACTIVE | ACHIEVED | CANCELLED
    achieved_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    KEY idx_mg_member (member_id, status),
    CONSTRAINT fk_mg_member FOREIGN KEY (member_id) REFERENCES member(id)
);
```

| 컬럼 | 설명 |
|---|---|
| period_days | 목표 기간. 7·14·30 |
| started_on | 목표 시작일. 연속 판정의 기준점 |
| status | ACTIVE 진행 중 · ACHIEVED 달성 · CANCELLED 취소. 재설정은 새 행 |
| achieved_at | B1이 목표 달성 검사에서 기록(★D3) |

**불변식**
- 회원당 `status='ACTIVE'` 행은 최대 1개다. **DB 제약이 아니라 조건부 로직으로 지킨다** — AU-7이 `SELECT ... FOR UPDATE`로 회원 행(`match_lock`의 `member:{id}`)을 잡은 뒤 활성 목표를 확인하고, 있으면 409 `GOAL_ALREADY_ACTIVE`를 낸다.
- 달성(`ACHIEVED`) 이후 그 행은 다시 `ACTIVE`가 되지 않는다. 재도전은 새 행.
- 미완주일이 생기면 연속이 끊길 뿐 목표 행은 `ACTIVE`로 유지된다(★D3). 끊김은 다음 완주 시점에 판정하므로 `current_streak`는 그때 1부터 다시 센다.

```sql
-- (잠정 — 팀 확인 대기: ★D2 Streak 단위)
CREATE TABLE streak_day (
    id           BIGINT NOT NULL AUTO_INCREMENT,
    member_id    BIGINT NOT NULL,
    completed_on DATE   NOT NULL,   -- 완주로 인정된 날짜(morak.timezone 기준)
    session_id   BIGINT NOT NULL,   -- 그날 완주를 성립시킨 첫 세션
    PRIMARY KEY (id),
    UNIQUE KEY uk_streak_day (member_id, completed_on),
    CONSTRAINT fk_sd_member FOREIGN KEY (member_id) REFERENCES member(id)
);
-- fk_sd_session은 live_session 생성 이후 말미 ALTER로 추가한다.
```

| 컬럼 | 설명 |
|---|---|
| completed_on | 날짜. 시각이 아니다. 자정 경계는 `morak.timezone`(Asia/Seoul) 기준 |
| session_id | 근거 세션. 하루에 여러 세션을 완주해도 첫 1건만 남는다 |

**불변식**
- 하루에 몇 세션을 완주하든 행은 1개다(`uk_streak_day`). 두 번째 완주의 INSERT는 제약 위반을 잡아 무시한다 — B1 재실행 멱등의 근거(★D2).
- 행의 존재 = 그날 완주. 행이 없는 날짜는 Streak를 끊는다.
- `member.current_streak`는 `last_completed_on`부터 역방향으로 연속한 행 수와 같다.
- 이의 인용(AD-6)으로 완주가 소급되면 이 테이블에 행을 INSERT하고 캐시를 재계산한다. 반대로 행을 지우는 경로는 없다.

```sql
CREATE TABLE media_consent (
    member_id  BIGINT      NOT NULL,
    agreed_at  DATETIME(6) NOT NULL,
    PRIMARY KEY (member_id),
    CONSTRAINT fk_mc_member FOREIGN KEY (member_id) REFERENCES member(id)
);
-- 동의 문구: "캠 영상은 기기에서만 분석하며 서버에 저장하지 않는다"(D17).
-- B4 시 함께 삭제
```

| 컬럼 | 설명 |
|---|---|
| member_id | 회원당 최대 1건이라 그대로 PK |
| agreed_at | 동의 시각. 철회는 행 삭제 |

**불변식**
- 이 행이 없는 회원은 SS-2 LiveKit 토큰을 받지 못한다(`CONSENT_REQUIRED`).
- 동의 대상은 **온디바이스 얼굴 검출**이지 영상 업로드가 아니다. 서버는 어떤 프레임도 저장하지 않으므로 이 동의로 저장 근거가 생기지는 않는다.

```sql
CREATE TABLE blocked_social_hash (
    social_hash  VARCHAR(64) NOT NULL,   -- HMAC-SHA256(provider + providerUserId, pepper=환경변수). 항상 64자 hex — 실제 생성 스키마와 표기 통일(구판 CHAR(64))
    reason       VARCHAR(30) NOT NULL,   -- SANCTION_PERMANENT
    created_at   DATETIME(6) NOT NULL,
    expires_at   DATETIME(6) NULL,       -- NULL=무기한. 보존 기한은 법무 확인 후 확정
    PRIMARY KEY (social_hash)
);
```

| 컬럼 | 설명 |
|---|---|
| social_hash | 소셜 식별자의 HMAC. 원문은 보유하지 않는다 |
| expires_at | NULL이면 무기한 차단 |

**불변식**
- 등재 대상은 PERMANENT 제재 이력자의 탈퇴 시(B4)뿐이다. TEMP는 등재하지 않는다.
- 원문(`provider_user_id`)은 보유하지 않는다. pepper 없는 순수 SHA-256은 조합이 좁아 역산 가능하므로 HMAC을 쓴다.
- 유효 차단 = `expires_at IS NULL OR expires_at > now`. AU-1 가입 경로가 이 판정을 먼저 통과해야 한다.
- [법무 확인] 영구 보관이 "SNS 식별자 자동 삭제" 약속과 충돌하는지.

---

## 2. 매칭 (match)

```sql
CREATE TABLE match_lock (
    lock_key VARCHAR(80) NOT NULL,   -- "match:{minutes}" | "member:{memberId}"
    PRIMARY KEY (lock_key)
);
-- 행은 런타임에 만들지 않는다.
--   조건 행 4개("match:60","match:120","match:180","match:240")는 기동 시 ApplicationRunner가 시드,
--   회원 행("member:{id}")은 가입 트랜잭션에서 동반 INSERT.
-- (미존재 행 FOR UPDATE는 갭 락이 없어 동시 진입 시 둘 다 0행을 보고 INSERT 경합 → 단일 트랜잭션에서 복구 불가. H2 실측)
```

| 컬럼 | 설명 |
|---|---|
| lock_key | 잠금 대상 이름. 데이터가 아니라 잠금 좌표 |

**불변식**
- 조건 행은 정확히 4개다. 구 스키마의 72개(분야×시간×기간 조합)에서 시간 단일 축으로 줄었다(D8).
- 대기열을 건드리는 모든 트랜잭션은 `SELECT ... FROM match_lock WHERE lock_key='match:{minutes}' FOR UPDATE`를 **먼저** 잡는다. 이 순서를 어기면 6인 확정이 겹친다.
- 회원 단위 직렬화가 필요한 경로(AU-7 목표 설정, MT-1 중복 요청 확인)는 `member:{id}` 행을 잡는다.
- 잠금 획득 순서는 항상 `member:{id}` → `match:{minutes}`다. 역순 획득 경로를 만들면 교착이 생긴다.

```sql
CREATE TABLE match_request (
    id                BIGINT      NOT NULL AUTO_INCREMENT,
    member_id         BIGINT      NOT NULL,   -- 요청한 회원
    target_minutes    INT         NOT NULL,   -- 60 | 120 | 180 | 240
    status            VARCHAR(20) NOT NULL,   -- MatchRequestStatus: WAITING | MATCHED | CANCELLED | EXPIRED
    active_member_id  BIGINT      NULL,       -- WAITING일 때만 member_id, 그 외 NULL
    requested_at      DATETIME(6) NOT NULL,
    expires_at        DATETIME(6) NOT NULL,   -- requested_at + match.wait-expire-minutes(10)
    matched_session_id BIGINT     NULL,       -- 성사 시 배정된 세션
    PRIMARY KEY (id),
    UNIQUE KEY uk_mr_active (active_member_id),   -- 회원당 활성 요청 1건 (이중 배정 DB 방어선)
    KEY idx_mr_queue (status, target_minutes, requested_at),
    KEY idx_mr_expire (status, expires_at),       -- B2
    CONSTRAINT fk_mr_member FOREIGN KEY (member_id) REFERENCES member(id)
);
-- fk_mr_session은 live_session 생성 이후 말미 ALTER로 추가한다.
```

| 컬럼 | 설명 |
|---|---|
| target_minutes | 매칭 조건 전부. 구 스키마의 category·period_days는 폐기 |
| active_member_id | WAITING 동안만 member_id를 담는 그림자 컬럼. UNIQUE의 재료 |
| expires_at | 이 시각까지 6명이 안 모이면 B2가 EXPIRED로 종료 |
| matched_session_id | MT-2 폴링이 MATCHED와 함께 돌려주는 세션 |

**불변식**
- 회원당 WAITING 요청은 최대 1개다(`uk_mr_active`). MySQL·H2 모두 UNIQUE 컬럼의 NULL을 서로 다른 값으로 보므로, 종료된 요청 여러 건은 공존한다.
- `status`를 바꾸는 모든 주체(MT-1·MT-3·B2·AD-4·AU-4)는 예외 없이 ① 조건 행 잠금 ② 조건부 UPDATE(`WHERE status='WAITING'`) ③ `active_member_id=NULL` 셋을 함께 수행한다. **하나라도 빠지면 그 회원의 재요청이 `uk_mr_active`로 영구 차단된다.**
- `status='MATCHED'`인 행은 `matched_session_id`가 NOT NULL이고, 그 외 상태는 NULL이다.
- 대기열 탐색은 같은 `target_minutes` 안에서 `requested_at` 오름차순(선착순)이다.

```sql
CREATE TABLE match_block (
    id                BIGINT      NOT NULL AUTO_INCREMENT,
    member_id         BIGINT      NOT NULL,   -- 이 회원의 대기열에서
    blocked_member_id BIGINT      NOT NULL,   -- 이 회원을 배제한다
    source            VARCHAR(20) NOT NULL,   -- REPORT
    created_at        DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_mb (member_id, blocked_member_id),
    CONSTRAINT fk_mb_member  FOREIGN KEY (member_id) REFERENCES member(id),
    CONSTRAINT fk_mb_blocked FOREIGN KEY (blocked_member_id) REFERENCES member(id)
);
-- RP-1이 신고 시 (신고자→대상), (대상→신고자) 2행을 함께 INSERT한다.
```

| 컬럼 | 설명 |
|---|---|
| member_id / blocked_member_id | 방향이 있는 쌍. 대칭은 2행으로 표현 |
| source | 등재 근거. 현재는 REPORT 하나뿐 |

**불변식**
- 차단은 **영구**다. 해제 경로가 없다(★D6).
- 신고 1건은 항상 2행을 만든다. 한쪽만 들어가면 대기열 방향에 따라 다시 같이 매칭될 수 있다.
- MT-1의 6인 확정은 후보 집합 안의 **모든 쌍**에 대해 이 테이블을 확인한다. 신고자와 대상이 각각 다른 사람과는 매칭될 수 있어야 하므로, 대기열에서 빼는 게 아니라 조합에서 배제한다.
- 신고를 해도 **아무도 진행 중인 세션에서 나가지 않는다**(★D6). 이 테이블은 다음 매칭부터 효력이 생긴다. 구 기획의 "신고자 즉시 퇴장"은 폐기.

```sql
CREATE TABLE match_event (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    member_id   BIGINT      NOT NULL,
    session_id  BIGINT      NULL,       -- 성사 이벤트만 채워진다
    type        VARCHAR(30) NOT NULL,   -- MatchEventType
    occurred_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_me_type (type, occurred_at)
);
-- 지표 원천: 매칭 완료율·대기 이탈률·30일 재참여율
-- 매칭 완료율의 분모는 match_request 행 수다(요청 1건 = 분모 1).
--   match_event에는 요청 이벤트가 없으므로 분모를 이 테이블에서 세지 않는다.
```

| 컬럼 | 설명 |
|---|---|
| session_id | 구 스키마의 group_id를 대체. 성사 이벤트에만 값이 있다 |
| type | 요청·성사·취소·만료. 값 정의는 api-spec |

**불변식**
- 집계 전용 append-only 테이블이다. UPDATE·DELETE 경로가 없다.
- FK를 걸지 않는다. 회원 삭제(B4)나 세션 정리가 지표를 막으면 안 된다.

---

## 3. 라이브 세션 (session)

**LiveKit 식별자 규약** — 세션 도메인에는 LiveKit 참가자 식별자를 담는 컬럼이 없다. 대신 규약으로 고정한다.

- **LiveKit participant identity = `member_id`의 문자열 표현**이다. SS-2 토큰 발급 시 서버가 이 값을 identity로 박는다.
- 방 이름은 `live_session.livekit_room_name`이며 `molock-{sessionId}` 형식이다. sessionId가 유일하므로 방 이름도 유일하다.
- **쓰기 순서 규약**: 이 컬럼은 NOT NULL + UNIQUE인데 sessionId는 INSERT가 끝나야 정해진다. 그래서 엔티티 생성자가 UUID 임시값을 넣고, 매칭 서비스가 `save()` 직후 같은 트랜잭션에서 `assignRoomName()`으로 확정값을 덮어쓴다. 이 호출을 빠뜨리면 방 이름이 임시값으로 남는다.
- SS-10 웹훅은 `(room_name, participant_identity)`를 받아 `live_session.livekit_room_name` → `session_id`, `identity` → `member_id`로 되짚어 `session_participant` 행을 특정한다. 두 값 모두 UNIQUE(`uk_ls_room`, `uk_sp`)라 매핑이 유일하다.
- 컬럼을 두지 않는 이유는 저장하면 진실이 둘(토큰에 박은 값 / DB에 적은 값)이 되기 때문이다. identity는 토큰 발급 시점에 서버가 결정하는 파생값이므로 별도 보관 대상이 아니다.
- 그 대가로 **identity 생성 규칙을 바꾸면 웹훅 매핑이 통째로 깨진다.** 토큰 발급과 웹훅 파싱은 같은 유틸을 공유해야 한다.
- LiveKit이 보내는 참가자 이름(name)·메타데이터는 매핑에 쓰지 않는다. 익명 닉네임은 변경 가능하고 유일하지도 않다.

```sql
CREATE TABLE live_session (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    target_minutes     INT          NOT NULL,   -- 60 | 120 | 180 | 240
    status             VARCHAR(20)  NOT NULL,   -- SessionStatus: LIVE | ENDED | CANCELLED
    livekit_room_name  VARCHAR(100) NOT NULL,   -- LiveKit 방 식별자. "molock-{sessionId}"
    started_at         DATETIME(6)  NOT NULL,   -- 6인 확정 시각 = 생성 시각(D21)
    ends_at            DATETIME(6)  NOT NULL,   -- started_at + target_minutes. B1의 판정 기준
    ended_at           DATETIME(6)  NULL,       -- 실제 종료 시각
    end_reason         VARCHAR(20)  NULL,       -- SessionEndReason: NORMAL | EARLY_UNDER_MIN. NULL=진행 중
    PRIMARY KEY (id),
    UNIQUE KEY uk_ls_room (livekit_room_name),
    KEY idx_ls_batch (status, ends_at)          -- B1
);
```

| 컬럼 | 설명 |
|---|---|
| started_at | 매칭 성사 즉시. 대기 시간은 포함하지 않는다(D21) |
| ends_at | 종료 예정. B1 스케줄러가 이 시각을 지난 LIVE 세션을 종료 처리 |
| ended_at | 실제 종료. 조기 종료(D12)면 ends_at보다 이르다 |
| end_reason | NORMAL 예정 시각 도달 · EARLY_UNDER_MIN 잔여 2인 미만 조기 종료(D12). 진행 중이면 NULL |
| status | LIVE 진행 중 · ENDED 종료. **CANCELLED는 enum에만 존재하고 v1에는 전이 경로가 없다** — 세션은 6인 확정 시점에만 생성되므로 시작조차 못 하는 경우가 없다 |

**불변식**
- 방 이름은 세션당 유일하다(`uk_ls_room`). 재사용하면 종료된 세션의 참가자가 새 세션에 들어온다.
- `status='ENDED'` ⟺ `ended_at IS NOT NULL AND end_reason IS NOT NULL`. 두 종료 경로(B1 정시 종료, 인원 미달 즉시 종료)가 모두 `end_reason`을 채운다. 이 값이 없으면 운영 지표에서 정상 종료와 조기 종료가 구분되지 않는다.
- `status='LIVE'`인 동안만 SS-2 토큰이 발급된다.
- `ENDED`·`CANCELLED`로 넘어간 세션은 다시 `LIVE`가 되지 않는다.
- 잔여 ACTIVE+PAUSED 참가자가 `session.min-participants`(2) 미만이 되면 즉시 종료하고 `end_reason='EARLY_UNDER_MIN'`을 기록한다. 그 시점까지 남아 있던 사람은 완주로 인정한다(D12). PAUSED를 모집단에 넣는 것은 Pause가 재실로 인정되기 때문이다(★D1) — 빼면 화장실 간 사람 때문에 세션이 종료된다.
- 세션 영상은 어떤 형태로도 저장하지 않는다(D17). 이 테이블에 미디어 참조 컬럼이 없는 이유다.

```sql
CREATE TABLE session_participant (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    session_id       BIGINT       NOT NULL,
    member_id        BIGINT       NOT NULL,
    status           VARCHAR(20)  NOT NULL,   -- ParticipantStatus: ACTIVE | PAUSED | LEFT | EVICTED
    joined_at        DATETIME(6)  NULL,       -- SS-10 웹훅이 최초 입장 시 1회 기록
    left_at          DATETIME(6)  NULL,
    left_reason      VARCHAR(30)  NULL,       -- LeftReason. LEFT일 때만
    warning_count    INT          NOT NULL DEFAULT 0,   -- 세션 스코프 누적(D11)
    pause_used       TINYINT(1)   NOT NULL DEFAULT 0,   -- 세션당 1회
    pause_started_at DATETIME(6)  NULL,       -- PAUSED 동안만
    goal_text        VARCHAR(50)  NULL,       -- '오늘 할 일' 한 줄
    completed        TINYINT(1)   NOT NULL DEFAULT 0,   -- B1이 확정
    point_awarded    INT          NOT NULL DEFAULT 0,   -- 완주 지급액 스냅샷
    PRIMARY KEY (id),
    UNIQUE KEY uk_sp (session_id, member_id),
    KEY idx_sp_member (member_id, joined_at),   -- SS-9 내 세션 이력
    CONSTRAINT fk_sp_session FOREIGN KEY (session_id) REFERENCES live_session(id),
    CONSTRAINT fk_sp_member  FOREIGN KEY (member_id) REFERENCES member(id)
);
```

| 컬럼 | 설명 |
|---|---|
| status | ACTIVE 참여 중 · PAUSED 화장실 모드 · LEFT 자율 퇴장 · EVICTED 3회 경고 퇴출 |
| joined_at | SS-10 웹훅이 최초 입장 시 1회 기록한다. NULL = 매칭됐으나 아직 입장하지 않음 |
| left_reason | PERSONAL·DEVICE_ISSUE·UNPLEASANT·ETC·WITHDRAWAL·SANCTION. **EVICTED는 사유가 아니라 status로 표현한다** |
| warning_count | 세션이 끝나면 의미가 소멸한다. 계정 누적 매너 점수는 Phase 4 |
| pause_used | true가 되면 다시 false로 돌아가지 않는다 |
| goal_text | 세션 참가자에게만 공개. 50자 |
| completed / point_awarded | 세션 결과. 별도 결과 테이블을 두지 않는 이유 |

**불변식**
- 세션당 회원 행은 1개다(`uk_sp`). 재입장은 새 행이 아니라 기존 행의 상태 복귀다(D13 재접속 유예 90초).
- 행은 매칭 성사 시점에 생기고 `joined_at`은 실제 입장 시점에 채워진다. 두 시각이 다르므로 NULL을 허용한다 — 매칭됐지만 끝내 입장하지 않은 사람은 `joined_at`이 NULL로 남는다. 재입장 때는 덮어쓰지 않는다(최초 1회).
- 세션당 행 수는 6을 넘지 않는다. 공석 충원은 v1 보류(FR-306).
- **완주(`completed=1`) 조건 = 세션 종료 시각까지 status가 LEFT도 EVICTED도 아님**(★D1). Pause 10분은 재실로 인정하며, 재실 비율 기준은 두지 않는다.
- `status='PAUSED'` ⟺ `pause_started_at IS NOT NULL`. 복귀(SS-6)가 이 값을 NULL로 되돌린다.
- Pause 시작은 조건부 UPDATE(`WHERE pause_used=0 AND status='ACTIVE'`)로만 한다. 0행이면 `PAUSE_ALREADY_USED`.
- `warning_count`가 `session.evict-warning-count`(3)에 도달하는 순간 status는 EVICTED가 되고 `eviction` 행이 생긴다. 이 둘은 같은 트랜잭션이다.
- `point_awarded`는 지급 사실이 아니라 금액 스냅샷이다. 지급의 진실은 `point_ledger`에 있다.
- 자율 퇴장은 포인트 차감이 없고 그 세션은 미완주다(D10).

```sql
-- (잠정 — 팀 확인 대기: ★D4 AI 신뢰 모델)
CREATE TABLE absence_event (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    session_id  BIGINT      NOT NULL,
    member_id   BIGINT      NOT NULL,   -- 보고자 = 대상. 남을 신고할 수 없다
    type        VARCHAR(10) NOT NULL,   -- AbsenceEventType: START | END
    client_seq  BIGINT      NOT NULL,   -- 클라이언트가 붙이는 단조 증가 시퀀스
    occurred_at DATETIME(6) NOT NULL,   -- 클라이언트가 관측한 시각
    reported_at DATETIME(6) NOT NULL,   -- 서버 수신 시각
    PRIMARY KEY (id),
    UNIQUE KEY uk_ae (session_id, member_id, client_seq),   -- 재전송 멱등
    CONSTRAINT fk_ae_session FOREIGN KEY (session_id) REFERENCES live_session(id),
    CONSTRAINT fk_ae_member  FOREIGN KEY (member_id) REFERENCES member(id)
);
```

| 컬럼 | 설명 |
|---|---|
| member_id | 항상 요청자 본인. 서버가 JWT와 대조해 강제한다 |
| client_seq | 세션·회원 스코프 시퀀스. 네트워크 재전송을 걸러내는 멱등키 |
| occurred_at | 경고 판정(60초 초과)의 계산 기준 |
| reported_at | 감사·레이트리밋 기준. occurred_at과 크게 벌어지면 조작 신호 |

**불변식**
- 같은 `client_seq`의 재전송은 새 행을 만들지 않는다(`uk_ae`). 위반 시 200이 아니라 `DUPLICATE_ABSENCE_EVENT`로 흡수한다.
- 클라이언트는 **자기 자신의 관측만** 보고한다. 타인 대상 이벤트는 접수 자체를 거부한다(★D4).
- **경고 부여 여부는 이 테이블이 결정하지 않는다.** 서버가 START/END 쌍의 간격이 `session.absence-threshold-seconds`(60)를 넘는지 계산해 `warning`을 만든다. 클라이언트는 판정 결과를 보내지 않는다.
- 짝이 안 맞는 이벤트(START 없는 END, END 없이 세션 종료)는 정상 입력이다. 전자는 무시하고, 후자는 세션 종료 시각을 END로 간주해 판정한다.
- `occurred_at`은 신뢰할 수 없는 입력이다. 레이트리밋(`ABSENCE_RATE_LIMITED`)과 `reported_at` 대조가 유일한 방어선이다.

```sql
-- (잠정 — 팀 확인 대기: ★D4 AI 신뢰 모델)
CREATE TABLE warning (
    id               BIGINT      NOT NULL AUTO_INCREMENT,
    session_id       BIGINT      NOT NULL,
    member_id        BIGINT      NOT NULL,
    seq              INT         NOT NULL,   -- 1 | 2 | 3
    absence_event_id BIGINT      NULL,       -- 자리비움 근거. Pause 초과 경고(D9)는 NULL
    created_at       DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_warning (session_id, member_id, seq),
    CONSTRAINT fk_wn_session FOREIGN KEY (session_id) REFERENCES live_session(id),
    CONSTRAINT fk_wn_member  FOREIGN KEY (member_id) REFERENCES member(id),
    CONSTRAINT fk_wn_absence FOREIGN KEY (absence_event_id) REFERENCES absence_event(id)
);
```

| 컬럼 | 설명 |
|---|---|
| seq | 세션 안에서의 경고 번호. `session_participant.warning_count`와 같은 값 |
| absence_event_id | 판정을 유발한 END 이벤트. Pause 10분 초과 경고에는 근거 이벤트가 없어 NULL |

**불변식**
- 같은 세션·회원의 같은 번호 경고는 1개다(`uk_warning`). 동시 판정이 겹쳐도 2번 경고가 두 번 생기지 않는다.
- 경고 부여는 `session_participant.warning_count` 증가와 같은 트랜잭션이다. 두 값이 어긋나면 이 테이블이 옳다.
- `seq`는 1부터 빈틈없이 증가한다. 3이 존재하면 반드시 `eviction` 행도 존재한다.
- 경고는 세션 스코프다(D11). 세션이 끝나면 계정에 남는 효과가 없다.
- 경고를 만드는 주체는 서버뿐이다. 클라이언트가 부르는 API가 없다.

```sql
CREATE TABLE eviction (
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    session_id    BIGINT      NOT NULL,
    member_id     BIGINT      NOT NULL,
    warning_count INT         NOT NULL,   -- 퇴출 시점 경고 수(=3) 스냅샷
    point_penalty INT         NOT NULL,   -- 차감액 절대값. point.eviction-penalty(300)
    created_at    DATETIME(6) NOT NULL,
    revoked_at    DATETIME(6) NULL,       -- 이의 인용(AD-6) 시각
    PRIMARY KEY (id),
    UNIQUE KEY uk_eviction (session_id, member_id),
    KEY idx_ev_member (member_id, created_at),   -- D14 재매칭 쿨다운 조회
    CONSTRAINT fk_ev_session FOREIGN KEY (session_id) REFERENCES live_session(id),
    CONSTRAINT fk_ev_member  FOREIGN KEY (member_id) REFERENCES member(id)
);
```

| 컬럼 | 설명 |
|---|---|
| point_penalty | 정책값 스냅샷. 나중에 정책이 바뀌어도 과거 판정이 흔들리지 않는다 |
| created_at | 재매칭 쿨다운 30분(D14)의 기준 시각 |
| revoked_at | 취소 표시. 행을 지우지 않는다 |

**불변식**
- 세션당 회원 퇴출은 1회다(`uk_eviction`). 이중 퇴출 시도는 `ALREADY_EVICTED`로 흡수한다.
- 퇴출 트랜잭션은 4가지를 함께 한다: 참가자 status=EVICTED, `point_ledger`에 -300 기록, `eviction` 행 생성, LiveKit 강제 퇴장 요청.
- MT-1은 `created_at + match.rematch-cooldown-minutes`(30)가 지나지 않은 미취소 퇴출이 있으면 `REMATCH_COOLDOWN`으로 막는다.
- 이의 인용은 `revoked_at`을 채울 뿐 행을 지우지 않는다. 취소된 퇴출은 쿨다운 판정에서 제외한다.
- 취소 시 포인트는 삭제가 아니라 **역분개**로 되돌린다(`APPEAL_REFUND` +300).

```sql
CREATE TABLE appeal_case (
    id          BIGINT        NOT NULL AUTO_INCREMENT,
    eviction_id BIGINT        NOT NULL,
    member_id   BIGINT        NOT NULL,   -- 신청자 = 퇴출 당사자
    status      VARCHAR(20)   NOT NULL,   -- AppealStatus: PENDING | ACCEPTED | REJECTED
    reason_text VARCHAR(200)  NOT NULL,   -- 신청자가 적는 이의 사유
    created_at  DATETIME(6)   NOT NULL,   -- 접수 시각
    sla_due_at  DATETIME(6)   NOT NULL,   -- created_at + report.sla-hours(24/72)
    decided_by  VARCHAR(20)   NULL,       -- DecidedBy
    decided_at  DATETIME(6)   NULL,
    note        VARCHAR(1000) NULL,       -- 관리자 처리 사유
    PRIMARY KEY (id),
    UNIQUE KEY uk_ap_eviction (eviction_id),   -- 퇴출당 이의 1회
    KEY idx_ap_queue (status, sla_due_at),     -- AD-5 큐. overdue 파생 조회를 받친다
    CONSTRAINT fk_ap_eviction FOREIGN KEY (eviction_id) REFERENCES eviction(id),
    CONSTRAINT fk_ap_member   FOREIGN KEY (member_id) REFERENCES member(id)
);
```

| 컬럼 | 설명 |
|---|---|
| eviction_id | 근거 퇴출. UNIQUE라 재신청 경로가 없다 |
| reason_text | 신청자가 적는 사유 200자. 관리자가 판단할 유일한 당사자 진술이라 NOT NULL |
| created_at | 접수 시각. 감사 기준이자 sla_due_at의 계산 원점 |
| sla_due_at | 처리 기한. 큐 정렬 키이자 overdue 판정 기준 |
| note | 관리자 처리 사유. 신청자의 `reason_text`와 구분한다 |

**불변식**
- 퇴출 1건당 이의는 1회다(`uk_ap_eviction`). 두 번째 신청은 `APPEAL_ALREADY_FILED`.
- 신청 자격은 `eviction.member_id` 본인뿐이다.
- `sla_due_at = created_at + report.sla-hours`. 접수 시각을 저장하므로 SLA를 사후에 재계산해 검증할 수 있다.
- `reason_text`(신청자 진술)와 `note`(관리자 판단)는 서로 덮어쓰지 않는다. AD-6 처리 시 `note`만 채운다.
- overdue는 **저장하지 않고 파생한다**: `status='PENDING' AND sla_due_at < now`. `report_case`도 같은 규칙이라 두 큐의 지연 판정이 하나의 식으로 통일된다.
- 인용(ACCEPTED) 처리는 세 가지를 함께 한다: `eviction.revoked_at` 기록, `point_ledger` 역분개(APPEAL_REFUND), 그날 완주 소급 재판정(★D1 기준 → `streak_day` INSERT).
- 역분개는 원장에 `EVICTION_PENALTY` 행이 실제로 있을 때만 만든다. 차감 주체가 B1이라 퇴출과 차감 사이에 틈이 있고, 그 틈에 인용하면 빠져나간 적 없는 300이 들어온다.
- 소급 완주가 만든 `streak_day` 행은 `member.current_streak`·`last_completed_on`을 **재계산으로** 갱신한다. 되살린 날이 마지막 완주일보다 과거일 수 있어 증분 갱신으로는 반영되지 않는다.
- 종결된 이의는 재오픈하지 않는다.

---

## 4. 포인트 (point)

```sql
-- (잠정 — 팀 확인 대기: ★D5 스파크 포인트 = 단일 통화)
CREATE TABLE point_ledger (
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    member_id     BIGINT      NOT NULL,
    delta         INT         NOT NULL,   -- 부호 있는 증감. 0 금지
    reason        VARCHAR(30) NOT NULL,   -- PointReason
    ref_type      VARCHAR(30) NOT NULL,   -- 근거 테이블. NULL 금지
    ref_id        BIGINT      NOT NULL,   -- 근거 행 id. NULL 금지
    balance_after INT         NOT NULL,   -- 기록 직후 잔액 스냅샷
    created_at    DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_pl_dedup (member_id, reason, ref_type, ref_id),   -- 중복 지급 방어선
    KEY idx_pl_member (member_id, created_at),                      -- PT-1 내역 조회
    CONSTRAINT fk_pl_member FOREIGN KEY (member_id) REFERENCES member(id)
);
```

| 컬럼 | 설명 |
|---|---|
| delta | +지급 / -차감. 취소는 UPDATE가 아니라 반대 부호 새 행(역분개) |
| reason | WELCOME · SESSION_COMPLETE · GOAL_ACHIEVED · EVICTION_PENALTY · ORDER_SPEND · ORDER_CANCEL · CHARGE · APPEAL_REFUND |
| ref_type / ref_id | 이 기록이 어느 행 때문에 생겼는지. 멱등키의 재료 |
| balance_after | 감사용 스냅샷. 잔액 계산의 근거가 아니라 검증 재료 |

**reason별 ref 규약** — 멱등키가 `member_id`를 포함하더라도, ref는 **회원별로 갈라지는 행**을 가리킨다. 제약과 규약이 같은 사고를 두 겹으로 막는 이중 방어다.

| reason | ref_type | ref_id |
|---|---|---|
| WELCOME | MEMBER | member.id |
| SESSION_COMPLETE | SESSION_PARTICIPANT | session_participant.id |
| EVICTION_PENALTY | EVICTION | eviction.id |
| APPEAL_REFUND | EVICTION | eviction.id |
| GOAL_ACHIEVED | GOAL | member_goal.id |
| ORDER_SPEND / ORDER_CANCEL | ORDER | store_order.id |
| CHARGE | CHARGE | point_charge.id |

**불변식**
- **같은 회원에게 같은 (reason, ref_type, ref_id) 조합은 두 번 기록되지 않는다.** B1 재실행·PG 웹훅 중복 수신·주문 더블클릭이 모두 이 하나의 제약에서 막힌다.
- `ref_type`·`ref_id`는 NOT NULL이다. MySQL·H2는 UNIQUE 안의 NULL을 서로 다른 값으로 보므로, NULL을 허용하는 순간 그 reason의 중복 방어가 통째로 사라진다.
- 멱등키 선두에 `member_id`가 있어 회원이 다르면 같은 ref라도 충돌하지 않는다. 그래도 ref는 세션(6인 공유)이 아니라 참가자 행을 가리킨다 — 위 규약 표를 지키면 `member_id` 없이도 안전하고, 규약을 어겨도 제약이 막는다. 어느 한쪽이 무너져도 중복 지급이 나지 않는 구조다.
- `member.point_balance`는 이 테이블의 `delta` 합과 같다. 어긋나면 원장이 옳다.
- **정정은 UPDATE·DELETE가 아니라 역분개다.** 기록된 행은 수정하지 않는다.
- 차감은 조건부 UPDATE(`UPDATE member SET point_balance = point_balance - ? WHERE id = ? AND point_balance >= ?`)가 0행이면 중단한다(`INSUFFICIENT_POINT`). 잔액 확인 후 차감하는 2단계는 동시성에서 깨진다.
- **지급도 같은 이유로 상대 UPDATE(`SET point_balance = point_balance + ?`)여야 한다.** 회원 행을 읽어 더한 절대값을 쓰면 같은 회원에게 지급 두 건이 동시에 들어올 때 둘 다 같은 잔액을 읽고 한쪽이 사라진다 — 원장에는 두 줄이 다 남으므로 위의 "잔액 = 원장 합"이 조용히 깨진다. 실측(8단계): PY-2·PY-3 20쌍 동시 도달 시 원장은 20줄인데 캐시는 12건을 잃었다. 지급은 잔액 부족을 막지 않으므로 조건절만 없다.
- 통화는 하나뿐이다. "스파크 포인트"는 별도 잔액이 아니라 `reason='GOAL_ACHIEVED'` 라벨일 뿐이다(★D5). 통화가 둘이 되면 이 테이블에 currency 컬럼과 잔액 캐시 분리가 필요해진다.
- 잠정 정책값(D15): WELCOME +1,000 / SESSION_COMPLETE +100×(target_minutes÷60) / EVICTION_PENALTY -300 / GOAL_ACHIEVED +1,000.

---

## 5. 스토어·결제 (store)

```sql
CREATE TABLE product (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    type        VARCHAR(20)  NOT NULL,   -- ProductType: GIFTICON | BOOK
    name        VARCHAR(100) NOT NULL,
    description VARCHAR(500) NULL,       -- SR-2 상세 설명
    image_url   VARCHAR(500) NULL,       -- 대표 이미지. 외부 URL
    price_point INT          NOT NULL,   -- 포인트 가격
    stock       INT          NOT NULL,   -- 남은 수량
    status      VARCHAR(20)  NOT NULL,   -- ProductStatus: ON_SALE | SOLD_OUT | HIDDEN
    PRIMARY KEY (id),
    KEY idx_pd_list (status, type)       -- SR-1 목록
);
```

| 컬럼 | 설명 |
|---|---|
| description | SR-2 상세에서만 쓴다. SR-1 목록 응답에는 싣지 않는다 |
| image_url | 외부 URL 문자열만 보관한다. 이미지 업로드·저장 경로는 v1에 없다 |
| price_point | 포인트 단위 가격. 원화 가격 컬럼은 두지 않는다(전액 포인트 결제만) |
| stock | 재고. 0이면 status를 SOLD_OUT으로 올린다 |
| status | HIDDEN은 목록에서 감춘다. 기존 주문은 유지 |

**불변식**
- 결제 수단은 포인트 전액뿐이다(D16). 실물+인앱결제 혼합(FR-505)은 스토어 정책 위반이라 구현하지 않는다.
- `stock`은 음수가 되지 않는다. 차감은 조건부 UPDATE(`WHERE stock >= ?`)로만 하고 0행이면 `OUT_OF_STOCK`.
- `status='ON_SALE'`이 아닌 상품은 주문할 수 없다.
- 가격은 주문 시점에 `store_order.point_amount`로 스냅샷된다. 이후 가격 변경이 과거 주문을 바꾸지 않는다.

```sql
CREATE TABLE store_order (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    member_id       BIGINT      NOT NULL,
    product_id      BIGINT      NOT NULL,
    quantity        INT         NOT NULL,
    point_amount    INT         NOT NULL,   -- 총 차감 포인트 = price_point × quantity 스냅샷
    status          VARCHAR(20) NOT NULL,   -- OrderStatus: ORDERED | CANCELLED
    idempotency_key VARCHAR(64) NOT NULL,   -- 클라이언트 생성 UUID
    ordered_at      DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_so_idem (idempotency_key),
    KEY idx_so_member (member_id, ordered_at),   -- SR-4 내 주문 목록
    CONSTRAINT fk_so_member  FOREIGN KEY (member_id) REFERENCES member(id),
    CONSTRAINT fk_so_product FOREIGN KEY (product_id) REFERENCES product(id)
);
```

| 컬럼 | 설명 |
|---|---|
| point_amount | 결제 금액 스냅샷. 상품 가격이 바뀌어도 고정 |
| idempotency_key | 클라이언트가 주문 화면 진입 시 만들어 재시도까지 같은 값으로 보낸다 |
| status | 환불(FR-506)은 v2 보류라 CANCELLED는 관리자 취소 경로만 |

**불변식**
- 같은 `idempotency_key`로는 주문이 1건만 생긴다. **버튼 더블클릭과 네트워크 재시도로 인한 이중 차감을 막는 자리다.** 위반 시 409 `DUPLICATE_ORDER`와 함께 `details.orderId`로 기존 주문 번호를 반환한다.
- 주문 트랜잭션은 셋을 함께 한다: 재고 조건부 차감, 포인트 조건부 차감 + `point_ledger`(ORDER_SPEND, ref=store_order.id), 주문 행 생성. 하나라도 실패하면 전부 롤백.
- `point_amount`는 항상 `product.price_point × quantity`와 같다(주문 시점 기준).
- 취소는 원장에 ORDER_CANCEL 역분개를 넣고 재고를 되돌린다. 원 주문 행의 금액은 건드리지 않는다.
- 커머스 기록이라 탈퇴 시에도 파기하지 않는다(아래 파기·보존 정책 참조).

**주문 이행은 v1 범위 밖이다.** 이 테이블은 **주문 접수까지**만 담는다. 기프티콘 발송 코드, 수령 연락처, 배송 상태를 담는 컬럼이 없는 것은 누락이 아니라 절단선이다(배송지 NFR-205·환불 FR-506은 v2 보류). v1에서 주문 이후 처리는 운영자가 `store_order` 목록을 보고 서비스 밖에서 수행한다. 이행 정보를 스키마에 넣으려면 그 시점에 별도 테이블을 신설한다 — 이 테이블에 컬럼을 덧붙이면 접수 기록과 이행 기록의 수명(전자는 법정 보존, 후자는 개인정보 최소 보관)이 뒤엉킨다.

```sql
CREATE TABLE point_charge (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    member_id    BIGINT       NOT NULL,
    amount_krw   INT          NOT NULL,   -- 결제 요청 금액(원)
    point_amount INT          NOT NULL,   -- 적립 예정 포인트
    status       VARCHAR(20)  NOT NULL,   -- ChargeStatus: READY | APPROVED | FAILED
    pg_order_id  VARCHAR(64)  NOT NULL,   -- 서버 생성 주문번호. PG에 전달
    pg_tid       VARCHAR(64)  NULL,       -- PG 거래번호. 승인 후 채워진다
    created_at   DATETIME(6)  NOT NULL,   -- PY-1 생성 시각
    approved_at  DATETIME(6)  NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_pc_order (pg_order_id),
    UNIQUE KEY uk_pc_tid (pg_tid),        -- NULL 다건 허용 → READY 상태끼리는 충돌하지 않는다
    KEY idx_pc_member (member_id, created_at),
    CONSTRAINT fk_pc_member FOREIGN KEY (member_id) REFERENCES member(id)
);
-- PG는 토스페이먼츠 테스트 모드(잠정, D16). 시크릿 키는 환경변수(morak.pg.secret-key).
```

| 컬럼 | 설명 |
|---|---|
| pg_order_id | PY-1이 만들어 클라이언트에 내려준다. PG 요청의 우리 쪽 키. 형식은 `molock-chg-{yyyyMMdd}-{충전 건 id 6자리}` — PG 콘솔에 찍힌 주문번호 하나로 우리 행을 되짚을 수 있어야 대사가 된다. id가 INSERT 후에 정해지므로 임시 유일값으로 넣고 같은 트랜잭션에서 덮는다(`live_session.livekit_room_name`과 같은 방식) |
| pg_tid | PG가 발급하는 거래 식별자. 승인 응답·웹훅 양쪽에 들어온다 |
| created_at | PY-1 생성 시각. PG 대사 기준이자 READY 방치 건 정리 기준 |
| status | READY 생성 · APPROVED 승인 · FAILED 실패 |

**불변식**
- **같은 `pg_tid`로 승인이 두 번 기록되지 않는다.** PY-2 확인 응답과 PY-3 웹훅이 같은 거래를 중복 전달하는 상황을 여기서 막는다. `pg_tid`가 NULL을 허용하는 것은 승인 전 READY 행 여러 건이 공존해야 하기 때문이다.
- 포인트 적립은 `point_ledger`(CHARGE, ref=point_charge.id)의 UNIQUE가 2차 방어선이다. `uk_pc_tid`가 뚫려도 중복 적립은 일어나지 않는다.
- `status='APPROVED'` ⟺ `pg_tid IS NOT NULL AND approved_at IS NOT NULL`.
- 승인 시 `amount_krw`가 PG가 알려준 실제 결제 금액과 다르면 적립하지 않는다(`PAYMENT_AMOUNT_MISMATCH`). 클라이언트가 보낸 금액을 믿지 않는다.
- 웹훅(PY-3)은 JWT 게이트를 전부 건너뛴다. 서명 검증이 유일한 인증이다(`INVALID_WEBHOOK_SIGNATURE`).
- 커머스 기록이라 탈퇴 시에도 파기하지 않는다.

---

## 6. 신고·제재 (report)

```sql
CREATE TABLE report_case (
    id                 BIGINT      NOT NULL AUTO_INCREMENT,
    target_type        VARCHAR(10) NOT NULL,   -- ReportTargetType: MEMBER | SESSION
    target_id          BIGINT      NOT NULL,
    open_target_id     BIGINT      NULL,       -- PENDING일 때만 target_id, 종결 시 NULL
    session_id         BIGINT      NULL,       -- 신고가 발생한 세션(MEMBER 신고의 맥락)
    target_nickname    VARCHAR(30) NOT NULL,   -- 신고 시점 표시명 스냅샷
    severity           VARCHAR(10) NOT NULL,   -- ReportSeverity
    status             VARCHAR(20) NOT NULL,   -- ReportStatus
    sla_due_at         DATETIME(6) NOT NULL,   -- 접수 + report.sla-hours(24/72)
    restriction_review TINYINT(1)  NOT NULL DEFAULT 0,   -- AD-3 기각 시 신고자 검토 플래그
    received_at        DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_rc_open (target_type, open_target_id),   -- 대상당 미처리 케이스 1건
    KEY idx_rc_console (status, severity, sla_due_at)      -- AD-1 목록·필터. overdue 파생 조회를 받친다
);
```

| 컬럼 | 설명 |
|---|---|
| target_type | 구 스키마의 POST가 빠지고 SESSION이 들어왔다 |
| open_target_id | 미처리 동안만 target_id를 담는 그림자 컬럼. UNIQUE의 재료 |
| session_id | 구 스키마 group_id를 대체. 관리자가 맥락(참가자·경고 로그)을 열 때 쓴다 |
| target_nickname | 신고 시점 스냅샷. 익명화 이후에도 케이스를 읽을 수 있게 한다. SESSION 신고는 대상이 개인이 아니라 표시명이 없으므로 `"세션 {id}"`를 넣는다 |
| sla_due_at | 처리 기한. 큐 정렬 키이자 overdue 판정 기준. 저장 플래그는 두지 않는다 |
| restriction_review | AD-3이 신고를 기각할 때 신고자에게 세우는 검토 플래그. 배치가 아니라 관리자 처리 경로가 쓴다. **v1에는 조회 경로가 없다** — 후속 제재 검토는 DB 직접 조회로 한다 |

**불변식**
- **overdue는 저장하지 않고 파생한다**: `status`가 미종결이고 `sla_due_at < now`. 별도 플래그 컬럼도, 그것을 마킹하는 배치도 없다. `appeal_case`와 동일한 규칙이라 신고 큐와 이의 큐의 지연 판정이 항상 같은 식을 쓴다.
- 파생으로 바꾼 이유는 저장 플래그가 배치 실행 시점에만 참이기 때문이다. 마킹 배치가 밀리면 이미 기한을 넘긴 케이스가 큐에서 정상으로 보인다. 조회 시점에 계산하면 그 창이 없다.
- `idx_rc_console`은 `(status, severity, sla_due_at)`이다. AD-1의 overdue 필터는 이 인덱스의 `sla_due_at` 범위 조건으로 처리한다.
- 대상당 미처리 케이스는 1건이다(`uk_rc_open`). 같은 대상 신고는 새 케이스가 아니라 기존 케이스에 `report` 행으로 병합한다.
- 종결 케이스는 재오픈하지 않는다(재검토는 새 케이스). 재오픈하면 `uk_rc_open` 충돌 경로가 되살아난다.
- 종결 시 `open_target_id`를 반드시 NULL로 만든다. 빠뜨리면 그 대상은 영원히 새 신고를 받지 못한다.
- 병합 시 더 높은 severity가 들어오면 케이스를 상향하고 `sla_due_at`을 재계산한다.
- 신고 접수는 세션에 아무 영향도 주지 않는다(★D6). 부수효과는 `match_block` 2행뿐이다.

```sql
CREATE TABLE report (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    case_id     BIGINT       NOT NULL,
    reporter_id BIGINT       NOT NULL,
    reason_code VARCHAR(30)  NOT NULL,   -- ReportReasonCode
    detail      VARCHAR(500) NULL,
    received_at DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_report (case_id, reporter_id),
    CONSTRAINT fk_rp_case     FOREIGN KEY (case_id) REFERENCES report_case(id),
    CONSTRAINT fk_rp_reporter FOREIGN KEY (reporter_id) REFERENCES member(id)
);
```

| 컬럼 | 설명 |
|---|---|
| reason_code | SEXUAL_CONTENT · VIOLENT_THREAT · AD_SPAM · INAPPROPRIATE_SCREEN · ETC |
| detail | 자유 입력 500자 |

**불변식**
- 한 사람이 같은 케이스에 신고를 두 번 넣지 않는다(`uk_report`). 위반 시 `DUPLICATE_REPORT`.
- 외부 식별자는 `case_id`로 통일한다. `report.id`는 노출하지 않는다.
- 자기 자신은 신고할 수 없다.

```sql
CREATE TABLE report_history (
    id           BIGINT        NOT NULL AUTO_INCREMENT,
    case_id      BIGINT        NOT NULL,
    admin_id     BIGINT        NOT NULL,
    status       VARCHAR(20)   NOT NULL,
    review_note  VARCHAR(1000) NULL,
    processed_at DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    KEY idx_rh_case (case_id, processed_at),
    CONSTRAINT fk_rh_case FOREIGN KEY (case_id) REFERENCES report_case(id)
);
```

| 컬럼 | 설명 |
|---|---|
| status | 그 시점에 확정한 상태(RESOLVED/REJECTED/SANCTIONED) |
| review_note | 처리 사유. AD-2 상세에서 관리자에게만 노출 |

**불변식**
- append-only다. 기록된 처리 이력은 수정·삭제하지 않는다.
- `report_case.status`가 바뀔 때마다 이 테이블에 행이 하나 늘어난다. 이력 없는 상태 변경 경로를 만들지 않는다.
- **접수(RP-1)는 여기 행을 만들지 않는다.** 케이스 생성은 상태 변경이 아니고, `admin_id`가 NOT NULL인데 접수 시점에는 처리한 관리자가 없다. 그래서 미처리 케이스의 AD-2 `history`는 빈 배열이다.

```sql
CREATE TABLE sanction (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    member_id  BIGINT      NOT NULL,   -- 제재 대상 회원
    case_id    BIGINT      NULL,       -- 근거 신고 케이스. 단독 제재(AD-4)면 NULL
    type       VARCHAR(20) NOT NULL,   -- SanctionType: TEMP | PERMANENT
    starts_at  DATETIME(6) NOT NULL,
    ends_at    DATETIME(6) NULL,       -- TEMP만
    admin_id   BIGINT      NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_sanction_member (member_id, starts_at, ends_at)
);
```

| 컬럼 | 설명 |
|---|---|
| case_id | NULL 허용. 신고 없이 내리는 제재 경로(AD-4)가 있다 |
| ends_at | PERMANENT는 NULL |

**불변식**
- 유효 제재 = `starts_at <= now AND (ends_at IS NULL OR ends_at > now)`. 전역 인터셉터 게이트 ④가 매 요청 이 식으로 판정한다.
- 회원당 유효 제재가 여러 건이면 가장 늦은 `ends_at`이 실질 만료다. 겹침을 금지하지 않는다.
- 제재 적용 시 진행 중 세션이 있으면 강제 퇴장하고, WAITING 매칭 요청도 함께 종료한다(`active_member_id=NULL`).
- PERMANENT 제재 이력자는 탈퇴(B4) 시 `blocked_social_hash`에 등재된다.

---

## 지연 FK

```sql
ALTER TABLE streak_day
    ADD CONSTRAINT fk_sd_session FOREIGN KEY (session_id) REFERENCES live_session(id);

ALTER TABLE match_request
    ADD CONSTRAINT fk_mr_session FOREIGN KEY (matched_session_id) REFERENCES live_session(id);
```

---

## 테이블 목록 (24)

member · member_agreement · member_goal · streak_day · media_consent · blocked_social_hash · match_lock · match_request · match_block · match_event · live_session · session_participant · absence_event · warning · eviction · appeal_case · point_ledger · product · store_order · point_charge · report_case · report · report_history · sanction

## UNIQUE 제약과 그 의미 (17)

각 제약이 막는 것은 "이론적 중복"이 아니라 실제로 일어나는 사고다.

| 제약 | 막는 사고 | 위반 시 응답 |
|---|---|---|
| `uk_member_provider` | 같은 소셜 계정으로 회원이 둘 생기는 것 | 로그인 경로로 흡수 |
| `uk_ma` | 재동의 요청이 동의 이력을 중복 적재하는 것 | upsert |
| `uk_streak_day` | 하루에 두 세션을 완주한 사람의 Streak가 2 오르는 것, B1 재실행이 완주일을 부풀리는 것 | 무시(이미 기록됨) |
| `uk_mr_active` | **이중 배정** — 대기 중인 사람이 요청을 한 번 더 넣어 두 세션에 동시 배정되는 것 | 409 `DUPLICATE_MATCH_REQUEST` |
| `uk_mb` | 같은 상대를 여러 번 신고했을 때 차단 행이 쌓이는 것 | 무시(이미 차단됨) |
| `uk_ls_room` | 종료된 세션의 방 이름 재사용으로 옛 참가자가 새 세션에 입장하는 것 | 서버가 sessionId 기반으로 생성(`molock-{sessionId}`), 발생 불가 |
| `uk_sp` | 재접속이 참가자 행을 새로 만들어 한 사람이 2인으로 세는 것 | 기존 행 복귀로 흡수 |
| `uk_ae` (client_seq) | **이벤트 재전송** — 네트워크 재시도로 같은 자리비움이 여러 번 접수돼 경고가 부당하게 쌓이는 것 | 409 `DUPLICATE_ABSENCE_EVENT` |
| `uk_warning` | 동시 판정이 겹쳐 2번 경고가 두 번 생기고 3회 퇴출이 앞당겨지는 것 | 재조회 후 현재 경고 수 반환 |
| `uk_eviction` | **이중 퇴출** — 자리비움 판정과 Pause 초과 판정이 동시에 걸려 -300이 두 번 빠지는 것 | 409 `ALREADY_EVICTED` |
| `uk_ap_eviction` | **이의 1회** 원칙이 깨져 같은 퇴출로 큐가 반복 채워지는 것 | 409 `APPEAL_ALREADY_FILED` |
| `uk_pl_dedup` (member_id, reason, ref_type, ref_id) | **중복 지급** — B1 재실행, 웹훅 재수신, 재시도가 포인트를 두 번 넣는 것. reason별 ref 규약과 함께 이중 방어 | 무시(이미 지급됨) |
| `uk_so_idem` | **더블클릭 중복 주문** — 주문 버튼 두 번 눌러 포인트가 두 번 빠지고 재고가 2개 나가는 것 | 409 `DUPLICATE_ORDER` + `details.orderId`로 기존 주문 번호 반환 |
| `uk_pc_order` | 같은 주문번호로 충전 건이 둘 생겨 PG 대사가 어긋나는 것 | 서버 채번, 발생 불가 |
| `uk_pc_tid` | **웹훅 중복 수신** — PY-2 확인과 PY-3 웹훅이 같은 거래를 각각 적립하는 것 | 무시(이미 승인됨) |
| `uk_rc_open` | 같은 대상 신고가 케이스를 계속 새로 만들어 관리자 큐가 중복으로 차는 것 | 기존 케이스에 병합 |
| `uk_report` | 한 사람이 같은 케이스를 반복 신고해 severity를 인위로 올리는 것 | 409 `DUPLICATE_REPORT` |

**모든 UNIQUE 위반은 500이 아니라 위 응답으로 매핑한다.** `DataIntegrityViolationException`을 잡아 제약명으로 분기.

UNIQUE가 **없는** 곳 중 의도적인 것:
- `member_goal` 활성 1건 — 조건부 로직(회원 행 잠금 + 상태 확인)으로 지킨다. 그림자 컬럼을 두지 않은 만큼 잠금을 빠뜨리면 뚫린다.
- `match_event`, `report_history`, `sanction` — append-only 이력이라 중복 개념이 없다.

## 인덱스 ↔ 쿼리

| 인덱스 | 쓰는 곳 |
|---|---|
| `idx_member_withdraw` | B4 탈퇴 파기 대상 조회 |
| `idx_mg_member` | AU-2 내 목표, AU-7 활성 목표 확인 |
| `idx_mr_queue` | MT-1 대기열 탐색(target_minutes 일치 + 선착순 정렬) |
| `idx_mr_expire` | B2 대기 만료 |
| `idx_ls_batch` | B1 세션 종료 처리 |
| `idx_sp_member` | SS-9 내 세션 이력 |
| `idx_ev_member` | MT-1 재매칭 쿨다운 검사(D14) |
| `idx_ap_queue` | AD-5 이의 큐, overdue 파생 조회 |
| `idx_pl_member` | PT-1 포인트 내역 |
| `idx_pd_list` | SR-1 상품 목록 |
| `idx_so_member` | SR-4 내 주문 목록 |
| `idx_pc_member` | 충전 이력 조회, READY 방치 건 정리 |
| `idx_rc_console` | AD-1 목록·필터, overdue 파생 조회 |
| `idx_rh_case` | AD-2 처리 이력 |
| `idx_sanction_member` | 전역 인터셉터 제재 검사(매 요청) |
| `idx_me_type` | 지표 집계 |

`absence_event`는 별도 인덱스를 두지 않는다. `uk_ae`의 선두 2컬럼(session_id, member_id)이 조회를 한 세션의 본인 이벤트로 좁혀 주고, 그 범위는 세션당 수십 행을 넘지 않는다.

## enum 전체

| enum | 값 | 쓰는 곳 |
|---|---|---|
| MemberRole | PARTICIPANT, ADMIN | member.role |
| MemberStatus | ACTIVE, WITHDRAW_PENDING, DELETED | member.status |
| AgeVerification | REQUIRED, VERIFIED | member.age_verification |
| SocialProvider | KAKAO, NAVER, GOOGLE, APPLE, DEV | member.provider |
| AgreementType | TOS, PRIVACY, MARKETING | member_agreement.type |
| GoalStatus | ACTIVE, ACHIEVED, CANCELLED | member_goal.status |
| MatchRequestStatus | WAITING, MATCHED, CANCELLED, EXPIRED | match_request.status |
| MatchEventType | MATCH_COMPLETED, WAIT_CANCELLED, WAIT_EXPIRED | match_event.type |
| SessionStatus | LIVE, ENDED, CANCELLED | live_session.status |
| SessionEndReason | NORMAL, EARLY_UNDER_MIN | live_session.end_reason |
| ParticipantStatus | ACTIVE, PAUSED, LEFT, EVICTED | session_participant.status |
| LeftReason | PERSONAL, DEVICE_ISSUE, UNPLEASANT, ETC, WITHDRAWAL, SANCTION | session_participant.left_reason |
| AbsenceEventType | START, END | absence_event.type |
| AppealStatus | PENDING, ACCEPTED, REJECTED | appeal_case.status |
| PointReason | WELCOME, SESSION_COMPLETE, GOAL_ACHIEVED, EVICTION_PENALTY, ORDER_SPEND, ORDER_CANCEL, CHARGE, APPEAL_REFUND | point_ledger.reason |
| ProductType | GIFTICON, BOOK | product.type |
| ProductStatus | ON_SALE, SOLD_OUT, HIDDEN | product.status |
| OrderStatus | ORDERED, CANCELLED | store_order.status |
| ChargeStatus | READY, APPROVED, FAILED | point_charge.status |
| ReportTargetType | MEMBER, SESSION | report_case.target_type |
| ReportReasonCode | SEXUAL_CONTENT, VIOLENT_THREAT, AD_SPAM, INAPPROPRIATE_SCREEN, ETC | report.reason_code |
| ReportSeverity | HIGH, NORMAL | report_case.severity |
| ReportStatus | PENDING, RESOLVED, REJECTED, SANCTIONED | report_case.status, report_history.status |
| SanctionType | TEMP, PERMANENT | sanction.type |
| DecidedBy | AI, ADMIN | appeal_case.decided_by |
| StickerType | CLAP, MUSCLE, FIRE | SS-11 응답 전용. **저장 테이블 없음** |

`LeftReason`에 EVICTED가 없는 것은 의도다. 퇴출은 사유가 아니라 상태이므로 `ParticipantStatus.EVICTED`로만 표현한다. 두 곳에 두면 "자율 퇴장인데 사유가 퇴출"인 행이 만들어진다.

`StickerType`은 SS-11 목록 응답에만 쓴다. 스티커 전송은 LiveKit 데이터 채널로 클라이언트 간에 오가고 서버는 저장하지 않는다(D17). 구 스키마의 `sticker_reaction`이 사라진 이유다.

폐기된 enum: GoalCategory, ProofMethod, ProofAiStatus, PostStatus, AiJudgmentType, AiVerdict, AiReviewType, AiTargetType, AiReviewStatus, AccessReason, GroupMemberStatus, GroupStatus. `BadgeCode`는 GOAL_ACHIEVED 단일값으로 재정의되어 저장하지 않고 파생한다.

## 파기·보존 정책

**B4(탈퇴 확정, 신청 + 30일) 파기 대상**

| 테이블 | 처리 |
|---|---|
| member | 익명화 — `provider_user_id='deleted:{id}'`, `nickname='탈퇴회원'`, `sns_nickname`·`sns_profile_image_url`·`birth_date`=NULL, `status='DELETED'`, `deleted_at`=now, `current_streak`=0·`last_completed_on`=NULL |
| media_consent | 행 삭제 |
| member_agreement | 행 삭제 |
| member_goal | 행 삭제 |
| streak_day | 행 삭제 |
| match_request | 활성 요청 종료 후 행 삭제 |
| match_block | 행 삭제(양방향 모두) |
| match_lock | 회원 잠금 행(`member:{id}`) 삭제. 잠글 대상이 없어진 행이라 남기면 고아가 된다 |
| session_participant | `goal_text`=NULL로 비운다. 행 자체는 다른 참가자의 세션 이력 정합성 때문에 남긴다 |
| absence_event, warning | 행 삭제 |

`current_streak`·`last_completed_on`을 함께 비우는 것은 익명화 규칙의 연장이다. 이 둘의 진실인 `streak_day`를 지우면서 캐시만 남기면 "이 사람이 이 날 완주했다"는 기록이 회원 행에 그대로 남아, 파기했다고 말한 것이 파기되지 않는다.

**파기 예외 — 커머스 기록**

`store_order`, `point_charge`, `point_ledger`는 전자상거래법상 보존 대상이라 파기하지 않는다. 대신 다음을 지킨다.

- 행은 남기되 `member_id`가 가리키는 `member` 행이 이미 익명화되어 있으므로 개인 식별로 이어지지 않는다.
- 보존 기간이 지난 뒤에는 `member_id`를 익명 식별자로 치환하거나 행을 파기한다. **구체적 보존 연한은 `docs/open-decisions.md`에서 확정한다**(법무 확인 대기).
- **원장은 거래분만 골라 남기지 않고 통째로 남긴다.** 거래분(`CHARGE`·`ORDER_SPEND`·`ORDER_CANCEL`)만 남기면 `balance_after`가 실제 잔액과 어긋나는 줄이 생기고, 주문의 근거가 된 적립(`SESSION_COMPLETE` 등)이 사라져 보존한 주문 기록만으로는 무엇으로 결제했는지 읽을 수 없다. 개인 식별자는 이미 회원 행에서 지웠으므로 남은 원장은 금액과 시각뿐이다.
- 같은 이유로 `member.point_balance`는 0으로 덮지 않는다. 잔액은 개인 기록이 아니라 남은 원장의 합이고, 덮으면 "잔액 = 원장 합" 불변식이 탈퇴 회원에서만 깨진다.

`eviction`·`appeal_case`·`report_case`·`report`·`report_history`·`sanction`은 분쟁 대응 근거라 유지한다. 신고 화면은 `report_case.target_nickname` 스냅샷으로 읽는다.

`match_event`도 유지한다. 집계 전용 append-only 로그라 UPDATE·DELETE 경로 자체가 없고(위 불변식), 남는 값이 회원 번호와 시각·유형뿐이라 익명화된 회원 행 너머로 개인을 가리키지 않는다. 여기서 지우면 매칭 완료율의 과거 지표가 탈퇴 건수만큼 조용히 달라진다.

`blocked_social_hash`는 탈퇴 시 **등재하는** 쪽이다(PERMANENT 이력자 한정). 파기 대상이 아니다.

세션 영상은 애초에 저장하지 않으므로 파기 대상 자체가 없다(D17).

## 개발(H2) 주의

- URL: `jdbc:h2:mem:morak;MODE=MySQL;LOCK_TIMEOUT=3000` (기본 잠금 타임아웃 약 2초)
- 행 잠금은 H2에서도 정상 동작한다(차단 후 타임아웃 확인). 다만 **미존재 행은 갭 락이 없으므로 `match_lock` 시드가 필수다.** 조건 행 4개는 `ApplicationRunner`가 기동 시 넣고, 회원 행은 가입 트랜잭션에서 동반 INSERT한다.
- v2.0에는 **생성 컬럼(GENERATED ALWAYS AS)이 없다.** 구 스키마의 `proof.daily_slot`·`ai_review_queue.member_key`가 폐기되면서 그 계열의 H2 제약(`CASE WHEN`만 동작, `IF()`는 문법 실패)도 함께 사라졌다. 새 스키마의 그림자 컬럼(`match_request.active_member_id`, `report_case.open_target_id`)은 애플리케이션이 직접 값을 넣는 일반 컬럼이라 이 제약과 무관하다.
- `TINYINT(1)`은 H2 `MODE=MySQL`에서 BOOLEAN으로 매핑된다. 엔티티는 `boolean`으로 선언한다.
- **UNIQUE 안의 NULL은 MySQL·H2 모두 서로 다른 값으로 취급한다.** 이 성질에 의존하는 곳(`uk_mr_active`, `uk_rc_open`, `uk_pc_tid`)과, 반대로 이 성질 때문에 NOT NULL이 필수인 곳(`point_ledger.ref_type`·`ref_id`)을 구분해야 한다. 후자에서 NULL을 허용하면 중복 지급 방어가 통째로 무력화된다.
- `ddl-auto=update`는 기존 테이블에 UNIQUE를 자동으로 붙여 주지 않는 경우가 있다. 스키마를 바꿨으면 개발 DB를 새로 만들거나 `ALTER`를 직접 확인한다.
- MySQL은 `DATETIME(6)`, H2도 동일 정밀도를 지원한다. `LocalDateTime` 매핑에서 정밀도 손실을 가정하지 않는다.
- **FK 제약은 개발 DB에 생성되지 않는다.** 엔티티가 `@ManyToOne` 없이 순수 `Long` 참조를 쓰는 프로젝트 관례 때문이다(E단계 실측). 이 문서 DDL의 `CONSTRAINT fk_*`는 관계의 의미를 적은 것이고, 참조 무결성은 애플리케이션이 지킨다. 운영 MySQL에 FK를 실제로 걸지는 12단계에서 결정한다.
- **DDL의 `DEFAULT` 절은 생성 스키마에 없다.** 엔티티 생성자가 모든 값을 채우므로 JPA 경로에서는 무해하다. 이 문서 DDL을 MySQL에 직접 적용해 만든 스키마와 개발 DB가 갈리는 지점이므로 12단계 전환 때 재확인한다.
- **enum 컬럼은 `VARCHAR(n)`이 아니라 네이티브 `enum(...)`으로 생성된다.** Hibernate 7의 `@Enumerated(STRING)` 기본 동작(E단계 실측, `member.provider`도 동일). 값 표현은 같지만 운영 MySQL에서 enum 값을 추가하려면 `ALTER TABLE`이 필요하다. VARCHAR로 강제할지는 12단계 MySQL 전환 때 결정한다.
