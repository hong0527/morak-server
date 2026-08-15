#!/usr/bin/env bash
#
# MoLock 서버 전 구간 호출 예제.
#
# 로그인부터 세션 완주·결과 조회까지를 순서대로 부른다. 프론트가 붙기 전에 이 스크립트를
# 한 번 돌리면 각 단계에서 서버가 실제로 무엇을 돌려주는지 눈으로 확인할 수 있다.
# 응답은 전부 그대로 출력하므로, 화면을 만들 때 필요한 필드 이름과 형태를 여기서 가져간다.
#
# 사용법:
#   ./gradlew bootRun --args='--server.port=8120'     # 다른 터미널에서 서버 기동
#   ./docs/frontend-walkthrough.sh                    # 기본 http://localhost:8120
#   ./docs/frontend-walkthrough.sh http://localhost:9000
#
# 전제:
#   - dev 프로필(기본값)이라야 한다. AU-1의 개발용 소셜 로그인과 /api/dev/** 가 dev에서만 켜진다.
#   - H2 인메모리라 서버를 내리면 데이터가 사라진다. 매번 새로 돌려도 된다.
#   - 시각을 앞으로 당기는 구간(9단계)이 있다. 끝에서 시계를 되돌리지만, 중간에 끊으면
#     서버 시계가 앞선 채로 남는다. 그때는 POST /api/dev/clock {"reset":true} 를 부른다.

set -u

BASE="${1:-http://localhost:8120}"
API="$BASE/api"

# 실행마다 다른 소셜 계정을 쓴다. 같은 authorizationCode는 같은 회원으로 붙으므로,
# 고정값을 쓰면 두 번째 실행에서 "이미 세션에 참여 중" 같은 상태 충돌이 난다.
RUN_ID="$(date +%s)"

RED=$'\033[31m'; GREEN=$'\033[32m'; CYAN=$'\033[36m'; DIM=$'\033[2m'; BOLD=$'\033[1m'; OFF=$'\033[0m'
if [ ! -t 1 ]; then RED=""; GREEN=""; CYAN=""; DIM=""; BOLD=""; OFF=""; fi

LAST_STATUS=""
LAST_BODY=""

# JSON에서 점 표기 경로 하나를 꺼낸다. jq가 없는 환경을 위해 python3로 처리한다.
jget() {
  printf '%s' "$1" | python3 -c '
import json,sys
try:
    d=json.load(sys.stdin)
except Exception:
    sys.exit(0)
for k in sys.argv[1].split("."):
    if d is None: break
    d = d[int(k)] if isinstance(d,list) else d.get(k)
print("" if d is None else d)
' "$2" 2>/dev/null
}

pretty() {
  printf '%s' "$1" | python3 -m json.tool --no-ensure-ascii 2>/dev/null || printf '%s\n' "$1"
}

step() {
  printf '\n%s%s== %s%s\n' "$BOLD" "$CYAN" "$1" "$OFF"
}

# call <설명> <METHOD> <경로> [토큰] [본문]
call() {
  local label="$1" method="$2" path="$3" token="${4:-}" body="${5:-}"
  local args=(-sS -m 20 -w '\n%{http_code}' -X "$method" "$API$path")
  [ -n "$token" ] && args+=(-H "Authorization: Bearer $token")
  if [ -n "$body" ]; then args+=(-H 'Content-Type: application/json' -d "$body"); fi

  printf '%s%s %s%s\n' "$DIM" "$method" "$path" "$OFF"
  [ -n "$body" ] && printf '%s  요청: %s%s\n' "$DIM" "$body" "$OFF"

  local raw; raw="$(curl "${args[@]}" 2>&1)"
  LAST_STATUS="${raw##*$'\n'}"
  LAST_BODY="${raw%$'\n'*}"
  [ "$LAST_BODY" = "$LAST_STATUS" ] && LAST_BODY=""

  local color="$GREEN"
  case "$LAST_STATUS" in 2*) color="$GREEN";; *) color="$RED";; esac
  printf '  %s← %s%s  %s\n' "$color" "$LAST_STATUS" "$OFF" "$label"
  [ -n "$LAST_BODY" ] && pretty "$LAST_BODY" | sed 's/^/  /'
  return 0
}

require() {
  if [ -z "${1:-}" ]; then
    printf '\n%s중단: %s%s\n' "$RED" "$2" "$OFF"
    printf '%s서버가 %s 에 떠 있고 dev 프로필인지 확인한다.%s\n' "$DIM" "$BASE" "$OFF"
    exit 1
  fi
}

printf '%s%sMoLock 전 구간 호출 예제%s  대상 %s\n' "$BOLD" "$CYAN" "$OFF" "$BASE"

# ---------------------------------------------------------------------------
step "0. 인증 없이 부르면"
# 모든 화면은 로그인 뒤에 열린다. 토큰 없이 부르면 이 형태로 떨어진다.
call "토큰 없음" GET "/stickers"

# ---------------------------------------------------------------------------
step "1. AU-1 로그인 — 6명"
# 매칭 확정 인원이 6명(morak.session.required-participants)이라 6개 계정을 만든다.
# 프론트 입장에서 의미 있는 건 첫 번째 계정이고, 나머지는 세션을 성사시키기 위한 상대다.
#
# authorizationCode가 곧 소셜 사용자 식별자다(개발용 스텁). 같은 값 = 같은 회원.
# 실서비스에서는 카카오/애플이 준 인가 코드가 여기 들어간다.

TOKENS=(); MEMBER_IDS=()
for i in 1 2 3 4 5 6; do
  BODY=$(cat <<JSON
{"provider":"KAKAO","authorizationCode":"walkthrough-$RUN_ID-$i","agreements":[{"type":"TOS","agreed":true},{"type":"PRIVACY","agreed":true},{"type":"MARKETING","agreed":false}]}
JSON
)
  if [ "$i" = "1" ]; then
    call "1번 회원 로그인(신규 가입)" POST "/auth/login" "" "$BODY"
  else
    printf '%s  ... %s번 회원 로그인%s' "$DIM" "$i" "$OFF"
    LAST_BODY="$(curl -sS -m 20 -X POST "$API/auth/login" -H 'Content-Type: application/json' -d "$BODY")"
    printf '\r'
  fi
  T="$(jget "$LAST_BODY" accessToken)"; M="$(jget "$LAST_BODY" memberId)"
  require "$T" "로그인 실패 (${i}번). 응답: $LAST_BODY"
  TOKENS+=("$T"); MEMBER_IDS+=("$M")
done
printf '%s  6명 로그인 완료. memberId = %s%s\n' "$DIM" "${MEMBER_IDS[*]}" "$OFF"

TOKEN="${TOKENS[0]}"
MEMBER_ID="${MEMBER_IDS[0]}"

# 로그인 응답의 needsBirthdate 가 true면 생년월일 화면으로 보낸다.
# ageVerification 을 클라이언트가 해석하지 않아도 되도록 서버가 계산해 내려주는 값이다.

# ---------------------------------------------------------------------------
step "2. AU-3 생년월일 — 연령 확인"
# 이걸 통과하지 않으면 이후 대부분의 API가 403 AGE_NOT_VERIFIED로 막힌다.
# 만 14세 미만이면 403 UNDER_AGE_SIGNUP_BLOCKED이고 되돌릴 수 없다.
call "본인 생년월일 등록" POST "/members/me/birthdate" "$TOKEN" '{"birthDate":"2000-03-15"}'
for i in 1 2 3 4 5; do
  curl -sS -m 20 -o /dev/null -X POST "$API/members/me/birthdate" \
    -H "Authorization: Bearer ${TOKENS[$i]}" -H 'Content-Type: application/json' \
    -d '{"birthDate":"1999-05-20"}'
done

# ---------------------------------------------------------------------------
step "3. AU-6 캠 분석 동의"
# 동의가 없으면 SS-2 접속 토큰이 403 CONSENT_REQUIRED로 막힌다. 즉 세션에 못 들어간다.
# true만 받는다. 철회 API는 없다.
call "캠 영상 온디바이스 분석 동의" POST "/members/me/media-consent" "$TOKEN" '{"agreed":true}'
for i in 1 2 3 4 5; do
  curl -sS -m 20 -o /dev/null -X POST "$API/members/me/media-consent" \
    -H "Authorization: Bearer ${TOKENS[$i]}" -H 'Content-Type: application/json' -d '{"agreed":true}'
done

# ---------------------------------------------------------------------------
step "4. AU-2 내 정보 — 홈 화면의 뼈대"
# 홈에서 한 번 부르는 화면 데이터. 진행 중인 세션이 있으면 activeSession이 채워지고,
# 프론트는 그걸 보고 바로 세션 화면으로 복귀시킨다.
call "내 정보" GET "/members/me" "$TOKEN"

# ---------------------------------------------------------------------------
step "5. AU-7 목표 기간 설정"
# 허용값은 7 / 14 / 30 뿐이다. 그 외에는 400이고, details에 허용값이 실린다.
call "30일 목표 시작" PUT "/members/me/goal" "$TOKEN" '{"periodDays":30}'
call "허용하지 않는 값(400 예시)" PUT "/members/me/goal" "$TOKEN" '{"periodDays":10}'

# ---------------------------------------------------------------------------
step "6. MT-1 매칭 신청"
# targetMinutes 는 60 / 120 / 180 / 240 만 받는다. 같은 조건끼리 6명이 모이면 확정된다.
call "1번 회원 매칭 신청" POST "/match-requests" "$TOKEN" '{"targetMinutes":60}'
MATCH_REQUEST_ID="$(jget "$LAST_BODY" matchRequestId)"

step "7. MT-2 매칭 상태 폴링"
# 성사 통지는 푸시가 없다. 이 GET을 주기적으로 부르는 것이 유일한 확인 수단이다.
# 지금은 1명뿐이라 WAITING이고 waitingCount로 몇 명 모였는지 보인다.
call "아직 나 혼자 (WAITING)" GET "/match-requests/me" "$TOKEN"

printf '\n%s  나머지 5명이 같은 조건으로 신청한다 → 6번째에서 확정%s\n' "$DIM" "$OFF"
for i in 1 2 3 4 5; do
  curl -sS -m 20 -o /dev/null -X POST "$API/match-requests" \
    -H "Authorization: Bearer ${TOKENS[$i]}" -H 'Content-Type: application/json' -d '{"targetMinutes":60}'
done

call "다시 폴링 → MATCHED + sessionId" GET "/match-requests/me" "$TOKEN"
SESSION_ID="$(jget "$LAST_BODY" sessionId)"
require "$SESSION_ID" "매칭이 성사되지 않았다. 응답: $LAST_BODY"
printf '%s  sessionId = %s — 이 값을 들고 세션 화면으로 넘어간다%s\n' "$DIM" "$SESSION_ID" "$OFF"

# ---------------------------------------------------------------------------
step "8. 세션 화면"

call "SS-1 세션 정보(참가자 목록·시작/종료 시각)" GET "/sessions/$SESSION_ID" "$TOKEN"

# LiveKit 접속에 필요한 값 일체. 프론트는 이 url + token 으로 LiveKit SDK를 붙인다.
# canPublishAudio 는 항상 false다 — 이 서비스는 영상만 쓴다.
call "SS-2 LiveKit 접속 토큰" POST "/sessions/$SESSION_ID/token" "$TOKEN"

call "SS-3 오늘 할 일 (최대 50자)" PUT "/sessions/$SESSION_ID/goal" "$TOKEN" '{"goalText":"명세서 정합 검사 끝내기"}'

step "8-1. SS-4 자리비움 보고 — 카메라 감지 담당자가 부르는 경로"
# 온디바이스로 얼굴을 못 찾으면 START, 다시 찾으면 END를 보낸다. 대상은 언제나 본인이다.
# clientSeq 는 0 이상이고 단조 증가해야 한다. 같은 값을 다시 보내면 409로 떨어지는데,
# 그건 정상 완료로 취급한다(재전송이 중복 집계되지 않았다는 뜻).
NOW_ISO="$(python3 -c 'import datetime;print(datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=9))).replace(microsecond=0).isoformat())')"
call "자리비움 시작" POST "/sessions/$SESSION_ID/absence-events" "$TOKEN" \
  "{\"type\":\"START\",\"clientSeq\":1,\"occurredAt\":\"$NOW_ISO\"}"

# 최소 간격(morak.session.absence-min-interval-seconds, 현재 5초)보다 빨리 보내면 429다.
# 429는 같은 clientSeq 그대로 간격 뒤에 다시 보내면 된다 — 새 번호를 매기지 않는다.
call "간격 미만 재전송 → 429 (재시도 규약 예시)" POST "/sessions/$SESSION_ID/absence-events" "$TOKEN" \
  "{\"type\":\"END\",\"clientSeq\":2,\"occurredAt\":\"$NOW_ISO\"}"

printf '%s  5초 기다렸다 같은 clientSeq로 재전송%s\n' "$DIM" "$OFF"
sleep 6
call "간격 지난 뒤 같은 clientSeq 재전송 → 성공" POST "/sessions/$SESSION_ID/absence-events" "$TOKEN" \
  "{\"type\":\"END\",\"clientSeq\":2,\"occurredAt\":\"$NOW_ISO\"}"

step "8-2. SS-5 / SS-6 화장실 모드"
# 세션당 한 번뿐이다. 상한(10분)을 넘기면 복귀할 때 경고가 붙는다.
call "화장실 모드 시작" POST "/sessions/$SESSION_ID/pause" "$TOKEN"
call "복귀" DELETE "/sessions/$SESSION_ID/pause" "$TOKEN"
call "두 번째 시도 → 409 (이미 사용)" POST "/sessions/$SESSION_ID/pause" "$TOKEN"

step "8-3. SS-8 세션이 끝나기 전에 결과를 부르면"
# 409 SESSION_NOT_ENDED. 오류가 아니라 "아직 정산 전"이라는 뜻이다.
call "아직 진행 중 → 409" GET "/sessions/$SESSION_ID/result" "$TOKEN"

# ---------------------------------------------------------------------------
step "9. 세션 종료 — 정시 종료와 1분 공백"
# 여기서부터는 개발용이다. 실제로는 60분을 기다려야 하므로 서버 시계를 앞으로 당긴다.
#
# 중요: 종료 예정 시각(endsAt)이 지나도 결과가 바로 나오지 않는다. 정산은 매분 :00에
# 도는 배치(B1)가 하고, 그전까지 세션은 LIVE로 남는다. 그 사이 결과 조회는 409다.
# 프론트는 이 구간을 오류로 보여주면 안 된다.

call "DEV-2 시계를 65분 앞으로" POST "/dev/clock" "" '{"offsetMinutes":65}'

call "endsAt은 지났지만 배치 전 → 아직 409 (이 구간이 그 1분이다)" GET "/sessions/$SESSION_ID/result" "$TOKEN"

call "DEV-4 종료 배치(B1) 수동 실행" POST "/dev/batches/B1" ""

call "SS-8 세션 결과 — 완주 여부·획득 포인트·경고 내역" GET "/sessions/$SESSION_ID/result" "$TOKEN"

# ---------------------------------------------------------------------------
step "10. 세션 후 화면"
call "PT-1 포인트 잔액·내역" GET "/members/me/points" "$TOKEN"
call "SS-9 내 세션 기록" GET "/members/me/sessions?page=0&size=5" "$TOKEN"
call "AU-2 내 정보 — 스트릭·목표 진행도 갱신 확인" GET "/members/me" "$TOKEN"

# ---------------------------------------------------------------------------
step "11. PY-1 / PY-2 포인트 충전"
# 결제는 개발용 스텁이다. 실제 PG를 부르지 않고, 보낸 값을 그대로 승인 처리한다.
# 프론트는 PY-1 응답의 provider 를 보고 어느 PG SDK로 결제창을 띄울지 고른다.
call "충전 건 생성 (아직 포인트는 늘지 않는다)" POST "/points/charges" "$TOKEN" '{"amountKrw":10000}'
CHARGE_ID="$(jget "$LAST_BODY" chargeId)"
PG_ORDER_ID="$(jget "$LAST_BODY" pgOrderId)"

if [ -n "$CHARGE_ID" ]; then
  # 실제로는 결제창이 닫히면서 받은 pgTid를 그대로 싣는다.
  # 개발용 스텁은 pgTid 접두사로 실패 경로를 흉내낸다: "fail-" 거절, "mismatch-" 금액 불일치.
  call "승인 확인 → 여기서 포인트가 적립된다" POST "/points/charges/$CHARGE_ID/confirm" "$TOKEN" \
    "{\"pgOrderId\":\"$PG_ORDER_ID\",\"pgTid\":\"dev-tid-$RUN_ID\",\"amountKrw\":10000}"
  call "같은 건을 다시 확인해도 같은 응답 (멱등)" POST "/points/charges/$CHARGE_ID/confirm" "$TOKEN" \
    "{\"pgOrderId\":\"$PG_ORDER_ID\",\"pgTid\":\"dev-tid-$RUN_ID\",\"amountKrw\":10000}"
fi

# ---------------------------------------------------------------------------
step "12. 스토어"
call "SR-1 상품 목록" GET "/store/products?page=0&size=5" "$TOKEN"
PRODUCT_ID="$(jget "$LAST_BODY" content.0.productId)"
if [ -n "$PRODUCT_ID" ]; then
  call "SR-2 상품 상세" GET "/store/products/$PRODUCT_ID" "$TOKEN"
  # idempotencyKey 는 클라이언트가 만든다. 같은 키로 다시 보내면 새 주문이 생기지 않는다.
  call "SR-3 교환" POST "/orders" "$TOKEN" \
    "{\"productId\":$PRODUCT_ID,\"quantity\":1,\"idempotencyKey\":\"wt-$RUN_ID-1\"}"
  call "같은 키로 재전송 → 409, details에 기존 orderId" POST "/orders" "$TOKEN" \
    "{\"productId\":$PRODUCT_ID,\"quantity\":1,\"idempotencyKey\":\"wt-$RUN_ID-1\"}"
  call "SR-4 내 교환 내역" GET "/orders?page=0&size=5" "$TOKEN"
else
  printf '%s  등록된 상품이 없어 건너뛴다.%s\n' "$DIM" "$OFF"
fi

# ---------------------------------------------------------------------------
step "13. 그 밖에 프론트가 쓰는 것"
call "SS-11 스티커 목록(리액션 버튼용)" GET "/stickers" "$TOKEN"
call "AP-2 내 이의 신청 목록" GET "/members/me/appeals?page=0&size=5" "$TOKEN"
call "관리자 전용 API를 일반 계정으로 → 403" GET "/admin/sessions" "$TOKEN"

# ---------------------------------------------------------------------------
step "14. 정리"
call "DEV-2 서버 시계 원복" POST "/dev/clock" "" '{"reset":true}'

printf '\n%s%s끝.%s 각 단계의 응답이 곧 화면이 받는 값이다.\n' "$BOLD" "$GREEN" "$OFF"
printf '%s화면별 호출 순서와 주의점은 docs/frontend-guide.md 를 본다.%s\n\n' "$DIM" "$OFF"
