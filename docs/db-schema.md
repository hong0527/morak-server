# 모락(Morak) DB 스키마 v1.0 — 통합 정본 (2026-08-11)

> 자기완결 문서. 구 판본(v0.2·v0.3)은 `docs/archive/`. 계약 정본은 `API명세서_모락_v1.0.md`(enum·계산식·상태 전이는 그쪽이 유일 정의처, 여기 주석은 참조).
> MySQL 8 / InnoDB / utf8mb4. 개발은 H2 2.4.240(`MODE=MySQL;LOCK_TIMEOUT=3000`).
> **UNIQUE 제약은 엔티티 `@Table(uniqueConstraints=…)`에도 반드시 선언한다** — `ddl-auto=update`만 믿으면 개발 DB에 방어선이 생기지 않아 게이트가 무의미해진다.
> 개수는 §목록의 행 수로만 말한다.

## 실행 순서
DDL은 이 문서 순서대로 위→아래 실행 가능하다(FK 대상이 항상 선행). 단 `match_request → challenge_group` FK만 말미 `ALTER`로 분리했다.

---

## 1. 회원·인증

```sql
CREATE TABLE member (
    id                    BIGINT       NOT NULL AUTO_INCREMENT,
    provider              VARCHAR(20)  NOT NULL,   -- SocialProvider
    provider_user_id      VARCHAR(191) NOT NULL,   -- 191 = utf8mb4 인덱스 안전 상한
    nickname              VARCHAR(30)  NOT NULL,   -- 표시용 익명 닉네임(서버 생성). 타인에게 보이는 화면은 전부 이 값만 사용
    sns_nickname          VARCHAR(50)  NULL,       -- SNS 원본(요구1-1 "저장한다"). 본인 확인용 — 타인 노출 금지
    sns_profile_image_url VARCHAR(500) NULL,       -- SNS 원본. 동일 규칙. B4가 두 컬럼을 함께 삭제(요구6-3)
    role                  VARCHAR(20)  NOT NULL DEFAULT 'PARTICIPANT',
    birth_date            DATE         NULL,
    age_verification      VARCHAR(20)  NOT NULL DEFAULT 'REQUIRED',
    status                VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    withdraw_requested_at DATETIME(6)  NULL,
    delete_scheduled_at   DATETIME(6)  NULL,       -- 신청 + withdrawal.grace-days
    deleted_at            DATETIME(6)  NULL,
    created_at            DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_member_provider (provider, provider_user_id),
    KEY idx_member_withdraw (status, delete_scheduled_at)
);
-- B4 삭제 방식 = 익명화: provider_user_id='deleted:{id}', nickname='탈퇴회원', birth_date=NULL.
--   컬럼 NULL 세팅이 아니다(NOT NULL 제약 + uk_member_provider 재가입 충돌 방지).

CREATE TABLE media_consent (
    member_id  BIGINT      NOT NULL,
    agreed_at  DATETIME(6) NOT NULL,
    PRIMARY KEY (member_id),
    CONSTRAINT fk_mc_member FOREIGN KEY (member_id) REFERENCES member(id)
);
-- B4 시 함께 삭제

CREATE TABLE blocked_social_hash (
    social_hash  CHAR(64)    NOT NULL,   -- HMAC-SHA256(provider + providerUserId, pepper=환경변수)
    reason       VARCHAR(30) NOT NULL,   -- SANCTION_PERMANENT
    created_at   DATETIME(6) NOT NULL,
    expires_at   DATETIME(6) NULL,       -- NULL=무기한. 보존 기한은 법무 확인 후 확정
    PRIMARY KEY (social_hash)
);
-- 등재 대상: PERMANENT 제재 이력자의 탈퇴 시(B4)만. TEMP는 등재하지 않는다.
-- 원문(provider_user_id)은 보유하지 않는다. 단 pepper 없는 순수 sha256은 역산 가능하므로 HMAC을 쓴다.
-- [법무 확인] 영구 보관이 "SNS 식별자 자동 삭제" 약속과 충돌하는지.
```

## 2. 매칭

```sql
CREATE TABLE match_lock (
    lock_key VARCHAR(80) NOT NULL,  -- "member:{memberId}" | "match:{category}:{minutes}:{days}"
    PRIMARY KEY (lock_key)
);
-- 행은 런타임에 만들지 않는다. 조건 행 72개는 기동 시 ApplicationRunner가 시드,
-- 회원 행은 가입 트랜잭션에서 동반 INSERT.
-- (미존재 행 FOR UPDATE는 갭 락이 없어 동시 진입 시 둘 다 0행을 보고 INSERT 경합 → 단일 트랜잭션에서 복구 불가. H2 실측)

CREATE TABLE challenge_group (
    id                   BIGINT      NOT NULL AUTO_INCREMENT,
    name                 VARCHAR(50) NOT NULL,
    category             VARCHAR(20) NOT NULL,
    daily_target_minutes INT         NOT NULL,
    period_days          INT         NOT NULL,
    start_date           DATE        NOT NULL,
    end_date             DATE        NOT NULL,
    status               VARCHAR(20) NOT NULL,
    created_at           DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_cg_batch (status, end_date)     -- B1
);

CREATE TABLE match_request (
    id                   BIGINT      NOT NULL AUTO_INCREMENT,
    member_id            BIGINT      NOT NULL,
    category             VARCHAR(20) NOT NULL,
    daily_target_minutes INT         NOT NULL,
    period_days          INT         NOT NULL,
    status               VARCHAR(20) NOT NULL,
    active_member_id     BIGINT      NULL,      -- WAITING일 때만 member_id, 그 외 NULL
    requested_at         DATETIME(6) NOT NULL,
    expires_at           DATETIME(6) NOT NULL,
    matched_group_id     BIGINT      NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_mr_active (active_member_id),  -- 회원당 활성 요청 1건 (이중 배정 DB 방어선)
    KEY idx_mr_queue (status, category, daily_target_minutes, period_days, requested_at),
    KEY idx_mr_expire (status, expires_at),      -- B2
    CONSTRAINT fk_mr_member FOREIGN KEY (member_id) REFERENCES member(id)
);
-- status를 바꾸는 모든 주체(MT-1·MT-3·B2·AD-4·AU-4)는 예외 없이
--   ① 조건 행 잠금 ② 조건부 UPDATE(WHERE status='WAITING') ③ active_member_id=NULL
-- 셋을 함께 수행한다. 하나라도 빠지면 재요청이 uk_mr_active로 영구 차단된다.

ALTER TABLE match_request
    ADD CONSTRAINT fk_mr_group FOREIGN KEY (matched_group_id) REFERENCES challenge_group(id);

CREATE TABLE group_member (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    group_id     BIGINT      NOT NULL,
    member_id    BIGINT      NOT NULL,
    status       VARCHAR(20) NOT NULL,   -- GroupMemberStatus
    joined_at    DATETIME(6) NOT NULL,
    left_at      DATETIME(6) NULL,
    left_reason  VARCHAR(30) NULL,       -- LeftReason (SANCTION·WITHDRAWAL은 서버 전용)
    exit_case_id BIGINT      NULL,       -- REPORT_EXIT의 근거 케이스
    PRIMARY KEY (id),
    UNIQUE KEY uk_gm (group_id, member_id),   -- 재입장 없음
    KEY idx_gm_member (member_id, status),    -- "내 활성 멤버십" 검증
    CONSTRAINT fk_gm_group  FOREIGN KEY (group_id) REFERENCES challenge_group(id),
    CONSTRAINT fk_gm_member FOREIGN KEY (member_id) REFERENCES member(id)
);
-- "활성 멤버십" = status = 'ACTIVE' 단일값

CREATE TABLE match_event (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    member_id   BIGINT      NOT NULL,
    group_id    BIGINT      NULL,
    type        VARCHAR(30) NOT NULL,   -- MatchEventType
    occurred_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_me_type (type, occurred_at)
);
-- 지표 원천: 매칭 완료율·대기 이탈률·30일 재참여율
```

## 3. 인증·AI

```sql
CREATE TABLE proof (
    id                  BIGINT      NOT NULL AUTO_INCREMENT,
    group_id            BIGINT      NOT NULL,
    member_id           BIGINT      NOT NULL,
    proof_date          DATE        NOT NULL,
    method              VARCHAR(20) NOT NULL,
    ai_status           VARCHAR(20) NOT NULL,   -- ProofAiStatus
    image_phash         BIGINT      NULL,
    superseded_by_id    BIGINT      NULL,       -- 재업로드로 대체된 구 HOLD/BLOCKED → 새 행
    hidden_by_case_id   BIGINT      NULL,       -- AD-6 hide 근거
    hidden_by_admin_id  BIGINT      NULL,
    hidden_at           DATETIME(6) NULL,
    submitted_at        DATETIME(6) NOT NULL,   -- 마감 판정 기준(접수 시각)
    daily_slot          BIGINT GENERATED ALWAYS AS
        (CASE WHEN ai_status IN ('APPROVED','PENDING_REVIEW') THEN member_id ELSE NULL END),
    PRIMARY KEY (id),
    UNIQUE KEY uk_proof_daily (group_id, proof_date, daily_slot),
    KEY idx_proof_date (group_id, proof_date),
    KEY idx_proof_phash (member_id, image_phash),
    CONSTRAINT fk_pf_group  FOREIGN KEY (group_id) REFERENCES challenge_group(id),
    CONSTRAINT fk_pf_member FOREIGN KEY (member_id) REFERENCES member(id)
);
-- daily_slot: SCREENING·HOLD·BLOCKED는 슬롯을 점유하지 않는다.
--   ① AI 처리 중 서버가 죽어도 그날 인증이 409로 잠기지 않고
--   ② 검열 오탐 1회가 그날 인증을 영구 상실시키지 않는다.
--   H2 실측: CASE WHEN 형식은 정상 동작(IF()는 문법 실패), ddl-auto의 ALTER ADD UNIQUE 경로에서도 성립.
--   JPA 매핑: @Column(insertable=false, updatable=false, columnDefinition="...")
-- 재업로드는 새 행 INSERT + 구 행에 superseded_by_id 기록(UPDATE 금지 — 차단 원본·감사 연결 보존).
-- idx_proof_phash는 정확 일치용. 해밍 거리 비교는 member_id로 좁힌 뒤 애플리케이션에서 계산한다.

CREATE TABLE proof_media (
    proof_id                BIGINT       NOT NULL,
    storage_key             VARCHAR(500) NOT NULL,   -- {groupId}/{proofId}/{uuid}.{ext}
    content_type            VARCHAR(50)  NOT NULL,   -- 매직바이트 검사 결과
    file_size               BIGINT       NOT NULL,
    consent_at              DATETIME(6)  NOT NULL,   -- 제출 시점 동의 스냅샷
    participant_view_end_at DATETIME(6)  NULL,       -- B1이 기록 → PF-4 종료 후 차단 판정에 사용
    delete_scheduled_at     DATETIME(6)  NOT NULL,   -- end_date + media.retention-days
    deleted_at              DATETIME(6)  NULL,
    PRIMARY KEY (proof_id),
    KEY idx_pm_delete (deleted_at, delete_scheduled_at),   -- B5
    CONSTRAINT fk_pm_proof FOREIGN KEY (proof_id) REFERENCES proof(id)
);
-- legal hold(B5 보류) 조건과 해제:
--   보류 = 미처리 신고 케이스의 대상 이거나 ai_status=BLOCKED(미복구)
--   해제 = 케이스 종결 + retention-days 경과 / BLOCKED 확정 + retention-days 경과
--   (무기한 보존 금지 — 가장 민감한 이미지만 영구 잔존하는 상태를 막는다)
-- 탈퇴 시 B4가 해당 회원의 delete_scheduled_at을 탈퇴 확정일로 앞당긴다.

CREATE TABLE ai_judgment (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    type        VARCHAR(20)  NOT NULL,   -- AiJudgmentType (FACE 포함)
    target_type VARCHAR(20)  NOT NULL,   -- AiTargetType: PROOF | MEMBER | GROUP
    target_id   BIGINT       NOT NULL,
    verdict     VARCHAR(20)  NOT NULL,   -- AiVerdict
    confidence  DECIMAL(4,3) NULL,
    risk_types  VARCHAR(100) NULL,       -- 탐지된 위험 유형 CSV (선정·폭력·개인정보·폭언·협박·스팸)
    reason      VARCHAR(200) NULL,
    judged_at   DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_aj_target (target_type, target_id)
);
-- target 규약: 대상 행이 아직 없는 판정은 target_type='MEMBER', target_id=member_id로 기록한다.
--   해당: 얼굴 선검사(proof 미저장), 신고 detail 검열, 게시글 소감 검열.
-- confidence·risk_types·reason은 AD-2 응답으로 관리자에게만 노출한다(write-only 방지).

CREATE TABLE ai_review_queue (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    type        VARCHAR(20) NOT NULL,   -- AiReviewType
    target_id   BIGINT      NOT NULL,   -- proof.id | challenge_group.id
    member_id   BIGINT      NULL,       -- COMPLETION 개인 경계 건의 대상. 그룹 경계는 NULL
    member_key  BIGINT GENERATED ALWAYS AS (COALESCE(member_id, 0)),
    judgment_id BIGINT      NOT NULL,   -- B1도 COMPLETION 판정을 ai_judgment에 먼저 INSERT한 뒤 적재
    status      VARCHAR(20) NOT NULL,
    admin_id    BIGINT      NULL,
    decided_at  DATETIME(6) NULL,
    created_at  DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_arq (type, target_id, member_key),   -- B1 재실행 멱등(judgment_id는 키에서 제외)
    KEY idx_arq_status (status, type, created_at)
);
```

## 4. 상호작용·신고·제재

```sql
CREATE TABLE sticker_reaction (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    proof_id     BIGINT      NOT NULL,
    member_id    BIGINT      NOT NULL,
    sticker_type VARCHAR(20) NOT NULL,
    reacted_at   DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_sr (proof_id, member_id, sticker_type),   -- 토글: 취소는 행 삭제
    CONSTRAINT fk_sr_proof FOREIGN KEY (proof_id) REFERENCES proof(id)
);

CREATE TABLE report_case (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    target_type        VARCHAR(10)  NOT NULL,   -- ReportTargetType (POST 포함)
    target_id          BIGINT       NOT NULL,
    open_target_id     BIGINT       NULL,       -- PENDING일 때만 target_id, 종결 시 NULL
    group_id           BIGINT       NULL,       -- POST는 게시글의 group_id (NULL 허용)
    target_nickname    VARCHAR(30)  NOT NULL,   -- 신고 시점 표시명 스냅샷
    severity           VARCHAR(10)  NOT NULL,
    status             VARCHAR(20)  NOT NULL,
    sla_due_at         DATETIME(6)  NOT NULL,
    overdue            TINYINT(1)   NOT NULL DEFAULT 0,   -- B3
    restriction_review TINYINT(1)   NOT NULL DEFAULT 0,   -- B3 (HIGH 초과 시 검토 플래그)
    received_at        DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_rc_open (target_type, open_target_id),  -- 대상당 미처리 케이스 1건
    KEY idx_rc_console (status, severity, overdue, sla_due_at)   -- AD-1 필터·B3
);
-- 종결 케이스는 재오픈하지 않는다(재검토는 새 케이스). 재오픈하면 uk_rc_open 충돌 경로가 되살아난다.
-- 병합 시 더 높은 severity가 들어오면 케이스를 상향하고 sla_due_at을 재계산한다.

CREATE TABLE report (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    case_id     BIGINT       NOT NULL,
    reporter_id BIGINT       NOT NULL,
    reason_code VARCHAR(30)  NOT NULL,
    detail      VARCHAR(500) NULL,       -- 텍스트 검열 통과분만 저장(위험 판정 시 제거하고 접수는 진행)
    received_at DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_report (case_id, reporter_id),
    CONSTRAINT fk_rp_case     FOREIGN KEY (case_id) REFERENCES report_case(id),
    CONSTRAINT fk_rp_reporter FOREIGN KEY (reporter_id) REFERENCES member(id)
);
-- 외부 식별자는 case_id로 통일한다. report.id는 노출하지 않는다.

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

CREATE TABLE sanction (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    member_id  BIGINT      NOT NULL,
    case_id    BIGINT      NULL,
    type       VARCHAR(20) NOT NULL,
    starts_at  DATETIME(6) NOT NULL,
    ends_at    DATETIME(6) NULL,        -- TEMP만
    admin_id   BIGINT      NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_sanction_member (member_id, starts_at, ends_at)
);
-- 유효 제재 = starts_at <= now AND (ends_at IS NULL OR ends_at > now)

CREATE TABLE media_access_log (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    admin_id      BIGINT       NOT NULL,
    proof_id      BIGINT       NOT NULL,
    case_id       BIGINT       NULL,     -- 신고 없는 자동 차단 건 열람은 NULL
    access_reason VARCHAR(30)  NOT NULL, -- REPORT_REVIEW | CENSORSHIP_QUEUE | AI_REVIEW
    accessed_at   DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_mal_proof (proof_id, accessed_at)
);
-- case_id를 NOT NULL로 두면 AI 자동 차단 건을 관리자가 볼 수 없어 오탐 구제가 불가능해진다.
```

## 5. 리포트·게시판

```sql
CREATE TABLE final_report (
    id                    BIGINT       NOT NULL AUTO_INCREMENT,
    group_id              BIGINT       NOT NULL,
    member_id             BIGINT       NOT NULL,
    proved_days           INT          NOT NULL,
    total_days            INT          NOT NULL,   -- period_days 스냅샷(분모)
    proof_rate            DECIMAL(5,4) NOT NULL,
    personal_met          TINYINT(1)   NOT NULL,
    group_avg_rate        DECIMAL(5,4) NOT NULL,   -- COMPLETED 0명이면 0.0000
    group_met             TINYINT(1)   NOT NULL,
    completed             TINYINT(1)   NOT NULL,
    criteria_personal_rate DECIMAL(5,4) NOT NULL,  -- 판정 당시 기준 스냅샷
    criteria_group_rate    DECIMAL(5,4) NOT NULL,
    decided_by            VARCHAR(20)  NOT NULL,   -- AI | ADMIN
    calculated_at         DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_fr (group_id, member_id),
    CONSTRAINT fk_fr_group  FOREIGN KEY (group_id) REFERENCES challenge_group(id),
    CONSTRAINT fk_fr_member FOREIGN KEY (member_id) REFERENCES member(id)
);
-- badgeCode는 저장하지 않는다 — proof_rate·completed에서 서버가 계산하는 파생값(API §0-5).
-- 생성 대상 = COMPLETED + REPORT_EXIT. LEFT는 생성하지 않는다.

CREATE TABLE completion_stats (
    group_id           BIGINT      NOT NULL,
    ended_member_count INT         NOT NULL,   -- 리포트 생성 대상 수(COMPLETED + REPORT_EXIT)
    completed_count    INT         NOT NULL,   -- 그중 이중 기준 충족자 (분자 ⊆ 분모)
    aggregated_at      DATETIME(6) NOT NULL,
    PRIMARY KEY (group_id),
    CONSTRAINT fk_cs_group FOREIGN KEY (group_id) REFERENCES challenge_group(id)
);

CREATE TABLE challenge_post (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    member_id           BIGINT       NOT NULL,
    group_id            BIGINT       NOT NULL,
    final_report_id     BIGINT       NOT NULL,
    author_alias        VARCHAR(30)  NOT NULL,   -- 게시 시점 생성 별칭(member.nickname과 분리)
    comment             VARCHAR(200) NULL,       -- 정규식 선차단 + AI 검열 통과분
    category            VARCHAR(20)  NOT NULL,   -- challenge_group.category 복사(final_report에는 없는 값)
    period_days         INT          NOT NULL,   -- 이하 4개는 서버가 final_report에서 복사(period_days ← final_report.total_days)
    proved_days         INT          NOT NULL,
    proof_rate          DECIMAL(5,4) NOT NULL,
    completed           TINYINT(1)   NOT NULL,
    status              VARCHAR(20)  NOT NULL,   -- PostStatus
    hidden_by_case_id   BIGINT       NULL,
    hidden_at           DATETIME(6)  NULL,
    created_at          DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_post_member_group (member_id, group_id),   -- 그룹당 1건(삭제 후 재작성 불가)
    KEY idx_post_list (status, created_at),
    CONSTRAINT fk_cp_member FOREIGN KEY (member_id) REFERENCES member(id),
    CONSTRAINT fk_cp_group  FOREIGN KEY (group_id) REFERENCES challenge_group(id),
    CONSTRAINT fk_cp_report FOREIGN KEY (final_report_id) REFERENCES final_report(id)
);
-- author_alias를 분리하는 이유: member.nickname을 그대로 쓰면 (분야·기간·작성일)이 같은 글 최대 6건을
--   묶어 외부인이 6인 그룹 구성원과 각자의 인증률을 재구성할 수 있고, 신고 후 퇴장한 사람이 추적된다.
--   같은 이유로 PB-2 목록은 시각을 일 단위로만 노출한다.
-- B6는 final_report 정정 시 이 스냅샷 5컬럼도 함께 갱신한다(위조 불가 원칙 유지).

CREATE TABLE post_like (
    id        BIGINT      NOT NULL AUTO_INCREMENT,
    post_id   BIGINT      NOT NULL,
    member_id BIGINT      NOT NULL,
    liked_at  DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_post_like (post_id, member_id),
    CONSTRAINT fk_pl_post FOREIGN KEY (post_id) REFERENCES challenge_post(id)
);
```

## 6. 이력 보존 (읽기 전용)

```sql
CREATE TABLE kick_history (
    id BIGINT NOT NULL AUTO_INCREMENT, group_id BIGINT NOT NULL, member_id BIGINT NOT NULL,
    admin_id BIGINT NOT NULL, reason VARCHAR(100) NOT NULL, kicked_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id)
);
CREATE TABLE fill_in_history (
    id BIGINT NOT NULL AUTO_INCREMENT, group_id BIGINT NOT NULL, member_id BIGINT NOT NULL,
    admin_id BIGINT NULL, reason VARCHAR(100) NOT NULL, assigned_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id)
);
-- v1 기능(강제 퇴장·공석 충원)이 폐지되어 쓰기 주체가 없다. 과거 데이터 보존용으로만 유지하며
-- 신규 구현 대상이 아니다. v1 신규 배포라면 생성하지 않아도 된다.
```

---

## 테이블 목록 (22 + 이력 2)

member · media_consent · blocked_social_hash · match_lock · challenge_group · match_request · group_member · match_event · proof · proof_media · ai_judgment · ai_review_queue · sticker_reaction · report_case · report · report_history · sanction · media_access_log · final_report · completion_stats · challenge_post · post_like  (+ kick_history · fill_in_history 이력)

## UNIQUE 제약과 그 의미

| 제약 | 강제하는 규칙 | 위반 시 응답 |
|---|---|---|
| `uk_member_provider` | 소셜 계정당 회원 1명 | 로그인 경로로 흡수 |
| `uk_mr_active` | 회원당 활성 매칭 요청 1건 → 이중 그룹 배정 차단 | 409 `DUPLICATE_MATCH_REQUEST` |
| `uk_gm` | 같은 그룹 재입장 없음 | 서비스에서 사전 차단 |
| `uk_proof_daily` (+`daily_slot`) | 하루 유효 인증 1건. SCREENING·HOLD·BLOCKED는 슬롯 미점유 | 409 `DUPLICATE_DAILY_PROOF` |
| `uk_arq` | B1 재실행 멱등 | 무시(이미 적재됨) |
| `uk_sr` / `uk_post_like` | 토글 동시 중복 방어 | 재조회 후 현재 상태 반환 |
| `uk_rc_open` | 대상당 미처리 신고 케이스 1건 | 기존 케이스에 병합 |
| `uk_report` | 같은 케이스 중복 신고 방지 | 409 `DUPLICATE_REPORT` |
| `uk_fr` | 회원·그룹당 리포트 1건 | upsert |
| `uk_post_member_group` | 그룹당 게시글 1건 | 409 `DUPLICATE_POST` |

**모든 UNIQUE 위반은 500이 아니라 위 응답으로 매핑한다.** `DataIntegrityViolationException`을 잡아 제약명으로 분기.

## 인덱스 ↔ 쿼리

| 인덱스 | 쓰는 곳 |
|---|---|
| `idx_mr_queue` | MT-1 대기열 탐색(조건 완전 일치 + 선착순 정렬) |
| `idx_mr_expire` | B2 |
| `idx_cg_batch` | B1 |
| `idx_gm_member` | 활성 멤버십 검증, GR-1 |
| `idx_proof_date` | PF-3, DB-1, B1 집계 |
| `idx_proof_phash` | 진위 재사용 대조(member로 좁힌 뒤 앱에서 해밍 계산) |
| `idx_pm_delete` | B5 |
| `idx_arq_status` | AD-7 |
| `idx_rc_console` | AD-1 필터, B3 |
| `idx_rh_case` | AD-2 이력 |
| `idx_sanction_member` | 전역 인터셉터 제재 검사 |
| `idx_post_list` | PB-2 |
| `idx_me_type` | 지표 집계 |
| `idx_member_withdraw` | B4 |

## 개발(H2) 주의

- 생성 컬럼은 `CASE WHEN` 형식만 동작(`IF()`는 문법 실패). `ddl-auto=update`의 `ALTER ADD UNIQUE` 경로에서도 성립 — 실측 확인
- URL: `jdbc:h2:mem:morak;MODE=MySQL;LOCK_TIMEOUT=3000` (기본 잠금 타임아웃 약 2초)
- 행 잠금은 H2에서도 정상 동작한다(차단 후 타임아웃 확인). 다만 미존재 행은 갭 락이 없으므로 `match_lock` 시드가 필수
