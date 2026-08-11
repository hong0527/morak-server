# morak-server

6인 랜덤 매칭 목표 챌린지 서비스 '모락'의 백엔드 서버입니다.

분야·목표시간·기간 세 가지 조건만 고르면 같은 조건의 6명이 자동으로 묶이고,
매일 사진 한 장으로 인증하며 기간을 완주하는 서비스입니다.
채팅은 없고 스티커로만 서로 응원합니다.

## 기술 스택

- Java 21, Spring Boot 4.1
- Spring Data JPA
- H2 (개발) / MySQL (운영)
- Gradle

## 실행 방법

```bash
./gradlew bootRun
```

기본 포트는 8080입니다. H2 콘솔은 http://localhost:8080/h2-console 에서 접속할 수 있고,
JDBC URL은 `jdbc:h2:mem:morak` 입니다.

정상 실행됐는지는 없는 경로를 호출해 보면 확인됩니다.

```bash
curl http://localhost:8080/api/ping
# {"error":{"code":"ENDPOINT_NOT_FOUND","message":"존재하지 않는 경로입니다.","details":{}}}
```

## 환경 변수

개발 환경에서는 기본값이 들어 있어 설정하지 않아도 실행됩니다.
운영 배포 시에는 아래 값을 반드시 주입해야 합니다.

| 변수 | 용도 |
| --- | --- |
| `MORAK_JWT_SECRET` | JWT 서명 키 |
| `MORAK_MEDIA_TOKEN_SECRET` | 인증 사진 열람 토큰 서명 키 |
| `MORAK_SOCIAL_HASH_PEPPER` | 소셜 식별자 해시용 pepper |
| `MORAK_GEMINI_API_KEY` | 이미지 판정 (7단계부터) |
| `MORAK_OPENAI_API_KEY` | 텍스트 검열 (7단계부터) |

## 프로젝트 구조

도메인별로 패키지를 나누고, 각 도메인 안에서 계층을 나눴습니다.

```
com.morak
├── common          공통 설정, 예외 처리, 공통 타입
├── auth            로그인, JWT, 소셜 연동
├── member          회원, 연령 확인, 탈퇴
├── match           매칭 요청, 그룹 편성
├── group           챌린지 그룹, 참여자
├── proof           일일 인증 제출·조회
├── reaction        응원 스티커
├── report          신고, 제재
├── post            완주 자랑 게시판
├── ai              이미지·텍스트 판정
├── media           파일 저장, 열람 토큰
├── batch           스케줄러
└── admin           운영자 API
```

각 도메인 패키지는 `controller`, `service`, `repository`, `entity`, `dto`, `type` 으로 구성합니다.
`dto`는 `request`와 `response`로 다시 나눕니다.

## 문서

| 문서 | 내용 |
| --- | --- |
| [docs/api-spec.md](docs/api-spec.md) | API 명세. 엔드포인트, 요청·응답, 에러 코드 |
| [docs/db-schema.md](docs/db-schema.md) | 테이블 DDL, 인덱스, 제약 조건 |
| [docs/functional-spec.md](docs/functional-spec.md) | 기능 명세 |
| [docs/git-convention.md](docs/git-convention.md) | 브랜치, 커밋, PR 규칙 |
| [docs/open-decisions.md](docs/open-decisions.md) | 아직 확정되지 않은 정책 |
| [docs/implementation-plan.md](docs/implementation-plan.md) | 구현 순서 |
| [docs/screen-api-map.md](docs/screen-api-map.md) | 화면별 호출 API |
| [docs/frontend-change-requests.md](docs/frontend-change-requests.md) | 프론트 전달 사항 |

프론트엔드는 API 명세와 화면-API 매핑 두 개를 보시면 됩니다.

## 진행 상황

| 범위 | 상태 |
| --- | --- |
| 프로젝트 설정, 전역 예외 처리, 공통 타입 | 완료 |
| 회원·인증 | 진행 예정 |
| 매칭 | |
| 그룹·인증 제출 | |
| AI 판정 | |
| 리포트·게시판 | |
| 신고·운영 | |
| 배치 | |

## 참고

- 에러 응답은 전부 `{"error": {"code": ..., "message": ..., "details": {...}}}` 형태입니다.
  code 값은 [api-spec.md](docs/api-spec.md)의 에러 코드 표를 참고하세요.
- 완주 기준 비율, 인증 마감 시각 같은 정책 값은 코드에 넣지 않고
  `application.yml`의 `morak.*` 아래에 모아 뒀습니다. 정책이 바뀌면 이 값만 고치면 됩니다.
