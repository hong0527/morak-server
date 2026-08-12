# 모락 백엔드 구현 파이프라인 v3 — 통합 정본 (2026-08-11)

> 자기완결 문서. 계약은 `API명세서_모락_v1.0.md`, 컬럼은 `DB스키마_모락_v1.0.md`.
> 전제: 화수 혼자 백엔드 전체 구현. Spring Boot 4.1 · Java 21 · Gradle · JPA · H2(개발) / MySQL(운영).
> 진행 원칙: **한 단계 = 한 커밋. 게이트(curl 실측) 통과 없이 다음 단계 금지.** 게이트는 그 단계 산출물만으로 실행 가능해야 한다.
> 강의 진행: ①왜 필요한가 → ②새 개념(비유) → ③전체 코드 → ④줄별 설명 → ⑤화수 타이핑 후 리뷰 → ⑥curl 게이트.

##  착수 전 팀 확정 (API 명세서 최상단 T1-1~T1-5)
신고자 처리 / startDate / 시험날짜 방식 / 이중 기준 / 매칭 완화. **T1-2는 2단계, T1-1·T1-4는 8~9단계 전까지** 필요.

##  Spring Boot 4.1 주의 (실측 — 인터넷의 3.x 자료와 다름)
1. **Jackson 3가 기본** — `com.fasterxml.jackson.*` 패키지 없음(`tools.jackson.*`). 3.x 강의의 ObjectMapper import는 컴파일 불가
2. **JWT는 `jjwt-gson`** — `jjwt-jackson`은 Jackson 2를 끌고 와 충돌
3. **`@MockBean` 삭제됨** → `@MockitoBean`
4. `spring.mvc.throw-exception-if-no-handler-found`는 죽은 키(제거). 없는 URL 404는 `NoResourceFoundException` 처리로 이미 동작
5. 스타터 명칭 `web`→`webmvc`, H2 콘솔은 별도 모듈. 인터셉터·`@Scheduled`·multipart·JPA는 3.x와 동일(`@EnableScheduling` 필요)

## 배치 실행 규약
B1~B7 전부 `@Scheduled` + 수동 트리거 `POST /api/dev/batches/{name}` 쌍으로 구현한다. 게이트에서 "배치 트리거"는 이 엔드포인트 호출을 뜻한다.

## 개발 전용 엔드포인트 (DEV-1~4)
활성 조건은 **`@Profile("dev")` AND `morak.dev.enabled=true`** 이중 스위치. 11단계 게이트에서 운영 프로필 기동 후 4경로 404를 실측한다.

---

## 0단계  완료
프로젝트 뼈대 · `GlobalExceptionHandler`+공통 에러 포맷 · enum 11종. **게이트 통과 실측 완료**(없는 URL → `{"error":{"code":"ENDPOINT_NOT_FOUND",...}}` 404).

## 0.5단계 — 정정 패치 (다음 작업, 약 30분)
- **enum 교체·신설**: `ProofStatus`→`ProofAiStatus{SCREENING,APPROVED,PENDING_REVIEW,HOLD,BLOCKED}` / `GroupMemberStatus{ACTIVE,LEFT,REPORT_EXIT,COMPLETED}` / `ReportStatus{PENDING,RESOLVED,REJECTED,SANCTIONED}` / `MatchRequestStatus`에 `EXPIRED` 추가
- **신설 enum**: `MemberRole` `MemberStatus` `AgeVerification` `MatchEventType` `LeftReason`(6값 — SANCTION 포함) `ReportSeverity` `SanctionType` `PostStatus` `AiJudgmentType` `AiTargetType` `AiVerdict` `AiReviewType` `AiReviewStatus` / `ReportTargetType`에 `POST` 추가
- **ErrorCode 재작성** — API 명세서 §6 표를 그대로 옮긴다(코드·status·메시지)
- **`application.yml` `morak.*` 재작성** — API 명세서 §0-6 전체를 그대로. 폐기 2키(`completion.personal-streak-days`·`fill-in.ack-expire-hours`) 제거, 죽은 키 `spring.mvc.throw-exception-if-no-handler-found` 제거
- **게이트**: 빌드 성공 + **`ErrorCode` enum ↔ API 명세서 §6 표를 diff** — **단 `REPORT_NOT_READY` 1건은 제외**(202 정상 응답이라 예외로 던지지 않으므로 표에는 있고 enum에는 없다. 이 예외를 안 적으면 diff가 항상 1건 불일치로 게이트가 영원히 실패) + yml 키 목록 대조(`morak.security.*` 2키 포함) + `spring.datasource.url`에 `;LOCK_TIMEOUT=3000` 확인

## 1단계 — 회원·연령검증·JWT (F: v4 1-1~1-3)
- **만들 것**: `Member`·`MemberRepository` / `MatchLock` 엔티티·Repository(**여기서 만든다** — 회원 생성 시 `member:{id}` 행 동반 INSERT) / **`Sanction` 엔티티·Repository(여기서 만든다 — 인터셉터 ④와 AU-2 응답이 읽어야 한다. 적용 로직 AD-3·AD-4만 9단계)** / JWT 발급·검증 / 전역 인터셉터 5항(§0-2) / `@LoginMemberId` / 가변 `Clock` 빈 / AU-2 · AU-3 · DEV-1 · DEV-2
- **환경변수 3종 선행**: `MORAK_JWT_SECRET`(32자+)·`MORAK_MEDIA_TOKEN_SECRET`·`MORAK_SOCIAL_HASH_PEPPER` — 기본값이 없어 **미설정 시 기동 자체가 실패**한다. 0.5단계에서 dev 프로필 기본값을 넣거나 export 해둘 것
- **확정 결정** (학생이 스스로 못 고르는 것들 — 여기 박아둠):
  - `io.jsonwebtoken:jjwt-api:0.12.6` + `jjwt-impl:0.12.6`(runtimeOnly) + `jjwt-gson:0.12.6`(runtimeOnly), HS256
  - `morak.jwt.secret`은 기본값 없는 환경변수(32자 이상 — 미만이면 `WeakKeyException`)
  - 인터셉터 제외: `/api/auth/**`, `/api/dev/**`, `/h2-console/**`, `/error`. 연령·제재 대상은 **엔드포인트 화이트리스트 상수**로 관리(경로 접두사로는 못 가른다 — GR-1·PF-1·AU-4가 전부 `/api/members/me/**`)
  - DEV-1: `provider='DEV'`, `provider_user_id=nickname` upsert. **`role` 파라미터 없음**(관리자는 DB 수동 UPDATE)
  - 닉네임: SNS 값 저장 안 함, 항상 `익명 {동물명}{2자리}` 생성
  - `Clock` 빈 경유 필수 — 4단계 마감 게이트를 재현할 유일한 수단
- **새 개념**: JWT(위조 불가 출입증) · 인터셉터(모든 요청의 검문소) · 만 나이(Period) · Clock 주입(시간을 코드로 조종)
- **게이트**: dev-login 2명 → `/members/me` 200 + 닉네임이 "익명 ○○" + **`match_lock`에 member 행 2개** / 토큰 없이 401 / **참여자 토큰으로 `/api/admin/reports` → 403 `FORBIDDEN_ROLE`** / AU-3 2013년생 → UNDER_AGE, 재호출 409 `ALREADY_VERIFIED`

## 2단계 — 매칭 엔진 (F: v4 2-1~2-3) — 최고 난도
- **만들 것**: `match_request`·`challenge_group`·`group_member`·`match_event` 엔티티 / MT-1 · MT-2 · MT-3 / B2 만료 배치 / DEV-4(배치 트리거) / `match_lock` 조건 행 72개 `ApplicationRunner` 시드
- **잠금 구현**(API §4 MT-1 절차 그대로): 회원 행 잠금 → 검증 → 조건 행 잠금 → INSERT → 선착순 6건 → **영향 행 수 = 6 검증** → 그룹 생성. 전체가 하나의 `@Transactional`, 해제는 커밋 시 자동
- **함정**: ① 잠금 행을 런타임에 만들지 말 것(갭 락 없음 → INSERT 경합) ② 자기 포함 계산(5명 대기+나=6) ③ **MT-3·B2에서도 `active_member_id=NULL`**(누락 시 재요청 영구 차단) ④ 7건 이상일 때 정확히 6건만
- **게이트**: 6명 순차 → 6번째 MATCHED / **H2 6스레드 동시 요청 → 정확히 6인**(MySQL 실측은 11단계) / 같은 회원 재요청 409 / **MT-3 취소 → 동일 회원 재요청 201** / `expires_at` 과거 세팅 → B2 트리거 → EXPIRED + **재요청 201** / 미검증 회원 MT-1 403

## 3단계 — 그룹 조회·자율 퇴장 (F: v4 3-3 일부·4-1)
- 만들 것: GR-1 · GR-2 · GR-3
- 함정: GR-2의 `memberCount`는 **ACTIVE 수 단일 정의**. 진행 중/종료 그룹의 members 집합이 다름(API §4)
- 게이트: 비멤버 403 / 퇴장 → GR-2 403 + **매칭은 다시 가능**(활성 멤버십 소멸) / 사유 없이 400

## 4단계 — 인증 제출 (F: v4 3-1) — AI는 스텁
- 만들 것: `proof`(daily_slot 생성 컬럼)·`proof_media`·`ai_judgment`·`ai_review_queue` 엔티티 / PF-1 · PF-2 / `AiClient` 인터페이스 4메서드 + 전부 통과 스텁 / B7 회수 배치
- **PF-2 실행 순서**(API §4 그대로): 검증 → **얼굴 선검사(파일·DB 쓰기 이전)** → tx1(proof SCREENING + flush + 파일 + media) → tx 밖 AI → tx2(조건부 UPDATE) → 응답 변환은 tx2 커밋 후
- 함정: ① 마감 판정은 접수 시각 ② SCREENING은 슬롯 미점유(그래서 AI 장애가 그날을 잠그지 않음) ③ 재업로드는 **새 행 INSERT + `superseded_by_id`**(UPDATE 금지) ④ `daily_slot`은 H2에서도 생성 컬럼 사용, JPA는 `insertable=false, updatable=false`
- 게이트: startDate 도래시킨 뒤 201 APPROVED / 재제출 409 / **DEV-2로 새벽 시각 조작 → 400 `PROOF_DEADLINE_PASSED`** / 스텁 조작해 검열 위험 → 422 + BLOCKED 저장 + **재업로드 시 proof 행 2개·구 행 BLOCKED 유지·`superseded_by_id` 확인** / 스텁 조작해 얼굴 감지 → 422 + **proof 행 0건·파일 0건**

## 5단계 — 인증 확인·열람·스티커 (F: v4 3-1 공개 규칙·3-2)
- 만들 것: PF-3 · PF-4 · PF-4r(HMAC 서명 토큰) · ST-1 · ST-2
- 함정: 타인에게는 APPROVED만 노출 / **raw 경로에도 동일 자격 검증을 다시 적용**(URL을 알아도 권한 없으면 403) / 스티커 대상이 APPROVED 아니면 404
- 게이트: A 인증 → B 조회·스티커 ON(201) → 재요청 OFF(200)·카운트 감소 / HOLD 인증은 B에게 안 보임 / **타 그룹 회원 토큰으로 raw URL 직접 호출 → 403** / **만료 토큰 → 403**

## 6단계 — 대시보드 (F: v4 3-3)
- 만들 것: DB-1 / **DEV-3(과거 일자 인증 시드)** — PF-2는 당일 1건만 받으므로 이게 없으면 이 게이트를 만들 데이터가 없다
- 함정: 분모는 `periodDays` 고정(경과 일수 아님). 순위 필드 없음
- 게이트: 시작 직후 0.0 / **DEV-3로 15일 시드한 30일 챌린지 → 0.5**

## 7단계 — AI 실물 (F: v4 7-1·7-2)
- 만들 것: 얼굴 탐지 실물 / 이미지 검열 실물(클라우드 moderation) / 진위 실물(pHash + 동일 회원 대조, 관련성은 shadow) / 텍스트 검열 실물 / BLOCKED → CENSORSHIP 큐 적재
- 새 개념: perceptual hash(사진의 지문) · 해밍 거리 · shadow 모드(차단 없이 데이터만 모으는 안전 운전)
- 함정: **모든 AI 실패는 fail-closed**(502, 몰래 통과 금지) / 관련성은 로깅만
- 게이트: 유해 샘플 → BLOCKED + **`ai_review_queue`에 CENSORSHIP·PENDING 행 생성** / 같은 사진 2일 연속 → 2번째 HOLD / **리사이즈한 같은 사진 → HOLD**(정확 일치만 잡으면 게이트는 녹색인데 기능은 무력) / 얼굴 사진 → 422 + 미저장 / 관련성 낮은 사진 → 통과 + 점수 로깅

## 8단계 — 종료·완주 판정 (F: v4 5-1·5-2·7-3)
- 만들 것: **B1**(매일 00:05, `end_date < 오늘` 대상 — 종료일 인증 06:00~23:59를 놓치지 않게. B7 선행) / FR-1 / AD-7 / B6 / `final_report`·`completion_stats`
- B1 절차: ①대상 선정 ②ENDED ③잔류 ACTIVE→COMPLETED ④`participant_view_end_at` 기록 ⑤**그룹 평균 계산 → 경계면 그룹 단위 1건만 큐 적재하고 개인 판정 중단** ⑥개인 판정 ⑦`ai_judgment(COMPLETION)` INSERT 후 리포트 생성
- 함정: **B1·B6 멱등** / COMPLETED 0명이면 `group_avg_rate=0.0000`·미충족 / HOLD·PENDING_REVIEW는 미인증 취급, 사후 승인 시 B6가 정정
- 게이트: 손계산 대조 / 경계 케이스 → 큐 적재·리포트 보류(202) / **AD-7로 그룹 경계 확정(PATCH) → 같은 그룹 `final_report` N건이 그 자리에서 생성되는지 확인**(B1은 다시 오지 않으므로 재개 주체가 없으면 영구 202) / 완주자 재매칭 201 / **REPORT_EXIT 케이스는 9단계 후 재실측**

## 9단계 — 신고·안전·제재 (F: v4 4-2·6-1·6-2)
- 만들 것: `report_case`·`report`·`report_history`·`sanction`·`media_access_log` / RP-1 / AD-1~AD-6 / B3 SLA 배치 / **제재 적용 단일 서비스 메서드**(sanction + membership LEFT(SANCTION) + 매칭 요청 취소)
- 함정: ① 케이스 병합 + severity 상향 시 `sla_due_at` 재계산 ② `targetType=POST`는 그룹 자격 검사 면제·`group_id` NULL 허용 ③ AD-3는 PENDING만·재오픈 불가 ④ AD-5 열람 조건 3종(신고 대상 / BLOCKED+큐 PENDING / AD-7 대상)
- 게이트: 신고 접수 → 케이스 생성 / 두 번째 신고자 병합 / `sla_due_at` 과거 → B3 → `overdue` + AD-1 필터 노출 / **참여자 토큰으로 AD-1~AD-7 각각 403** / 제재 → 로그인 403 + **진행 중 membership이 LEFT(SANCTION)로 전이되어 그룹 평균 분모에서 제외** / **T1-1 확정 후** 신고자 처리 실측

## 9.5단계 — 완주 자랑 게시판·공유 (F: v4 8-1~8-4)
- 만들 것: `challenge_post`·`post_like` / PB-1~PB-5 / **소감 정규식 선차단**(전화번호·카톡ID·`@핸들`·URL·이메일) / AD-6 게시글 hide·unhide / `ReportTargetType.POST` 분기
- 함정: ① 수치는 **서버가 `final_report`에서 복사** ② 인증 사진은 게시 대상 아님 ③ `author_alias`는 게시 시점 생성(member.nickname 재사용 금지) ④ 목록 시각은 일 단위
- 게이트: 리포트 보유자 작성 201 / 재작성 409 / 진행 중 그룹 409 / **저장된 `proofRate`가 `final_report`와 일치하는지 DB 확인** / 좋아요 ON→OFF / hide → 목록에서 사라지고 **PB-3 직접 호출도 404** / **소감에 "인스타 @abc" → 422** / **미성년 토큰: PB-2 200 · PB-1 403 · 신고 201**
- 공유: 백엔드 작업 없음(PB-3 데이터로 프론트가 이미지 카드 생성)

## 10단계 — 탈퇴 (F: v4 6-3)
- 만들 것: AU-4 · AU-5 · AD-8 / **B4**(익명화 + 동반 처리 4종: `blocked_social_hash` 등재(PERMANENT 제재 이력자만) · `challenge_post` DELETED · `proof_media.delete_scheduled_at` 앞당김 · `media_consent` 삭제) / **B5 미디어 삭제 배치**(legal hold 해제 규칙 포함) / `blocked_social_hash` 엔티티
- 게이트: 신청 → WITHDRAW_PENDING + 참여 API 403 / 재로그인 → 복구 / 예정일 과거 → B4 → **익명화 확인**(컬럼 NULL 아님) / 제재 이력자 탈퇴 후 같은 계정 로그인 → **403 `REJOIN_BLOCKED`** / B5 → 예정일 지난 미디어만 삭제, hold 대상은 잔존

## 11단계 — 운영 준비
- AU-1 카카오 실연동 / 로컬→S3 전환(PF-4 응답 계약 불변) / MySQL 전환 + **동시성 재실측** / CORS·배포(Cloudtype → AWS)
- 게이트: 카카오 실계정 e2e / **운영 프로필 기동 후 DEV-1~4 각각 404**

---

## 컷 라인 (일정 압박 시 —  명세 위반 표기 필수)
1. AI 관련성 점수(shadow조차 제외 — v4 7-1 부분 미충족) → 2. 예상 대기 시간 → 3. AD-8 → 4. 소셜 카카오 외 3종(v4 1-1 부분 충족) → 5. 9.5단계 게시판 전체(v4 영역 8 미구현 — 팀장 요구라 승인 필수)
**못 버리는 것**: 2단계 매칭 / 4·5·7단계 인증·AI / 8단계 완주 판정 / 9단계 신고·제재 / 10단계 B4·B5(법적) / 1단계 연령 검증(법적)

## 우선순위
① 매칭 엔진(2) — 동시성 최고 난도 ② 인증+AI(4·5·7) — 서비스 신뢰의 심장 ③ 완주 판정(8) — 지표 직결. 나머지는 표준 CRUD+배치.
