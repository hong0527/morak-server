# MoLock 프론트엔드

6인 랜덤 매칭 라이브 캠스터디 서비스의 웹 프론트다.

**아직 뼈대다.** 세션 화면 하나만 끝까지 만들어 두었고 나머지는 뼈대와 할 일만 있다.
무엇이 되어 있고 무엇이 아닌지는 4절에 정직하게 적었다.

---

## 1. 시작하는 법

### 폴더 배치

서버와 감지 모듈이 **한 저장소 안에** 있다. 클론 하나로 끝난다(3절).

```
morak-server/             저장소 뿌리. 백엔드 (Spring Boot)
├── docs/                 명세 정본. openapi.yaml 을 여기서 바로 읽는다
└── frontend/             여기
    └── ai-detection/     자리비움 감지 모듈. 브라우저에서 도는 클라이언트 코드다
```

### 처음 한 번

```bash
cd ai-detection
npm install
npm run vendor        # 모델 3.76MB 를 받는다. 한 번만 하면 된다

cd ..
npm install
```

### 띄우기

```bash
# 터미널 1 — 백엔드. dev 프로필이라야 개발용 로그인과 /api/dev/** 가 열린다
cd morak-server
./gradlew bootRun --args='--server.port=8180 --spring.profiles.active=dev'

# 터미널 2 — 프론트
cd morak-server/frontend
npm run dev           # http://localhost:5173
```

`npm run dev` 는 먼저 `sync:ai` 를 돌려 감지 모듈을 빌드하고 워커·모델·wasm 을 `public/ai`
에 놓는다. 처음에는 20초쯤 걸린다.

백엔드 주소는 `vite.config.ts` 의 프록시 한 곳에만 있다. 다른 포트면
`MORAK_BACKEND=http://localhost:8130 npm run dev` 로 바꾼다. 코드에는 `/api` 상대경로만 있다.

### 로그인

**진짜 소셜 로그인이 아직 아니다.** 카카오·애플 키를 못 받아, dev 프로필에서는 입력한
문자열이 그대로 소셜 사용자 식별자가 된다. 아무 문자열이나 넣으면 그게 계정이고, 같은 값을
다시 넣으면 같은 계정으로 들어온다. 실제 SDK 를 붙일 때 바뀌는 것은 그 값 하나뿐이라
화면 코드는 그대로 간다.

### 세션 화면을 보려면 6명이 필요하다

매칭은 6명이 모여야 성사된다. 혼자 확인할 때는 계정 6개를 만들어 매칭을 태운다.

```bash
B=http://localhost:8180/api
for i in 1 2 3 4 5 6; do
  T=$(curl -s -X POST $B/auth/login -H 'Content-Type: application/json' \
    -d "{\"provider\":\"KAKAO\",\"authorizationCode\":\"u$i\",\"agreements\":[{\"type\":\"TOS\",\"agreed\":true},{\"type\":\"PRIVACY\",\"agreed\":true},{\"type\":\"MARKETING\",\"agreed\":false}]}" \
    | python3 -c 'import sys,json;print(json.load(sys.stdin)["accessToken"])')
  curl -s -o /dev/null -X POST $B/members/me/birthdate     -H "Authorization: Bearer $T" -H 'Content-Type: application/json' -d '{"birthDate":"2000-03-15"}'
  curl -s -o /dev/null -X POST $B/members/me/media-consent -H "Authorization: Bearer $T" -H 'Content-Type: application/json' -d '{"agreed":true}'
  curl -s -o /dev/null -X POST $B/match-requests           -H "Authorization: Bearer $T" -H 'Content-Type: application/json' -d '{"targetMinutes":60}'
done
```

그다음 브라우저에서 `u1` 로 로그인하면 홈이 `activeSession` 을 보고 세션 화면으로 보낸다.

`morak-server/docs/frontend-walkthrough.sh` 는 이걸 포함해 완주 결과까지 한 번에 돌린다.

### 잘 붙었는지 자동으로 확인하기

```bash
npm run smoke -- --code=u1
```

headless 크롬으로 로그인부터 세션 화면까지 실제로 밟아 본다. 카메라는 크롬이 주는 합성
영상이라 얼굴이 없다 — **확인되는 것은 "끝까지 이어진다"까지이고 감지 정확도는 아니다.**

---

## 2. 왜 이 구조인가

### Vite + React + TypeScript, 그 외에는 거의 없다

발표까지 2주다. 담당자가 도구부터 배우기 시작하면 그 시간이 화면을 만드는 시간에서 빠진다.
그래서 **상태관리 라이브러리도 UI 라이브러리도 넣지 않았다.**

| 필요한 것 | 넣은 것 | 왜 라이브러리를 안 썼나 |
|---|---|---|
| 전역 상태 | `useSyncExternalStore` + 작은 스토어(`auth/session.ts`) | 전역 상태가 토큰 하나다. Redux·Zustand 를 얹을 값이 없다 |
| 서버 상태·폴링 | `hooks/usePolling.ts` (60줄) | React Query 의 대부분이 캐시 무효화인데, 이 서비스는 폴링 두 곳이 전부다 |
| 스타일 | `styles.css` 한 장 | 디자인이 아직 안 나왔다. 지금 고르면 나올 때 두 번 고른다 |
| 라우팅 | react-router-dom | 이건 직접 쓸 값이 아니다 |
| 캠 | livekit-client | 대안이 없다 |

런타임 의존성은 넷이다 — react, react-dom, react-router-dom, livekit-client.

### API 클라이언트는 절반만 손으로 썼다

46개 오퍼레이션의 **타입은 `../docs/openapi.yaml` 에서 생성**하고(`src/api/schema.gen.ts`),
**함수는 손으로 썼다**(`src/api/client.ts`).

둘 다 손으로 쓰면 서버가 필드를 바꿨을 때 프론트만 옛 모양으로 남는데 아무도 모른다.
반대로 클라이언트까지 통째로 생성하면 `api.session.startPause(id)` 대신
`client.POST("/api/sessions/{sessionId}/pause", {...})` 를 쓰게 되고, 명세 번호(SS-5)와
그 오퍼레이션의 함정을 적어 둘 자리가 사라진다. 그 함정이 이 서버의 핵심이라 나눴다.

서버 명세가 바뀌면 이렇게 따라간다.

```bash
npm run gen:api        # ../docs/openapi.yaml 을 그대로 읽는다
npm run typecheck      # 안 맞는 화면이 여기서 걸린다
```

명세를 복사해 두지 않고 `../docs/openapi.yaml` 을 직접 읽는다. 서버와 같은 저장소에 있으니
사본을 둘 이유가 없고, 사본은 서버가 명세를 고쳤을 때 조용히 낡는다.

프론트가 부르는 것은 46개 중 **44개**다. `POST /webhooks/livekit` 과 `POST /webhooks/payment`
는 LiveKit·PG 가 서버로 직접 부르는 것이라 넣지 않았다.

---

## 3. 감지 모듈을 어떻게 뒀나

`ai-detection/` 은 브라우저에서 도는 코드다. `frontend/` 아래에 두고 npm `file:` 의존성으로
잇는다.

```json
"molock-ai-detection": "file:./ai-detection"
```

`npm install` 이 `node_modules/molock-ai-detection` 을 그 폴더로 심볼릭 링크한다.
복사본이 아니라 **원본 폴더 그 자체**다.

### 왜 복사가 아닌가

**감지 코드는 계속 튜닝될 예정이고, 튜닝 도구가 `ai-detection` 안에 있다.**

임계값을 정하는 방법이 `harness/` 의 record → replay → score → sweep 이다. 영상을 한 번
추론해 로그를 남기고 그 위에서 판정을 재계산하는 구조라, `src/scoring.ts` 와 `src/config.ts`
가 하네스와 같은 폴더에 있어야 한다. 프론트로 복사해 오면 **튜닝은 저쪽에서 하고 서비스는
이쪽 복사본이 도는** 상태가 되고, 두 벌은 반드시 어긋난다. 어긋난 것을 알아챌 방법도 없다.

지금은 `ai-detection/src/config.ts` 의 숫자를 고치고 `npm run sync:ai` 만 다시 돌리면
프론트에 그대로 반영된다. 사본이 없으니 어긋날 자리가 없다.

### 왜 submodule 이 아닌가

`git submodule update --init` 을 빠뜨린 사람이 "모델 로딩이 30초 안에 끝나지 않았다"만
보게 되는데, 그 오류가 워커 로딩 실패와 구분되지 않는다(ai-detection/README 9-9). 같은
저장소에 두면 클론만으로 끝나므로 빠뜨릴 단계 자체가 없다.

### 왜 npm 배포가 아닌가

임계값 한 줄 고칠 때마다 publish 해야 한다. 소비자가 앱 하나뿐인데 버전 관리 비용만 는다.

### 왜 서버와 같은 저장소인가

프론트를 따로 떼면 `docs/openapi.yaml` 사본을 들고 다녀야 하고, 그 사본은 서버가 명세를
고쳤을 때 조용히 낡는다. 한 저장소에 있으면 `npm run gen:api` 가 정본을 그대로 읽는다.
명세와 구현이 같은 PR 에 담기는 것도 같은 이유로 낫다.

### 자산 배치

`npm run sync:ai` 가 하는 일 셋이다.

| 무엇 | 어디로 | 왜 |
|---|---|---|
| `worker-classic.js` | `public/ai/` | **모듈 워커로는 모델이 뜨지 않는다.** esbuild 로 IIFE 한 덩이를 뽑아야 한다(9-9) |
| `face_landmarker.task` (3.76MB) | `public/ai/` | CDN 을 안 쓴다. 발표 당일 CDN 이 느리면 그대로 시연이 막힌다 |
| `wasm/` | `public/ai/wasm/` | 같은 이유 |

`public/ai` 는 gitignore 에 있다. 38MB 라 저장소에 넣지 않는다.

esbuild 옵션을 이 스크립트에 옮겨 적지 않고 `ai-detection` 의 `npm run build:worker` 를
그대로 부른다. 두 곳에 적으면 어긋난다.

### 규약을 코드가 지키게 한 방법

`ai-detection/README` 4절에 "앱이 부르지 않으면 조용히 깨지는 것 셋"이 있다. 셋 다 화면이
기억하지 않아도 되게 `src/detection/useAbsenceDetection.ts` 안에서 막았다.

| 규약 | 안 지키면 | 어떻게 막았나 |
|---|---|---|
| `sessionStartedAtMs` 를 넘긴다 | 탭을 닫았다 들어오면 `clientSeq` 가 겹쳐 **그 세션 자리비움이 통째로 사라진다** | 훅의 필수 인자다. 안 넘기면 타입 검사가 막는다 |
| 401 뒤 `resumeAfterReauth()` | 전송이 멈춘 채 큐만 쌓이고 **세션이 끝날 때까지 하나도 안 나간다** | 토큰 저장소를 구독해 새 토큰이 오면 자동으로 부른다 |
| SS-5 성공 **뒤에** `onRestroomStarted(응답)` | 화장실에 있던 시간이 자리비움 경고가 된다 | **SS-5 호출 자체를 훅이 갖는다.** 화면은 `startRestroom()` 만 부르고 순서는 훅 안에서 지켜진다 |

세 번째가 이 훅에 API 호출을 들인 이유다. 화면이 `api.session.startPause()` 를 직접 부를 수
있게 두면 감지를 멈추는 호출을 빠뜨려도 아무 경고가 없다.

**카메라는 `src/livekit/useRoom.ts` 한 곳에서만 잡는다.** 두 번 잡으면 기기에 따라 화면이
멈춘다. 감지 모듈은 `getUserMedia` 를 부르지 않고 거기서 만든 `<video>` 를 받아 쓴다.

---

## 4. 무엇이 되어 있고 무엇이 아닌가

### 실제로 돌려서 확인한 것

`npm run smoke` 13개 항목이 통과한다(2026-08-16, 백엔드 8180 dev, 헤드리스 크롬 + 합성 카메라).

- 로그인 → 토큰 저장 → 홈이 `activeSession` 을 보고 세션 화면으로 복귀
- SS-1 로 세션 화면이 그려지고 2.5초 간격으로 다시 물어본다
- SS-2 로 LiveKit 토큰이 발급된다
- **클래식 워커가 뜨고 모델이 로드되고 캘리브레이션 8초가 돈다**
- **자리비움이 실제로 서버까지 갔다 온다.** 프롬프트가 t0+30초에 뜨고, 15초 무응답 뒤
  `{"type":"START","clientSeq":293,...}` 가 200 으로 받아들여졌고 응답의 `warningCount` 가
  화면 경고 배지에 반영됐다.
  `clientSeq` 가 1이 아니라 293인 것이 `sessionStartedAtMs` 가 실제로 먹었다는 증거다.
  번호의 바닥이 **세션 시작으로부터 흐른 초**여서, 세션 시작 02:15:37 에 번호를 뗀 시각
  02:20:30 이면 293이 나온다. 안 넘겼으면 바닥이 1이고, 탭을 닫았다 다시 들어온 사용자의
  번호가 앞 실행과 겹쳐 서버가 전부 409 로 거절한다(W1)
- `npm run typecheck` 무오류, `npm run build` 성공. MediaPipe 는 메인 번들에 들어가지 않고
  워커에만 있다

### 만든 화면

| 화면 | 상태 |
|---|---|
| 로그인 (S1) | 됨 |
| 생년월일 (S2) | 됨. 되돌릴 수 없어 확인 단계를 뒀다 |
| 캠 동의 (S3) | 됨. 문구 수치는 상수 한 곳에 모아 뒀다 |
| 홈 (S4) | 됨 |
| 매칭 대기 (S6) | 됨. 폴링 + 감지 자산 미리 받기 |
| **세션 (S7)** | **끝까지 만들었다.** 아래 참조 |
| 결과 (S8) | 됨. 정산 중 상태와 409 재시도 포함 |
| 이의 신청 (S9) | 됨 |
| 목표·기록·포인트·스토어·신고 | **뼈대와 할 일만.** 부를 API 와 그 화면의 함정만 적혀 있다 |
| 관리자 (AD-1~9) | 안 만들었다. 같은 앱에 둘지 별도 웹인지 팀 결정 전이다 |

### 세션 화면이 지키는 것

- SS-1 을 **계속** 다시 묻는다. 남의 퇴장·퇴출·화장실 모드와 조기 종료를 아는 경로가 이것뿐이다
- 입장 순서가 SS-1 → SS-2 → LiveKit 이다
- 저장해 둔 LiveKit 토큰을 재사용하지 않는다. 수명 1시간, 세션 최대 4시간이다
- `LEFT`·`EVICTED` 참가자 카드를 지우지 않는다
- 퇴출되면 LiveKit 을 **직접 끊는다.** 서버가 끊어 주지 않는다
- 화장실 모드 버튼을 `pauseUsed` 로 미리 막는다
- 자율 퇴장에 사유 본문을 싣는다

### 안 된 것 · 확인 못 한 것

- **LiveKit 서버가 로컬에 없다.** dev 설정이 `ws://localhost:7880` 인데 아무도 안 띄웠다.
  붙지 못하면 카메라만 열어 감지를 돌리고 그 사실을 화면에 띄운다. **남의 캠이 실제로 보이는지,
  6인 영상 수신 중에 추론이 프레임을 놓치는지는 확인하지 못했다.** LiveKit 을 띄우기 전까지
  닫히지 않는다
- **사람 얼굴로 확인한 적이 없다.** 위 확인은 전부 합성 영상이다. 오탐률은
  `ai-detection/README` 6절 측정으로만 나온다. 여기 숫자를 정확도로 읽으면 안 된다
- **스티커 전송.** 목록 API 는 있지만 전송은 LiveKit 데이터 채널이라 LiveKit 이 있어야 한다
- **결제.** PG 테스트 키가 없다
- iOS Safari, 실기기 성능, 발열
- 디자인. 화면 구조가 보일 만큼만 CSS 를 뒀다

### 팀이 정해야 화면이 닫히는 것

`frontend-guide.md` §8 에 목록이 있다. 화면에 직접 묶이는 것은 이 둘이다.

- **자리비움 임계 60초 / 퇴출 3회 / -300P** — 캠 동의 문구에 수치가 들어간다.
  **바꾸면 재동의가 필요하다.** 지금은 `MediaConsentScreen.tsx` 의 `TERMS` 한 곳에 모아 뒀다
- **폴링 간격** — 지금 매칭·세션 모두 2.5초다. 서버가 강제하지 않는다

---

## 5. 폴더

```
src/
├── api/
│   ├── schema.gen.ts   openapi.yaml 에서 생성. 손대지 않는다
│   ├── types.ts        읽기 좋은 이름을 붙인 것
│   ├── http.ts         토큰·401·에러 정규화·503 재시도. 화면은 fetch 를 직접 안 쓴다
│   └── client.ts       오퍼레이션 44개
├── auth/               토큰 보관. 리프레시가 없어 하는 일이 적다
├── detection/          감지 모듈 연결. 규약 셋을 여기서 지킨다
├── livekit/            캠. 카메라를 잡는 유일한 곳
├── hooks/usePolling.ts 서버가 아무것도 밀어 주지 않아서 필요하다
└── screens/
```

---

## 6. 이어받는 사람에게 — 첫 30분

1. **띄운다** (10분) — 1절 그대로. `npm run smoke -- --code=u1` 이 13/13 이면 환경이 맞다.
2. **세션 화면을 읽는다** (10분) — `src/screens/SessionScreen.tsx` 와
   `src/detection/useAbsenceDetection.ts` 둘이다. 나머지 화면은 전부 이 둘보다 단순하다.
   주석의 §번호는 `morak-server/docs/frontend-guide.md` 의 절 번호다.
3. **하나를 채워 본다** (10분) — `src/screens/stubs.tsx` 의 목표 설정(S5)이 가장 작다.
   API 하나에 선택지 세 개다. `api.member.setGoal()` 을 부르고 409 를 처리하면 끝난다.

그다음 순서는 이렇게 본다.

1. **LiveKit 을 띄운다.** 지금 닫히지 않은 것 중 제일 크다. 남의 캠, 스티커, 6인 부하가
   전부 여기 묶여 있다. `docker run livekit/livekit-server --dev` 면 `ws://localhost:7880` 이
   뜨고 dev 설정과 맞는다
2. **감지 모듈 담당자와 대본 A(60초)를 찍는다.** `ai-detection/README` 6-2 다. 고개 숙여
   필기하는 자세에서 얼굴이 잡히는지가 이 프로젝트의 진짜 질문이고 아직 미확인이다.
   프론트가 아니라 감지 쪽 일이지만 **결과에 따라 임계값이 바뀌고, 바뀌면 캠 동의 문구가
   바뀐다**
3. 남은 화면. 스토어·포인트가 스크린 수는 많지만 전부 목록과 폼이라 세션 화면보다 쉽다

읽을 순서는 `morak-server/docs/frontend-guide.md` §2(여덟 가지) → §3 끝의 "실례" →
`ai-detection/README` 3·4절이다. 그 셋이 이 코드가 왜 이 모양인지의 전부다.
