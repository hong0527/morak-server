# morak-server

시간 단일 조건 6인 자동 매칭 실시간 캠스터디 서비스 'MoLock'의 백엔드 서버입니다.

하루 목표 시간 하나만 고르면 같은 시간을 고른 6명이 자동으로 묶이고,
실시간 캠 세션에서 함께 공부합니다. 자리를 비우면 경고가 쌓이고 3회면 퇴출됩니다.
완주하면 포인트와 Streak가 쌓이고, 포인트는 스토어에서 씁니다.

패키지와 레포 이름은 `morak`을 유지합니다. 서비스 표기만 MoLock입니다.

## 기술 스택

- Java 21, Spring Boot 4.1
- Spring Data JPA
- H2 (개발) / MySQL (운영)
- LiveKit (실시간 캠 세션)
- Gradle

## 실행 방법

```bash
./gradlew bootRun
```

기본 포트는 8080입니다. 프로필을 지정하지 않으면 `dev`로 뜹니다.
H2 콘솔은 http://localhost:8080/h2-console 에서 열리고,
JDBC URL은 `jdbc:h2:mem:morak;MODE=MySQL;LOCK_TIMEOUT=3000` 입니다.

정상 실행됐는지는 없는 경로를 호출해 보면 확인됩니다.

```bash
curl http://localhost:8080/api/ping
# {"error":{"code":"ENDPOINT_NOT_FOUND","message":"존재하지 않는 경로입니다.","details":null}}
```

### 재기동 실측·시연 시 주의 두 가지

**파일 H2를 쓸 때는 URL에 `WRITE_DELAY=0`을 붙입니다.**
`jdbc:h2:file:./data/morak;MODE=MySQL;LOCK_TIMEOUT=3000;WRITE_DELAY=0` 형태입니다.
H2의 기본값은 500ms 비동기 기록이라, 그 사이에 프로세스를 죽이면 **이미 200을 돌려준
커밋이 사라집니다**. 실측에서 로그인 10건 중 7건이 소실됐습니다. 붙이지 않으면 재기동
실측의 결과 자체를 믿을 수 없습니다.

**v1 배포는 단일 인스턴스가 전제입니다.**
재접속 유예 창(D13)이 인스턴스 메모리에 있어서, 인스턴스를 둘 이상 띄우면 웹훅을 받은
쪽만 타이머를 쥡니다. 다른 쪽이 받은 재접속은 유예를 닫지 못해 멀쩡한 참가자가 이탈로
판정됩니다(실측 확인). 수평 확장은 12단계 과제이며, 유예 창을 DB로 내리는 것이 선결
조건입니다.

## 설정 파일

프로필별로 셋으로 나눠 뒀습니다.

| 파일 | 담는 것 |
| --- | --- |
| `application.yml` | 프로필 무관 공통값과 정책값. 비밀값은 환경변수 참조만 |
| `application-dev.yml` | H2 접속·콘솔·개발용 소셜 로그인·로컬 전용 비밀값 |
| `application-prod.yml` | 운영 DB·`ddl-auto: validate`·콘솔 끄기. 값은 전부 환경변수 |

공통 파일에는 비밀값의 기본값을 두지 않습니다. 공개 저장소에 커밋되는 파일이라
폴백이 있으면 운영에서 환경변수를 빠뜨려도 누구나 아는 키로 조용히 기동합니다.

## 외부 연동은 아직 스텁입니다

소셜 로그인(카카오)과 결제(토스페이먼츠) 실구현은 앱 키·테스트 키가 나오는 12단계에 넣습니다.
그때까지 두 자리는 프로필에 따라 다른 구현이 채웁니다.

| 프로필 | 소셜 로그인 | PG 승인 조회 |
| --- | --- | --- |
| `dev` (+ `morak.dev.enabled=true`) | `DevSocialClient` — 받은 인가 코드를 그대로 소셜 계정 번호로 씁니다 | `DevPgClient` — 받은 거래를 승인으로 돌려줍니다(`fail-`·`mismatch-` 접두사로 실패 경로 재현) |
| 그 외 (`prod` 포함) | `RejectingSocialClient` — 어떤 코드든 401 `INVALID_SOCIAL_TOKEN` | `RejectingPgClient` — 어떤 거래도 승인하지 않아 409 `PAYMENT_NOT_APPROVED` |

**거절하는 쪽이 기본값인 것이 핵심입니다.** 통과시키는 스텁이 운영에 남으면 인증 없는 로그인과
결제 없는 포인트 적립이 열립니다. 그래서 운영 프로필에서는 로그인과 결제가 막힌 상태로 기동하고,
그 상태가 정상입니다 — 나머지 API·배치·헬스체크는 실서버에서 그대로 확인할 수 있습니다.

## 환경 변수

개발은 `application-dev.yml`에 로컬 값이 있어 설정하지 않아도 실행됩니다.
운영 배포에는 아래 값을 주입해야 하고, 없으면 기동에 실패합니다.

| 변수 | 용도 | 필요 시점 |
| --- | --- | --- |
| `MORAK_DB_URL`·`MORAK_DB_USERNAME`·`MORAK_DB_PASSWORD` | 운영 MySQL 접속 | 운영 배포 |
| `MORAK_JWT_SECRET` | JWT 서명 키 | 현재 |
| `MORAK_SOCIAL_HASH_PEPPER` | 소셜 식별자 해시용 pepper | 현재 |
| `MORAK_LIVEKIT_HOST`·`MORAK_LIVEKIT_API_KEY`·`MORAK_LIVEKIT_API_SECRET` | LiveKit 토큰 발급·웹훅 서명 검증 | 라이브 세션 단계 |
| `MORAK_PG_SECRET_KEY` | 포인트 충전 PG 웹훅 서명 검증 | 결제 단계 |

운영 프로필로 기동만 확인할 때도 위 값이 전부 필요합니다. 폴백이 없어서 하나라도 비면
기동 자체가 실패하고, 그것이 의도한 동작입니다.

## 프로젝트 구조

도메인별로 패키지를 나누고, 각 도메인 안에서 계층을 나눴습니다.

```
com.morak
├── common          공통 설정, 예외 처리, 인증 인터셉터
├── auth            로그인, JWT 발급, 소셜 연동
├── member          회원, 약관, 연령 확인, 목표, Streak, 탈퇴
├── match           매칭 요청, 매칭 엔진, 재매칭 차단
├── session         라이브 세션, 참가자, 자리비움·경고·퇴출, 퇴출 이의, 스티커
├── point           포인트 원장, 충전, PG 웹훅
├── store           상품, 주문
├── report          신고, 제재
└── dev             개발 전용 API (시각 조작, 배치 트리거, 세션 시드)
```

각 도메인 패키지는 `controller`, `service`, `repository`, `entity`, `dto`, `type` 으로 구성합니다.
`dto`는 `request`와 `response`로 다시 나눕니다.

`batch`, `admin`, `media`, `payment`, `appeal`은 도메인이 아니라 별도 패키지를 만들지
않습니다. 배치가 하는 일은 세션 종료, 매칭 만료, 탈퇴 파기라서 각 도메인
안에 둡니다. 관리자 API도 결국 report·member·session을 조작하므로
`report/controller/ReportAdminController.java` 처럼 도메인 안에 둡니다.
포인트 충전·PG 웹훅은 point 소속, 퇴출 이의신청은 session 소속입니다.

## 문서

| 문서 | 내용 |
| --- | --- |
| [docs/project-overview.md](docs/project-overview.md) | 프로젝트 입구. 범위, 문서 지도, 진행 상태 |
| [docs/api-spec.md](docs/api-spec.md) | 계약 정본. 엔드포인트, 요청·응답, 에러 코드 |
| [docs/db-schema.md](docs/db-schema.md) | 테이블 DDL, 인덱스, 제약 조건 |
| [docs/functional-spec.md](docs/functional-spec.md) | 요구 정본 |
| [docs/implementation-plan.md](docs/implementation-plan.md) | 구현 순서와 단계별 게이트 |
| [docs/open-decisions.md](docs/open-decisions.md) | 아직 확정되지 않은 정책 |
| [docs/ai-pipeline.md](docs/ai-pipeline.md) | 자리비움 감지 파이프라인. 모델 선정, 판정 임계값, 오탐률 측정 절차 |
| [docs/git-convention.md](docs/git-convention.md) | 브랜치, 커밋, PR 규칙 |
| [docs/screen-api-map.md](docs/screen-api-map.md) | 화면별 호출 API (구판 — 새 와이어프레임 수령 후 재작성) |
| [docs/frontend-change-requests.md](docs/frontend-change-requests.md) | 프론트 전달 사항 (구판 — 새 와이어프레임 수령 후 재작성) |

프론트엔드는 API 명세를 먼저 보시면 됩니다. 화면-API 매핑은 구 모락 24화면 기준이라
지금은 참고용입니다.
`docs/openapi.yaml`은 `api-spec.md` v2.0 기준으로 재생성되어(2026-08-12) 같이 보셔도 됩니다.
두 문서가 어긋나면 `api-spec.md`가 정본입니다.

## 진행 상황

| 범위 | 상태 |
| --- | --- |
| 프로젝트 골격, 전역 예외 처리, 공통 타입 | 완료 |
| 회원·인증·연령 검증·탈퇴 | 완료 |
| enum·에러 코드 교체, 엔티티 재편 | 완료 |
| 약관·목표·Streak | 완료 |
| 매칭 | 완료 |
| 라이브 세션, 경고·퇴출 | 완료 |
| 완주 판정, 포인트 | 완료 |
| 스토어, 결제 | 완료 (PG는 개발용 스텁 기준) |
| 신고·운영, 관리자 콘솔 | 완료 |
| 운영 준비 | 진행 중 — 카카오·토스페이먼츠 실연동이 남았습니다 |

2026-08-12 기획이 MoLock으로 전환됐고, 그 뒤 12단계까지의 구현이 끝났습니다.
남은 것은 위 표의 마지막 줄(실연동)과 강의 자료 정리입니다.
단계별 상세는 [docs/implementation-plan.md](docs/implementation-plan.md),
경위와 범위는 [docs/project-overview.md](docs/project-overview.md)에 있습니다.

## 참고

- 에러 응답은 전부 `{"error": {"code": ..., "message": ..., "details": {...}}}` 형태입니다.
  code 값은 [api-spec.md](docs/api-spec.md)의 에러 코드 표를 참고하세요.
- 경고 임계 시간, 포인트 값, 매칭 만료 같은 정책 값은 코드에 넣지 않고
  `application.yml`의 `morak.*` 아래에 모아 뒀습니다. 정책이 바뀌면 이 값만 고치면 됩니다.
