# MoLock API 명세서 v2.0 (2026-08-12)

구 모락 v1.0(사진 인증 기획)을 MoLock 피벗에 따라 전면 개정했다. 범위는 팀 제출 범위조정 절단선 기준이다.

- 근거 원천: `docs/source/molock-requirements.md`(요구사항정의서 v1.0, FR-101~702·NFR), 분장표 MoSCoW(P0~P3), 범위조정 제안 절단선
- 함께 읽는 문서: `docs/db-schema.md`(컬럼·제약 정본), `docs/implementation-plan.md`(구현 순서), `docs/open-decisions.md`(팀 확인 목록)
- 구 v1.0의 사진 인증(proof)·촬영물 열람·AI 이미지 판정·완주 게시판은 전량 폐기했다. 유지한 것은 공통 규약, 회원·인증, 매칭 잠금 구조, 신고·제재·관리자 큐, 탈퇴다.
- 코드 패키지·레포명은 `morak`를 유지한다. 문서상 서비스 표기만 MoLock이다.
- 개수는 표의 행 수로만 말한다. 본문에 개수를 숫자로 박지 않는다.
- 본문에 "(잠정 — 팀 확인 대기, open-decisions Qn)"으로 표기된 항목은 팀 확정 전 잠정 적용분이다. 확정 시 이 문서를 먼저 고친다.

---

## §0. 공통 규약

### 0-1. 기본

- Base `/api`, 인증 `Authorization: Bearer {accessToken}` (JWT, HS256, 유효 24h, refresh 없음)
- 성공 응답은 각 API에 명시. 실패는 공통 포맷

```json
{"error": {"code": "SESSION_NOT_FOUND", "message": "존재하지 않는 세션입니다.", "details": null}}
```

- 시각 표기 ISO-8601(`+09:00`), 일자 `YYYY-MM-DD`. 모든 시각은 `Clock` 빈 경유(`Clock.system(ZoneId.of(morak.timezone))`, 개발·테스트는 가변)
- 목록 응답은 커스텀 `PageResponse<T>` = `{content[], page, size, totalElements, totalPages}` (Spring `Page` 직렬화 금지)
- 페이지 파라미터: `page`(0-base, 기본 0), `size`(기본 20, 최대 50). 초과 시 400 `VALIDATION_FAILED`
- 에러 코드 하나에는 HTTP status 하나만 대응한다. 같은 코드가 상황에 따라 다른 status를 내지 않는다.
- 금액·포인트는 정수. 포인트 잔액의 진실은 `point_ledger`이며 `member.point_balance`는 캐시다.

### 0-2. 전역 인터셉터 (검문소 — 개별 API가 아니라 여기 한 곳에서 판정)

모든 검사는 JWT 클레임이 아니라 요청 시점의 DB 현재값으로 한다. 토큰이 24h이므로 클레임을 믿으면 제재·탈퇴가 최대 하루 늦게 반영된다.

| 순서 | 검사 | 실패 응답 | 예외 경로 |
|---|---|---|---|
| ① | JWT 유효성 | 401 `UNAUTHORIZED` / `TOKEN_EXPIRED` | `/api/auth/**`(AU-1), `/api/dev/**`(DEV-2~4), **`POST /api/webhooks/livekit`(SS-10)**, **`POST /api/webhooks/payment`(PY-3)**, `/h2-console/**`, `/error` |
| ② | 회원 상태 `member.status` | WITHDRAW_PENDING → 403 `WITHDRAWAL_PENDING`(참여 API 차단) / DELETED → 401 `UNAUTHORIZED` | AU-1, AU-2, AU-5(철회), 웹훅 2종, DEV |
| ③ | 관리자 역할 (`/api/admin/**`) | 403 `FORBIDDEN_ROLE` | — |
| ④ | 유효 제재 `starts_at <= now AND (ends_at IS NULL OR ends_at > now)` | 403 `MEMBER_SANCTIONED` (details: 종료 시각) | AU-1, AU-2, AU-4(탈퇴), AU-5(철회 — 막으면 계정 복구가 영구 불가해진다), 웹훅 2종, DEV |
| ⑤ | 연령 `age_verification` | REQUIRED → 403 `AGE_NOT_VERIFIED` | §0-3 매트릭스 참조 |

**JWT skip 경로의 신원 확인 방식**

| 경로 | JWT 대체 수단 | 실패 시 |
|---|---|---|
| AU-1 `POST /api/auth/login` | 소셜 인가 코드 검증 | 401 `INVALID_SOCIAL_TOKEN` |
| DEV-2~4 `/api/dev/**` | `@Profile("dev")` AND `morak.dev.enabled=true` 이중 스위치. 운영 프로필에서는 빈 미등록으로 404 | 404 `ENDPOINT_NOT_FOUND` |
| SS-10 `POST /api/webhooks/livekit` | LiveKit 웹훅 서명 검증(`livekit.api-secret`) | 401 `INVALID_WEBHOOK_SIGNATURE` |
| PY-3 `POST /api/webhooks/payment` | PG 웹훅 서명 검증(`pg.secret-key`) | 401 `INVALID_WEBHOOK_SIGNATURE` |

웹훅 2종은 ①~⑤ 게이트를 전부 통과하지 않는다. 호출 주체가 회원이 아니라 외부 서버이므로 회원 상태·제재·연령 검사가 성립하지 않는다. 신원 보장은 전적으로 서명 검증이 진다.

**⑤ 연령 게이트의 실제 범위** — ★D7(만 14세 미만 가입 자체 차단)에 따라 미만 판정 시 계정을 만들지 않으므로, 미성년 상태로 저장되는 회원 행이 존재할 수 없다. 그래서 `AgeVerification`은 `REQUIRED`·`VERIFIED` 2값뿐이고, ⑤ 게이트는 `REQUIRED`(생년월일 미입력) 상태의 차단 전용이다. 구 v1.0의 `UNDER_AGE` 값은 폐기했다.

### 0-3. 엔드포인트 × 게이트 매트릭스

`—` = 검사 없음, `✓` = 검사, `본인` = 소유권 검사

| API | 역할③ | 회원상태② | 제재④ | 연령⑤ | 세션 참가자 | 소유권 | 대상 상태 |
|---|---|---|---|---|---|---|---|
| AU-1 로그인 | — | 예외 | 예외 | — | — | — | — |
| AU-2 내 정보 | — | 예외 | 예외 | — | — | 본인 | — |
| AU-3 생년월일 | — | ✓ | ✓ | — | — | 본인 | `age_verification=REQUIRED` |
| AU-4 탈퇴 신청 | — | ✓ | 예외 | — | — | 본인 | — |
| AU-5 탈퇴 철회 | — | 예외 | 예외 | — | — | 본인 | `WITHDRAW_PENDING` |
| AU-6 캠 분석 동의 | — | ✓ | ✓ | — | — | 본인 | — |
| AU-7 목표 기간 설정 | — | ✓ | ✓ | ✓ | — | 본인 | 활성 목표 없음 |
| MT-1 매칭 요청 | — | ✓ | ✓ | ✓ | — | — | 활성 세션·대기 없음, 쿨다운 경과 |
| MT-2 매칭 상태 | — | ✓ | ✓ | ✓ | — | 본인 | — |
| MT-3 매칭 취소 | — | ✓ | ✓ | ✓ | — | 본인 | `WAITING` |
| SS-1 세션 조회 | — | ✓ | ✓ | ✓ | ✓ 이력 보유 | — | — |
| SS-2 LiveKit 토큰 | — | ✓ | ✓ | ✓ | ✓ ACTIVE·PAUSED | 본인 | 세션 `LIVE` + 캠 분석 동의 |
| SS-3 오늘의 목표 | — | ✓ | ✓ | ✓ | ✓ ACTIVE·PAUSED | 본인 | 세션 `LIVE` |
| SS-4 자리비움 이벤트 | — | ✓ | ✓ | ✓ | ✓ ACTIVE·PAUSED | 본인만 보고 | 세션 `LIVE` |
| SS-5 Pause 시작 | — | ✓ | ✓ | ✓ | ✓ ACTIVE | 본인 | 세션 `LIVE`, `pause_used=false` |
| SS-6 Pause 복귀 | — | ✓ | ✓ | ✓ | ✓ PAUSED | 본인 | 세션 `LIVE` |
| SS-7 자율 퇴장 | — | ✓ | ✓ | ✓ | ✓ ACTIVE·PAUSED | 본인 | 세션 `LIVE` |
| SS-8 세션 결과 | — | ✓ | ✓ | ✓ | ✓ 이력 보유 | — | 세션 `ENDED` |
| SS-9 내 세션 이력 | — | ✓ | ✓ | **—** | — | 본인 | — |
| SS-10 LiveKit 웹훅 | — | 예외 | 예외 | 예외 | — | — | 서명 검증 |
| SS-11 스티커 목록 | — | ✓ | ✓ | ✓ | — | — | — |
| AP-1 퇴출 이의 신청 | — | ✓ | ✓ | ✓ | — | 본인 eviction | 이의 미제출 |
| PT-1 포인트 조회 | — | ✓ | ✓ | ✓ | — | 본인 | — |
| SR-1 상품 목록 | — | ✓ | ✓ | ✓ | — | — | `HIDDEN` 제외 |
| SR-2 상품 상세 | — | ✓ | ✓ | ✓ | — | — | `HIDDEN` 제외 |
| SR-3 주문 생성 | — | ✓ | ✓ | ✓ | — | — | `ON_SALE` + 재고 |
| SR-4 주문 목록 | — | ✓ | ✓ | ✓ | — | 본인 | — |
| SR-5 주문 상세 | — | ✓ | ✓ | ✓ | — | 본인 | — |
| PY-1 충전 생성 | — | ✓ | ✓ | ✓ | — | 본인 | — |
| PY-2 충전 승인 확인 | — | ✓ | ✓ | ✓ | — | 본인 | `READY`·`APPROVED` |
| PY-3 결제 웹훅 | — | 예외 | 예외 | 예외 | — | — | 서명 검증 |
| RP-1 신고 | — | ✓ | ✓ | **—** | 대상 유형별 | — | — |
| AD-1~AD-8 관리자 | ADMIN | ✓ | — | — | — | — | 각 API |
| DEV-2~4 개발 전용 | — | — | — | — | — | — | 이중 스위치 |

**연령 게이트 제외 2종의 이유** (구 v1.0 판단 승계)

- SS-9 내 세션 이력 — 본인 상태 확인은 막지 않는다. 참여 자체는 MT-1에서 이미 막힌다.
- RP-1 신고 — 안전 도구는 절대 막지 않는다. 생년월일 미입력 상태의 유저가 유해 상황을 보고도 신고하지 못하는 상태를 방지한다.

### 0-4. enum 정본 (여기가 유일한 정의처. DB 주석·코드는 참조만)

```
MemberRole          PARTICIPANT, ADMIN
MemberStatus        ACTIVE, WITHDRAW_PENDING, DELETED
AgeVerification     REQUIRED, VERIFIED
SocialProvider      KAKAO, NAVER, GOOGLE, APPLE, DEV
  ↳ DEV는 개발 프로필 전용. AU-1이 DevSocialClient로 분기하는 값이며 별도 로그인 엔드포인트는 없다
AgreementType       TOS, PRIVACY, MARKETING
GoalStatus          ACTIVE, ACHIEVED, CANCELLED
MatchRequestStatus  WAITING, MATCHED, CANCELLED, EXPIRED
MatchEventType      MATCH_COMPLETED, WAIT_CANCELLED, WAIT_EXPIRED
SessionStatus       LIVE, ENDED, CANCELLED
SessionEndReason    NORMAL, EARLY_UNDER_MIN
  ↳ 진행 중에는 NULL. NORMAL=예정 시각 도래, EARLY_UNDER_MIN=잔여 인원 미달 조기 종료(D12)
ParticipantStatus   ACTIVE, PAUSED, LEFT, EVICTED
LeftReason          PERSONAL, DEVICE_ISSUE, UNPLEASANT, ETC, WITHDRAWAL, SANCTION
AbsenceEventType    START, END
StickerType         CLAP, MUSCLE, FIRE
  ↳ 한글 라벨: 파이팅 / 힘내요 / 열공
PointReason         WELCOME, SESSION_COMPLETE, GOAL_ACHIEVED, EVICTION_PENALTY,
                    ORDER_SPEND, ORDER_CANCEL, CHARGE, APPEAL_REFUND
ProductType         GIFTICON, BOOK
ProductStatus       ON_SALE, SOLD_OUT, HIDDEN
OrderStatus         ORDERED, CANCELLED
ChargeStatus        READY, APPROVED, FAILED
AppealStatus        PENDING, ACCEPTED, REJECTED
ReportTargetType    MEMBER, SESSION
ReportReasonCode    SEXUAL_CONTENT, VIOLENT_THREAT, AD_SPAM, INAPPROPRIATE_SCREEN, ETC
ReportSeverity      HIGH, NORMAL
ReportStatus        PENDING, RESOLVED, REJECTED, SANCTIONED
SanctionType        TEMP, PERMANENT
DecidedBy           AI, ADMIN
BadgeCode           GOAL_ACHIEVED
```

선택지 값: `targetMinutes {60, 120, 180, 240}`(대기열 4종) · `periodDays {7, 14, 30}`

`LeftReason`의 `EVICTED`는 없다. 퇴출은 `ParticipantStatus=EVICTED`로 표현한다.
`WITHDRAWAL`·`SANCTION`은 서버 전용 사유이며 SS-7 요청으로 받지 않는다.

구 v1.0에서 폐기한 enum: `GoalCategory`, `ProofMethod`, `ProofAiStatus`, `GroupStatus`, `GroupMemberStatus`, `PostStatus`, `AiJudgmentType`, `AiVerdict`, `AiReviewType`, `AiTargetType`, `AiReviewStatus`, `AccessReason`.

### 0-5. 정책값 (`application.yml`의 `morak.*` — 최종 전체 목록)

```yaml
morak:
  timezone: Asia/Seoul
  jwt:
    secret: ${MORAK_JWT_SECRET}          # 기본값 없음. 32자 이상(HS256)
    expire-hours: 24
  dev:
    enabled: false                        # @Profile("dev")와 AND 조건 (이중 스위치)
  security:
    social-hash-pepper: ${MORAK_SOCIAL_HASH_PEPPER}   # blocked_social_hash HMAC
  match:
    wait-expire-minutes: 10               # 대기 만료 (D8)
    rematch-cooldown-minutes: 30          # 퇴출자 재매칭 쿨다운 (D14)
  session:
    absence-threshold-seconds: 60         # 경고 부여 임계 (★D4)
    evict-warning-count: 3                # 퇴출 누적 경고 수 (FR-304)
    reconnect-grace-seconds: 90           # 재접속 유예 (D13)
    pause-limit-minutes: 10               # 화장실 모드 상한 (FR-305, D9)
    min-participants: 2                   # 미만이면 조기 종료 (D12)
    required-participants: 6              # 매칭 확정 인원 (FR-201·FR-203)
    absence-min-interval-seconds: 5       # SS-4 이벤트 최소 간격. 미만이면 429 ABSENCE_RATE_LIMITED
  point:
    welcome: 1000                         # (D15, 잠정)
    session-complete-per-hour: 100        # (D15, 잠정)
    eviction-penalty: 300                 # (D15, 잠정)
    goal-achieved: 1000                   # (D15, 잠정)
  report:
    sla-hours:
      high: 24
      normal: 72
  withdrawal:
    grace-days: 30
  livekit:
    host: ${MORAK_LIVEKIT_HOST}
    api-key: ${MORAK_LIVEKIT_API_KEY}
    api-secret: ${MORAK_LIVEKIT_API_SECRET}
    token-ttl-seconds: 3600               # SS-2 접속 토큰 유효 시간
  pg:
    provider: toss-test                   # 테스트 모드 (D16, 잠정)
    secret-key: ${MORAK_PG_SECRET_KEY}
```

구 v1.0에서 폐기한 설정: `proof.*`, `ai.*`, `completion.*`, `media.retention-days`, `storage.local-path`, `security.media-token-secret`(촬영물 열람 HMAC이 사라져 쓰임이 없다), multipart 10MB 제한.

운영 기동에 반드시 필요한 환경변수는 6개다 — `MORAK_JWT_SECRET`, `MORAK_SOCIAL_HASH_PEPPER`, `MORAK_LIVEKIT_HOST`·`MORAK_LIVEKIT_API_KEY`·`MORAK_LIVEKIT_API_SECRET`, `MORAK_PG_SECRET_KEY`. 기본값을 두지 않으며 미설정 시 기동에 실패한다.

### 0-6. 계산식 (한 곳에만 정의)

| 값 | 식 | 비고 |
|---|---|---|
| 세션 완주 | 세션 종료 시각에 `session_participant.status ∈ {ACTIVE, PAUSED}` | LEFT·EVICTED는 미완주. 재실 비율 기준 없음 (★D1) |
| 세션 완주 포인트 | `point.session-complete-per-hour × (target_minutes ÷ 60)` | 120분 세션 = 200p. **실제 재실 시간이 아니라 `target_minutes` 기준이다** — D12 조기 종료로 30분 만에 끝난 60분 세션도 +100 (D15 보충) |
| Streak 증가 | 하루에 1세션 이상 완주하면 그날 +1. `streak_day` UNIQUE(member_id, completed_on)로 멱등 | 하루 다회 완주해도 1일 (★D2) |
| Streak 리셋 | 완주일이 하루라도 끊기면 `member.current_streak = 0` | 목표는 유지 (★D3) |
| 세션 시점 Streak | SS-8의 `before`·`after` = `streak_day`에서 해당 `completed_on`을 기준으로 **역방향 연속 행 수** | `member.current_streak`(현재값 캐시)를 쓰면 과거 세션 결과를 다시 열었을 때 그때가 아닌 지금 값이 나온다 |
| 목표 달성 | `current_streak >= member_goal.period_days` | 달성 시 `GoalStatus=ACHIEVED`, `point.goal-achieved` 지급, `BadgeCode=GOAL_ACHIEVED`. 재설정 가능 (★D3) |
| 경고 부여 | **캠이 연결된 상태에서** 얼굴 미검출 지속시간 > `session.absence-threshold-seconds` | 경고 1회. `warning.seq`는 세션 내 1~3 (★D4) |
| 연결 끊김 처리 | `participant_left` 후 `session.reconnect-grace-seconds` 내 미복귀 | **자리비움 경고와 별개 축이다.** 경고·포인트 차감 없이 `LEFT(DEVICE_ISSUE)` 자동 처리, 그 세션만 미완주 (D13 확정) |
| 퇴출 | `warning_count >= session.evict-warning-count` | `EVICTED` + `point.eviction-penalty` 차감 + LiveKit 강제 퇴장 |
| 경고 카운터 범위 | 세션 스코프. 세션 종료 시 소멸 | 계정 누적 매너 점수는 Phase 4 (D11) |
| 재매칭 가능 시각 | `eviction.created_at + match.rematch-cooldown-minutes` | (D14) |
| 세션 종료 예정 | `started_at + target_minutes` | `started_at` = 6인 확정 시각 (D21) |

---

## §1. 도메인 모델

컬럼·타입·제약의 정본은 `docs/db-schema.md`. 여기는 목록만 둔다.

**회원** `member` · `member_agreement` · `member_goal` · `streak_day` · `media_consent` · `blocked_social_hash`
**매칭** `match_lock` · `match_request` · `match_block` · `match_event`
**세션** `live_session` · `session_participant` · `absence_event` · `warning` · `eviction` · `appeal_case`
**포인트·커머스** `point_ledger` · `product` · `store_order` · `point_charge`
**신고·운영** `report_case` · `report` · `report_history` · `sanction`

세션 결과는 별도 테이블 없이 `session_participant(completed, point_awarded)`에서 파생한다.

구 v1.0에서 폐기한 테이블: `challenge_group`, `group_member`, `proof`, `proof_media`, `ai_judgment`, `ai_review_queue`, `sticker_reaction`, `media_access_log`, `final_report`, `completion_stats`, `challenge_post`, `post_like`, `kick_history`, `fill_in_history`.

**중복 방어선 3종** — 이 세 제약이 깨지면 중복 지급·이중 배정·중복 판정이 성립한다.

| 제약 | 막는 것 |
|---|---|
| `point_ledger` UNIQUE(member_id, reason, ref_type, ref_id) | 포인트 중복 지급·중복 차감 |
| `match_request` UNIQUE(active_member_id) | 한 회원의 이중 매칭 배정 |
| `absence_event` UNIQUE(session_id, member_id, client_seq) | 클라이언트 재전송으로 인한 중복 경고 |

**`point_ledger`의 `ref_type`·`ref_id` 규약** — 둘 다 NOT NULL이다. 멱등키가 성립하려면 사유마다 참조 대상이 하나로 고정되어야 한다.

| reason | ref_type | ref_id |
|---|---|---|
| WELCOME | MEMBER | `member.id` |
| SESSION_COMPLETE | SESSION_PARTICIPANT | `session_participant.id` (세션이 아니라 참가 행이다 — 같은 세션의 6인이 각자 1행씩 받는다) |
| EVICTION_PENALTY, APPEAL_REFUND | EVICTION | `eviction.id` |
| GOAL_ACHIEVED | GOAL | `member_goal.id` |
| ORDER_SPEND, ORDER_CANCEL | ORDER | `store_order.id` |
| CHARGE | CHARGE | `point_charge.id` |

---

## §2. 상태 머신

| 엔티티 | 상태 | 진입 조건 | 이탈 조건 | 이탈 시 동반 갱신 |
|---|---|---|---|---|
| **member** | ACTIVE | 가입(AU-1) 또는 AU-5 철회 | AU-4 → WITHDRAW_PENDING | `withdraw_requested_at`, `delete_scheduled_at`(+30일), 활성 match_request CANCELLED, 진행 세션 참가자 `LEFT(WITHDRAWAL)` |
| | WITHDRAW_PENDING | AU-4 | AU-5·재로그인 → ACTIVE / B4(유예 30일 경과) → DELETED | 철회 시 시각 컬럼 NULL / 삭제 시 익명화 |
| | DELETED | B4 | (종점) | `provider_user_id='deleted:{id}'`, 닉네임 치환, `birth_date` NULL, media_consent 삭제, 제재 이력자면 blocked_social_hash 등재. 커머스 기록(store_order·point_charge)은 파기 예외 |
| **member_goal** | ACTIVE | AU-7 설정 | Streak가 `period_days` 도달 → ACHIEVED / AU-7 재설정 → CANCELLED | ACHIEVED 시 `achieved_at`, `point_ledger(GOAL_ACHIEVED)`, 뱃지 부여 (★D3) |
| | ACHIEVED | B1 목표 달성 검사 | AU-7 재설정 → 새 ACTIVE 행 생성 | (종점) |
| | CANCELLED | AU-7 재설정·탈퇴 | (종점) | — |
| **match_request** | WAITING | MT-1 등록 | MT-1 성사 → MATCHED / MT-3 → CANCELLED / B2(10분 경과) → EXPIRED / AU-4 탈퇴 → CANCELLED / AD-4 제재 → CANCELLED | 모든 이탈에서 `active_member_id=NULL`(조건 행 잠금 + 조건부 UPDATE 동반). MATCHED는 `matched_session_id` 기록, CANCELLED·EXPIRED는 `match_event` 기록 |
| **live_session** | LIVE | MT-1 6인 확정(D21). `end_reason=NULL` | B1(`ends_at` 도래) → ENDED(`end_reason=NORMAL`) / SS-7·SS-10·AD-4로 잔여 인원 < `min-participants` → ENDED(`end_reason=EARLY_UNDER_MIN`) | 잔류 참가자 완주 판정, 포인트 지급, `streak_day` INSERT, 목표 달성 검사, LiveKit 룸 종료 |
| | ENDED | B1 또는 조기 종료 | (종점) | `ended_at`·`end_reason` 확정. SS-8 결과 조회 가능. 조기 종료여도 완주 포인트는 `target_minutes` 기준 그대로다(D15 보충) |
| | CANCELLED | (v1에서 전이 경로 없음) | — | enum에는 정의되어 있으나 v1에서 이 상태로 보내는 경로가 없다 |
| **session_participant** | ACTIVE | MT-1 6인 확정 | SS-5 → PAUSED / SS-7 → LEFT / SS-4·SS-6 경고 3회 → EVICTED / SS-10 재접속 유예 초과 → LEFT / AU-4·AD-4 → LEFT | 세션 종료 시각에 ACTIVE·PAUSED면 `completed=true`, `point_awarded` 기록 |
| | PAUSED | SS-5(세션당 1회, `pause_used=false`) | SS-6 복귀 → ACTIVE / 10분 초과 복귀 → ACTIVE + 경고 1회(D9) / 그 경고가 3회째면 → EVICTED | `pause_started_at` NULL 복원 |
| | LEFT | SS-7 자율 퇴장(`PERSONAL`·`DEVICE_ISSUE`·`UNPLEASANT`·`ETC`) / **SS-10 재접속 유예 90초 초과(`DEVICE_ISSUE`, 서버 자동)** / AU-4 탈퇴(`WITHDRAWAL`) / AD-4 제재(`SANCTION`) | (종점) | `left_at`, `left_reason`. 포인트 차감 없음, 그 세션 미완주(D10). 재입장 불가 |
| | EVICTED | 경고 누적 3회 | (종점) | `eviction` INSERT, `point_ledger(EVICTION_PENALTY, -300)`, LiveKit 강제 퇴장, 재매칭 쿨다운 30분 기산. AD-6 이의 인용 시 `eviction.revoked_at` 기록 + `completed` 소급(상태는 EVICTED로 남는다) |
| **point_charge** | READY | PY-1 생성 | PY-2·PY-3 승인 확인 → APPROVED / PG 실패 통보 → FAILED | APPROVED 시 `pg_tid` 기록, `point_ledger(CHARGE, +pointAmount)` |
| | APPROVED | PY-2·PY-3 | (종점) | 재호출은 멱등 흡수(원장 UNIQUE) |
| | FAILED | PG 실패 통보 | (종점) | 포인트 적립 없음 |
| **store_order** | ORDERED | SR-3 생성 | (v1에서 취소 경로 없음) | 재고 차감, `point_ledger(ORDER_SPEND, -amount)` |
| | CANCELLED | (v1에서 전이 경로 없음) | — | 환불(FR-506)이 보류이므로 v1에서 이 상태로 보내는 경로가 없다. `ORDER_CANCEL` 원장 사유도 동일 |
| **report_case** | PENDING | RP-1 접수·병합. `sla_due_at` 계산 | AD-3 → RESOLVED·REJECTED·SANCTIONED | 종결 시 `open_target_id=NULL`. 재오픈 불가(재검토는 새 케이스). REJECTED 시 신고자에게 `restriction_review`. `overdue`는 저장하지 않고 `status=PENDING AND sla_due_at < now`로 파생한다 |
| **appeal_case** | PENDING | AP-1 신청(퇴출 1건당 1회). `reason_text`·`created_at`·`sla_due_at` 기록 | AD-6 → ACCEPTED·REJECTED | ACCEPTED 시 `eviction.revoked_at` + `point_ledger(APPEAL_REFUND, +300)` + 해당일 완주 소급 재판정. `overdue`는 저장하지 않고 `status=PENDING AND sla_due_at < now`로 파생한다 |
| **sanction** | (기간형) | AD-3 SANCTIONED / AD-4 단독 적용 | `ends_at` 경과(TEMP) | 유효식은 §0-2 ④ |

---

## §3. 엔드포인트 총람

| ID | Method Path | 한 줄 | 단계 |
|---|---|---|---|
| AU-1 | POST /api/auth/login | 소셜 로그인·가입 | 1 |
| AU-2 | GET /api/members/me | 내 정보 | 1 |
| AU-3 | POST /api/members/me/birthdate | 생년월일·연령 검증 | 1 |
| AU-4 | POST /api/members/me/withdrawal | 탈퇴 신청 | 11 |
| AU-5 | DELETE /api/members/me/withdrawal | 탈퇴 철회 | 11 |
| AU-6 | POST /api/members/me/media-consent | 캠 영상 온디바이스 분석 동의 | 3 |
| AU-7 | PUT /api/members/me/goal | 목표 기간 설정 | 1.5 |
| MT-1 | POST /api/match-requests | 매칭 요청 | 2 |
| MT-2 | GET /api/match-requests/me | 매칭 상태 폴링 | 2 |
| MT-3 | DELETE /api/match-requests/{id} | 매칭 취소 | 2 |
| SS-1 | GET /api/sessions/{id} | 세션 상세 | 3 |
| SS-2 | POST /api/sessions/{id}/token | LiveKit 접속 토큰 | 3 |
| SS-3 | PUT /api/sessions/{id}/goal | 오늘 할 일 등록 | 3 |
| SS-4 | POST /api/sessions/{id}/absence-events | 자리비움 이벤트 보고 | 4 |
| SS-5 | POST /api/sessions/{id}/pause | 화장실 모드 시작 | 4 |
| SS-6 | DELETE /api/sessions/{id}/pause | 화장실 모드 복귀 | 4 |
| SS-7 | DELETE /api/sessions/{id}/participation | 자율 퇴장 | 4 |
| SS-8 | GET /api/sessions/{id}/result | 세션 결과 | 5 |
| SS-9 | GET /api/members/me/sessions | 내 세션 이력 | 5 |
| SS-10 | POST /api/webhooks/livekit | LiveKit 입퇴장 웹훅 | 3 |
| SS-11 | GET /api/stickers | 스티커 종류 목록 | 3 |
| AP-1 | POST /api/evictions/{evictionId}/appeals | 퇴출 이의 신청 | 10 |
| PT-1 | GET /api/members/me/points | 포인트 잔액·원장 | 6 |
| SR-1 | GET /api/store/products | 상품 목록 | 7 |
| SR-2 | GET /api/store/products/{id} | 상품 상세 | 7 |
| SR-3 | POST /api/orders | 주문 생성(포인트 결제) | 7 |
| SR-4 | GET /api/orders | 내 주문 목록 | 7 |
| SR-5 | GET /api/orders/{id} | 주문 상세 | 7 |
| PY-1 | POST /api/points/charges | 포인트 충전 생성 | 8 |
| PY-2 | POST /api/points/charges/{id}/confirm | 충전 승인 확인 | 8 |
| PY-3 | POST /api/webhooks/payment | PG 결제 웹훅 | 8 |
| RP-1 | POST /api/reports | 신고·재매칭 차단 | 9 |
| AD-1 | GET /api/admin/reports | 신고 케이스 목록 | 9 |
| AD-2 | GET /api/admin/reports/{caseId} | 신고 케이스 상세 | 9 |
| AD-3 | PATCH /api/admin/reports/{caseId} | 신고 처리(+제재) | 9 |
| AD-4 | POST /api/admin/members/{memberId}/sanctions | 제재 단독 적용 | 9 |
| AD-5 | GET /api/admin/appeals | 이의 큐 | 10 |
| AD-6 | PATCH /api/admin/appeals/{appealId} | 이의 처리 | 10 |
| AD-7 | GET /api/admin/sessions | 진행 중 세션 모니터 | 10 |
| AD-8 | GET /api/admin/withdrawals | 탈퇴 처리 결과 | 11 |
| DEV-2 | POST /api/dev/clock | 개발 전용 시각 조작 | 1 |
| DEV-3 | POST /api/dev/sessions/seed | 개발 전용 완주 이력 시드 | 5 |
| DEV-4 | POST /api/dev/batches/{name} | 개발 전용 배치 트리거 | 2 |

**식별자 규칙**: 신고는 전역에서 `caseId`(=`report_case.id`)로 통일한다. `report.id`는 외부에 노출하지 않는다.

---

## §4. 엔드포인트 상세

### AU-1 소셜 로그인·가입

`POST /api/auth/login` — FR-101, FR-102, FR-103

목적: 소셜 인가 코드로 로그인하거나 가입한다. 별도 가입 폼 없이 약관 동의를 이 요청에 함께 싣는다.

```json
{
  "provider": "KAKAO",
  "authorizationCode": "hK9x2Lm...",
  "agreements": [
    {"type": "TOS", "agreed": true},
    {"type": "PRIVACY", "agreed": true},
    {"type": "MARKETING", "agreed": false}
  ]
}
```

절차

1. 소셜 검증 실패 → 401 `INVALID_SOCIAL_TOKEN`
2. `HMAC-SHA256(provider + providerUserId, pepper)`가 `blocked_social_hash`에 있으면 → 403 `REJOIN_BLOCKED`
3. 기존 회원이 `WITHDRAW_PENDING`이면 철회·복구(시각 컬럼 NULL, `ACTIVE`) 후 `loginResult="RESTORED"`
4. 신규 회원: 필수 약관 2종(`TOS`, `PRIVACY`)이 모두 `agreed=true`가 아니면 → 400 `AGREEMENT_REQUIRED`. `MARKETING`은 선택이며, **누락되거나 `agreed=false`이면 `member_agreement` 행을 만들지 않는다** — 행의 유무가 곧 동의 여부다(미동의를 뜻하는 행을 따로 저장하지 않는다) (D20). 위치정보 약관은 근거가 없어 두지 않는다
5. 신규 회원: SNS 프로필 이미지·닉네임을 받아 저장(`sns_profile_image_url`, `sns_nickname`)하고, 서버가 표시용 `익명 {동물명}{2자리}`를 생성한다(`nickname`, 중복 시 재생성). 타인에게 보이는 모든 화면은 `nickname`만 쓰고 SNS 값은 본인 확인용으로만 쓴다
6. 생년월일을 소셜에서 받으면 즉시 검증한다. 이때 만 14세 미만이면 계정을 만들지 않고 403 `UNDER_AGE_SIGNUP_BLOCKED` (★D7 — 잠정, 팀 확인 대기, open-decisions Q6). 미수신이면 `age_verification=REQUIRED` + `needsBirthdate=true`

응답 200

```json
{
  "accessToken": "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMDQyIn0...",
  "memberId": 1042,
  "isNewMember": true,
  "needsBirthdate": true,
  "ageVerification": "REQUIRED",
  "loginResult": "NORMAL"
}
```

**개발용 로그인도 이 엔드포인트를 쓴다** — `provider=DEV`로 호출하면 `DevSocialClient`가 실제 소셜 검증을 대신하고, `authorizationCode`를 `provider_user_id`로 삼아 upsert한다(같은 값으로 다시 부르면 기존 회원 로그인). `@Profile("dev")` AND `morak.dev.enabled=true` 이중 스위치가 걸려 있어 운영 프로필에서는 `DevSocialClient` 빈이 등록되지 않고 401 `INVALID_SOCIAL_TOKEN`으로 떨어진다. 별도 dev 로그인 엔드포인트를 두지 않는 이유는, 가입 경로가 둘이면 약관·웰컴 포인트·`match_lock` 시드 같은 부수효과가 한쪽에서만 빠지기 때문이다. 관리자 계정은 어느 경로로도 만들 수 없고 DB 수동 UPDATE로만 만든다(`role` 파라미터 없음).

발생 에러: 401 `INVALID_SOCIAL_TOKEN` / 403 `REJOIN_BLOCKED` / 403 `UNDER_AGE_SIGNUP_BLOCKED` / 400 `AGREEMENT_REQUIRED` / 400 `VALIDATION_FAILED`

게이트: ① 예외(JWT skip) · ② 예외 · ④ 예외 · ⑤ 미적용

트랜잭션·부수효과 (신규 가입 시 한 트랜잭션)

- `member` INSERT
- `member_agreement` INSERT 2~3행 (UNIQUE(member_id, type))
- `match_lock('member:{id}')` 동반 INSERT — 잠금 행은 런타임에 만들지 않는다(§MT-1 참조)
- `point_ledger` INSERT: `reason=WELCOME`, `delta=+1000`, `ref_type=MEMBER`, `ref_id={memberId}` (FR-103). UNIQUE(member_id, reason, ref_type, ref_id)로 재가입·재호출 시에도 1회만 지급된다
- `member.point_balance = 1000` 반영

### AU-2 내 정보

`GET /api/members/me` — FR-102, FR-151, FR-152

목적: 홈 화면 진입 시 필요한 회원 상태·포인트·목표·Streak를 한 번에 내려준다.

응답 200

```json
{
  "memberId": 1042,
  "nickname": "익명 치타37",
  "role": "PARTICIPANT",
  "memberStatus": "ACTIVE",
  "ageVerification": "VERIFIED",
  "mediaConsented": true,
  "pointBalance": 1300,
  "goal": {
    "goalId": 88,
    "periodDays": 14,
    "startedOn": "2026-08-01",
    "status": "ACTIVE",
    "achievedAt": null
  },
  "streak": {"current": 5, "lastCompletedOn": "2026-08-11"},
  "sanction": null
}
```

목표 미설정이면 `"goal": null`. 유효 제재 보유 시 `"sanction": {"type": "TEMP", "endsAt": "2026-08-15T00:00:00+09:00"}`.

발생 에러: 401 `UNAUTHORIZED` / 401 `TOKEN_EXPIRED`

게이트: ② 예외 · ④ 예외 (제재·탈퇴 유예 중에도 본인 상태는 볼 수 있어야 한다)

부수효과: 없음.

### AU-3 생년월일·연령 검증

`POST /api/members/me/birthdate` — FR-101, NFR-201

목적: 소셜에서 생년월일을 받지 못한 경우 수동 입력을 받아 연령을 검증한다.

```json
{"birthDate": "2005-03-01"}
```

절차

1. `age_verification != REQUIRED`(즉 이미 `VERIFIED`)이면 409 `ALREADY_VERIFIED`. 미만 판정을 받은 계정은 아예 사라지므로 재입력으로 뒤집는 경로 자체가 없다
2. 만 나이 = `Period.between(birthDate, LocalDate.now(clock)).getYears()`
3. 만 14세 이상이면 `age_verification=VERIFIED` → 200
4. 만 14세 미만이면 **계정을 남기지 않는다** → 403 `UNDER_AGE_SIGNUP_BLOCKED` (★D7 — 잠정, 팀 확인 대기, open-decisions Q6)

응답 200

```json
{"ageVerification": "VERIFIED"}
```

응답 403

```json
{"error": {"code": "UNDER_AGE_SIGNUP_BLOCKED", "message": "만 14세 미만은 가입할 수 없습니다.", "details": null}}
```

발생 에러: 409 `ALREADY_VERIFIED` / 403 `UNDER_AGE_SIGNUP_BLOCKED` / 400 `VALIDATION_FAILED`(미래 일자·형식 오류)

게이트: ② ✓ · ④ ✓ · ⑤ 미적용(이 API가 ⑤를 해소하는 API다)

트랜잭션·부수효과 (미만 판정 시 한 트랜잭션에서 계정 파기)

- `member` 행 삭제 (탈퇴 유예 30일을 적용하지 않는다. 가입이 성립하지 않은 상태이므로 보관할 근거가 없다)
- `member_agreement`, `media_consent`, `match_lock('member:{id}')` 함께 삭제
- 웰컴 포인트 `point_ledger(WELCOME)` 행 삭제, 잔액 캐시 소멸
- 발급된 accessToken은 다음 요청부터 ② 게이트에서 401 `UNAUTHORIZED`

구 v1.0은 "가입 유지 + 기능 차단"이었다. FR-101·NFR-201의 "가입 및 챌린지 진입을 시스템적으로 차단" 문면에 맞춰 가입 자체를 차단하는 것으로 변경했다. 1단계 코드 수정 대상이다.

### AU-4 탈퇴 신청

`POST /api/members/me/withdrawal` — NFR-202

목적: 30일 유예 후 자동 파기되는 탈퇴를 신청한다.

요청 본문 없음.

응답 202

```json
{"deleteScheduledAt": "2026-09-11T00:00:00+09:00"}
```

발생 에러: 409 `NOT_WITHDRAWING`은 여기서 발생하지 않는다. 이미 `WITHDRAW_PENDING`이면 ② 게이트가 403 `WITHDRAWAL_PENDING`으로 먼저 잡는다.

게이트: ② ✓ · ④ 예외(제재 중에도 탈퇴는 막지 않는다)

트랜잭션·부수효과

- `member.status = WITHDRAW_PENDING`, `withdraw_requested_at`, `delete_scheduled_at = now + withdrawal.grace-days`
- 활성 `match_request` → `CANCELLED` + `active_member_id=NULL` (조건 행 잠금 후 조건부 UPDATE)
- 진행 중 `session_participant` → `LEFT`, `left_reason=WITHDRAWAL`. LiveKit 강제 퇴장
- 실제 파기는 B4가 수행한다

### AU-5 탈퇴 철회

`DELETE /api/members/me/withdrawal`

응답 204. `WITHDRAW_PENDING`이 아니면 409 `NOT_WITHDRAWING`.

게이트: ② 예외 · ④ 예외 — 제재 중 철회를 막으면 계정 복구가 영구히 불가능해진다.

부수효과: `member.status = ACTIVE`, `withdraw_requested_at`·`delete_scheduled_at` NULL. 취소된 매칭 요청과 퇴장한 세션은 복구하지 않는다.

### AU-6 캠 영상 온디바이스 분석 동의

`POST /api/members/me/media-consent` — NFR-203, FR-303

목적: 라이브 세션 진입 전 캠 영상 처리 방식에 동의를 받는다.

동의 문구(고정): "라이브 세션 중 웹캠 영상은 내 기기에서만 분석되며, 서버로 전송하거나 저장하지 않습니다. 서버에는 자리비움 여부와 시각만 기록됩니다."

```json
{"agreed": true}
```

응답 204. `agreed=false`는 400 `VALIDATION_FAILED`(미동의를 저장하지 않는다 — 동의 철회는 v1 범위 밖).

발생 에러: 400 `VALIDATION_FAILED`

게이트: ② ✓ · ④ ✓ · ⑤ 미적용

부수효과: `media_consent` INSERT(재호출 시 UPDATE). 미동의 상태에서는 SS-2 토큰 발급이 403 `CONSENT_REQUIRED`로 막힌다. 세션 영상은 저장하지 않는다(D17) — NFR-203의 "저장이 필요한 경우"는 없음으로 고정했다.

### AU-7 목표 기간 설정

`PUT /api/members/me/goal` — FR-151

목적: 마이페이지에서 개인 목표 챌린지 기간을 설정한다. 매칭 조건이 아니라 Streak 달성 판정의 기준이다.

```json
{"periodDays": 14}
```

허용값 `{7, 14, 30}`. 위반 400 `VALIDATION_FAILED`.

응답 200

```json
{
  "goalId": 88,
  "periodDays": 14,
  "startedOn": "2026-08-12",
  "status": "ACTIVE",
  "achievedAt": null
}
```

발생 에러: 409 `GOAL_ALREADY_ACTIVE`(진행 중 목표가 있으면 새 목표를 만들지 않는다) / 400 `VALIDATION_FAILED`

게이트: ② ✓ · ④ ✓ · ⑤ ✓

부수효과: `match_lock('member:{id}')`를 `FOR UPDATE`로 먼저 잠근 뒤 `member_goal` INSERT(`status=ACTIVE`, `started_on = LocalDate.now(clock)`). 활성 1건 제약을 유니크 인덱스가 아니라 조건부 로직으로 걸기 때문에, 동시 요청 두 건이 각각 "활성 목표 없음"을 보고 통과하는 것을 회원 행 잠금으로 막는다(MT-1과 같은 잠금 행을 쓴다). `ACHIEVED` 목표가 있는 상태에서는 새 목표 설정이 허용된다(재도전).

### MT-1 매칭 요청

`POST /api/match-requests` — FR-201, FR-203, NFR-401

목적: 하루 목표 시간만 선택해 6인 자동 매칭 대기열에 등록한다. 6인이 차면 그 자리에서 라이브 세션이 생성된다.

```json
{"targetMinutes": 120}
```

허용값 `{60, 120, 180, 240}` 4종 고정 (D8). 위반 400 `VALIDATION_FAILED`.

절차 (전체가 하나의 `@Transactional`)

1. `match_lock` **회원 행** 잠금 — `@Lock(PESSIMISTIC_WRITE) findByLockKey("member:{memberId}")`
2. 활성 `WAITING` 요청 없음 → 있으면 409 `DUPLICATE_MATCH_REQUEST`
3. 활성 세션 참가 없음(`session_participant.status ∈ {ACTIVE, PAUSED}` + 세션 `LIVE`) → 있으면 409 `ALREADY_IN_ACTIVE_SESSION`
4. 퇴출 쿨다운 — 최근 `eviction.created_at + 30분`이 아직 지나지 않았으면 409 `REMATCH_COOLDOWN` (details: `availableAt`) (D14)
5. `match_lock` **조건 행** 잠금 — `"match:{targetMinutes}"`
6. 내 요청 INSERT (`status=WAITING`, `active_member_id=member_id`, `expires_at = now + match.wait-expire-minutes`)
7. 동일 `target_minutes`의 `WAITING`을 `requested_at` 오름차순 조회. 제외 대상: 유효 제재 보유자 / 활성 세션 보유자 / **요청자와 `match_block` 관계인 회원**(★D6)
8. 선택된 후보 6인 집합 안에 상호 `match_block` 쌍이 남아 있으면 후순위 요청으로 교체한다. 6인을 채우지 못하면 대기 상태로 응답한다
9. 자신 포함 6건이 모이면 선착순 정확히 6건 선택 → `UPDATE match_request SET status='MATCHED', ... WHERE id IN (6건) AND status='WAITING'` 실행 후 **영향 행 수 = 6 검증, 미달 시 전체 롤백**
10. `live_session` INSERT — `status=LIVE`, `started_at = now`(6인 확정 시각, D21), `ends_at = started_at + target_minutes`, `livekit_room_name = "molock-{sessionId}"` UNIQUE
11. `session_participant` 6행 INSERT (`status=ACTIVE`, `warning_count=0`, `pause_used=false`, **`joined_at=NULL`**). 매칭 확정과 실제 입장은 다른 사건이므로 여기서 `joined_at`을 채우지 않는다 — 기록은 SS-10 `participant_joined` 웹훅이 최초 1회만 하고, `NULL`은 "매칭됐지만 아직 입장하지 않음"을 뜻한다
12. 요청 6건에 `matched_session_id` 기록 + `active_member_id=NULL`
13. `match_event(MATCH_COMPLETED)` 기록 — 성사된 6명 각각 1행(총 6행). `member_id`가 NOT NULL이고 재참여율 지표가 회원 단위라 단 건 기록으로는 나머지 5명의 성사 이력이 사라진다

**잠금 행은 런타임에 만들지 않는다** — 조건 행 4개(`match:60`, `match:120`, `match:180`, `match:240`)는 기동 시 `ApplicationRunner`가 시드하고, 회원 행은 가입 시 동반 INSERT한다. 미존재 행에 `FOR UPDATE`를 걸면 갭 락이 없어 동시 진입 시 INSERT 경합이 나고 복구가 불가능하다(H2 실측 확인).

H2 URL에 `;LOCK_TIMEOUT=3000`. `PessimisticLockingFailureException` → 503 `LOCK_ACQUISITION_FAILED`.

응답 201 (대기)

```json
{
  "matchRequestId": 901,
  "status": "WAITING",
  "targetMinutes": 120,
  "requestedAt": "2026-08-12T09:00:00+09:00",
  "expiresAt": "2026-08-12T09:10:00+09:00",
  "waitingCount": 4,
  "requiredCount": 6,
  "sessionId": null
}
```

응답 201 (성사)

```json
{
  "matchRequestId": 901,
  "status": "MATCHED",
  "targetMinutes": 120,
  "requestedAt": "2026-08-12T09:00:00+09:00",
  "expiresAt": "2026-08-12T09:10:00+09:00",
  "waitingCount": 6,
  "requiredCount": 6,
  "sessionId": 5501
}
```

발생 에러: 409 `DUPLICATE_MATCH_REQUEST` / 409 `ALREADY_IN_ACTIVE_SESSION` / 409 `REMATCH_COOLDOWN` / 503 `LOCK_ACQUISITION_FAILED` / 400 `VALIDATION_FAILED`

게이트: ② ✓ · ④ ✓ · ⑤ ✓

FR-202(대기 2분 초과 시 인접 시간대 합류 팝업)는 보류다. 매칭 키가 단일 시간이라 인접 합류를 넣으면 잠금 행과 후보 조회가 다중 키로 바뀐다.

### MT-2 매칭 상태 폴링

`GET /api/match-requests/me` — FR-201

목적: 대기 화면이 주기적으로 호출해 매칭 성사를 확인한다. 푸시 알림은 v1에 없다 (D18).

응답 200

```json
{
  "matchRequestId": 901,
  "status": "WAITING",
  "targetMinutes": 120,
  "requestedAt": "2026-08-12T09:00:00+09:00",
  "expiresAt": "2026-08-12T09:10:00+09:00",
  "waitingCount": 4,
  "requiredCount": 6,
  "sessionId": null
}
```

`requiredCount`는 `session.required-participants`(6)를 그대로 내려준 값이며, `waitingCount`가 여기 도달하면 매칭이 성사된다.
종결 상태(CANCELLED·EXPIRED)에서는 대기열 자리가 없으므로 `waitingCount`를 0으로 내린다.

`status=MATCHED`면 `sessionId`가 채워진다. 클라이언트는 이 값을 받으면 폴링을 멈추고 SS-2로 넘어간다.
`status=EXPIRED`면 대기가 만료된 것이며(B2), 재시도 안내를 띄운다.

발생 에러: 404 `NO_ACTIVE_MATCH_REQUEST`(활성 요청도 최근 종결 요청도 없음)

게이트: ② ✓ · ④ ✓ · ⑤ ✓ · 소유권 본인

부수효과: 없음.

### MT-3 매칭 취소

`DELETE /api/match-requests/{matchRequestId}`

목적: 대기 중 조건을 바꾸거나 그만두기 위해 대기를 취소한다.

응답 204.

발생 에러: 409 `ALREADY_MATCHED`(이미 성사된 요청) / 403 `FORBIDDEN`(타인 요청) / 404 `NO_ACTIVE_MATCH_REQUEST`(없는 요청 id) / 503 `LOCK_ACQUISITION_FAILED`

이미 CANCELLED·EXPIRED인 요청의 재취소는 204로 흘린다(멱등 — 두 번 눌러도 같은 결과). `ALREADY_MATCHED`는 정말 성사된 경우에만 쓴다.

게이트: ② ✓ · ④ ✓ · ⑤ ✓ · 소유권 본인

트랜잭션·부수효과: 조건 행 잠금 후 `status=CANCELLED` + `active_member_id=NULL` + `match_event(WAIT_CANCELLED)`. NULL 해제가 없으면 `uk_mr_active` 때문에 재요청이 영구 불가해진다. 조건 변경 플로우가 여기에 걸린다.

### SS-1 세션 상세

`GET /api/sessions/{sessionId}` — FR-203, FR-301, FR-302

목적: 라이브 화면이 참가자 목록과 진행 상태를 그린다.

응답 200

```json
{
  "sessionId": 5501,
  "status": "LIVE",
  "targetMinutes": 120,
  "startedAt": "2026-08-12T09:00:00+09:00",
  "endsAt": "2026-08-12T11:00:00+09:00",
  "endedAt": null,
  "endReason": null,
  "roomName": "molock-5501",
  "participants": [
    {
      "memberId": 1042, "nickname": "익명 치타37", "isMe": true,
      "status": "ACTIVE", "warningCount": 1, "paused": false, "pauseUsed": false,
      "joinedAt": "2026-08-12T09:00:14+09:00", "goalText": "정처기 필기 3단원"
    },
    {
      "memberId": 1088, "nickname": "익명수달12", "isMe": false,
      "status": "PAUSED", "warningCount": 0, "paused": true, "pauseUsed": true,
      "joinedAt": null, "goalText": "토익 LC 파트3"
    }
  ]
}
```

`participants`는 LEFT·EVICTED 참가자도 포함한다(상태를 병기하므로 자리 수 변화가 화면에 드러난다). 실명·SNS 값은 어떤 경우에도 내려가지 않는다.

`endReason`은 진행 중이면 `null`, 종료 후에는 `NORMAL`(예정 시각 도래) 또는 `EARLY_UNDER_MIN`(잔여 인원이 `session.min-participants` 미만이 되어 조기 종료, D12)이다.

`joinedAt`이 `null`이면 매칭은 됐지만 아직 LiveKit 룸에 들어오지 않은 참가자다. 값은 SS-10 `participant_joined` 웹훅이 최초 1회만 채우므로, 재접속을 반복해도 최초 입장 시각이 유지된다.

발생 에러: 404 `SESSION_NOT_FOUND` / 403 `NOT_SESSION_PARTICIPANT`

게이트: ② ✓ · ④ ✓ · ⑤ ✓ · 세션 참가 이력 보유

부수효과: 없음.

### SS-2 LiveKit 접속 토큰 발급

`POST /api/sessions/{sessionId}/token` — FR-301, NFR-102

목적: 클라이언트가 LiveKit 룸에 접속할 수 있는 단기 토큰을 받는다. 서버는 토큰만 발급하고 미디어는 경유하지 않는다.

요청 본문 없음.

응답 200

```json
{
  "url": "wss://molock.livekit.cloud",
  "roomName": "molock-5501",
  "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "identity": "1042",
  "canPublishAudio": false,
  "expiresInSeconds": 3600
}
```

`expiresInSeconds`는 `livekit.token-ttl-seconds`(3600)를 그대로 내려준 값이다.

**identity 규약** — LiveKit `identity`는 `member_id`를 문자열로 만든 값이다(`"1042"`). 별도 매핑 컬럼을 두지 않으므로 SS-10 웹훅이 `participant.identity`를 파싱해 회원을 찾는 것도 이 규약에 전적으로 의존한다. 규약을 바꾸면 토큰 발급과 웹훅 처리가 함께 깨진다.

**마이크는 서버가 막는다 (D23)** — 발급 토큰의 grant에서 **오디오 publish 권한을 제외**한다. 비디오 publish와 전체 subscribe만 준다. FR-301의 "마이크 기본 Mute"를 클라이언트 UI가 아니라 토큰 권한으로 강제하는 것이며, v1에서는 언뮤트 수단이 없다. 응답의 `canPublishAudio`는 항상 `false`이고, 클라이언트는 이 값을 보고 마이크 버튼을 비활성 상태로 그린다.

절차

1. 세션 존재 → 없으면 404 `SESSION_NOT_FOUND`
2. 세션 `LIVE` → 아니면 409 `SESSION_ENDED`
3. 참가자 본인 → 아니면 403 `NOT_SESSION_PARTICIPANT`
4. 참가자 상태가 `EVICTED`면 409 `ALREADY_EVICTED`, `LEFT`면 403 `NOT_SESSION_PARTICIPANT`
5. 캠 영상 분석 동의(AU-6) 없으면 403 `CONSENT_REQUIRED`
6. LiveKit AccessToken 발급 — `identity = String.valueOf(memberId)`, grant는 해당 룸의 **비디오 publish + subscribe**만. 오디오 publish와 룸 생성·관리 권한은 주지 않는다 (D23)

발생 에러: 404 `SESSION_NOT_FOUND` / 409 `SESSION_ENDED` / 403 `NOT_SESSION_PARTICIPANT` / 409 `ALREADY_EVICTED` / 403 `CONSENT_REQUIRED`

게이트: ② ✓ · ④ ✓ · ⑤ ✓ · 세션 참가자 ACTIVE·PAUSED

부수효과: 없음(토큰 발급 자체는 상태를 바꾸지 않는다). 실제 입장은 SS-10 웹훅으로 기록된다. 6인 동율 그리드와 기본 Mute는 클라이언트 책임이다.

### SS-3 오늘 할 일 등록

`PUT /api/sessions/{sessionId}/goal` — FR-302

목적: 세션 진입 시 한 줄 목표를 입력받아 본인 화면 하단에 노출한다.

```json
{"goalText": "정처기 필기 3단원까지"}
```

최대 50자. 초과·빈 문자열 400 `VALIDATION_FAILED`.

응답 200

```json
{"goalText": "정처기 필기 3단원까지"}
```

발생 에러: 404 `SESSION_NOT_FOUND` / 403 `NOT_SESSION_PARTICIPANT` / 409 `SESSION_ENDED` / 409 `ALREADY_EVICTED`(퇴출된 참가자 — SS-2와 동일 취급) / 400 `VALIDATION_FAILED`

게이트: ② ✓ · ④ ✓ · ⑤ ✓ · 세션 참가자 ACTIVE·PAUSED · 소유권 본인

부수효과: `session_participant.goal_text` UPDATE. 세션 중 수정 가능하다.

### SS-4 자리비움 이벤트 보고

`POST /api/sessions/{sessionId}/absence-events` — FR-303, FR-304, FR-602, NFR-103

목적: 클라이언트의 온디바이스 AI가 **자기 자신의** 얼굴 미검출 시작·종료만 보고한다. 경고 부여와 퇴출 판정은 서버가 계산한다 (★D4 — 잠정, 팀 확인 대기, open-decisions Q3).

**이 API는 캠이 연결된 상태의 자리비움만 다룬다.** 연결 자체가 끊긴 경우(네트워크 단절·앱 종료)는 SS-10 웹훅이 별개 축으로 처리하며 경고를 만들지 않는다 (D13 확정). 두 축을 섞으면 와이파이가 끊긴 사람이 자리비움 경고를 받고 퇴출된다.

```json
{"type": "START", "clientSeq": 17, "occurredAt": "2026-08-12T09:41:12+09:00"}
```

신뢰 모델

- 클라이언트는 타인에 대한 판정을 보낼 수 없다. `memberId`는 요청 본문이 아니라 JWT에서 가져온다
- `clientSeq`는 세션 내 단조 증가하는 클라이언트 시퀀스다. `UNIQUE(session_id, member_id, client_seq)`가 재전송을 흡수한다
- 지속시간(60초 초과) 계산의 기준은 **`occurredAt` 간격**이다(서버 수신 시각이 아님 — 자기 자신만 보고하는 구조라 부풀려도 자해일 뿐이다). 서버는 수신 시각(`reported_at`)을 감사용으로 함께 저장한다
- `occurredAt` 허용 범위는 [세션 시작 −5초, 현재 +5초]. 벗어나면 400 `VALIDATION_FAILED`(±5초 여유는 단말이 초 단위로 자른 시각을 보내는 것을 흡수하기 위함 — 4단계 실측)
- 같은 참가자의 직전 이벤트로부터 `session.absence-min-interval-seconds`(5초) 안에 다시 들어오면 429 `ABSENCE_RATE_LIMITED`. 정상 클라이언트는 얼굴 검출 상태가 바뀔 때만 보내므로 초당 여러 건이 올 이유가 없다 — 위조·폭주 트래픽을 정상과 분리하는 방어선이다

서버 판정

1. `type=START` — `absence_event` INSERT. 이 시점에는 경고를 만들지 않는다
2. `type=END` — 직전 미종료 `START`와 짝을 지어 지속시간을 계산한다. `session.absence-threshold-seconds`(60초)를 초과했으면 `warning` INSERT (`seq = warning_count + 1`, UNIQUE(session_id, member_id, seq))
3. `warning_count`가 `session.evict-warning-count`(3)에 도달하면 즉시 퇴출 처리
4. 짝이 없는 `START`(END 미수신)는 세션 종료 처리(B1) 시점에 세션 종료 시각을 END로 간주해 정산한다

응답 200 (경고 부여)

```json
{"accepted": true, "warningCount": 2, "evicted": false, "evictionId": null, "pointDelta": 0}
```

응답 200 (퇴출)

```json
{"accepted": true, "warningCount": 3, "evicted": true, "evictionId": 77, "pointDelta": -300}
```

발생 에러: 409 `DUPLICATE_ABSENCE_EVENT`(같은 `clientSeq` 재수신 — 서버 상태는 바뀌지 않으므로 클라이언트는 정상 종료로 취급한다) / 429 `ABSENCE_RATE_LIMITED` / 409 `ALREADY_EVICTED` / 409 `SESSION_ENDED` / 403 `NOT_SESSION_PARTICIPANT` / 404 `SESSION_NOT_FOUND`

게이트: ② ✓ · ④ ✓ · ⑤ ✓ · 세션 참가자 ACTIVE·PAUSED · 본인 이벤트만

트랜잭션·부수효과 (퇴출 시 한 트랜잭션)

- `absence_event` INSERT
- `warning` INSERT
- `session_participant.status = EVICTED`, `warning_count = 3`, `left_at = now` (`left_reason`은 채우지 않는다 — 퇴출은 상태로 표현한다)
- `eviction` INSERT (`warning_count=3`, `point_penalty=300`)
- `point_ledger` INSERT — `reason=EVICTION_PENALTY`, `delta=-300`, `ref_type=EVICTION`, `ref_id={evictionId}` (FR-602). 잔액이 300 미만이면 음수를 허용한다(패널티는 잔액 부족으로 회피할 수 없어야 한다)
- LiveKit `RemoveParticipant` 호출로 룸에서 강제 퇴장
- 재매칭 쿨다운 30분 기산 (D14). 이의 신청 경로는 AP-1

FR-303의 딴짓·이상행동 감지는 보류다. v1은 자리비움(얼굴 미검출)만 판정한다.

### SS-5 화장실 모드 시작

`POST /api/sessions/{sessionId}/pause` — FR-305

목적: 세션당 1회, 10분간 카메라 송출을 중단한다.

요청 본문 없음.

절차: 조건부 UPDATE로 방어한다.

```sql
UPDATE session_participant
   SET status = 'PAUSED', pause_used = true, pause_started_at = :now
 WHERE session_id = :sid AND member_id = :mid
   AND status = 'ACTIVE' AND pause_used = false
```

영향 행 0이면 원인을 구분해 응답한다. `pause_used=true`면 409 `PAUSE_ALREADY_USED`, `status != ACTIVE`면 상태에 맞는 코드(`ALREADY_EVICTED` / `NOT_SESSION_PARTICIPANT`).

응답 200

```json
{
  "status": "PAUSED",
  "pausedAt": "2026-08-12T09:50:00+09:00",
  "pauseLimitSeconds": 600,
  "resumeDueAt": "2026-08-12T10:00:00+09:00"
}
```

발생 에러: 409 `PAUSE_ALREADY_USED` / 409 `ALREADY_EVICTED` / 409 `SESSION_ENDED` / 403 `NOT_SESSION_PARTICIPANT` / 404 `SESSION_NOT_FOUND`

게이트: ② ✓ · ④ ✓ · ⑤ ✓ · 세션 참가자 ACTIVE · 소유권 본인

부수효과: PAUSED 구간은 자리비움 판정 대상에서 제외된다. Pause 10분은 재실로 인정하므로 완주 판정(★D1)에 영향을 주지 않는다. 대체 일러스트 전환은 클라이언트 책임이다.

### SS-6 화장실 모드 복귀

`DELETE /api/sessions/{sessionId}/pause` — FR-305

목적: 카메라 송출을 재개한다. 10분을 넘겼으면 서버가 경고를 부여하고 Pause를 강제 종료한다 (D9).

요청 본문 없음.

절차

1. 참가자 상태가 `PAUSED`가 아니면 409 `PAUSE_NOT_ACTIVE`
2. 경과 = `now - pause_started_at`
3. 경과 ≤ `session.pause-limit-minutes` → `status=ACTIVE`, `pause_started_at=NULL`. 경고 없음
4. 경과 초과 → 경고 1회 부여(`warning` INSERT) 후 `status=ACTIVE` 복귀. 그 경고가 3회째면 퇴출 처리(SS-4의 퇴출 절차와 동일 경로)
5. 클라이언트가 복귀를 호출하지 않은 채 세션이 끝나면 B1이 같은 규칙으로 정산한다

응답 200 (정상 복귀)

```json
{"status": "ACTIVE", "elapsedSeconds": 420, "warningIssued": false, "warningCount": 1, "evicted": false}
```

응답 200 (초과 복귀)

```json
{"status": "ACTIVE", "elapsedSeconds": 730, "warningIssued": true, "warningCount": 2, "evicted": false}
```

발생 에러: 409 `PAUSE_NOT_ACTIVE` / 409 `SESSION_ENDED` / 403 `NOT_SESSION_PARTICIPANT` / 404 `SESSION_NOT_FOUND`

게이트: ② ✓ · ④ ✓ · ⑤ ✓ · 세션 참가자 PAUSED · 소유권 본인

부수효과: 초과 시 `warning` INSERT. 3회째면 SS-4와 동일하게 `eviction` + `point_ledger(EVICTION_PENALTY)` + LiveKit 강제 퇴장이 따른다. `pause_used`는 복귀해도 `true`로 남는다(세션당 1회).

### SS-7 자율 퇴장

`DELETE /api/sessions/{sessionId}/participation` — FR-401

목적: 사정이 생겼을 때 사유를 남기고 세션에서 나간다.

```json
{"reason": "DEVICE_ISSUE"}
```

허용값 `PERSONAL | DEVICE_ISSUE | UNPLEASANT | ETC`. 서버 전용 사유 `WITHDRAWAL`·`SANCTION`은 요청으로 받지 않는다(400 `VALIDATION_FAILED`).

응답 204.

발생 에러: 400 `REASON_REQUIRED` / 409 `ALREADY_LEFT` / 409 `ALREADY_EVICTED`(퇴출된 참가자 — SS-2·SS-3과 동일 취급) / 409 `SESSION_ENDED` / 403 `NOT_SESSION_PARTICIPANT` / 404 `SESSION_NOT_FOUND`

게이트: ② ✓ · ④ ✓ · ⑤ ✓ · 세션 참가자 ACTIVE·PAUSED · 소유권 본인

트랜잭션·부수효과

- `session_participant.status = LEFT`, `left_at`, `left_reason` 기록
- 포인트 차감 없음. 그 세션은 미완주 처리된다 (D10)
- LiveKit `RemoveParticipant`
- 재입장 불가, 공석 충원 없음 (FR-306 보류)
- 잔여 `ACTIVE`+`PAUSED` 인원이 `session.min-participants`(2) 미만이 되면 세션을 조기 종료한다 (D12). 이때 그 시점의 잔류자는 완주로 인정하고 포인트·Streak를 지급한다. 처리 경로는 B1과 동일한 종료 루틴이다

### SS-8 세션 결과

`GET /api/sessions/{sessionId}/result` — FR-501, FR-152

목적: 세션 종료 후 본인 완주 여부와 지급 결과를 보여준다.

응답 200

```json
{
  "sessionId": 5501,
  "status": "ENDED",
  "targetMinutes": 120,
  "startedAt": "2026-08-12T09:00:00+09:00",
  "endedAt": "2026-08-12T11:00:00+09:00",
  "endReason": "NORMAL",
  "my": {
    "completed": true,
    "participantStatus": "ACTIVE",
    "leftReason": null,
    "warningCount": 1,
    "pointAwarded": 200,
    "streak": {"before": 4, "after": 5, "countedToday": true},
    "goalAchieved": false,
    "badgeCode": null
  },
  "participants": [
    {"memberId": 1042, "nickname": "익명 치타37", "isMe": true, "participantStatus": "ACTIVE", "completed": true, "warningCount": 1},
    {"memberId": 1088, "nickname": "익명수달12", "isMe": false, "participantStatus": "EVICTED", "completed": false, "warningCount": 3},
    {"memberId": 1105, "nickname": "익명너구리04", "isMe": false, "participantStatus": "LEFT", "completed": false, "warningCount": 0}
  ]
}
```

목표 달성이 함께 일어난 경우 `"goalAchieved": true, "badgeCode": "GOAL_ACHIEVED"`가 되고, `point_ledger`에 `GOAL_ACHIEVED +1000`이 별도로 쌓인다. 이 지급이 기획서의 "스파크 포인트"이며, 일반 포인트와 동일 통화이고 사유 라벨로만 구분한다 (★D5 — 잠정, 팀 확인 대기, open-decisions Q4).

`countedToday=false`는 같은 날 이미 다른 세션으로 완주가 기록되어 Streak가 중복 증가하지 않았다는 뜻이다 (★D2).

`pointAwarded`는 `targetMinutes` 기준으로 계산한다. `endReason=EARLY_UNDER_MIN`으로 30분 만에 끝난 60분 세션이어도 완주자는 +100을 받는다 — 세션이 일찍 끝난 것은 남은 사람의 책임이 아니기 때문이다 (D15 보충).

`participantStatus`가 `LEFT`이고 `leftReason=DEVICE_ISSUE`인데 본인이 퇴장을 누른 적이 없다면, 연결이 90초 넘게 끊겨 서버가 자동 처리한 경우다 (SS-10, D13). 이때도 경고나 포인트 차감은 없고 그 세션만 미완주다.

발생 에러: 409 `SESSION_NOT_ENDED` / 403 `NOT_SESSION_PARTICIPANT` / 404 `SESSION_NOT_FOUND`

게이트: ② ✓ · ④ ✓ · ⑤ ✓ · 세션 참가 이력 보유 · 세션 ENDED

부수효과: 없음. 지급은 B1이 이미 끝냈고 이 API는 읽기만 한다.

### SS-9 내 세션 이력

`GET /api/members/me/sessions?status=&page=&size=` — FR-152

응답 200 — `PageResponse<T>`

```json
{
  "content": [
    {
      "sessionId": 5501, "targetMinutes": 120,
      "startedAt": "2026-08-12T09:00:00+09:00", "endedAt": "2026-08-12T11:00:00+09:00",
      "status": "ENDED", "participantStatus": "ACTIVE",
      "completed": true, "pointAwarded": 200, "warningCount": 1
    }
  ],
  "page": 0, "size": 20, "totalElements": 37, "totalPages": 2
}
```

발생 에러: 400 `VALIDATION_FAILED`(size 초과)

게이트: ② ✓ · ④ ✓ · ⑤ **미적용**(본인 상태 확인은 막지 않는다) · 소유권 본인

부수효과: 없음.

### SS-10 LiveKit 웹훅

`POST /api/webhooks/livekit` — FR-303, D13

목적: 실제 입퇴장·연결 끊김의 진실 원천이다. 클라이언트 보고(SS-4)는 얼굴 검출만 다루고, 연결 자체의 상태는 이 웹훅만 안다.

**연결 끊김은 자리비움 경고와 별개 축이다 (D13 확정).** 이 웹훅이 만드는 결과는 경고가 아니라 `LEFT` 전이뿐이며, 경고·포인트 차감으로 이어지지 않는다.

헤더: `Authorization: {LiveKit 서명}`. **JWT 게이트 ①~⑤ 전부 skip.** 서명 검증이 유일한 신원 보장 수단이다.

요청 (LiveKit 표준 페이로드)

```json
{
  "event": "participant_left",
  "room": {"name": "molock-5501"},
  "participant": {"identity": "1042"},
  "createdAt": 1786584072
}
```

처리 이벤트

| event | 처리 |
|---|---|
| `participant_joined` | `session_participant.joined_at` 기록(최초 1회만). 유예 타이머가 걸려 있으면 해제 |
| `participant_left` | 재접속 유예 타이머 시작 — `session.reconnect-grace-seconds`(90초) (D13) |
| `room_finished` | 룸 종료 확인. 세션이 아직 `LIVE`면 B1과 동일한 종료 루틴 실행 |

`participant.identity`는 `member_id`의 문자열 표현이다(SS-2와 같은 규약). 이 값을 정수로 파싱해 참가자를 찾으며, 알 수 없는 identity는 200으로 흡수하고 로그만 남긴다.

재접속 유예 판정 (D13 확정)

- `participant_left` 수신 후 90초 이내에 같은 `identity`의 `participant_joined`가 오면 **아무 일도 일어나지 않는다.** 상태는 `ACTIVE`(또는 `PAUSED`) 그대로이고 기록도 남기지 않는다. 일시적 네트워크 단절을 벌하지 않기 위한 장치다
- 90초를 넘겨도 복귀하지 않으면 **`status=LEFT`, `left_reason=DEVICE_ISSUE`, `left_at` 기록으로 자동 처리한다.** 경고를 부여하지 않고 포인트도 차감하지 않는다. 결과는 그 세션 미완주뿐이며, 이는 SS-7 자율 퇴장과 같은 취급이다
- **자리비움 경고(★D4)로 이어지지 않는다.** 경고는 캠이 연결된 상태에서 얼굴이 60초 넘게 잡히지 않을 때만 붙는다. 연결이 끊긴 사람은 애초에 얼굴 검출 이벤트를 보낼 수 없으므로 두 축이 겹치지 않는다
- 참가자가 SS-7으로 이미 퇴장했거나 `EVICTED` 상태면 유예 타이머를 걸지 않는다
- 이 자동 `LEFT`로 잔여 `ACTIVE`+`PAUSED` 인원이 `session.min-participants`(2) 미만이 되면 세션을 조기 종료한다(`end_reason=EARLY_UNDER_MIN`, D12)

응답 200

```json
{"received": true}
```

처리 실패도 200으로 응답한다. 5xx를 내면 LiveKit이 재시도를 반복해 중복 처리가 늘어난다. 실패는 서버 로그로만 남긴다.

발생 에러: 401 `INVALID_WEBHOOK_SIGNATURE`(서명 불일치 — 이 경우에만 non-2xx)

게이트: 전부 예외(서명 검증으로 대체)

부수효과: `joined_at`·`left_at` 갱신, 유예 초과 시 `status=LEFT(DEVICE_ISSUE)` 전이, 잔여 인원 미달 시 세션 조기 종료. **경고·퇴출·포인트 차감은 이 경로에서 발생하지 않는다.** 세션 영상은 어떤 경로로도 저장하지 않는다 (D17).

### SS-11 스티커 종류 목록

`GET /api/stickers` — FR-403

목적: 세션 중 보낼 수 있는 정형 스티커 종류를 내려준다. 자유 텍스트 채팅은 지원하지 않는다.

응답 200

```json
{
  "stickers": [
    {"type": "CLAP", "label": "파이팅"},
    {"type": "MUSCLE", "label": "힘내요"},
    {"type": "FIRE", "label": "열공"}
  ]
}
```

발생 에러: 없음(401 계열 제외)

게이트: ② ✓ · ④ ✓ · ⑤ ✓

부수효과: 없음. **스티커 전송은 LiveKit 데이터 채널로 클라이언트 간 직접 이뤄지며 서버는 저장하지 않는다** (D17). 구 v1.0의 `sticker_reaction` 테이블과 토글 API는 폐기했다.

### AP-1 퇴출 이의 신청

`POST /api/evictions/{evictionId}/appeals` — NFR-402, 리스크 대응(AI 오탐)

목적: AI 3회 경고 퇴출에 대해 운영진 2차 개입을 요청한다.

```json
{"reasonText": "카메라 각도 때문에 얼굴이 안 잡혔습니다. 자리를 비운 적 없습니다."}
```

`reasonText`는 필수이며 최대 200자다. 빈 문자열·누락·초과는 400 `VALIDATION_FAILED`. 관리자가 AD-6에서 판단할 유일한 사용자 측 근거이므로 선택 항목으로 두지 않는다.

응답 201

```json
{
  "appealId": 41,
  "evictionId": 77,
  "status": "PENDING",
  "reasonText": "카메라 각도 때문에 얼굴이 안 잡혔습니다. 자리를 비운 적 없습니다.",
  "createdAt": "2026-08-12T11:20:00+09:00",
  "slaDueAt": "2026-08-15T11:20:00+09:00"
}
```

발생 에러: 403 `FORBIDDEN`(타인의 퇴출 건이거나 존재하지 않는 `evictionId` — 존재 여부를 노출하지 않기 위해 404가 아니라 403으로 통일한다) / 409 `APPEAL_ALREADY_FILED`(퇴출 1건당 1회) / 400 `VALIDATION_FAILED`

게이트: ② ✓ · ④ ✓ · ⑤ ✓ · 소유권 본인 eviction

부수효과: `appeal_case` INSERT (`eviction_id` UNIQUE, `status=PENDING`, `reason_text`, `created_at`, `sla_due_at = now + report.sla-hours.normal`). AD-5 큐에 등재된다. `overdue`는 저장하지 않고 조회 시 `status=PENDING AND sla_due_at < now`로 파생한다. 이의 신청만으로는 퇴출·포인트 차감이 되돌아가지 않는다. 원복은 AD-6 인용 시에만 일어난다.

### PT-1 포인트 잔액·원장 조회

`GET /api/members/me/points?page=&size=` — FR-103, FR-501, FR-602

목적: 잔액과 적립·차감 내역을 보여준다. 잔액의 진실은 원장이다.

응답 200

```json
{
  "balance": 1300,
  "ledger": {
    "content": [
      {"ledgerId": 5021, "delta": -300, "reason": "EVICTION_PENALTY", "reasonLabel": "퇴출 패널티",
       "refType": "EVICTION", "refId": 77, "balanceAfter": 1300, "createdAt": "2026-08-12T09:41:12+09:00"},
      {"ledgerId": 5013, "delta": 200, "reason": "SESSION_COMPLETE", "reasonLabel": "세션 완주",
       "refType": "SESSION_PARTICIPANT", "refId": 31047, "balanceAfter": 1600, "createdAt": "2026-08-11T21:00:00+09:00"},
      {"ledgerId": 4980, "delta": 1000, "reason": "GOAL_ACHIEVED", "reasonLabel": "스파크 포인트(목표 달성)",
       "refType": "GOAL", "refId": 88, "balanceAfter": 1400, "createdAt": "2026-08-10T21:00:00+09:00"},
      {"ledgerId": 4001, "delta": 1000, "reason": "WELCOME", "reasonLabel": "웰컴 포인트",
       "refType": "MEMBER", "refId": 1042, "balanceAfter": 1000, "createdAt": "2026-08-01T10:02:11+09:00"}
    ],
    "page": 0, "size": 20, "totalElements": 12, "totalPages": 1
  }
}
```

`reasonLabel`은 서버가 `PointReason`에서 파생하는 표시용 문자열이며 저장하지 않는다.

발생 에러: 400 `VALIDATION_FAILED`

게이트: ② ✓ · ④ ✓ · ⑤ ✓ · 소유권 본인

부수효과: 없음.

### SR-1 상품 목록

`GET /api/store/products?type=&page=&size=` — FR-503

목적: 포인트로 교환 가능한 기프티콘·제휴 서적 목록을 제공한다.

`type` 필터는 `GIFTICON | BOOK`(생략 시 전체). `HIDDEN` 상품은 응답에 포함하지 않는다.

응답 200

```json
{
  "content": [
    {"productId": 31, "type": "GIFTICON", "name": "편의점 5,000원 금액권", "pricePoint": 5500, "stock": 42, "status": "ON_SALE"},
    {"productId": 33, "type": "BOOK", "name": "합격을 부르는 공부법", "pricePoint": 14000, "stock": 0, "status": "SOLD_OUT"}
  ],
  "page": 0, "size": 20, "totalElements": 8, "totalPages": 1
}
```

발생 에러: 400 `VALIDATION_FAILED`

게이트: ② ✓ · ④ ✓ · ⑤ ✓

부수효과: 없음. 도서 상품은 제휴 출판사·물류사 재고 정책을 따르며 재고 소진 시 `SOLD_OUT`으로 즉시 구매가 제한된다(요구사항 5.1 제약).

### SR-2 상품 상세

`GET /api/store/products/{productId}` — FR-503, FR-504(부분)

응답 200

```json
{
  "productId": 31,
  "type": "GIFTICON",
  "name": "편의점 5,000원 금액권",
  "description": "전국 편의점에서 사용 가능한 모바일 금액권입니다. 유효기간 발행일로부터 90일.",
  "imageUrl": "https://cdn.molock.app/products/31.png",
  "pricePoint": 5500,
  "stock": 42,
  "status": "ON_SALE"
}
```

`description`·`imageUrl`은 NULL을 허용한다(등록되지 않은 상품은 `null`로 내려간다).

발생 에러: 404 `PRODUCT_NOT_FOUND`(존재하지 않거나 `HIDDEN`)

게이트: ② ✓ · ④ ✓ · ⑤ ✓

부수효과: 없음. FR-504의 장바구니는 보류다.

### SR-3 주문 생성

`POST /api/orders` — FR-505(부분), NFR-204

목적: 보유 포인트로 상품을 주문한다. **포인트 전액 결제만 지원한다** (D16).

```json
{"productId": 31, "quantity": 1, "idempotencyKey": "a7f2c1e4-8b30-4c5d-9f11-2e6d0b7a3c58"}
```

절차 (전체가 하나의 `@Transactional`)

1. `idempotency_key` 중복 → 409 `DUPLICATE_ORDER`. 네트워크 재시도로 이중 주문이 생기는 것을 막는다. **응답 `details`에 기존 주문 번호를 담는다** — `{"error":{"code":"DUPLICATE_ORDER","message":"이미 접수된 주문입니다.","details":{"orderId":612}}}`. 클라이언트가 재시도 실패와 이미 성공한 주문을 구분해 주문 상세로 넘어갈 수 있어야 한다
2. 상품 조회 — 없거나 `HIDDEN`이면 404 `PRODUCT_NOT_FOUND`, `SOLD_OUT`이면 409 `OUT_OF_STOCK`
3. 재고 조건부 UPDATE — `UPDATE product SET stock = stock - :qty WHERE id = :pid AND stock >= :qty`. 영향 행 0이면 409 `OUT_OF_STOCK`. 차감 후 `stock=0`이면 `status=SOLD_OUT`
4. 포인트 조건부 차감 — `UPDATE member SET point_balance = point_balance - :amt WHERE id = :mid AND point_balance >= :amt`. 영향 행 0이면 409 `INSUFFICIENT_POINT`(재고까지 롤백된다)
5. `store_order` INSERT (`status=ORDERED`)
6. `point_ledger` INSERT — `reason=ORDER_SPEND`, `delta=-{pointAmount}`, `ref_type=ORDER`, `ref_id={orderId}`

응답 201

```json
{
  "orderId": 612,
  "productId": 31,
  "productName": "편의점 5,000원 금액권",
  "quantity": 1,
  "pointAmount": 5500,
  "status": "ORDERED",
  "orderedAt": "2026-08-12T13:04:00+09:00",
  "pointBalance": 800
}
```

발생 에러: 409 `DUPLICATE_ORDER` / 404 `PRODUCT_NOT_FOUND` / 409 `OUT_OF_STOCK` / 409 `INSUFFICIENT_POINT` / 400 `VALIDATION_FAILED`

게이트: ② ✓ · ④ ✓ · ⑤ ✓

부수효과: 재고 차감, 포인트 차감, 원장 기록.

**주문 이행은 v1 범위 밖이다.** `store_order`는 주문 접수까지만 다루며, 기프티콘 발송 코드·수령 연락처·배송 처리 상태를 저장하지 않는다. 실제 발송은 운영자가 주문 목록을 보고 수동으로 처리한다. FR-505의 배송지 입력(NFR-205)과 포인트·인앱결제 혼합 결제도 보류다. 혼합 결제는 앱스토어·플레이스토어 정책과 충돌하므로 구현하지 않으며, 요구사항 문구 수정이 필요하다(팀 전달 사항).

### SR-4 내 주문 목록

`GET /api/orders?page=&size=` — FR-506(부분)

응답 200 — `PageResponse<T>`

```json
{
  "content": [
    {"orderId": 612, "productId": 31, "productName": "편의점 5,000원 금액권", "type": "GIFTICON",
     "quantity": 1, "pointAmount": 5500, "status": "ORDERED", "orderedAt": "2026-08-12T13:04:00+09:00"}
  ],
  "page": 0, "size": 20, "totalElements": 3, "totalPages": 1
}
```

게이트: ② ✓ · ④ ✓ · ⑤ ✓ · 소유권 본인

부수효과: 없음. FR-506의 환불·배송 상태 조회는 보류다.

### SR-5 주문 상세

`GET /api/orders/{orderId}`

응답 200 — SR-4 항목 + `{"idempotencyKey", "pointLedgerId"}`

발생 에러: 404 `ORDER_NOT_FOUND` / 403 `FORBIDDEN`(타인 주문)

게이트: ② ✓ · ④ ✓ · ⑤ ✓ · 소유권 본인

### PY-1 포인트 충전 생성

`POST /api/points/charges` — FR-601, NFR-204

목적: 부족한 포인트를 결제로 충전하기 위해 PG 주문을 만든다. v1은 웹 PG 테스트 모드 1회 왕복만 지원한다 (D16 — 잠정, 팀 확인 대기).

```json
{"amountKrw": 10000}
```

응답 201

```json
{
  "chargeId": 208,
  "pgOrderId": "molock-chg-20260812-000208",
  "amountKrw": 10000,
  "pointAmount": 10000,
  "status": "READY",
  "provider": "toss-test"
}
```

환산 비율은 1원 = 1포인트다(잠정 — 값 확정 대기, open-decisions).

발생 에러: 400 `VALIDATION_FAILED`(금액 형식·범위)

게이트: ② ✓ · ④ ✓ · ⑤ ✓

부수효과: `point_charge` INSERT (`status=READY`, `pg_order_id` UNIQUE). 이 시점에는 포인트가 늘지 않는다. 클라이언트는 `pgOrderId`로 PG 결제창을 띄운다. IAP(인앱결제)는 보류다.

### PY-2 충전 승인 확인

`POST /api/points/charges/{chargeId}/confirm` — FR-601

목적: PG 결제창에서 돌아온 클라이언트가 승인을 확정하고 포인트를 적립받는다.

```json
{"pgOrderId": "molock-chg-20260812-000208", "pgTid": "tviva20260812130455ABCD", "amountKrw": 10000}
```

절차

1. 충전 건 조회 — 없거나 타인 것이면 404 `CHARGE_NOT_FOUND`
2. `pgOrderId` 불일치 또는 `amountKrw` 불일치 → 400 `PAYMENT_AMOUNT_MISMATCH`
3. 이미 `APPROVED`면 아래 200 응답을 그대로 반환한다(멱등)
4. `status=FAILED`이면 409 `PAYMENT_NOT_APPROVED`
5. PG 승인 API 호출 — 미승인 응답이면 `status=FAILED` 기록 후 409 `PAYMENT_NOT_APPROVED`
6. 승인 확인 → `status=APPROVED`, `pg_tid` 기록(UNIQUE), `approved_at`
7. `point_ledger` INSERT — `reason=CHARGE`, `delta=+{pointAmount}`, `ref_type=CHARGE`, `ref_id={chargeId}`

응답 200

```json
{
  "chargeId": 208,
  "status": "APPROVED",
  "amountKrw": 10000,
  "pointAmount": 10000,
  "pointBalance": 10800,
  "approvedAt": "2026-08-12T13:05:02+09:00"
}
```

발생 에러: 404 `CHARGE_NOT_FOUND` / 400 `PAYMENT_AMOUNT_MISMATCH` / 409 `PAYMENT_NOT_APPROVED`

게이트: ② ✓ · ④ ✓ · ⑤ ✓ · 소유권 본인

트랜잭션·부수효과: `point_charge` 상태 전이 + `point_ledger` 적립 + 잔액 캐시 갱신이 한 트랜잭션이다. **PY-3 웹훅과 이 API가 같은 건을 동시에 처리해도 `point_ledger` UNIQUE(member_id, reason, ref_type, ref_id)가 이중 적립을 막는다.** 결제 실패 시 포인트를 적립하지 않으며 실패 사유를 응답에 담아 안내한다(요구사항 5.2 리스크 대응).

### PY-3 PG 결제 웹훅

`POST /api/webhooks/payment` — FR-601, NFR-204

목적: 클라이언트가 결제창에서 이탈해 PY-2를 호출하지 못한 경우에도 승인 결과를 반영한다.

헤더: PG 서명 헤더. **JWT 게이트 ①~⑤ 전부 skip.**

요청 (PG 표준 페이로드)

```json
{
  "eventType": "PAYMENT_STATUS_CHANGED",
  "data": {"orderId": "molock-chg-20260812-000208", "paymentKey": "tviva20260812130455ABCD", "status": "DONE", "totalAmount": 10000}
}
```

처리

- 서명 검증 실패 → 401 `INVALID_WEBHOOK_SIGNATURE`
- `status=DONE` → PY-2 6~7단계와 동일한 서비스 메서드를 호출한다. `pg_tid` UNIQUE와 원장 멱등키가 중복 수신을 흡수하므로 몇 번 재수신되어도 적립은 1회다
- `status=CANCELED|ABORTED|EXPIRED` → `point_charge.status = FAILED`
- 금액 불일치 → 적립하지 않고 `FAILED` 기록 + 로그

응답 200

```json
{"received": true}
```

발생 에러: 401 `INVALID_WEBHOOK_SIGNATURE`

게이트: 전부 예외(서명 검증으로 대체)

부수효과: PY-2와 동일. 알 수 없는 `orderId`는 200으로 흡수하고 로그만 남긴다(재시도 폭주 방지).

### RP-1 신고

`POST /api/reports` — FR-402, FR-701

목적: 불쾌한 유저나 세션을 신고하고, 그 즉시 해당 유저와의 재매칭을 영구 차단한다.

```json
{
  "targetType": "MEMBER",
  "targetMemberId": 1088,
  "sessionId": 5501,
  "reasonCode": "INAPPROPRIATE_SCREEN",
  "detail": "화면에 부적절한 내용이 계속 떠 있었습니다."
}
```

- `targetType=MEMBER` — `targetMemberId` 필수, `sessionId` 필수(같은 세션 참가 이력으로 자격을 검증한다)
- `targetType=SESSION` — `sessionId` 필수, `targetMemberId`는 보내지 않는다
- `detail` 최대 500자, 생략 가능

절차

1. 자격: 요청자와 대상이 같은 세션 참가 이력을 가져야 한다. 아니면 403 `NOT_SESSION_PARTICIPANT`. 대상 회원·세션이 없으면 404 `TARGET_NOT_FOUND`
2. 케이스 병합: 동일 `(target_type, target_id)`의 `PENDING` 케이스가 있으면 신고자만 추가하고, 없으면 새 케이스를 만든다. 같은 케이스에 이미 신고했으면 409 `DUPLICATE_REPORT`
   - `target_nickname`: 대상 표시명 스냅샷
   - `severity`: `SEXUAL_CONTENT`·`VIOLENT_THREAT`·`INAPPROPRIATE_SCREEN` → `HIGH`, `AD_SPAM`·`ETC` → `NORMAL`
   - `sla_due_at` = 접수 시각 + `report.sla-hours.{high|normal}`
   - 병합 시 더 높은 severity가 오면 케이스를 상향하고 `sla_due_at`을 재계산한다
3. **재매칭 차단**: `match_block`에 양방향 2행 INSERT (`(신고자, 대상)`, `(대상, 신고자)`, `source=REPORT`). UNIQUE(member_id, blocked_member_id)로 중복 신고 시에도 2행을 넘지 않는다. 이 차단은 영구이며 해제 API가 없다
4. **아무도 세션에서 나가지 않는다** (★D6 — 잠정, 팀 확인 대기, open-decisions Q5). 신고자도 피신고자도 세션에 남는다. 상호 비노출(해당 참가자 타일 가리기)은 클라이언트가 처리한다
   - 구 v1.0의 "신고자 즉시 퇴장(REPORT_EXIT)"은 폐기했다. 라이브 세션은 시간 단위이고 완주 판정이 세션 종료 시각 기준이므로, 신고했다는 이유로 나가면 그 세션 완주를 잃는다
   - 피신고자에 대한 조치는 운영자가 AD-3에서 `SANCTIONED`로 확정한 뒤에만 적용된다. 신고만으로 상대를 내보내면 아무나 신고해 남을 쫓아내는 악용이 성립한다
   - 그 대가로 신고 처리 SLA(고위험 24h)가 실질적인 피해자 보호 장치가 된다

응답 201

```json
{
  "caseId": 1204,
  "severity": "HIGH",
  "receivedAt": "2026-08-12T10:12:00+09:00",
  "blockedMemberId": 1088
}
```

`targetType=SESSION`이면 `"blockedMemberId": null`(차단 대상 개인이 특정되지 않는다).

발생 에러: 409 `DUPLICATE_REPORT` / 404 `TARGET_NOT_FOUND` / 403 `NOT_SESSION_PARTICIPANT` / 400 `VALIDATION_FAILED`

게이트: ② ✓ · ④ ✓ · ⑤ **미적용**(안전 도구는 막지 않는다)

부수효과: `report_case`(신규 또는 병합), `report` INSERT (UNIQUE(case_id, reporter_id)), `match_block` 2행. `match_block`은 MT-1 7단계 후보 조회에서 즉시 반영된다.

### AD-1 신고 케이스 목록

`GET /api/admin/reports?status=&severity=&overdue=&q=&page=&size=` — FR-701, NFR-302

응답 200 — `PageResponse<T>`

```json
{
  "content": [
    {"caseId": 1204, "targetType": "MEMBER", "targetNickname": "익명수달12", "reasonCode": "INAPPROPRIATE_SCREEN",
     "severity": "HIGH", "status": "PENDING", "overdue": false, "reportCount": 2,
     "receivedAt": "2026-08-12T10:12:00+09:00", "slaDueAt": "2026-08-13T10:12:00+09:00"}
  ],
  "page": 0, "size": 20, "totalElements": 14, "totalPages": 1
}
```

**`overdue`는 저장 컬럼이 아니라 조회 시점의 파생값이다** — `status = PENDING AND sla_due_at < now`. `overdue=true` 필터도 같은 조건을 WHERE 절에 붙이는 것이며, 별도 플래그 컬럼을 읽지 않는다. `(status, sla_due_at)` 인덱스로 처리한다. 마킹 배치가 없으므로 조회 시각이 곧 판정 시각이고, 배치 주기만큼 실제와 어긋나는 구간이 존재하지 않는다.

발생 에러: 403 `FORBIDDEN_ROLE`

게이트: ③ ADMIN · ② ✓ · ④ 미적용 · ⑤ 미적용

### AD-2 신고 케이스 상세

`GET /api/admin/reports/{caseId}` — FR-701

응답 200

```json
{
  "caseId": 1204,
  "targetType": "MEMBER",
  "target": {"memberId": 1088, "nickname": "익명수달12", "sessionId": 5501},
  "severity": "HIGH",
  "status": "PENDING",
  "overdue": false,
  "receivedAt": "2026-08-12T10:12:00+09:00",
  "slaDueAt": "2026-08-13T10:12:00+09:00",
  "reporters": [
    {"reasonCode": "INAPPROPRIATE_SCREEN", "detail": "화면에 부적절한 내용이 계속 떠 있었습니다.", "receivedAt": "2026-08-12T10:12:00+09:00"}
  ],
  "targetSessions": [
    {"sessionId": 5501, "startedAt": "2026-08-12T09:00:00+09:00", "participantStatus": "ACTIVE", "warningCount": 0, "completed": false}
  ],
  "targetWarnings": [
    {"sessionId": 5490, "seq": 1, "createdAt": "2026-08-11T20:14:00+09:00"}
  ],
  "history": [
    {"adminId": 7, "status": "PENDING", "reviewNote": null, "processedAt": "2026-08-12T10:12:00+09:00"}
  ]
}
```

`reporters[].detail`은 신고자 신원을 노출하지 않는다. 세션 영상은 저장하지 않으므로 열람 대상이 없다 — 판단 근거는 세션 이력·경고 로그·신고 사유다.

발생 에러: 404 `REPORT_NOT_FOUND` / 403 `FORBIDDEN_ROLE`

게이트: ③ ADMIN · ② ✓

### AD-3 신고 처리

`PATCH /api/admin/reports/{caseId}` — FR-701

```json
{"status": "SANCTIONED", "reviewNote": "화면 공유 내용 확인. 7일 이용 제한.", "sanction": {"type": "TEMP", "days": 7}}
```

- `PENDING`만 변경 가능하다. 그 외 409 `ALREADY_PROCESSED`. **재오픈 불가**(재검토는 새 케이스로 만든다)
- `status=REJECTED`(기각) 확정 시 신고자에게 `restriction_review` 플래그를 세운다. 반복 허위 신고자를 제재 검토 대상으로 남기기 위한 장치다. **이것이 `restriction_review`를 세우는 유일한 경로다** — SLA 초과를 근거로 자동 마킹하지 않는다. 처리가 늦은 것은 운영 측 사정이지 신고자의 잘못이 아니며, 지연의 가시성은 AD-1의 `overdue` 필터가 담당한다
- `status=SANCTIONED`면 `sanction` 필수. 제재 적용은 AD-4와 동일한 단일 서비스 메서드를 호출해 한 트랜잭션에서 처리한다
- 종결 시 `open_target_id=NULL`

응답 200

```json
{"caseId": 1204, "status": "SANCTIONED", "processedAt": "2026-08-12T14:30:00+09:00", "sanctionId": 55}
```

발생 에러: 404 `REPORT_NOT_FOUND` / 409 `ALREADY_PROCESSED` / 400 `VALIDATION_FAILED` / 403 `FORBIDDEN_ROLE`

게이트: ③ ADMIN · ② ✓

부수효과: `report_case` 상태 전이, `report_history` INSERT, `sanction` INSERT(SANCTIONED 시), 신고자 `restriction_review`(REJECTED 시). `match_block`은 RP-1에서 이미 등재되었으므로 여기서 추가 처리하지 않는다.

### AD-4 제재 단독 적용

`POST /api/admin/members/{memberId}/sanctions` — FR-701

```json
{"type": "TEMP", "days": 7, "caseId": 1204}
```

`type=PERMANENT`면 `days`를 받지 않는다(`ends_at=NULL`).

응답 201

```json
{"sanctionId": 55, "memberId": 1088, "type": "TEMP", "startsAt": "2026-08-12T14:30:00+09:00", "endsAt": "2026-08-19T14:30:00+09:00"}
```

제재 적용 절차 (AD-3와 공유하는 단일 메서드, 한 트랜잭션)

1. `sanction` INSERT
2. 진행 중 `session_participant` → `LEFT`, `left_reason=SANCTION` + LiveKit `RemoveParticipant`
3. 활성 `WAITING` 매칭 요청 → `CANCELLED` + `active_member_id=NULL`

발생 에러: 400 `VALIDATION_FAILED` / 403 `FORBIDDEN_ROLE`

게이트: ③ ADMIN · ② ✓

부수효과: 제재 발효 즉시 ④ 게이트가 참여 API를 전부 막는다. 세션 조기 종료 조건(잔여 2인 미만)에 걸리면 세션도 함께 종료된다.

### AD-5 이의 큐

`GET /api/admin/appeals?status=&overdue=&page=&size=` — FR-701, NFR-402

응답 200 — `PageResponse<T>`

```json
{
  "content": [
    {"appealId": 41, "evictionId": 77, "memberId": 1042, "nickname": "익명 치타37",
     "sessionId": 5501, "warningCount": 3, "status": "PENDING", "overdue": false,
     "createdAt": "2026-08-12T11:20:00+09:00", "slaDueAt": "2026-08-15T11:20:00+09:00"}
  ],
  "page": 0, "size": 20, "totalElements": 5, "totalPages": 1
}
```

`overdue`는 AD-1과 동일하게 조회 시점의 파생값이다 — `status = PENDING AND sla_due_at < now`. 신고 케이스와 이의가 같은 규칙을 쓴다.

발생 에러: 403 `FORBIDDEN_ROLE`

게이트: ③ ADMIN · ② ✓

### AD-6 이의 처리

`PATCH /api/admin/appeals/{appealId}` — FR-701, NFR-402

```json
{"decision": "ACCEPTED", "note": "자리비움 이벤트 로그상 카메라 각도 문제로 확인. 퇴출 취소."}
```

응답 200

```json
{
  "appealId": 41,
  "status": "ACCEPTED",
  "decidedBy": "ADMIN",
  "decidedAt": "2026-08-13T09:10:00+09:00",
  "pointRefunded": 300,
  "sessionCompletedRestored": true,
  "streakAfter": 5
}
```

발생 에러: 404 `APPEAL_NOT_FOUND` / 409 `ALREADY_PROCESSED` / 400 `VALIDATION_FAILED` / 403 `FORBIDDEN_ROLE`

게이트: ③ ADMIN · ② ✓

트랜잭션·부수효과 (`ACCEPTED` 시 한 트랜잭션)

- `appeal_case.status = ACCEPTED`, `decided_by=ADMIN`, `decided_at`, `note`
- `eviction.revoked_at = now` — 퇴출 기록 자체는 남긴다(감사 추적). `session_participant.status`는 `EVICTED`로 유지하고 취소 사실은 `revoked_at`으로 표현한다
- `point_ledger` INSERT — `reason=APPEAL_REFUND`, `delta=+300`, `ref_type=EVICTION`, `ref_id={evictionId}`. 역분개이며 원래의 `EVICTION_PENALTY` 행은 지우지 않는다
- 완주 소급 재판정 (★D1 기준) — 퇴출이 없었다면 세션 종료 시각까지 참가한 것으로 보아 `completed=true`, `point_awarded` 지급(`point_ledger(SESSION_COMPLETE, ref_type=SESSION_PARTICIPANT, ref_id=participantId)` — UNIQUE로 중복 지급이 막힌다)
- `streak_day` INSERT(UNIQUE(member_id, completed_on)로 멱등) → `member.current_streak`·`last_completed_on` 재계산 → 목표 달성 검사(★D3). 달성 시 `GOAL_ACHIEVED` 지급
- 재매칭 쿨다운은 이 시점부터 해제된다

`REJECTED`면 상태와 `note`만 기록하고 원복은 일어나지 않는다.

### AD-7 진행 중 세션 모니터

`GET /api/admin/sessions?status=LIVE&page=&size=` — FR-701

응답 200 — `PageResponse<T>`

```json
{
  "content": [
    {
      "sessionId": 5501, "status": "LIVE", "targetMinutes": 120,
      "startedAt": "2026-08-12T09:00:00+09:00", "endsAt": "2026-08-12T11:00:00+09:00",
      "activeCount": 4, "pausedCount": 1, "leftCount": 0, "evictedCount": 1,
      "participants": [
        {"memberId": 1042, "nickname": "익명 치타37", "status": "ACTIVE", "warningCount": 1, "paused": false}
      ]
    }
  ],
  "page": 0, "size": 20, "totalElements": 3, "totalPages": 1
}
```

발생 에러: 403 `FORBIDDEN_ROLE`

게이트: ③ ADMIN · ② ✓

부수효과: 없음. 관리자에게도 영상은 제공하지 않는다(저장하지 않으므로 존재하지 않는다, D17).

### AD-8 탈퇴 처리 결과

`GET /api/admin/withdrawals?status=&page=&size=` — NFR-202

응답 200 — `PageResponse<{memberId, requestedAt, deleteScheduledAt, deletedAt, status}>`

발생 에러: 403 `FORBIDDEN_ROLE`

게이트: ③ ADMIN · ② ✓

### DEV-2~4 개발 전용

활성 조건: `@Profile("dev")` AND `morak.dev.enabled=true` (이중 스위치). 운영 프로필에서는 빈이 등록되지 않아 404 `ENDPOINT_NOT_FOUND`.

- **DEV-2** `POST /api/dev/clock` · `{"offsetMinutes": 1440}` → 가변 Clock 오프셋 설정. Streak 일 경계 테스트에 쓴다
- **DEV-3** `POST /api/dev/sessions/seed` · `{"memberId": 1042, "dates": ["2026-08-08", "2026-08-09", "2026-08-10"]}` → 해당 일자에 완주한 `live_session`(ENDED) + `session_participant`(completed=true) + `streak_day`를 만든다. 구 `POST /api/dev/proofs/seed`의 대체다
- **DEV-4** `POST /api/dev/batches/{B1|B2|B4}` → 해당 배치 즉시 실행. B3(SLA 마킹)은 폐지되어 트리거 대상이 아니다. 응답은 `{"batch": "B2", "processed": 2}`(처리 건수 — 게이트 실측용, 개발 전용이라 공개 계약 아님). 없는 배치 이름은 404

---

## §5. 배치

| ID | 주기 | 대상·동작 | 단계 |
|---|---|---|---|
| B1 | 매분 | `ends_at <= now`인 `LIVE` 세션 → 종료 루틴(`end_reason=NORMAL`) | 5 |
| B2 | 매분 | `expires_at < now`인 `WAITING` → `EXPIRED` + `active_member_id=NULL` + `match_event(WAIT_EXPIRED)`. 조건 행 잠금 후 조건부 UPDATE | 2 |
| B4 | 매일 | `delete_scheduled_at < now`인 `WITHDRAW_PENDING` → 익명화·`DELETED` + §2 동반 갱신 전부 | 11 |

**B1 세션 종료 루틴** (세션 단위 한 트랜잭션, 멱등)

1. 미종료 `absence_event(START)`를 세션 종료 시각을 END로 간주해 정산 → 60초 초과분은 `warning` 부여
2. 미복귀 `PAUSED` 참가자를 10분 초과 여부로 정산 → 초과면 `warning` 부여 (D9)
3. 1~2에서 경고가 3회에 도달하면 퇴출 처리(`EVICTED` + `eviction` + `EVICTION_PENALTY`)
4. `live_session.status = ENDED`, `ended_at`·`end_reason`(`NORMAL` 또는 `EARLY_UNDER_MIN`) 기록, LiveKit 룸 종료
5. 완주 판정 — 종료 시점 `status ∈ {ACTIVE, PAUSED}`인 참가자를 `completed=true`로 기록 (★D1)
6. 포인트 지급 — `point_ledger(SESSION_COMPLETE, +100×(target_minutes÷60), ref_type=SESSION_PARTICIPANT, ref_id=participantId)`. 조기 종료여도 `target_minutes` 기준 그대로다(D15 보충). UNIQUE로 재실행 시 중복 지급되지 않는다
7. Streak 갱신 — `streak_day(member_id, completed_on, session_id)` INSERT. UNIQUE(member_id, completed_on)로 하루 다회 완주가 1일로 흡수된다 (★D2). `member.current_streak`·`last_completed_on` 갱신
8. 목표 달성 검사 — `current_streak >= member_goal.period_days`면 `GoalStatus=ACHIEVED` + `point_ledger(GOAL_ACHIEVED, +1000, ref_type=GOAL, ref_id=goalId)` + 뱃지 (★D3)

조기 종료(D12, 잔여 2인 미만)와 `room_finished` 웹훅(SS-10)도 4~8 단계를 그대로 호출한다. 진입점이 셋이므로 멱등이 필수다.

**공통**: 모든 배치는 `@Scheduled` + DEV-4 수동 트리거 쌍을 가진다. 재실행이 안전해야 한다.

B4의 파기 대상에서 커머스 기록(`store_order`, `point_charge`, 관련 `point_ledger`)은 제외한다. 전자상거래법상 보존 의무가 있어 개인 식별자만 익명화하고 거래 기록 자체는 남긴다(보존 기간 값은 open-decisions).

**SLA overdue 마킹 배치(구 B3)는 폐지했다.** `overdue`는 신고 케이스·이의 모두 저장 컬럼이 아니라 조회 시점의 파생값(`status` 미종결 AND `sla_due_at < now`)이므로, 이를 주기적으로 계산해 컬럼에 써 두는 배치가 존재할 이유가 없다. 배치가 도는 사이 실제 상태와 컬럼이 어긋나는 구간도 함께 사라진다. `sla_due_at`은 접수 시점에 한 번 계산해 저장한다.

NFR-302의 SLA 준수율 집계(95% 이상)는 보류다. 큐와 `sla_due_at`은 유지하되 준수율 통계 산출은 v1 범위 밖이다.

---

## §6. 에러 코드

### 6-1. 전체 표

| 계열 | 코드 | Status | 발생 API | 조건 |
|---|---|---|---|---|
| 공통 | UNAUTHORIZED | 401 | 전역① | 토큰 없음·무효·DELETED 회원 |
| 공통 | TOKEN_EXPIRED | 401 | 전역① | 만료 |
| 공통 | FORBIDDEN_ROLE | 403 | 전역③ | ADMIN 아님 |
| 공통 | FORBIDDEN | 403 | MT-3, AP-1, SR-5 | 소유권 없음 |
| 공통 | ENDPOINT_NOT_FOUND | 404 | 전역 | 없는 경로, 운영 프로필의 DEV 경로 |
| 공통 | VALIDATION_FAILED | 400 | 전역 | 입력 검증 실패 |
| 공통 | INTERNAL_SERVER_ERROR | 500 | 전역 | 미분류 |
| 회원·인증 | INVALID_SOCIAL_TOKEN | 401 | AU-1 | 소셜 인증 실패 |
| 회원·인증 | REJOIN_BLOCKED | 403 | AU-1 | `blocked_social_hash` 등재 |
| 회원·인증 | WITHDRAWAL_PENDING | 403 | 전역② | 탈퇴 유예 중 참여 API |
| 회원·인증 | NOT_WITHDRAWING | 409 | AU-5 | 탈퇴 상태 아님 |
| 회원·인증 | ALREADY_VERIFIED | 409 | AU-3 | 이미 연령 검증됨 |
| 회원·인증 | AGE_NOT_VERIFIED | 403 | 전역⑤ | 생년월일 미입력 |
| 회원·인증 | MEMBER_SANCTIONED | 403 | 전역④ | 유효 제재 |
| 회원·인증 | **UNDER_AGE_SIGNUP_BLOCKED** | 403 | AU-1, AU-3 | 만 14세 미만 — 계정 생성 차단 (★D7) |
| 회원·인증 | **AGREEMENT_REQUIRED** | 400 | AU-1 | 필수 약관 2종 미동의 |
| 회원·인증 | **GOAL_ALREADY_ACTIVE** | 409 | AU-7 | 진행 중 목표 존재 |
| 매칭 | DUPLICATE_MATCH_REQUEST | 409 | MT-1 | 활성 대기 요청 존재 |
| 매칭 | **ALREADY_IN_ACTIVE_SESSION** | 409 | MT-1 | 활성 세션 참가 중 |
| 매칭 | NO_ACTIVE_MATCH_REQUEST | 404 | MT-2 | 요청 없음 |
| 매칭 | ALREADY_MATCHED | 409 | MT-3 | 이미 성사 |
| 매칭 | LOCK_ACQUISITION_FAILED | 503 | MT-1, MT-3, B2 | 잠금 타임아웃 |
| 매칭 | **REMATCH_COOLDOWN** | 409 | MT-1 | 퇴출 후 30분 미경과 (D14) |
| 세션 | **SESSION_NOT_FOUND** | 404 | SS-1~8 | 없는 세션 |
| 세션 | **NOT_SESSION_PARTICIPANT** | 403 | SS-1~8, RP-1 | 참가 자격 없음 |
| 세션 | **SESSION_ENDED** | 409 | SS-2~7 | 종료된 세션에 대한 참여 요청 |
| 세션 | **SESSION_NOT_ENDED** | 409 | SS-8 | 진행 중 세션의 결과 조회 |
| 세션 | ALREADY_LEFT | 409 | SS-7 | 이미 퇴장 |
| 세션 | REASON_REQUIRED | 400 | SS-7 | 퇴장 사유 없음 |
| 세션 | CONSENT_REQUIRED | 403 | SS-2 | 캠 영상 온디바이스 분석 미동의 |
| 세션 | **DUPLICATE_ABSENCE_EVENT** | 409 | SS-4 | 같은 `clientSeq` 재수신 |
| 세션 | **ABSENCE_RATE_LIMITED** | 429 | SS-4 | 이벤트 보고 레이트리밋 초과 |
| 세션 | **ALREADY_EVICTED** | 409 | SS-2, SS-4, SS-5 | 이미 퇴출된 참가자 |
| 세션 | **PAUSE_ALREADY_USED** | 409 | SS-5 | 세션당 1회 초과 |
| 세션 | **PAUSE_NOT_ACTIVE** | 409 | SS-6 | Pause 상태가 아님 |
| 세션 | **INVALID_WEBHOOK_SIGNATURE** | 401 | SS-10, PY-3 | 웹훅 서명 불일치 |
| 포인트·커머스 | **INSUFFICIENT_POINT** | 409 | SR-3 | 잔액 부족 |
| 포인트·커머스 | **PRODUCT_NOT_FOUND** | 404 | SR-2, SR-3 | 없거나 HIDDEN 상품 |
| 포인트·커머스 | **OUT_OF_STOCK** | 409 | SR-3 | 재고 부족·품절 |
| 포인트·커머스 | **DUPLICATE_ORDER** | 409 | SR-3 | `idempotencyKey` 중복. `details.orderId`에 기존 주문 번호 |
| 포인트·커머스 | **ORDER_NOT_FOUND** | 404 | SR-5 | 없는 주문 |
| 결제 | **CHARGE_NOT_FOUND** | 404 | PY-2 | 없는 충전 건 |
| 결제 | **PAYMENT_AMOUNT_MISMATCH** | 400 | PY-2 | 요청 금액 ≠ 충전 건 금액 |
| 결제 | **PAYMENT_NOT_APPROVED** | 409 | PY-2 | PG 미승인·실패 상태 |
| 신고·운영 | DUPLICATE_REPORT | 409 | RP-1 | 같은 케이스에 이미 신고 |
| 신고·운영 | TARGET_NOT_FOUND | 404 | RP-1 | 신고 대상 없음 |
| 신고·운영 | REPORT_NOT_FOUND | 404 | AD-2, AD-3 | 없는 케이스 |
| 신고·운영 | ALREADY_PROCESSED | 409 | AD-3, AD-6 | 이미 종결된 케이스·이의 |
| 신고·운영 | **APPEAL_ALREADY_FILED** | 409 | AP-1 | 퇴출 1건당 1회 초과 |
| 신고·운영 | **APPEAL_NOT_FOUND** | 404 | AD-6 | 없는 이의 |

굵은 코드는 v2.0 신설 또는 개명이다.

### 6-2. 승계·폐기·신설 확정 목록

현행 `com.morak.common.error.ErrorCode`(구 v1.0 기준) 대비 확정 내역이다.

**승계 — 이름·status 그대로 유지**

| 계열 | 코드 |
|---|---|
| 공통 | UNAUTHORIZED, TOKEN_EXPIRED, FORBIDDEN_ROLE, FORBIDDEN, ENDPOINT_NOT_FOUND, VALIDATION_FAILED, INTERNAL_SERVER_ERROR |
| 회원·인증 | INVALID_SOCIAL_TOKEN, REJOIN_BLOCKED, WITHDRAWAL_PENDING, NOT_WITHDRAWING, ALREADY_VERIFIED, AGE_NOT_VERIFIED, MEMBER_SANCTIONED |
| 매칭 | DUPLICATE_MATCH_REQUEST, NO_ACTIVE_MATCH_REQUEST, ALREADY_MATCHED, LOCK_ACQUISITION_FAILED |
| 세션 | ALREADY_LEFT, REASON_REQUIRED, CONSENT_REQUIRED(메시지만 수정 — "촬영물 처리 동의" → "캠 영상 분석 동의") |
| 신고·운영 | DUPLICATE_REPORT, TARGET_NOT_FOUND, REPORT_NOT_FOUND, ALREADY_PROCESSED |

**개명 — 그룹 개념이 세션으로 바뀌면서 이름만 교체(status·의미 동일)**

| 구 v1.0 | v2.0 | Status |
|---|---|---|
| ALREADY_IN_ACTIVE_GROUP | ALREADY_IN_ACTIVE_SESSION | 409 |
| GROUP_NOT_FOUND | SESSION_NOT_FOUND | 404 |
| NOT_GROUP_MEMBER | NOT_SESSION_PARTICIPANT | 403 |
| GROUP_ENDED | SESSION_ENDED | 409 |
| GROUP_NOT_ENDED | SESSION_NOT_ENDED | 409 |

**폐기 — 사진 인증·미디어·리포트·게시판 계열 전량**

| 계열 | 폐기 코드 |
|---|---|
| 사진 인증 | OUT_OF_CHALLENGE_PERIOD, PROOF_DEADLINE_PASSED, DUPLICATE_DAILY_PROOF, PROOF_HIDDEN_BY_ADMIN, PROOF_NOT_FOUND |
| AI 이미지 판정 | FACE_DETECTED_RETRY, FACE_RETRY_EXCEEDED, CONTENT_BLOCKED, AI_SCREENING_UNAVAILABLE |
| 미디어 | MEDIA_UPLOAD_FAILED, INVALID_FILE, VIEW_BLOCKED_GROUP_ENDED, INVALID_MEDIA_TOKEN, MEDIA_DELETED |
| 리포트 | REPORT_NOT_READY |
| 관리자 열람 | NOT_REPORTED_PROOF |
| 게시판 | POST_REPORT_REQUIRED, DUPLICATE_POST, POST_NOT_FOUND |

**신설**

UNDER_AGE_SIGNUP_BLOCKED, AGREEMENT_REQUIRED, GOAL_ALREADY_ACTIVE, REMATCH_COOLDOWN, DUPLICATE_ABSENCE_EVENT, ABSENCE_RATE_LIMITED, ALREADY_EVICTED, PAUSE_ALREADY_USED, PAUSE_NOT_ACTIVE, INVALID_WEBHOOK_SIGNATURE, INSUFFICIENT_POINT, PRODUCT_NOT_FOUND, OUT_OF_STOCK, DUPLICATE_ORDER, ORDER_NOT_FOUND, CHARGE_NOT_FOUND, PAYMENT_AMOUNT_MISMATCH, PAYMENT_NOT_APPROVED, APPEAL_ALREADY_FILED, APPEAL_NOT_FOUND

---

## §7. 요구사항 추적표 (FR-101 ~ FR-702)

| FR | 요구사항명 | 대응 엔드포인트·배치 | 비고 |
|---|---|---|---|
| FR-101 | 만 14세 미만 가입 제한 | AU-1, AU-3 | 미만 판정 시 계정을 만들지 않는다 (★D7 — 잠정, open-decisions Q6) |
| FR-102 | 익명 프로필 생성 | AU-1, AU-2 | 닉네임은 **서버가 생성**한다(`익명 {동물명}{2자리}`). SNS 값은 본인 확인용. FR-102 문면의 "닉네임을 설정하여"는 사용자 직접 입력을 뜻하지만, 무마찰 가입(NFR-401)과 충돌해 v1은 서버 생성으로 두고 **닉네임 직접 설정은 v2 과제로 기록**한다 |
| FR-103 | 웰컴 포인트 지급 | AU-1, PT-1 | `point_ledger(WELCOME, +1000)` |
| FR-151 | 개인 목표 기간 설정 | AU-7, AU-2 | `periodDays {7, 14, 30}` |
| FR-152 | Streak 관리 | AU-2, SS-8, SS-9, B1 | 일 단위, `streak_day` UNIQUE로 멱등 (★D2) |
| FR-201 | 원클릭 시간 매칭 | MT-1, MT-2, MT-3 | 시간 단일 기준, 옵션 4종 (D8) |
| FR-202 | 스마트 대기열 스케일링 | **보류(v2)** | 절단선 밖. 인접 시간대 합류는 매칭 잠금 행과 후보 조회를 다중 키로 바꿔야 해 매칭 엔진 재설계가 따른다 |
| FR-203 | 6인 룸 자동 생성 | MT-1(9~13단계), SS-1, SS-2 | 6인 확정 즉시 `live_session` 생성 (D21) |
| FR-301 | 6인 동율 캠 그리드 | SS-2 | 그리드 배치는 클라이언트. **기본 Mute는 토큰에서 오디오 publish 권한을 빼 서버가 강제한다**(D23) — v1에서 언뮤트 수단이 없다 |
| FR-302 | 세션 목표 공유 | SS-3, SS-1 | 50자 한 줄 |
| FR-303 | AI 이상반응 감지 | SS-4(자리비움), SS-10(연결 끊김) | **자리비움(얼굴 미검출)만 v1 범위.** 연결 끊김은 별개 축이라 경고가 아니라 `LEFT(DEVICE_ISSUE)`로 처리한다(D13 확정). 딴짓·이상행동 감지는 보류 — 판정 기준·모델이 정의되지 않았고 오탐률(NFR-301) 관리 근거가 없다 |
| FR-304 | 3회 경고 자동 퇴출 | SS-4, SS-6, B1, AP-1 | 경고 부여·퇴출 판정은 전부 서버 계산 (★D4 — 잠정, open-decisions Q3) |
| FR-305 | 화장실 10분 타이머(Pause) | SS-5, SS-6 | 세션당 1회, 초과 시 경고 1회 (D9) |
| FR-306 | 공석 실시간 충원(Fill-in) | **보류(v2)** | 절단선 밖. NFR-303도 함께 보류. 세션 중 참가자 추가는 완주 판정 기준(★D1)·포인트 지급 단위를 인원 시점별로 나눠야 한다 |
| FR-401 | 자율 퇴장 | SS-7 | 사유 enum 필수, 포인트 차감 없음 (D10). 연결 끊김 90초 초과의 자동 `LEFT(DEVICE_ISSUE)`(SS-10)도 같은 취급이다 |
| FR-402 | 즉시 신고·차단 | RP-1 | `match_block` 양방향 등재로 재매칭 영구 차단 (★D6 — 잠정, open-decisions Q5) |
| FR-403 | 정형화 응원 스티커 | SS-11 | 서버는 종류 목록만. 전송은 LiveKit 데이터 채널, 무저장 (D17) |
| FR-501 | 완주 검증 및 Streak 누적 | B1, SS-8, PT-1 | 목표 달성 시 스파크 포인트(`GOAL_ACHIEVED`) + 뱃지 (★D3, ★D5) |
| FR-502 | 인스타그램 스토리 공유 | **서버 미대응 / 부분 보류** | 카드 생성·공유는 클라이언트 기능. **공유 보너스 포인트는 보류** — 공유 성공을 서버가 검증할 수단이 없어 무한 적립이 성립한다 |
| FR-503 | 리워드 스토어(상품 목록) | SR-1, SR-2 | GIFTICON·BOOK |
| FR-504 | 커머스(구매) 페이지 | SR-2(부분), SR-3(`quantity`) | **장바구니 보류(v2)** — 절단선 밖. 다상품 동시 주문은 재고·포인트 차감을 다건 트랜잭션으로 확장해야 한다 |
| FR-505 | 주문·결제 프로세스 | SR-3(부분) | 포인트 전액 결제만. **배송지 입력(NFR-205) 보류**, **포인트·인앱결제 혼합 보류** — 혼합 결제는 앱 스토어 정책과 충돌해 구현하지 않으며 요구사항 문구 수정이 필요하다(팀 전달 사항, D16) |
| FR-506 | 주문내역 및 환불 | SR-4, SR-5(부분) | **환불·주문 취소·배송 상태 조회 보류(v2)** — 절단선 밖. 주문 이행(발송 코드·수령 연락처)도 v1 범위 밖이라 `store_order`는 접수까지만 다룬다. `OrderStatus.CANCELLED`와 `PointReason.ORDER_CANCEL`은 enum에만 두고 전이 경로를 만들지 않는다 |
| FR-601 | 포인트 충전(인앱결제) | PY-1, PY-2, PY-3 | 웹 PG 테스트키 왕복만. **IAP 보류** — 스토어 심사·영수증 검증이 절단선 밖 (D16) |
| FR-602 | 퇴출 패널티 포인트 차감 | SS-4, SS-6, B1 | `point_ledger(EVICTION_PENALTY, -300)` |
| FR-701 | 운영진 2차 개입 큐 | AD-1~AD-8, AP-1 | SLA 24h/72h. `sla_due_at`은 접수 시 저장, `overdue`는 조회 시 파생(마킹 배치 없음). **NFR-302의 준수율 95% 집계는 보류** |
| FR-702 | 광고 및 출판사 제휴 상품 관리 | **보류(v2)** | 절단선 밖(Phase 2, 요구사항 우선순위 "선택"). 광고 소재 관리와 상품 등록·수정 관리자 API를 두지 않는다. v1의 `product`는 시드 데이터로 채운다 |

**참고 — 관련 NFR 대응**

| NFR | 대응 |
|---|---|
| NFR-201 연령 검증 | AU-1, AU-3 (★D7) |
| NFR-202 탈퇴 데이터 파기 | AU-4, AU-5, B4, AD-8 |
| NFR-203 영상 데이터 처리 | AU-6, SS-2. 저장하지 않으므로 암호화·접근 통제 대상이 없다 (D17) |
| NFR-204 결제 정보 보호 | PY-1~PY-3. 카드 정보는 서버에 오지 않고 PG가 보유한다 |
| NFR-302 운영진 SLA | AD-1, AD-5 (overdue는 조회 시 파생. 준수율 집계는 보류) |
| NFR-402 이의 신청 접근성 | AP-1, AD-5, AD-6 |
| NFR-205 배송지 정보 보호 | 보류(FR-505 배송지 보류에 연동) |
| NFR-303 공석 충원 | 보류(FR-306에 연동) |
| NFR-101·102·301 | 성능·오탐률 목표치. API 계약 사항 아님 |

---

## §8. 잠정 결정 요약

아래 항목은 팀 확정 전 잠정 적용분이다. 확정 결과에 따라 해당 엔드포인트 명세가 바뀐다. 상세는 `docs/open-decisions.md`.

| # | 결정 | 잠정 내용 | 영향 엔드포인트 |
|---|---|---|---|
| ★D1 (Q1) | 세션 완주 정의 | 세션 종료 시각까지 참가 상태 유지(LEFT·EVICTED 아님). Pause 10분은 재실 인정. 재실 비율 기준 없음 | SS-7, SS-8, AD-6, B1 |
| ★D2 (Q2) | Streak 단위 | 일 단위. 하루 1세션 이상 완주 → 그날 완주(+1). UNIQUE(member, date)로 멱등 | AU-2, SS-8, B1 |
| ★D3 | 목표 달성 정의 | Streak가 `period_days`에 연속 도달. 미완주일 발생 시 Streak 0, 목표는 유지. 달성 시 포인트+뱃지, 목표 ACHIEVED(재설정 가능) | AU-7, SS-8, B1 |
| ★D4 (Q3) | AI 신뢰 모델 | 클라이언트는 자기 자신의 얼굴 미검출 시작·종료만 보고. 경고 부여와 3회 퇴출 판정은 서버 계산. `client_seq` 멱등키 + 레이트리밋으로 위조 방어 | SS-4, SS-10 |
| ★D5 (Q4) | 스파크 포인트 | 일반 포인트와 동일 통화. 지급 사유 라벨(`GOAL_ACHIEVED`)로만 구분 | SS-8, PT-1 |
| ★D6 (Q5) | 신고 시 세션 처리 | 아무도 세션에서 나가지 않는다. 상호 비노출(클라이언트) + `match_block` 양방향 등재(재매칭 영구 차단). 구 기획의 "신고자 즉시 퇴장"은 폐기 | RP-1 |
| ★D7 (Q6) | 만 14세 미만 처리 | 가입 자체 차단. 미만 판정 시 계정을 만들지 않는다(생성된 임시 상태면 즉시 삭제). 구 코드의 "가입 유지 + 기능 차단"에서 변경 | AU-1, AU-3 |

아래 3건은 이후 검토에서 **확정**된 항목이다(잠정 아님). 초기 서술과 달라진 부분이 있어 함께 남긴다.

| # | 결정 | 확정 내용 | 영향 엔드포인트 |
|---|---|---|---|
| D13 | 연결 끊김 처리 | 자리비움 경고와 **별개 축**. 90초 이내 재접속은 아무 일 없음, 초과는 `LEFT(DEVICE_ISSUE)` 자동 처리 — 경고·포인트 차감 없이 그 세션만 미완주. 자리비움 경고는 캠 연결 상태에서 얼굴 미검출 60초 초과일 때만 | SS-4, SS-8, SS-10 |
| D15 보충 | 완주 포인트 기준 | 실제 재실 시간이 아니라 `targetMinutes` 기준. 조기 종료(D12)로 30분 만에 끝난 60분 세션도 +100 | SS-8, B1 |
| D23 | 마이크 정책 | v1 언뮤트 불가. SS-2 토큰에서 오디오 publish 권한을 제외해 서버가 강제(FR-301 "기본 Mute"의 구현) | SS-2 |

값 확정 대기: 포인트 4종(1,000 / 100×h / 300 / 1,000), 매칭 시간 옵션 4종, 경고 임계 60초, 충전 원-포인트 환산 비율, 상품 목록·가격, 커머스 기록 보존 기간.
