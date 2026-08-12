# 모락(Morak) API 명세서 v1.0 — 통합 정본 (2026-08-11)

> **이 문서는 자기완결적이다.** v0.2·v0.3·v0.4를 참조하지 않으며, "승계"라는 개념을 폐기했다. 구 판본은 `docs/archive/`로 이동한다.
> 근거 정본: `기능명세서_모락_v4.md`. 함께 읽는 문서: `DB스키마_모락_v1.0.md`(컬럼·제약 정본), `구현파이프라인_v3.md`(구현 순서·게이트).
> 개수는 **표의 행 수로만** 말한다. 본문에 개수를 숫자로 박지 않는다(11라운드 동안 개수 앵커가 4회 연속 틀렸다).

---

##  착수 차단 — 팀 확정이 필요한 항목 (T1)

이 5건이 확정되기 전에는 해당 단계를 시작할 수 없다. 나머지 미결은 §9.

| # | 질문 | 막는 단계 | 확정 안 되면 |
|---|---|---|---|
| ~~T1-1~~ **확정(8/12)** | **신고자 처리 = (a) 즉시 퇴장.** 원문 그대로. 신고자는 접수 즉시 그룹 접근을 잃고(`REPORT_EXIT`), 신고의 정당성은 **운영자가 사후 판단**한다 | — | **악용 방지 장치 동반 필수**: ① AD-3에서 **REJECTED(기각) 확정 시 신고자에게 `restriction_review` 플래그** — 반복 허위 신고자를 제재 검토 대상으로 ② REPORT_EXIT은 개인 리포트 대상에 포함(정당한 신고자가 완주를 잃지 않게), 단 **그룹 평균 분모에서는 제외** |
| ~~T1-2~~ **확정(8/12)** | **startDate = 매칭 당일. 단 매칭 시각이 그날 인증 마감(23:59) 이후면 다음날.** 즉 `매칭 시각이 마감 전 → 오늘 / 마감 후 → 내일` 자동 결정 | — | 당일 시작이 자연스럽지만 **밤 11시 59분에 매칭되면 1일차를 통째로 날린다.** 자동 판정으로 그 케이스만 방어. `endDate = startDate + periodDays - 1` |
| **T1-3** | **자격증 시험날짜 챌린지** = (a) 프리셋 시험 목록 선택 (b) 자유 날짜 입력 | 2단계 | (a)면 match_request 스키마·매칭 키·잠금 행 시드가 전부 바뀜. (b)는 6인 자동 매칭과 구조적으로 양립 불가 |
| **T1-4** | **완주 이중 기준 결합**: 개인 AND 그룹이 맞는가 | 8단계 | 현행대로면 **100% 인증자도 타인 성적 때문에 미완주**. "COMPLETED 0명이면 전원 실패"와 겹치면 전원 이탈 그룹의 피해자가 전원 미완주 |
| **T1-5** | **매칭 완화 정책**: 큐가 72개(완전 일치)이고 실패 시 24h 만료·재시도 안내가 전부다. 완화(조건 확장·대기 연장·추천)를 v1에 넣는가 | 2단계 | 매칭 성사가 단일 실패점인데 완화책 0개 |
| **T1-6** | **인원 미달 시 완주 판정 별도 기준** — 원문 요구4-1이 "퇴장으로 그룹 인원이 기준 미만이 되면 공식 완주 판정 기준을 별도로 적용한다"고 명시했다. **기준 인원은 몇 명이고, 별도 기준은 무엇인가?** | 8단계 | 현행 규칙은 오히려 더 가혹하다(COMPLETED 0명이면 전원 미완주). **6명 중 4명이 퇴장한 그룹의 잔류자 2명이 그룹 평균 때문에 완주에 실패한다 — 원문이 정확히 막으려던 상황** |
| ~~T1-7~~ **확정(8/12)** | **얼굴이 감지되면 거부하고 재촬영 안내.** 익명 서비스 컨셉과 정합 — 원래 문항: | — | 아래는 확정 전 서술(참고용) |
| (구)T1-7 | **얼굴 검열 ↔ 캠 인증 모순** — 원문 7-2는 "얼굴을 탐지해 고위험이면 차단", 원문 3-1은 "캠을 켜서 인증". 문면 그대로면 캠 인증이 전량 차단된다. **얼굴이 나오면 거부하는 게 맞는가(익명 보호), 본인 얼굴은 허용인가?** | 4·7단계 | 현재 정본은 "거부"로 확정 구현돼 있고 파이프라인 게이트에도 박혀 있다. **원문 대비 변경이므로 승인이 필요하다**(→ `원문대비_변경승인요청.md`) |

---

## §0. 공통 규약

### 0-1. 기본
- Base `/api`, 인증 `Authorization: Bearer {accessToken}` (JWT, HS256, 유효 24h, refresh 없음)
- 응답 성공은 각 API에 명시. 실패는 공통 포맷 `{"error":{"code","message","details"}}`
- 시각 표기 ISO-8601(+09:00), 일자 `YYYY-MM-DD`. **모든 시각은 `Clock` 빈 경유**(`Clock.system(ZoneId.of(morak.timezone))`, 테스트·개발은 가변)
- 목록 응답은 커스텀 `PageResponse<T>` = `{content[], page, size, totalElements, totalPages}` (Spring `Page` 직렬화 금지)

### 0-2. 전역 인터셉터 (검문소 — 개별 API가 아니라 여기 한 곳에서 판정)

**모든 검사는 JWT 클레임이 아니라 요청 시점의 DB 현재값으로 한다.** 토큰이 24h이므로 클레임을 믿으면 제재·탈퇴가 최대 하루 늦게 반영된다.

| 순서 | 검사 | 실패 응답 | 예외 경로 |
|---|---|---|---|
| ① | JWT 유효성 | 401 `UNAUTHORIZED` / `TOKEN_EXPIRED` | `/api/auth/**`, `/api/dev/**`, `/h2-console/**`, `/error`, **`GET /api/proofs/*/media/raw`**(`<img src>`로 열려 헤더를 못 실음 — 대신 HMAC 토큰이 신원을 대신하고 자격 검증은 그대로 수행) |
| ② | **회원 상태** `member.status` | WITHDRAW_PENDING → 403 `WITHDRAWAL_PENDING`(참여 API 차단) / DELETED → 401 `UNAUTHORIZED` | AU-1, AU-2, AU-5(철회) |
| ③ | 관리자 역할 (`/api/admin/**`) | 403 `FORBIDDEN_ROLE` | — |
| ④ | 유효 제재 `starts_at <= now AND (ends_at IS NULL OR ends_at > now)` | 403 `MEMBER_SANCTIONED` (details: 종료 시각) | AU-1, AU-2, AU-4(탈퇴), **AU-5(철회 — 막으면 계정 복구가 영구 불가해진다)** |
| ⑤ | 연령 `age_verification` | REQUIRED·UNDER_AGE → 403 `AGE_NOT_VERIFIED` | 아래 매트릭스 참조 |

### 0-3. 엔드포인트 × 게이트 매트릭스

`—` = 검사 없음, `✓` = 검사, `본인` = 소유권 검사

| API | 역할 | 회원상태② | 제재④ | 연령⑤ | 그룹소속 | 소유권 | 대상상태 |
|---|---|---|---|---|---|---|---|
| AU-1 로그인 | — | 예외 | 예외 | — | — | — | — |
| AU-2 내 정보 | — | 예외 | 예외 | — | — | 본인 | — |
| AU-3 생년월일 | — | ✓ | ✓ | — | — | 본인 | — |
| AU-4 탈퇴 | — | ✓ | 예외 | — | — | 본인 | — |
| AU-5 탈퇴 철회 | — | 예외 | 예외 | — | — | 본인 | — |
| DEV-1~4 개발 전용 | — | — | — | — | — | — | — |
| MT-1 매칭 요청 | — | ✓ | ✓ | ✓ | — | — | — |
| MT-2 매칭 상태 | — | ✓ | ✓ | ✓ | — | 본인 | — |
| MT-3 매칭 취소 | — | ✓ | ✓ | ✓ | — | 본인 | — |
| GR-1 내 그룹 목록 | — | ✓ | ✓ | **—** | — | 본인 | — |
| GR-2 그룹 상세 | — | ✓ | ✓ | ✓ | ✓ ACTIVE·COMPLETED | — | 그룹 상태별 분기 |
| GR-3 자율 퇴장 | — | ✓ | ✓ | ✓ | ✓ ACTIVE | 본인 | 그룹 ACTIVE |
| PF-1 촬영물 동의 | — | ✓ | ✓ | ✓ | — | 본인 | — |
| PF-2 인증 제출 | — | ✓ | ✓ | ✓ | ✓ ACTIVE | 본인 | 그룹 ACTIVE |
| PF-3 인증 목록 | — | ✓ | ✓ | ✓ | ✓ ACTIVE·COMPLETED | — | ai_status 필터 |
| PF-4 촬영물 열람 | — | ✓ | ✓ | ✓ | ✓ ACTIVE | — | 대상 APPROVED |
| PF-4r 스트리밍 | — | ✓ | ✓ | ✓ | ✓ ACTIVE | — | **서명 토큰 + 동일 자격 재검증** |
| ST-1 스티커 토글 | — | ✓ | ✓ | ✓ | ✓ ACTIVE | — | proof APPROVED |
| ST-2 스티커 조회 | — | ✓ | ✓ | ✓ | ✓ | — | proof APPROVED |
| RP-1 신고 | — | ✓ | ✓ | **—** | 대상 유형별 | — | — |
| DB-1 대시보드 | — | ✓ | ✓ | ✓ | ✓ | — | — |
| FR-1 완주 리포트 | — | ✓ | ✓ | ✓ | ✓ **COMPLETED·REPORT_EXIT만**(LEFT 403) | — | 그룹 ENDED |
| PB-1 게시글 작성 | — | ✓ | ✓ | ✓ | — | 본인 리포트 | 그룹 ENDED |
| PB-2 게시글 목록 | — | ✓ | ✓ | **—** | — | — | VISIBLE만 |
| PB-3 게시글 상세 | — | ✓ | ✓ | **—** | — | — | VISIBLE만 |
| PB-4 좋아요 토글 | — | ✓ | ✓ | ✓ | — | — | VISIBLE만 |
| PB-5 게시글 삭제 | — | ✓ | ✓ | ✓ | — | 본인 | VISIBLE·HIDDEN |
| AD-1~AD-8 | ADMIN | ✓ | — | — | — | — | 각 API |

**연령 게이트 제외 4종(GR-1·RP-1·PB-2·PB-3)의 이유**: GR-1(내 그룹 목록 — 상태 확인은 막지 않음), RP-1(**안전 도구는 절대 막지 않는다** — 미성년이 유해물을 보고도 신고 못 하는 상태를 방지), PB-2·PB-3(열람은 허용, 작성·좋아요는 차단).

### 0-4. enum 정본 (여기가 유일한 정의처. DB 주석·코드는 참조만)

```
MemberRole          PARTICIPANT, ADMIN
MemberStatus        ACTIVE, WITHDRAW_PENDING, DELETED
AgeVerification     REQUIRED, VERIFIED, UNDER_AGE
SocialProvider      KAKAO, NAVER, GOOGLE, APPLE, DEV
GoalCategory        EXERCISE, STUDY, READING, DIET, SLEEP, ETC
  ↳ 한글 라벨(그룹명·화면 표기용): 운동 / 공부 / 독서 / 식단 / 수면 / 기타
AccessReason        REPORT_REVIEW, CENSORSHIP_QUEUE, AI_REVIEW   (media_access_log)
MatchRequestStatus  WAITING, MATCHED, CANCELLED, EXPIRED
MatchEventType      MATCH_COMPLETED, REJOIN_ENTRY, WAIT_CANCELLED, WAIT_EXPIRED
GroupStatus         ACTIVE, ENDED
GroupMemberStatus   ACTIVE, LEFT, REPORT_EXIT, COMPLETED
LeftReason          PERSONAL, SCHEDULE, HEALTH, ETC, WITHDRAWAL, SANCTION
ProofMethod         PHOTO, LIVE_CAM
ProofAiStatus       SCREENING, APPROVED, PENDING_REVIEW, HOLD, BLOCKED
StickerType         CLAP, MUSCLE, FIRE
ReportTargetType    PROOF, MEMBER, POST
ReportReasonCode    INAPPROPRIATE_CONTENT, ABUSIVE_LANGUAGE, SPAM_PROOF, FAKE_PROOF, PRIVACY_VIOLATION, ETC
ReportSeverity      HIGH, NORMAL
ReportStatus        PENDING, RESOLVED, REJECTED, SANCTIONED
SanctionType        TEMP, PERMANENT
PostStatus          VISIBLE, HIDDEN, DELETED
AiJudgmentType      FACE, CENSORSHIP, AUTHENTICITY, COMPLETION
AiTargetType        PROOF, MEMBER, GROUP
AiVerdict           PASS, FACE_REJECT, BLOCK, HOLD, ESCALATE, ERROR
AiReviewType        AUTHENTICITY, CENSORSHIP, COMPLETION
AiReviewStatus      PENDING, CONFIRMED, OVERRIDDEN
```

선택지 값: `periodDays {30, 60, 100}` · `dailyTargetMinutes {20, 30, 60, 120}` (조합 = 6×4×3 = 72개 대기열)

### 0-5. 계산식 (한 곳에만 정의)

| 값 | 식 | 분모 집합 |
|---|---|---|
| 개인 인증률 | `provedDays ÷ periodDays` | 기간 전체 고정 (경과 일수 아님) |
| 그룹 평균 인증률 | `Σ(멤버 인증률) ÷ n` | **n = 종료 시점 COMPLETED 멤버**. n=0이면 `0.0000`·그룹 기준 미충족 |
| 완주 판정 | 개인 인증률 ≥ `personal-proof-rate` **AND** 그룹 평균 ≥ `group-avg-proof-rate` | [T1-4 확정 대기] |
| 완주율(집계) | `이중 기준 충족자 수 ÷ 리포트 생성 대상 수` | 분모 = COMPLETED + REPORT_EXIT (분자 ⊆ 분모) |
| 경계 구간 | `기준 ± ai.boundary` | 개인·그룹 각각. 교차 시 §5 FR-1 규칙 |
| 완주 배지 | `badgeCode`는 **저장하지 않는 파생값**. **`completed=false`면 무조건 `NONE`**. `completed=true`일 때만 인증률로 `≥0.95 GOLD / ≥0.85 SILVER / 그 외 BRONZE`. (전제 조건이 없으면 개인 0.96·그룹 미달인 **미완주자가 GOLD**를 받아 공개 게시판에 나간다) | 리포트·게시글 응답에서 서버가 계산 |
| 인증 마감 | `proof.deadline-from` ~ `deadline-to` (기본 06:00~23:59 KST). 판정 = **업로드 접수 시각** | AI 처리 지연과 무관 |

### 0-6. 설정 격리 (`application.yml`의 `morak.*` — 최종 전체 목록)

```yaml
morak:
  timezone: Asia/Seoul
  jwt:
    secret: ${MORAK_JWT_SECRET}      # 기본값 없음. 32자 이상(HS256)
    expire-hours: 24
  dev:
    enabled: false                    # @Profile("dev")와 AND 조건 (이중 스위치)
  match:
    expire-hours: 24
  proof:
    deadline-from: "06:00"
    deadline-to: "23:59"
    face-retry-limit: 10              # 일 N회
    screening-timeout-minutes: 10     # B7 회수 임계
  ai:
    boundary: 0.05
    phash-hamming-threshold: 5
    relevance-threshold: 0.30         # 잠정 — shadow 모드라 차단에 미사용
    relevance-shadow: true
  completion:
    personal-proof-rate: 0.7          # [T1-4 연동]
    group-avg-proof-rate: 0.6
  report:
    sla-hours:
      high: 24
      normal: 72
  media:
    retention-days: 30
  storage:
    local-path: ./storage             # 11단계에 S3로 교체
  security:
    media-token-secret: ${MORAK_MEDIA_TOKEN_SECRET}   # PF-4r HMAC 서명
    social-hash-pepper: ${MORAK_SOCIAL_HASH_PEPPER}   # blocked_social_hash HMAC
  withdrawal:
    grace-days: 30
```

---

## §1. 도메인 모델

컬럼·타입·제약의 정본은 `DB스키마_모락_v1.0.md`. 여기는 목록만.

`member` · `media_consent` · `match_request` · `match_lock` · `challenge_group` · `group_member` · `proof` · `proof_media` · `ai_judgment` · `ai_review_queue` · `sticker_reaction` · `report_case` · `report` · `report_history` · `sanction` · `kick_history`(이력 보존용) · `fill_in_history`(이력 보존용) · `media_access_log` · `final_report` · `completion_stats` · `match_event` · `challenge_post` · `post_like` · `blocked_social_hash`

## §2. 상태 머신

| 엔티티 | 상태 | 진입 | 이탈 | 이탈 시 동반 갱신 |
|---|---|---|---|---|
| **member** | ACTIVE | 가입(AU-1) | AU-4 → WITHDRAW_PENDING | `withdraw_requested_at`, `delete_scheduled_at`(+30일), 활성 매칭 요청 CANCELLED, 진행 그룹 membership LEFT(WITHDRAWAL) |
| | WITHDRAW_PENDING | AU-4 | AU-5·재로그인 → ACTIVE / B4 → DELETED | 철회 시 시각 컬럼 NULL / 삭제 시 익명화 |
| | DELETED | B4 | (종점) | `provider_user_id='deleted:{id}'`, 닉네임 치환, `birth_date` NULL, media_consent 삭제, challenge_post → DELETED, proof_media 삭제 예정일 앞당김, 제재 이력자면 blocked_social_hash 등재 |
| **match_request** | WAITING | MT-1 | MT-1 성사 → MATCHED / MT-3 → CANCELLED / B2 → EXPIRED / AU-4 → CANCELLED / **AD-4 제재 → CANCELLED** | **모든 이탈에서 `active_member_id=NULL`**(조건 행 잠금 + 조건부 UPDATE 동반), MATCHED는 `matched_group_id` 기록, CANCELLED·EXPIRED는 match_event 기록 |
| **challenge_group** | ACTIVE | MT-1 성사 | B1 → ENDED | 잔류 ACTIVE 멤버 COMPLETED 전이 + `participant_view_end_at` 기록 + 완주 판정 |
| **group_member** | ACTIVE | 매칭 성사 | GR-3 → LEFT / **AU-4 탈퇴 → LEFT(WITHDRAWAL)** / RP-1 → REPORT_EXIT [T1-1] / AD-4 제재 → LEFT(SANCTION) / B1 → COMPLETED | `left_at`, `left_reason`, REPORT_EXIT은 `exit_case_id` |
| | COMPLETED | B1(종료 시 잔류자) | (종점) | 리포트 생성 대상 |
| **proof** | SCREENING | PF-2 tx1 | tx2 → APPROVED·PENDING_REVIEW·HOLD·BLOCKED / B7(10분 초과) → HOLD | 슬롯 미점유 상태 |
| | APPROVED | tx2 / AD-6 unhide / AD-7 OVERRIDE | AD-6 hide → BLOCKED | 슬롯 점유. 변경 시 B6 트리거 |
| | PENDING_REVIEW | tx2(진위 애매) | AD-7 CONFIRM → HOLD / OVERRIDE → APPROVED | 슬롯 점유(그래서 재업로드 불가 — 관리자 처리 필요) |
| | HOLD | tx2(진위 미달)·B7 | 재업로드 시 `superseded_by_id`로 연결(상태 유지) | 슬롯 미점유 |
| | BLOCKED | tx2(검열)·AD-6 hide | AD-6 unhide → APPROVED(superseded 없을 때)·HOLD(있을 때) | 슬롯 미점유. legal hold 대상 |
| **report_case** | PENDING | RP-1 | AD-3 → RESOLVED·REJECTED·SANCTIONED | 종결 시 `open_target_id=NULL`. **재오픈 불가**(재검토는 새 케이스) |
| **challenge_post** | VISIBLE | PB-1 | PB-5 → DELETED / AD-6 → HIDDEN | HIDDEN은 `hidden_by_case_id`·`hidden_at` |
| | HIDDEN | AD-6 hide | AD-6 unhide → VISIBLE | **DELETED는 복구 불가** |
| **sanction** | (기간형) | AD-3 SANCTIONED | `ends_at` 경과(TEMP) | 유효식은 §0-2 ④ |

---

## §3. 엔드포인트 총람

| ID | Method Path | 한 줄 | 단계 |
|---|---|---|---|
| AU-1 | POST /api/auth/login | 소셜 로그인·가입 | 11 |
| AU-2 | GET /api/members/me | 내 정보 | 1 |
| AU-3 | POST /api/members/me/birthdate | 생년월일·연령 검증 | 1 |
| AU-4 | POST /api/members/me/withdrawal | 탈퇴 신청 | 10 |
| AU-5 | DELETE /api/members/me/withdrawal | 탈퇴 철회 | 10 |
| DEV-1 | POST /api/auth/dev-login | 개발 전용 로그인 | 1 |
| DEV-2 | POST /api/dev/clock | 개발 전용 시각 조작 | 1 |
| DEV-3 | POST /api/dev/proofs/seed | 개발 전용 인증 시드 | 6 |
| DEV-4 | POST /api/dev/batches/{name} | 개발 전용 배치 트리거 | 2 |
| MT-1 | POST /api/match-requests | 매칭 요청 | 2 |
| MT-2 | GET /api/match-requests/me | 매칭 상태 | 2 |
| MT-3 | DELETE /api/match-requests/{id} | 매칭 취소 | 2 |
| GR-1 | GET /api/members/me/groups | 내 그룹 목록 | 3 |
| GR-2 | GET /api/groups/{groupId} | 그룹 상세 | 3 |
| GR-3 | DELETE /api/groups/{groupId}/membership | 자율 퇴장 | 3 |
| PF-1 | POST /api/members/me/media-consent | 촬영물 동의 | 4 |
| PF-2 | POST /api/groups/{groupId}/proofs | 인증 제출 | 4 |
| PF-3 | GET /api/groups/{groupId}/proofs?date= | 일자별 인증 | 5 |
| PF-4 | GET /api/proofs/{proofId}/media | 촬영물 열람 URL | 5 |
| PF-4r | GET /api/proofs/{proofId}/media/raw?t= | 촬영물 스트리밍(서명) | 5 |
| ST-1 | POST /api/proofs/{proofId}/reactions | 스티커 토글 | 5 |
| ST-2 | GET /api/proofs/{proofId}/reactions | 스티커 조회 | 5 |
| DB-1 | GET /api/groups/{groupId}/dashboard | 대시보드 | 6 |
| FR-1 | GET /api/groups/{groupId}/report | 완주 리포트 | 8 |
| RP-1 | POST /api/reports | 신고 | 9 |
| AD-1 | GET /api/admin/reports | 신고 목록 | 9 |
| AD-2 | GET /api/admin/reports/{caseId} | 신고 상세 | 9 |
| AD-3 | PATCH /api/admin/reports/{caseId} | 신고 처리(+제재) | 9 |
| AD-4 | POST /api/admin/members/{memberId}/sanctions | 제재 단독 적용 | 9 |
| AD-5 | GET /api/admin/proofs/{proofId}/media | 관리자 촬영물 열람 | 9 |
| AD-6 | POST /api/admin/proofs/{id}/hide\|unhide, /api/admin/posts/{id}/hide\|unhide | 비노출·복구 | 9 / 9.5 |
| AD-7 | GET /api/admin/ai-reviews, PATCH /api/admin/ai-reviews/{id} | AI 검토 큐 | 8 |
| AD-8 | GET /api/admin/withdrawals | 탈퇴 처리 결과 | 10 |
| PB-1 | POST /api/posts | 완주 게시글 작성 | 9.5 |
| PB-2 | GET /api/posts | 게시글 목록 | 9.5 |
| PB-3 | GET /api/posts/{postId} | 게시글 상세 | 9.5 |
| PB-4 | POST /api/posts/{postId}/likes | 좋아요 토글 | 9.5 |
| PB-5 | DELETE /api/posts/{postId} | 게시글 삭제 | 9.5 |

**식별자 규칙**: 신고는 전역에서 `caseId`(=`report_case.id`)로 통일. `report.id`는 외부 노출하지 않는다.

---

## §4. 엔드포인트 상세

### AU-1 소셜 로그인
`POST /api/auth/login` · `{"provider","authorizationCode"}`
1. 소셜 검증 실패 → 401 `INVALID_SOCIAL_TOKEN`
2. `HMAC-SHA256(provider+providerUserId, pepper)`가 `blocked_social_hash`에 있으면 → 403 `REJOIN_BLOCKED`
3. 기존 회원: WITHDRAW_PENDING이면 **철회·복구**(시각 컬럼 NULL, ACTIVE) 후 `loginResult="RESTORED"`
4. 신규 회원: **SNS 프로필 이미지·닉네임을 받아 저장**(`sns_profile_image_url`·`sns_nickname` — 별도 가입 폼 없이 정보를 가져오는 것이 SNS 로그인의 목적) **+ 서버가 표시용 `익명 {동물명}{2자리}`를 함께 생성**(`nickname`, 중복 시 재생성). **타인에게 보이는 모든 화면은 `nickname`만 쓰고 SNS 값은 본인 확인용으로만 쓴다.** `match_lock('member:{id}')` 동반 INSERT
5. 생년월일 수신 시 즉시 검증 → VERIFIED, 미수신 → REQUIRED
- 200 `{"accessToken","isNewMember","ageVerification","loginResult":"NORMAL|RESTORED"}`

### AU-2 내 정보
`GET /api/members/me` → 200 `{"memberId","nickname","role","ageVerification","memberStatus","mediaConsented","sanction":{"active":bool,"endsAt"}|null}`

### AU-3 생년월일·연령 검증
`POST /api/members/me/birthdate` · `{"birthDate":"2005-03-01"}`
- `age_verification != REQUIRED`이면 409 `ALREADY_VERIFIED` (**UNDER_AGE 판정 후 재입력으로 뒤집을 수 없다** — v3 1-3 "재시도 불가")
- 만 나이 = `Period.between(birthDate, LocalDate.now(clock)).getYears()`
- 200 `{"ageVerification":"VERIFIED|UNDER_AGE"}`

### AU-4 / AU-5 탈퇴·철회
- `POST .../withdrawal` → 202 `{"deleteScheduledAt"}`. 동반: 활성 매칭 요청 CANCELLED(+`active_member_id=NULL`), 진행 그룹 membership `LEFT(WITHDRAWAL)`
- `DELETE .../withdrawal` → 204. WITHDRAW_PENDING이 아니면 409 `NOT_WITHDRAWING`

### DEV-1~4 개발 전용
**활성 조건: `@Profile("dev")` AND `morak.dev.enabled=true` (이중 스위치).** 운영 프로필에서는 빈이 등록되지 않아 404.
- DEV-1 `{"nickname","birthDate"?}` → 200 `{"accessToken","memberId","ageVerification"}`. `provider='DEV'`, `provider_user_id=nickname`으로 **upsert**(동일 nickname 재호출 = 기존 회원 로그인). **`role` 파라미터 없음** — 관리자 계정은 DB 수동 UPDATE로만 만든다
- DEV-2 `{"offsetMinutes"}` → 가변 Clock 오프셋 설정
- DEV-3 `{"groupId","memberId","dates":[]}` → 과거 일자 인증 시드(APPROVED)
- DEV-4 `POST /api/dev/batches/{B1|B2|B3|B4|B5|B6|B7}` → 해당 배치 즉시 실행

### MT-1 매칭 요청
`POST /api/match-requests` · `{"category","dailyTargetMinutes","periodDays","rejoinFromGroupId"?}`

허용값: category 6종, dailyTargetMinutes {20,30,60,120}, periodDays {30,60,100}. 위반 400 `VALIDATION_FAILED`.

**절차 (전체가 하나의 `@Transactional`)**
1. `match_lock` **회원 행** 잠금 — `@Lock(PESSIMISTIC_WRITE) findByLockKey("member:{id}")`
2. 검증: 활성 WAITING 요청 없음(409 `DUPLICATE_MATCH_REQUEST`) / 활성 group_member(ACTIVE) 없음(409 `ALREADY_IN_ACTIVE_GROUP`)
3. `match_lock` **조건 행** 잠금 — `"match:{category}:{minutes}:{days}"`
4. 내 요청 INSERT (`status=WAITING`, `active_member_id=member_id`, `expires_at=now+24h`)
5. 동일 조건 WAITING을 `requested_at` 오름차순 조회 — **유효 제재 보유자·활성 멤버십 보유자 제외**
6. 자신 포함 6건 이상이면 **선착순 정확히 6건** 선택 → `UPDATE ... WHERE id IN (6건) AND status='WAITING'` 실행하고 **영향 행 수 = 6 검증, 미달 시 전체 롤백**
7. 그룹 생성: `name = "{분야 한글} {periodDays}일 챌린지"`, `startDate` [T1-2], `endDate = startDate+periodDays-1`
8. group_member 6명 ACTIVE / 요청들 `MATCHED`+`matched_group_id`+`active_member_id=NULL` / `match_event(MATCH_COMPLETED)`
9. `rejoinFromGroupId` 있으면 `match_event(REJOIN_ENTRY)`

**잠금 행은 런타임에 만들지 않는다** — 조건 행 72개는 기동 시 `ApplicationRunner` 시드, 회원 행은 가입 시 동반 INSERT. (미존재 행 `FOR UPDATE`는 갭 락이 없어 동시 진입 시 INSERT 경합 → 복구 불가. H2 실측 확인)
H2 URL에 `;LOCK_TIMEOUT=3000`. `PessimisticLockingFailureException` → 503 `LOCK_ACQUISITION_FAILED`.

- 201 대기 `{"matchRequestId","status":"WAITING","requestedAt","expiresAt"}` / 201 성사 `{...,"status":"MATCHED","groupId"}`

### MT-2 매칭 상태
`GET /api/match-requests/me` → 200 `{"matchRequestId","status","requestedAt","expiresAt","groupId":null,"conditions":{...},"waitingCount","requiredCount":6}` / 404 `NO_ACTIVE_MATCH_REQUEST`

### MT-3 매칭 취소
`DELETE /api/match-requests/{matchRequestId}` — **조건 행 잠금 후** `status=CANCELLED` + **`active_member_id=NULL`** + `match_event(WAIT_CANCELLED)` → 204
(NULL 해제가 없으면 `uk_mr_active` 때문에 재요청이 영구 불가. "조건 조정" 플로우가 여기에 걸린다.)
- 409 `ALREADY_MATCHED` / 403 `FORBIDDEN`

### GR-1 내 그룹 목록
`GET /api/members/me/groups?status=` → 200 `{"groups":[{"groupId","name","category","dailyTargetMinutes","periodDays","startDate","endDate","status","memberCount","membershipStatus","remainingDays"}]}`
- `membershipStatus` ∈ {ACTIVE, LEFT, REPORT_EXIT, COMPLETED} — 모든 이력 그룹 표시(진입은 GR-2에서 통제)

### GR-2 그룹 상세
`GET /api/groups/{groupId}`
- 자격: group_member 이력 보유. **LEFT·REPORT_EXIT는 403 `NOT_GROUP_MEMBER`**(그룹 접근 상실)
- 200 `{"groupId","name","category","dailyTargetMinutes","periodDays","startDate","endDate","remainingDays","status","memberCount","members":[{"memberId","nickname","isMe","membershipStatus","proofStatus"}]}`
- `memberCount` = **ACTIVE 수** (단일 정의)
- 진행 중: members = ACTIVE만 / 종료: members = COMPLETED+LEFT+REPORT_EXIT 전원(membershipStatus 병기)
- `proofStatus`: 본인 5값(SCREENING·APPROVED·PENDING_REVIEW·HOLD·BLOCKED) / 타인 2값(APPROVED·NONE)

### GR-3 자율 퇴장
`DELETE /api/groups/{groupId}/membership` · `{"reason":"PERSONAL|SCHEDULE|HEALTH|ETC"}`
(서버 전용 사유 `WITHDRAWAL`·`SANCTION`은 요청으로 받지 않는다)
- ACTIVE → LEFT, `left_at`·`left_reason` 기록. **재입장 불가**, 충원 없음
- 204 / 400 `REASON_REQUIRED` / 409 `ALREADY_LEFT` / 409 `GROUP_ENDED`

### PF-1 촬영물 동의
`POST /api/members/me/media-consent` · `{"agreed":true}` → 204. false는 400.

### PF-2 인증 제출 
`POST /api/groups/{groupId}/proofs` · `multipart/form-data`: `file`(jpg/png/webp, ≤10MB, **매직바이트 검사**) + `method`(PHOTO|LIVE_CAM)

**1. 검증** — 멤버십 ACTIVE / 그룹 ACTIVE(409 `GROUP_ENDED`) / 오늘이 기간 내(400 `OUT_OF_CHALLENGE_PERIOD`) / 마감 시간대(400 `PROOF_DEADLINE_PASSED`) / 동의(403 `CONSENT_REQUIRED`) / **당일 `SCREENING`·`APPROVED`·`PENDING_REVIEW` 행 존재 시 409 `DUPLICATE_DAILY_PROOF`** / 당일 `hidden_at` 있는 행 존재 시 409 `PROOF_HIDDEN_BY_ADMIN`

**2. 얼굴 선검사 (파일·DB 쓰기 이전, 메모리 바이트 상태)**
- 감지 → 바이트 폐기, `ai_judgment(type=FACE, target_type=MEMBER, target_id=memberId, verdict=FACE_REJECT)` 기록, **422 `FACE_DETECTED_RETRY`**
- 일 `face-retry-limit` 초과 → 429 `FACE_RETRY_EXCEEDED`
- **AI 장애 → 502 `AI_SCREENING_UNAVAILABLE` (fail-closed. 통과시키지 않는다)**
- 이 순서 때문에 "얼굴 사진 미저장"이 실제로 성립한다(선저장 후 삭제는 슬롯 점유·고아 파일·dangling 로그를 만든다)

**3. tx1** — proof INSERT(`ai_status=SCREENING`) → flush로 id 확보 → 파일 쓰기(`{groupId}/{proofId}/{uuid}.{ext}`) → proof_media INSERT → 커밋. 파일 쓰기 실패 시 전체 롤백(503 `MEDIA_UPLOAD_FAILED`)
**4. tx 밖** — 검열(선정·폭력·신분증·차량번호) → 진위(pHash 해밍 ≤ 임계 재사용 대조, 관련성은 shadow)
**5. tx2** — `UPDATE proof SET ai_status=? WHERE id=? AND ai_status='SCREENING'`(조건부 — B7과 경합 방지) + `ai_judgment` INSERT + **큐 적재 2경로: BLOCKED → `ai_review_queue(CENSORSHIP)` / PENDING_REVIEW → `ai_review_queue(AUTHENTICITY)`**. **PENDING_REVIEW는 슬롯을 점유하므로 큐에 안 올라가면 관리자가 처리할 수 없고 재업로드도 409로 막혀 그날 인증이 영구 소실된다**(이 적재 누락이 최종 검증에서 잡힌 치명 1건). **에러 응답 변환은 tx2 커밋 후 컨트롤러에서**(tx 안에서 던지면 BLOCKED 갱신·큐 적재가 함께 롤백된다)
**6. AI 예외 시** — tx2에서 즉시 HOLD 강등 후 502 반환(그날 재제출 가능). B7은 서버 다운 전용 안전망

- 201 `{"proofId","proofDate","aiStatus":"APPROVED|PENDING_REVIEW"}` / 200 `{"proofId","aiStatus":"HOLD","guide"}` / 422 `CONTENT_BLOCKED`·`FACE_DETECTED_RETRY` / 429 / 502

### PF-3 일자별 인증
`GET /api/groups/{groupId}/proofs?date=`
- 200 `{"date","members":[{"memberId","nickname","membershipStatus","proofStatus","proof":{"proofId","method","submittedAt","mediaViewable","stickerCounts":{},"myReactions":[]}|null}]}`
- **타인에게는 APPROVED만 노출**(SCREENING·PENDING_REVIEW·HOLD·BLOCKED는 `proofStatus="NONE"`). 본인은 5값 그대로
- 퇴장자·완주자 포함(membershipStatus 병기). date가 기간 밖이면 400

### PF-4 / PF-4r 촬영물 열람
- `GET /api/proofs/{proofId}/media` → 200 `{"url":"/api/proofs/{id}/media/raw?t={token}","expiresInSeconds":300}`
  - 자격: 같은 그룹 **ACTIVE** 멤버 → 그룹 진행 중(403 `VIEW_BLOCKED_GROUP_ENDED`) → 대상 APPROVED
  - `token` = HMAC(`proofId|memberId|exp`, 서버 시크릿)
- `GET .../media/raw?t=` → **토큰 우선 인증**: `<img src>`로 열리므로 Bearer 헤더를 실을 수 없다. 따라서 이 경로만 **§0-2 ① JWT 검사에서 제외**하고, **HMAC 토큰에 담긴 memberId로 신원을 확정한 뒤 §PF-4와 동일한 자격 검증을 그대로 재수행**한다(같은 그룹 ACTIVE → 그룹 진행 중 → 대상 APPROVED). 서명 불일치·만료 → 403. 즉 토큰은 "누구인지"만 대신하고 권한 판정은 매번 다시 한다
- 410 `MEDIA_DELETED`

### ST-1 / ST-2 스티커
- `POST /api/proofs/{proofId}/reactions` `{"stickerType"}` — 대상이 APPROVED가 아니면 404 `PROOF_NOT_FOUND`(타인의 차단 인증 존재 여부가 새지 않게). 없으면 201 ON, 있으면 200 OFF(행 삭제). unique(proof, member, type)
- `GET .../reactions` → `{"total","byType":{},"myReactions":[]}`

### DB-1 대시보드
`GET /api/groups/{groupId}/dashboard`
- 200 `{"my":{"provedDays","totalDays","proofRate"},"members":[{"memberId","nickname","membershipStatus","dailyStatuses":[{"date","proofStatus"}]}],"groupDaily":[{"date","provedCount"}]}`
- **분모는 `periodDays` 고정** — `elapsedDays`·`achievementRate`는 폐기된 개념이다. 순위 필드 없음

### FR-1 완주 리포트
`GET /api/groups/{groupId}/report`
- **검사 순서**: ① 그룹 존재(404) → ② **그룹 ENDED 여부(ACTIVE면 409 `GROUP_NOT_ENDED`)** → ③ 멤버십이 COMPLETED·REPORT_EXIT인지(아니면 403 `NOT_GROUP_MEMBER`. LEFT 포함) → ④ 리포트 존재(없으면 202)
  - 순서를 바꾸면 **진행 중 그룹의 참가자가 본인 그룹인데 403**을 받는다(멤버십이 ACTIVE라 ③에 먼저 걸림)
- 리포트 미생성(경계 에스컬레이션 대기 중)이면 **202 `REPORT_NOT_READY`**
- 200 `{"personal":{"provedDays","totalDays","proofRate","criteriaMet"},"group":{"avgProofRate","criteriaMet","avgDenominator"(그룹 평균 분모 n = COMPLETED 수. GR-1/GR-2의 memberCount와 이름을 분리 — 같은 이름이면 종료 그룹에서 0이 되는 값과 헷갈린다)},"completed","badgeCode","criteria":{"personalProofRate","groupAvgProofRate"},"dailyGrid":[{"date","members":[{"memberId","proofStatus"}]}],"memberSummary":[{"memberId","nickname","provedDays","proofRate"}]}`

### RP-1 신고
`POST /api/reports` · `{"targetType","targetId","reasonCode","detail"?}`
0. `detail` 텍스트 검열 — 위험 판정 시 **detail만 제거하고 접수는 진행**(신고를 막지 않는다). AI 장애 시 502
1. 자격: `targetType=PROOF|MEMBER`는 같은 그룹 ACTIVE 멤버 / **`POST`는 그룹 자격 검사 면제**(게시판은 전체 공개)
2. 케이스 병합: 동일 `(targetType,targetId)`의 PENDING 케이스가 있으면 신고자만 추가, 없으면 신규 생성
   - `group_id`: PROOF·MEMBER는 해당 그룹 / **POST는 게시글의 group_id(NULL 허용)**
   - `target_nickname`: 대상 표시명 스냅샷 / `severity`: `INAPPROPRIATE_CONTENT·PRIVACY_VIOLATION·ABUSIVE_LANGUAGE` → HIGH, 그 외 NORMAL / `sla_due_at` 계산
   - **병합 시 더 높은 severity가 오면 케이스를 상향**하고 `sla_due_at`을 재계산한다
3. **신고자 처리 (확정 8/12)**: `targetType=PROOF·MEMBER`이면 **신고자 본인**의 membership → `REPORT_EXIT`(그룹 접근 상실, `exit_case_id` 기록). `targetType=POST`는 아무 처리 없음(그룹과 무관)
4. ** 피신고자는 신고만으로 아무 조치도 받지 않는다.** 계속 그룹에 남아 인증·응원이 가능하다. **제재(그룹에서 내보내기·이용 제한)는 운영자가 AD-3에서 `SANCTIONED`로 확정한 뒤에만** 적용된다
   - 이유: 신고 즉시 피신고자를 차단하면 **아무나 신고해서 남을 쫓아내는 악용**이 성립한다. 원문의 "즉시 차단"은 **신고자 본인**에 대한 것이며(본인이 관계를 끊는 안전장치), 상대에 대한 조치는 운영자 판단 사항이다
   - 그 대가로 **신고 처리 SLA(고위험 24h)가 실질적인 피해자 보호 장치**가 된다. 신고자는 이미 그룹을 떠난 상태이므로 그 사이 추가 노출은 없다
   - 프론트: 신고 버튼에 **"신고하면 이 그룹에서 나가게 됩니다"** 확인 다이얼로그 필수(되돌릴 수 없음)
- 201 `{"caseId","severity","receivedAt"}` / 409 `DUPLICATE_REPORT`(같은 PENDING 케이스에 이미 신고)

### AD-1~AD-8 관리자
- **AD-1** `GET /api/admin/reports?status=&severity=&overdue=&q=&page=&size=` → `PageResponse` `{"caseId","targetType","targetNickname","reasonCode","severity","status","overdue","receivedAt","slaDueAt","reportCount"}`
- **AD-2** `GET /api/admin/reports/{caseId}` → 위 + `{"target":{"memberId","nickname","groupId","groupName","proofId"?,"postId"?,"proofDate"?},"reporters":[{"reasonCode","detail","receivedAt"}],"history":[{"adminId","status","reviewNote","processedAt"}],"aiJudgments":[{"type","verdict","confidence","reason","judgedAt"}]}`
  - 대상 회원 도출: MEMBER→`target_id` / PROOF→`proof.member_id` / POST→`challenge_post.member_id`
- **AD-3** `PATCH /api/admin/reports/{caseId}` · `{"status":"RESOLVED|REJECTED|SANCTIONED","reviewNote"?,"sanction":{"type","days"?}?}`
  - PENDING만 변경 가능(그 외 409 `ALREADY_PROCESSED`). **재오픈 불가**
  - **REJECTED(기각) 확정 시 신고자에게 `restriction_review` 플래그** — 신고자는 접수 즉시 그룹을 떠나므로(T1-1 확정), 완주 회피 목적의 허위 신고를 억제할 장치가 필요하다. 반복 기각자는 관리자가 제재 검토
  - SANCTIONED면 `sanction` 필수 — **제재 적용은 단일 서비스 메서드**(§AD-4 절차)를 호출해 한 트랜잭션에서 처리
  - 종결 시 `open_target_id=NULL`
- **AD-4** `POST /api/admin/members/{memberId}/sanctions` · `{"type","days"?,"caseId"?}` — **제재 적용 절차(AD-3와 공유)**: ① sanction INSERT ② 진행 중 group_member → `LEFT(SANCTION)` ③ 활성 WAITING 매칭 요청 CANCELLED(+`active_member_id=NULL`)
- **AD-5** `GET /api/admin/proofs/{proofId}/media` — 열람 허용 조건 3종: ① 접수된 신고의 대상 ② `ai_status=BLOCKED`이고 CENSORSHIP 큐 PENDING ③ AD-7 큐에 올라온 대상. `media_access_log` 기록(`case_id` NULL 허용, `access_reason` 필수). 410 `MEDIA_DELETED`
- **AD-6** hide/unhide — proof: hide → BLOCKED(`hidden_by_case_id`·`hidden_at`), unhide → `superseded_by_id` 없으면 APPROVED·있으면 HOLD. **AI 자동 차단 건 unhide는 진위 판정을 다시 거친다** / post: hide → HIDDEN, unhide → VISIBLE(**DELETED는 불가**)
- **AD-7** `GET /api/admin/ai-reviews?type=&status=` / `PATCH /api/admin/ai-reviews/{id}` · `{"decision":"CONFIRM|OVERRIDE","groupAvgRate"?,"groupMet"?}`(COMPLETION OVERRIDE 시 관리자 값)
  - **COMPLETION 그룹 경계 건을 확정하면 그 트랜잭션에서 해당 그룹의 개인 판정·리포트 생성을 이어서 실행한다.** B1은 `end_date < 오늘`인 ACTIVE 그룹만 보므로 이미 ENDED인 그룹에 다시 오지 않는다 — 재개 주체가 없으면 리포트가 영구 202에 머문다(최종 검증 치명 2건)
  | type | CONFIRM | OVERRIDE |
  |---|---|---|
  | AUTHENTICITY | proof → HOLD | proof → APPROVED (슬롯 점유 중이면 HOLD) + **B6 트리거** |
  | CENSORSHIP | BLOCKED 유지 | AD-6 unhide 절차 호출 + **B6 트리거** |
  | COMPLETION | AI 판정대로 리포트 생성 | 관리자 값으로 리포트 생성 |
- **AD-8** `GET /api/admin/withdrawals?page=` → `{"memberId","requestedAt","deleteScheduledAt","deletedAt","status"}`

### PB-1~5 완주 게시판
- **PB-1** `POST /api/posts` · `{"groupId","comment"?}`
  - 검증 순서: 그룹 존재(404) → **ENDED**(409 `GROUP_NOT_ENDED`) → 본인 `final_report` 존재(409 **`POST_REPORT_REQUIRED`**) → 중복(409 `DUPLICATE_POST`)
  - `comment` ≤200자, **① 정규식 선차단**(전화번호·카톡ID·`@핸들`·URL·이메일) → 422 `CONTENT_BLOCKED` **② AI 텍스트 검열** → 422, 장애 시 502
  - 서버 스냅샷: `author_alias`(게시글 전용 별칭 신규 생성), category, periodDays, provedDays, proofRate, completed
  - 201 `{"postId","createdAt"}`
- **PB-2** `GET /api/posts?page=&size=` (size ≤50) → `PageResponse` `{"postId","authorAlias","category","periodDays","provedDays","proofRate","completed","badgeCode","comment","likeCount","myLiked","createdDate"}`. VISIBLE만. 시각은 **일 단위**만 노출(같은 날 종료 글 6건 묶어 그룹 구성원을 재구성하는 것을 막는다)
- **PB-3** `GET /api/posts/{postId}` → PB-2 항목 + `{"shareTitle","categoryLabel","periodLabel"}`. VISIBLE 아니면 404 `POST_NOT_FOUND`
- **PB-4** `POST /api/posts/{postId}/likes` — VISIBLE만, 201 ON / 200 OFF
- **PB-5** `DELETE /api/posts/{postId}` — 본인만, DELETED(soft), 204. 삭제 후 같은 그룹 재작성 불가

---

## §5. 배치

| ID | 주기 | 대상·동작 | 단계 |
|---|---|---|---|
| B1 | 매일 00:05 | `end_date < 오늘`인 ACTIVE 그룹 → ENDED + 잔류 ACTIVE→COMPLETED + `participant_view_end_at` 기록 + 완주 판정 → `ai_judgment(COMPLETION)` INSERT 후 경계면 `ai_review_queue` 적재, 아니면 `final_report`·`completion_stats` 생성. **B7 선행 실행** | 8 |
| B2 | 매시 | `expires_at < now`인 WAITING → EXPIRED + `active_member_id=NULL` + `match_event(WAIT_EXPIRED)`. **조건 행 잠금 후 조건부 UPDATE** | 2 |
| B3 | 매시 | `sla_due_at < now`인 PENDING 케이스 → `overdue=1`, HIGH는 `restriction_review=1` | 9 |
| B4 | 매일 | `delete_scheduled_at < now`인 WITHDRAW_PENDING → 익명화·DELETED + §2 동반 갱신 전부 | 10 |
| B5 | 매일 | `delete_scheduled_at < now`이고 legal hold 아닌 미디어 삭제. **hold 해제 = 케이스 종결+30일 / BLOCKED 확정+30일**(무기한 보존 금지) | 10 |
| B6 | 이벤트 | 종료 그룹의 proof가 APPROVED로 바뀔 때 → 해당 그룹 `final_report`·`completion_stats`·`challenge_post` 스냅샷 재계산 | 8 |
| B7 | 매시 | `screening-timeout-minutes` 초과 SCREENING → HOLD | 4 |

**공통**: 모든 배치는 `@Scheduled` + `DEV-4` 수동 트리거 쌍. 멱등(재실행 안전).

### 경계 교차 규칙 (개인 × 그룹)
**그룹 평균이 경계 구간에 걸리면 개인 판정을 전부 중단하고 그룹 단위 1건만 큐에 올린다**(`member_id=NULL`). 관리자 확정 후 개인 판정을 진행한다. 그룹 값이 흔들리면 전원의 `group_met`이 흔들리므로 개인 판정이 무의미하기 때문이다.

---

## §6. 에러 코드

| 코드 | Status | 발생 API | 조건 |
|---|---|---|---|
| UNAUTHORIZED | 401 | 전역 | 토큰 없음·무효·DELETED 회원 |
| TOKEN_EXPIRED | 401 | 전역 | 만료 |
| FORBIDDEN_ROLE | 403 | /api/admin/** | ADMIN 아님 |
| FORBIDDEN | 403 | MT-3, PB-5 | 소유권 없음 |
| ENDPOINT_NOT_FOUND | 404 | 전역 | 없는 경로 |
| VALIDATION_FAILED | 400 | 전역 | 입력 검증 실패 |
| INTERNAL_SERVER_ERROR | 500 | 전역 | 미분류 |
| INVALID_SOCIAL_TOKEN | 401 | AU-1 | 소셜 인증 실패 |
| REJOIN_BLOCKED | 403 | AU-1 | blocked_social_hash 등재 |
| WITHDRAWAL_PENDING | 403 | 전역② | 탈퇴 유예 중 참여 API |
| NOT_WITHDRAWING | 409 | AU-5 | 탈퇴 상태 아님 |
| ALREADY_VERIFIED | 409 | AU-3 | 이미 검증됨 |
| AGE_NOT_VERIFIED | 403 | 전역⑤ | 미검증·미성년 |
| MEMBER_SANCTIONED | 403 | 전역④ | 유효 제재 |
| DUPLICATE_MATCH_REQUEST | 409 | MT-1 | 활성 요청 존재 |
| ALREADY_IN_ACTIVE_GROUP | 409 | MT-1 | 활성 멤버십 존재 |
| NO_ACTIVE_MATCH_REQUEST | 404 | MT-2 | 요청 없음 |
| ALREADY_MATCHED | 409 | MT-3 | 이미 성사 |
| LOCK_ACQUISITION_FAILED | 503 | MT-1, MT-3, B2 | 잠금 타임아웃 |
| GROUP_NOT_FOUND | 404 | GR-2, PF-2, PB-1 | 없는 그룹 |
| NOT_GROUP_MEMBER | 403 | GR-2, PF-*, ST-*, DB-1, FR-1, **RP-1(PROOF·MEMBER 신고 시)** | 자격 없음 |
| ALREADY_LEFT | 409 | GR-3 | 이미 퇴장 |
| REASON_REQUIRED | 400 | GR-3 | 사유 없음 |
| GROUP_ENDED | 409 | GR-3, PF-2 | 종료된 그룹 |
| GROUP_NOT_ENDED | 409 | FR-1, PB-1 | 진행 중 그룹 |
| CONSENT_REQUIRED | 403 | PF-2 | 촬영물 미동의 |
| OUT_OF_CHALLENGE_PERIOD | 400 | PF-2, PF-3 | 기간 밖 일자 |
| PROOF_DEADLINE_PASSED | 400 | PF-2 | 마감 시간대 밖 |
| DUPLICATE_DAILY_PROOF | 409 | PF-2 | 당일 유효 인증 존재 |
| PROOF_HIDDEN_BY_ADMIN | 409 | PF-2 | 당일 관리자 조치 존재 |
| FACE_DETECTED_RETRY | 422 | PF-2 | 얼굴 감지 |
| FACE_RETRY_EXCEEDED | 429 | PF-2 | 일 재촬영 한도 초과 |
| CONTENT_BLOCKED | 422 | PF-2, PB-1 | 검열 위험 판정 |
| AI_SCREENING_UNAVAILABLE | 502 | PF-2, RP-1, PB-1 | AI 장애(fail-closed) |
| MEDIA_UPLOAD_FAILED | 503 | PF-2 | 파일 저장 실패 |
| INVALID_FILE | 400 | PF-2 | 형식·크기·매직바이트 |
| VIEW_BLOCKED_GROUP_ENDED | 403 | PF-4 | 종료 후 열람 |
| INVALID_MEDIA_TOKEN | 403 | PF-4r | 서명 불일치·만료 |
| MEDIA_DELETED | 410 | PF-4, AD-5 | 삭제됨 |
| PROOF_NOT_FOUND | 404 | ST-1, ST-2, AD-5 | 없음·비공개 |
| REPORT_NOT_READY | 202 | FR-1 | 리포트 미생성(정상 응답) |
| DUPLICATE_REPORT | 409 | RP-1 | 같은 케이스 중복 |
| TARGET_NOT_FOUND | 404 | RP-1 | 대상 없음 |
| REPORT_NOT_FOUND | 404 | AD-2, AD-3 | 없는 케이스 |
| ALREADY_PROCESSED | 409 | AD-3 | 종결된 케이스 |
| NOT_REPORTED_PROOF | 400 | AD-5 | 열람 조건 3종 미충족 |
| POST_REPORT_REQUIRED | 409 | PB-1 | 리포트 미생성 |
| DUPLICATE_POST | 409 | PB-1 | 그룹당 1건 위반 |
| POST_NOT_FOUND | 404 | PB-3, PB-4, PB-5 | 없음·비공개 |

> `REPORT_NOT_READY`는 예외로 던지지 않는다 — FR-1 컨트롤러가 202로 직접 반환한다(코드당 status 1개 구조를 지키기 위함).

---

## §7. AI 모듈

| 모듈 | 시점 | 실패 정책 | 결과 |
|---|---|---|---|
| 얼굴 | PF-2 저장 이전 | fail-closed(502) | FACE_REJECT → 미저장·422 |
| 이미지 검열 | PF-2 tx 밖 | fail-closed(502) | BLOCK → BLOCKED + CENSORSHIP 큐 |
| 텍스트 검열 | RP-1 detail, PB-1 comment | fail-closed(502) | BLOCK → detail 제거(RP-1) / 422(PB-1) |
| 진위 | PF-2 tx 밖 | fail-closed(502) | ① pHash 재사용 → HOLD ② **관련성 점수 < 임계 → HOLD(재촬영 안내)**, 경계 구간 → PENDING_REVIEW + 큐 — **AI가 서비스 핵심 엔진이므로 관련성 판정도 실제 차단까지 수행한다**(shadow는 7단계 초기 튜닝 기간에만 켜고, 임계값이 잡히면 `ai.relevance-shadow: false`로 전환) |
| 완주 판정 | B1 | 재시도 | 명확 → 자동 확정 / 경계 → COMPLETION 큐 |

`AiClient` 인터페이스 4메서드(얼굴·이미지 검열·텍스트 검열·진위) + 스텁 → 7단계에서 실물 교체.
모든 판정은 `ai_judgment`에 기록(type·target_type·target_id·verdict·confidence·reason·riskTypes).

---

## §8. 프론트 요청 사항 (통합 목록)

1. 촬영물 처리 동의 UI (인증 첫 진입 시)
2. 동료 인증 사진 열람 UI (그룹 화면 썸네일)
3. 관리 탭 role 분기 (참여자에게 노출 금지)
4. 챌린지 기간 칩 30/60/100일로 교체 (현재 7/14/21/30)
5. 캠 인증 촬영 가이드 — "얼굴이 나오지 않게" 문구
6. 완주 리포트 공유 카드 + Web Share API
7. **신규 화면 3종**: 게시판 목록·작성·상세

## §9. 미결 레지스터

**T1 (착수 차단)** — 최상단 배너 참조: T1-1 신고자 처리 / T1-2 startDate / T1-3 시험날짜 / T1-4 이중 기준 / T1-5 매칭 완화

**T2 (값 조정 — 설계 불변)**
- 완주 기준값(0.7 / 0.6) · AI 경계(0.05) · pHash 임계(5) · 인증 시간대(06:00~23:59) · 매칭 만료(24h) · 얼굴 재촬영 한도(10) · SLA(24h/72h)
- **알림 범위 확정(8/12)**: **매칭 완료 알림 1종만 v1에 포함**(원문 요구2-2 결과 조항. 6명이 모이는 시점을 사용자가 알아야 챌린지가 시작된다). 구현은 **매칭 대기 화면 폴링(MT-2) + 앱 재진입 시 배너**로 하고, 앱을 닫아도 오는 푸시는 v2. 응원 알림·인증 리마인더는 v2 백로그
- "첫 인증 완료율"의 "첫" 정의(1일차 vs 기간 내 최초)

**T3 (설정 격리 — 값만 교체)**: §0-6 yml 전체

**법무 확인 필요**: `blocked_social_hash`는 개인정보 해시를 영구 보관하므로 v3 6-3의 "SNS 식별자 자동 삭제"와 충돌 소지. HMAC(pepper=환경변수)로 저장하고 보존 기한을 정할 것.

**v1 범위 밖(백로그)**: 공유 횟수 지표, 알림/푸시, 매칭 완화 추천, 본인 사진 종료 후 회수, 미성년·성인 그룹 분리
