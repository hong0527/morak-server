# MoLock 서버 배포 안내

운영 프로필(`prod`)로 실제 서버에 올리는 절차다. 이 문서의 명령과 수치는 배포 대상과 같은 제약(2 vCore / 메모리 4GB / 리눅스)을 로컬에 걸어 실제로 실행해 얻은 것이다. 추정값에는 그렇다고 표시했다.

정본 참조: 스키마는 `docs/db-schema.md`, API 계약은 `docs/api-spec.md`.

---

# 배포 당일 한 장

**이 문서는 836줄이고 8/18에 그걸 다 읽을 시간은 없다.** 아래 순서대로만 따라가면 끝난다.
각 줄의 괄호가 막혔을 때 펼칠 절이다.

## 전날까지 (8/17)

| # | 할 일 | 시간 | 상세 |
|---|---|---|---|
| 0-1 | **도메인 준비.** 없으면 카메라가 안 켜져 세션 화면이 통째로 죽는다 | 10분 | §7 |
| 0-2 | 환경변수 9개 값을 모아 둔다. DB 3개는 여기서 정하면 된다 | 10분 | §3 |
| 0-3 | 로컬에서 `./gradlew clean bootJar` — 서버에서 빌드하지 않는다 | 3분 | §5 |

## 당일 — 서버를 받은 뒤 (총 90~120분)

| # | 할 일 | 시간 | 상세 |
|---|---|---|---|
| 1 | 서버 접속. JDK 21 이상 설치 확인 (`java -version`) | 10분 | §1 |
| 2 | MySQL 8 설치. `/etc/mysql/conf.d/morak.cnf`에 §4-1 설정을 넣고 재시작 | 15분 | §4-1 |
| 3 | DB·계정 생성 (`morak`, utf8mb4, localhost 한정) | 5분 | §4-2 |
| 4 | **스키마 생성.** `db-schema.md`에서 `schema.sql`을 뽑아 넣고 **테이블 24개 확인** | 10분 | §4-3 |
| 5 | jar 업로드 (`/opt/morak/morak-server.jar`) | 5분 | §5 |
| 6 | `/etc/morak/env`(권한 600) + systemd 유닛 작성 | 15분 | §6 |
| 7 | `systemctl enable --now morak` → **로그에 `Started MorakServerApplication`** | 5분 | §6 · §10-1 |
| 8 | 호출 확인. 로그인은 401이 정상이다(키가 없으면) | 5분 | §10-2 |
| 9 | DB 확인. `match_lock` 조건 행 4개가 자동으로 들어갔는지 | 5분 | §10-3 |
| 10 | nginx + 도메인 + 인증서. **프론트와 API를 같은 오리진에** | 25분 | §7 |
| 11 | 백업 크론 등록. **서버 밖으로 내보내는 것까지** | 15분 | §9 |
| 12 | 관리자 계정 승격 | 5분 | §11 |

**7번까지가 "서버가 산다", 10번까지가 "브라우저에서 쓸 수 있다"**이다. 시간이 모자라면
11·12를 다음 날로 미뤄도 되지만, **10번을 미루면 아무도 접속할 수 없다.**

## 막혔을 때 어디를 보나

| 증상 | 먼저 볼 곳 |
|---|---|
| **기동이 안 된다** — `Could not resolve placeholder 'MORAK_...'` | 환경변수 누락. §3 표의 9개를 전부 넣었는지 |
| **기동이 안 된다** — `Access denied for user 'morak'` | DB 3종 변수가 안 들어왔거나 비밀번호가 틀림. §3 마지막 문단 |
| **기동이 안 된다** — `Schema validation: missing column [...]` | 예전 DDL로 만든 DB다. §4-5의 변경분 표를 적용 |
| **기동이 안 된다** — `missing table [...]` | 스키마를 안 넣었다. §4-3 |
| **브라우저에서 카메라가 안 켜진다** | HTTPS가 아니다. IP로는 안 된다 — 도메인·인증서. §7 |
| **브라우저에서 API 호출이 전부 막힌다** | CORS. 프론트와 API를 같은 오리진에 둔다. §7 |
| **무관한 조회까지 느리다가 500** | 커넥션 풀 고갈. JDBC URL의 `innodb_lock_wait_timeout=5` 확인. §8 |
| **로그 시각이 DB와 9시간 다르다** | `TZ=Asia/Seoul` 누락. §6 · §8 |
| **매칭이 6명 모여도 안 된다** | `match_lock` 조건 행. §4-4 · §10-3 |
| 헬스체크 URL이 404 | 그런 것이 없다. §12 |

## 8/18 전에 한 번 해 볼 것

실기기가 없어도 **도커로 같은 제약을 걸어 위 4~9번을 그대로 예행할 수 있다**(§13).
스키마 넣기부터 기동 확인까지 같은 순서라, 당일 처음 해 보는 것보다 훨씬 빠르다.
실측 소요는 §13 머리말에 적어 두었다.

---

## 0. 배포 대상

부트캠프가 제공하는 가비아 클라우드 서버 1대다.

| 항목 | 값 |
|---|---|
| CPU | 2 vCore (High CPU) |
| 메모리 | 4GB |
| 공인 IP | 1개 |
| 트래픽 | 무료 1TB |
| 사용 기간 | **8/18(화) ~ 8/28(금), 10일** |

**8/28에 서버가 일괄 삭제된다.** 그 안에 있는 데이터는 함께 사라지므로 §9 백업이 선택 사항이 아니다.

한 대에 API 서버·MySQL·프론트가 모두 올라간다. 사양을 올리면 과금되므로 아래 설정은 전부 4GB 안에서 끝나도록 잡았다.

---

## 1. 준비물

| 항목 | 요구 | 비고 |
|---|---|---|
| 빌드 JDK | 21 | `build.gradle` 툴체인이 21로 고정돼 있다 |
| 실행 JRE | 21 이상 | 클래스 파일이 major 65(Java 21)다 |
| DB | MySQL 8, InnoDB, utf8mb4 | 8.4.11에서 확인 |
| 웹 서버 | nginx | 프론트 정적 파일 + API 리버스 프록시(§7). **없으면 브라우저에서 API를 부를 수 없다** |
| 도메인 | 필요 | HTTPS 없이는 카메라가 열리지 않는다(§7) |

---

## 2. 메모리 배분 (4GB)

실측값이다. 앱과 MySQL을 각각 컨테이너에 넣고 CPU를 2코어로 묶은 상태에서 쟀다.

| 구성 요소 | 설정 | 유휴 | 부하 시 | 비고 |
|---|---|---|---|---|
| API 서버 | `-Xmx512m` | 382MB | **513MB** | 힙이 가득 차면 최대 약 750MB까지(추정) |
| MySQL | 튜닝 후(§4-1) | 174MB | 181MB | **빈 DB 기준.** 아래 주석을 본다 |
| MySQL | **기본 설정** | 454MB | — | 튜닝 전. 참고용 |
| nginx + 프론트 정적 | — | 약 30MB(추정) | | 정적 파일만 서빙할 때 |
| OS(리눅스 최소 설치) | — | 300~400MB(추정) | | |

**합계는 최악의 경우로 잡아도 약 1.7GB**라 4GB에 2GB 이상 남는다. 여유는 파일 캐시로 쓰인다.

MySQL의 174MB는 **테이블만 있고 데이터가 없을 때**의 값이다. 회원 46명·세션 13개·원장 131행을 넣고
여정을 네 개 돌린 뒤 다시 재면 **287MB**였다. 버퍼 풀에 읽은 페이지가 남기 때문이고, 데이터가 늘면
`innodb_buffer_pool_size`로 잡은 256M까지 올라간다. 위 표의 174MB를 운영 중 기대값으로 읽지 않는다 —
**MySQL은 300MB 안팎으로 잡아 두는 편이 맞다.** 그래도 합계는 4GB 안에서 넉넉하다.

### 반드시 지정해야 하는 두 가지

**`-Xmx`를 반드시 준다.** JVM 기본 최대 힙은 물리 메모리의 1/4이라 4GB 서버에서 1024MB가 잡힌다(실측 확인). 앱이 실제로 쓴 최대치는 그 절반 이하였으므로 512m로 충분하고, 그만큼을 MySQL과 프론트에 남긴다.

```
4GB 서버에서 -Xmx 없을 때 : MaxHeapSize = 1024 MB
-Xmx512m 지정 시          : MaxHeapSize = 512 MB
```

2코어에서도 G1GC가 그대로 선택된다(`UseG1GC = true`). SerialGC로 떨어지는 소형 판정에는 걸리지 않는다.

**`TZ=Asia/Seoul`을 준다.** §8에 이유가 있다.

---

## 3. 환경변수

**9개 전부 필수다.** 하나라도 없으면 기동하지 않는다(폴백을 두지 않은 설계다 — 폴백이 있으면 운영에서 변수를 빠뜨려도 개발용 비밀값으로 조용히 뜬다).

| 변수 | 의미 |
|---|---|
| `MORAK_DB_URL` | JDBC 접속 URL. `jdbc:mysql://127.0.0.1:3306/morak` |
| `MORAK_DB_USERNAME` | DB 계정 |
| `MORAK_DB_PASSWORD` | DB 비밀번호 |
| `MORAK_JWT_SECRET` | JWT 서명 키. HS256이므로 **32바이트 이상**이어야 한다 |
| `MORAK_SOCIAL_HASH_PEPPER` | 탈퇴자 소셜 해시(`blocked_social_hash`)에 섞는 값. **바뀌면 기존 차단 등재가 전부 무효가 된다** |
| `MORAK_LIVEKIT_HOST` | LiveKit 서버 주소(`wss://…`) |
| `MORAK_LIVEKIT_API_KEY` | LiveKit 접속 토큰 서명용 키 |
| `MORAK_LIVEKIT_API_SECRET` | 같은 용도의 시크릿 |
| `MORAK_PG_SECRET_KEY` | 결제(토스) 시크릿 키 |

그 밖의 정책값(포인트 액수, 매칭 대기 시간, 경고 임계 등)은 `application.yml`에 있고 환경변수가 아니다.

기동 실패 메시지는 변수에 따라 친절함이 다르다. `MORAK_` 접두사 6종(JWT·PEPPER·LIVEKIT 3종·PG)은 `Could not resolve placeholder 'MORAK_JWT_SECRET'`처럼 이름을 말해 준다. **DB 3종은 그렇지 않다** — 값이 `${MORAK_DB_PASSWORD}` 문자열 그대로 전달돼, 비밀번호 누락이 `Access denied for user 'morak'@'…'`로 보인다. DB 접속이 거부되면 비밀번호가 틀린 것인지 변수가 안 들어온 것인지 먼저 확인한다.

---

## 4. 최초 배포: DB 준비

### 4-1. MySQL 설정

기본 설정 그대로 두면 454MB를 쓴다. 아래를 `/etc/mysql/conf.d/morak.cnf`에 넣으면 **174MB로 내려간다**(실측). 버퍼 풀을 오히려 128M에서 256M으로 늘리고도 그렇다 — 줄어든 대부분은 `performance_schema`다.

```ini
[mysqld]
character-set-server = utf8mb4
collation-server     = utf8mb4_0900_ai_ci

# 성능 계측 테이블. 켜 두면 그것만으로 수백 MB를 잡는데, 이 규모에서 볼 일이 없다.
performance_schema = OFF

innodb_buffer_pool_size      = 256M
innodb_buffer_pool_instances = 1
innodb_log_buffer_size       = 16M

# 접속당 버퍼가 붙는다. 앱 한 대의 커넥션 풀이 10이라 151은 쓸 일이 없다.
max_connections  = 50
table_open_cache = 200

tmp_table_size      = 16M
max_heap_table_size = 16M
```

### 4-2. 데이터베이스와 계정

```sql
CREATE DATABASE morak CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER 'morak'@'localhost' IDENTIFIED BY '<비밀번호>';
GRANT ALL ON morak.* TO 'morak'@'localhost';
FLUSH PRIVILEGES;
```

같은 서버에 올리므로 `localhost`로 제한한다. **MySQL 포트(3306)를 외부에 열지 않는다.**

정렬 `utf8mb4_0900_ai_ci`는 MySQL 8 기본값이고 **대소문자를 구분하지 않는다**(§8 참조).

### 4-3. 스키마 생성

DDL 정본은 `docs/db-schema.md`다. 문서 안의 sql 코드 블록을 위에서 아래 순서대로 실행하면 된다(FK 대상이 항상 먼저 오고, 순환하는 FK 2건은 문서 말미 `ALTER`로 분리돼 있다). 총 24개 테이블이다.

문서에서 DDL만 뽑아 한 파일로 만들려면 아래를 `morak-server` 디렉터리에서 실행한다.

````bash
python3 - <<'EOF' > schema.sql
import re
src = open('docs/db-schema.md', encoding='utf-8').read()
blocks = re.findall(r'```sql\n(.*?)```', src, re.S)
print('\n'.join(b for b in blocks if re.search(r'CREATE TABLE|ALTER TABLE', b)))
EOF

mysql -u morak -p morak < schema.sql
````

적용 후 테이블이 24개인지 확인한다.

```sql
SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'morak';
```

`schema.sql`은 `db-schema.md`에서 뽑아낸 사본이라 정본이 아니다. `.gitignore`에 없으므로
**쓰고 나면 지운다** — 남겨 두면 커밋에 딸려 들어가 정본이 둘이 된다.

```bash
rm schema.sql
```

### 4-4. match_lock 조건 행

수동 작업이 필요 없다. `MatchLockSeeder`가 기동할 때마다 시간 옵션(60·120·180·240)에 해당하는 잠금 행을 채우고, 이미 있으면 넘어간다. 회원별 잠금 행(`member:{id}`)은 가입 트랜잭션이 함께 넣는다.

### 4-5. 이미 만든 DB가 있으면 — 스키마 변경분

**새로 만드는 DB는 이 절이 필요 없다.** §4-3이 `db-schema.md`에서 DDL을 뽑아 만들고, 그 문서에는 최신 컬럼이 이미 들어 있다.

문제가 되는 것은 **예전 DDL로 이미 만들어 둔 DB**다. §13 도커 검증용으로 며칠 전에 만든 DB가 여기 해당한다. 운영 프로필은 `ddl-auto: validate`라 엔티티와 테이블이 한 칸이라도 어긋나면 기동 자체가 실패한다.

```
SchemaManagementException: Schema validation: missing column [achieved_session_id] in table [member_goal]
```

**앱이 뜨지 않고 종료 코드 1로 죽는다.** §6의 유닛 파일은 `Restart=on-failure`·`RestartSec=5`라
**5초마다 같은 실패를 되풀이한다.** 기동 실패 한 번이 12.5KB를 남기므로 시간당 8.6MB, 하루 207MB다.
방치하면 §8에서 정한 journald 상한 500MB를 **2.4일 만에 채우고**, 그때부터 오래된 로그가 밀려나
원인이 적힌 첫 실패가 사라진다. 배포 중에 눈앞에서 보고 있으면 바로 알아채지만, 저녁에 올려 두고
다음 날 아침에 보면 이미 밀려 있을 수 있다. 기동에 실패하면 먼저 서비스를 멈추고 원인을 본다.

```bash
sudo systemctl stop morak
sudo journalctl -u morak -n 50 | grep "Schema validation"
```

이때는 DB를 다시 만들거나, 아래 변경분을 순서대로 적용한다.

| 날짜 | 변경 | 적용 문장 |
|---|---|---|
| 2026-08-15 | `member_goal`에 목표를 채운 세션 컬럼 추가. SS-8 `goalAchieved`의 근거가 시각 비교에서 이 컬럼으로 바뀌었다 | `ALTER TABLE member_goal ADD COLUMN achieved_session_id BIGINT NULL;` |

위 변경분은 실제로 걸어 확인했다. 컬럼을 뺀 DDL로 DB를 만들어 기동 실패를 재현한 뒤 `ALTER`를
적용했더니 정상 기동했고, 그렇게 따라온 `member_goal`이 §4-3으로 새로 만든 DB와 컬럼·타입까지
같았다. **`ALTER`로 따라잡은 DB와 새로 만든 DB가 같은 결과가 되는 것이 이 표가 지켜야 할 조건이다.**

기존 행은 `NULL`로 남는다. 그 행들은 SS-8에서 `goalAchieved: false`가 되는데, 8/18 이전 데이터는 전부 검증용이라 채우지 않는다. 실사용 데이터가 생긴 뒤에 같은 변경을 하면 그때는 백필을 함께 계획해야 한다.

**enum 값이 느는 것은 여기 적지 않는다.** `AppealStatus`에 `CLOSED`가, `DecidedBy`에 `SYSTEM`이 늘었지만 두 컬럼 모두 `VARCHAR(20)`이라 DDL이 그대로다. 적는 것은 테이블 구조가 실제로 바뀌는 경우뿐이다.

#### 규칙 — 컬럼이 늘면 두 곳을 함께 고친다

1. `docs/db-schema.md` — DDL 정본. `CREATE TABLE` 블록과 컬럼 설명표 양쪽.
2. **이 표** — 이미 만든 DB를 따라오게 할 `ALTER` 문장.

1번만 하면 새 DB는 맞지만 기존 DB가 `validate`에서 막히고, 2번만 하면 새로 만드는 DB에 컬럼이 아예 없다. 마이그레이션 도구(Flyway·Liquibase)를 두지 않기로 한 이상 이 표가 그 역할을 대신하므로, 엔티티에 `@Column`을 더한 커밋은 이 표에도 한 줄을 남긴다.

---

## 5. 빌드

```bash
./gradlew clean bootJar
```

결과물은 `build/libs/morak-server-0.0.1-SNAPSHOT.jar`(약 70MB, 실행 가능 fat jar)다. 같은 디렉터리에 생기는 `-plain.jar`는 실행용이 아니다.

빌드는 서버에서 하지 않아도 된다. 2코어에서 굳이 돌릴 이유가 없으므로 로컬에서 만들어 jar만 올리는 편이 낫다.

---

## 6. 실행

`/etc/morak/env` (권한 600):

```
SPRING_PROFILES_ACTIVE=prod
SERVER_PORT=8080
TZ=Asia/Seoul
MORAK_DB_URL=jdbc:mysql://127.0.0.1:3306/morak?sessionVariables=innodb_lock_wait_timeout=5
MORAK_DB_USERNAME=morak
MORAK_DB_PASSWORD=...
MORAK_JWT_SECRET=...
MORAK_SOCIAL_HASH_PEPPER=...
MORAK_LIVEKIT_HOST=wss://...
MORAK_LIVEKIT_API_KEY=...
MORAK_LIVEKIT_API_SECRET=...
MORAK_PG_SECRET_KEY=...
```

`EnvironmentFile`은 `KEY=value` 형식이고 따옴표를 값의 일부로 읽으므로 감싸지 않는다. URL의 `sessionVariables` 부분은 §8 잠금 대기 항목에서 설명한다.

`/etc/systemd/system/morak.service`:

```ini
[Unit]
Description=MoLock API server
After=network.target mysql.service

[Service]
User=morak
EnvironmentFile=/etc/morak/env
ExecStart=/usr/bin/java -Xms256m -Xmx512m -jar /opt/morak/morak-server.jar
SuccessExitStatus=143
Restart=on-failure
RestartSec=5

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now morak
sudo journalctl -u morak -f
```

---

## 7. 공개 경로: HTTPS·CORS·웹훅

공인 IP가 1개뿐이라 이 셋이 한 덩어리로 묶인다. **결론부터: nginx 하나를 앞에 두고 도메인과 인증서를 붙인다.**

### 왜 HTTPS가 선택이 아닌가

MoLock은 웹캠 서비스다. 브라우저는 **보안 컨텍스트(HTTPS 또는 localhost)에서만 카메라를 허용한다.** `http://<공인IP>`로 접속하면 `navigator.mediaDevices`가 아예 없어 세션 화면이 동작하지 않는다. 즉 IP로만 서비스하는 선택지는 없다.

무료 인증서(Let's Encrypt)는 도메인 기준으로 발급받는 것이 일반적이므로 **도메인이 필요하다.** 8/18 전에 도메인을 하나 준비해 이 서버의 공인 IP로 A 레코드를 걸어 둔다.

### 왜 nginx 리버스 프록시인가 (CORS)

**이 서버에는 CORS 설정이 없다.** 다른 오리진에서 온 요청에 `Access-Control-Allow-Origin` 헤더를 붙이지 않는다(실측 확인 — 프리플라이트는 200이지만 헤더가 없어 브라우저가 응답을 버린다).

따라서 프론트를 `https://도메인`으로, API를 `https://도메인:8080`으로 따로 노출하면 **포트가 달라 오리진이 갈리고 모든 API 호출이 브라우저에서 막힌다.** 서버 코드를 고치지 않고 해결하는 방법이 리버스 프록시다 — 프론트와 API를 같은 오리진에 두면 CORS 자체가 발생하지 않는다.

```nginx
server {
    listen 443 ssl;
    server_name <도메인>;

    ssl_certificate     /etc/letsencrypt/live/<도메인>/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/<도메인>/privkey.pem;

    # 프론트 정적 빌드
    location / {
        root /opt/morak-web;
        try_files $uri $uri/ /index.html;
    }

    # API는 같은 오리진의 /api 아래로. 이것이 CORS를 없애는 자리다.
    location /api/ {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host              $host;
        proxy_set_header X-Real-IP         $remote_addr;
        proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}

server {
    listen 80;
    server_name <도메인>;
    return 301 https://$host$request_uri;
}
```

API 서버(8080)는 방화벽에서 외부에 열지 않는다. 밖으로 여는 것은 80·443뿐이다.

### LiveKit·PG 웹훅

두 웹훅은 **외부에서 우리 서버로 들어오는 요청**이라 공개 경로가 성립해야 한다. 위 구성이면 그대로 된다.

| 웹훅 | 등록할 주소 | 인증 |
|---|---|---|
| LiveKit (SS-10) | `https://<도메인>/api/webhooks/livekit` | `Authorization` 헤더 서명 |
| 결제 (PY-3) | `https://<도메인>/api/webhooks/payment` | 전용 서명 헤더 |

둘 다 JWT 게이트를 통과하지 않고 **서명 검증이 유일한 인증이다.** 경로가 공개돼 있어도 서명이 없으면 거절한다. LiveKit Cloud 콘솔과 토스 콘솔에 각각 위 주소를 등록해야 하며, 등록하지 않으면 세션 종료·결제 승인이 웹훅 경로로는 들어오지 않는다.

---

## 8. 운영에서 눈여겨볼 값

### 로그 시각이 KST가 아니면 DB와 9시간 어긋난다

애플리케이션의 업무 시각은 `morak.timezone`(Asia/Seoul)에 고정된 `Clock` 빈에서만 나오므로 서버 타임존과 무관하게 항상 KST다. **그런데 로그 타임스탬프는 JVM 기본 타임존을 따른다.** 서버가 UTC면 같은 사건이 이렇게 갈린다(실측).

```
로그    : 2026-08-15T09:45:01.418Z   ← UTC
DB 기록 : 2026-08-15 18:34:06.339985 ← KST
```

장애를 보는 사람이 두 시각을 맞춰 읽어야 하는 상태다. `TZ=Asia/Seoul`을 주면 로그도 KST로 찍혀 DB 값과 같은 축이 된다. 업무 로직은 어느 쪽이든 바뀌지 않는다 — 순전히 읽기 편하자고 맞추는 것이다.

### 잠금 대기 50초

MySQL의 `innodb_lock_wait_timeout` 기본값은 **50초**다. 개발용 H2는 3초라 16배 차이가 난다(실측). 매칭·세션 종료가 `SELECT … FOR UPDATE`로 행을 잡으므로, 경합이 나면 요청 하나가 최대 50초 동안 톰캣 스레드와 DB 커넥션을 함께 붙들고 있다가 503 `LOCK_ACQUISITION_FAILED`로 떨어진다.

커넥션 풀이 10개(HikariCP 기본값)라 이런 요청이 10개만 겹쳐도 풀이 비고, 뒤따르는 요청은 커넥션을 못 받아 30초 뒤 실패한다. 2코어 서버에서는 그 사이 스레드도 함께 묶인다.

코드를 고치지 않고 JDBC URL로 낮춘다(§6의 URL에 이미 넣어 두었다).

```
jdbc:mysql://127.0.0.1:3306/morak?sessionVariables=innodb_lock_wait_timeout=5
```

**이 파라미터가 있고 없고가 실제로 갈리는 것을 재 봤다.** `match:60` 잠금 행을 밖에서 잡아 둔 채
매칭 요청을 넣고, 동시에 무관한 조회(`/api/members/me`)가 되는지를 함께 봤다.

| | 매칭 요청 | 그 사이 무관한 조회 |
|---|---|---|
| URL에 넣었을 때 | **5초** 뒤 503 `LOCK_ACQUISITION_FAILED` | 200, 2초 |
| 넣지 않았을 때 | **50초** 뒤 503 (기본값 그대로) | **500, 30초** |

동시 11건이 잠금에 걸린 상태에서 잰 값이다. 아래 줄이 이 설정을 넣는 이유다 —
**잠금과 아무 상관 없는 조회까지 30초를 기다리다 500으로 죽는다.** 풀 고갈은 503처럼 다듬어진
응답이 아니라 그냥 500이라, 화면에서는 원인을 알 수 없는 오류로 보인다.

### 격리 수준이 H2와 다르다

MySQL InnoDB 기본은 `REPEATABLE-READ`이고 **개발용 H2는 `READ_COMMITTED`다**(실측 —
기동 로그의 `Isolation level: REPEATABLE_READ`로도 확인된다). 같은 트랜잭션 안에서 두 번 읽으면
MySQL은 처음 읽은 스냅샷을 계속 보여 준다. 네 여정을 MySQL에서 그대로 돌렸을 때 이 차이로
깨진 자리는 없었지만, 읽고→판단하고→쓰는 경로를 새로 만들 때는 H2에서 통과한 것이 근거가
되지 않는다는 뜻이다.

### CPU 2코어

매분 도는 배치와 조회 부하를 2코어로 묶어 실측했다.

- **B1 세션 종료 배치**: 50개 세션 300명을 종료·완주 판정·포인트 지급·Streak 기록까지 **1.4초**에 끝냈다. 실제 운영에서 한 분에 50개 세션이 동시에 끝날 일은 없으므로 여유가 크다.
- **조회 부하**: 동시 30으로 `/api/members/me`를 때렸을 때 앱 164% + MySQL 35%로 **2코어가 포화**했다. 다만 1,000요청이 전부 200으로 돌아왔고 실패가 없었다.

즉 배치가 밀릴 걱정은 없고, 한계는 동시 조회 쪽이다. 매칭 대기 화면이 폴링으로 동작하므로(D18) **폴링 주기가 짧으면 이 한계에 먼저 닿는다.** 대기자가 많아지면 폴링 간격을 늘리는 쪽이 서버를 키우는 것보다 싸다.

### 로그 양

운영 로그는 조용하다. 매분 도는 배치 3종은 처리 건수가 0이면 아무것도 남기지 않고, 1초마다 도는 재접속 유예 스위퍼는 메모리만 보므로 DB를 건드리지도 로그를 남기지도 않는다. 기동 완료 후 2분간(배치 2회 통과) 추가 로그가 한 줄도 없었다.

데이터가 든 상태(회원 46명·세션 13개)에서 3분을 그냥 두고 다시 재 봤다. **증가한 로그가 0줄이었다** — 배치가 매분 세 번 지나가는 동안에도 그렇다. 조용하다는 말은 사실이다.

따라서 로그가 쌓인다면 그것은 실제 처리 건수이거나 오류다. 배치 실패는 처음 두 번은 `WARN`(경합으로 보고 다음 회차에 맡긴다), 같은 대상이 3회 연속 실패하면 `ERROR`로 올라온다 — **`ERROR`로 올라온 배치 로그는 다음 회차가 알아서 해결해 주지 않는다는 뜻이므로 사람이 봐야 한다.**

**문제는 배치가 아니라 예외 하나의 크기다.** 잠금 대기 실패(`CannotAcquireLockException`) 한 건이
스택 트레이스 111줄, **약 15KB**를 남긴다(실측 — 11건에서 170KB). 유휴가 0바이트인 것과 대비된다.

| 잠금 실패 빈도 | 하루 | 10일 |
|---|---|---|
| 분당 1건 | 21MB | **213MB** |
| 분당 10건 | 213MB | **2.1GB** |

매칭이 몰리는 시간대에 경합이 나는 것은 정상 동작이고, 그때마다 이 크기가 쌓인다.
**그래서 journald 상한은 선택이 아니다.** 10일 쓰고 버릴 서버라 넉넉히 잡아도 500MB면 된다.

```bash
# /etc/systemd/journald.conf
SystemMaxUse=500M
```

```bash
sudo systemctl restart systemd-journald
journalctl --disk-usage          # 현재 사용량 확인
```

### 정렬 규칙이 대소문자를 구분하지 않는다

`utf8mb4_0900_ai_ci`에서 `'AbCdEf' = 'abcdef'`가 참이다. UNIQUE 제약도 같은 기준이라 대소문자만 다른 두 값이 중복으로 걸린다(실측: `Duplicate entry` 발생). H2는 구분하므로 개발에서 통과한 값이 운영에서 중복 판정될 수 있다.

영향을 받는 자리는 `store_order.idempotency_key`, `point_charge.pg_tid`, `member.provider_user_id`다. 실제로 부딪히려면 대소문자만 다른 값이 발급돼야 해서 확률은 낮고, 부딪히더라도 중복 주문·중복 적립을 막는 쪽으로 실패한다.

### 길이 제한은 코드포인트 기준

MySQL의 `VARCHAR(30)`은 바이트가 아니라 30자다. 한글 30자(90바이트)도 이모지 30자(120바이트)도 들어간다. 자바 `String.length()`는 UTF-16 단위라 이모지 30자를 60으로 세므로, Bean Validation이 DB보다 엄격하다. 검증을 통과한 값은 DB에도 들어간다.

---

## 9. 백업 — 8/28 삭제 대비

**서버가 8/28에 삭제된다. 그때 백업이 서버 안에만 있으면 함께 사라진다.**

### 매일 자동 덤프

`/opt/morak/backup.sh`:

```bash
#!/bin/bash
set -euo pipefail
BACKUP_DIR=/opt/morak/backups
STAMP=$(date +%Y%m%d-%H%M)
mkdir -p "$BACKUP_DIR"

# --single-transaction: InnoDB를 잠그지 않고 일관된 시점을 뜬다. 서비스 중에 돌려도 된다.
# --no-tablespaces: 이것이 없으면 morak 계정에 PROCESS 권한이 없어 아래 줄이 매번 찍힌다.
#   mysqldump: Error: 'Access denied; you need (at least one of) the PROCESS privilege(s)
#              for this operation' when trying to dump tablespaces
#   덤프 자체는 정상이고 종료 코드도 0이라 백업은 만들어지지만(확인함), 매일 새벽 로그에
#   Error가 한 줄씩 쌓여 진짜 실패와 구별되지 않는다. 우리는 테이블스페이스 정보가 필요 없다.
mysqldump -u morak -p"$MORAK_DB_PASSWORD" \
  --single-transaction --routines --triggers --no-tablespaces \
  --default-character-set=utf8mb4 morak \
  | gzip > "$BACKUP_DIR/morak-$STAMP.sql.gz"

# 7일치만 남긴다
find "$BACKUP_DIR" -name 'morak-*.sql.gz' -mtime +7 -delete
```

crontab (매일 새벽 3시. B4 탈퇴 파기가 4시라 그 전에 뜬다):

```
0 3 * * * MORAK_DB_PASSWORD='...' /opt/morak/backup.sh >> /var/log/morak-backup.log 2>&1
```

덤프는 작다. 회원 300명·세션 50개·원장 300행 기준으로 **143KB, gzip 후 16KB**였다(실측). 10일치를 다 모아도 수 MB다.

### 서버 밖으로 내보내기 (이것이 핵심이다)

서버 안의 백업은 8/28에 서버와 함께 사라진다. **최소 하루 한 번은 로컬로 내려받는다.**

```bash
# 로컬에서 실행
scp <계정>@<서버IP>:/opt/morak/backups/morak-*.sql.gz ./backups/
```

**8/28 서버 반납 전 반드시 할 일**

1. 마지막 덤프를 수동으로 뜨고 로컬로 내려받는다.
2. 복원이 되는지 로컬에서 확인한다(아래).
3. 덤프 파일을 팀 저장소가 아닌 곳에 둔다 — **덤프에는 회원 정보가 들어 있으므로 공개 저장소에 커밋하지 않는다.**

### 복원 절차

덤프에는 `CREATE TABLE`이 들어 있으므로 빈 DB에 그대로 넣으면 된다.

**`morak` 계정으로는 이 절차가 되지 않는다.** §4-2에서 `GRANT ALL ON morak.*`만 줬기 때문에
다른 이름의 DB를 만들 권한도, 거기에 쓸 권한도 없다. 그대로 하면 이렇게 막힌다(실측).

```
ERROR 1044 (42000): Access denied for user 'morak'@'localhost' to database 'morak_restore'
```

**복원은 `root`로 한다.** 복원본은 검증용이지 서비스가 붙는 DB가 아니므로 앱 계정에 권한을
넓히지 않는다.

```bash
mysql -u root -p -e "CREATE DATABASE morak_restore
  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"

gunzip -c morak-20260828-0300.sql.gz | mysql -u root -p --default-character-set=utf8mb4 morak_restore
```

확인 — 테이블 24개, 원장 행 수와 포인트 합, 한글이 깨지지 않았는지를 본다.

**`--default-character-set=utf8mb4`를 빼면 한글이 `????`로 보인다.** 데이터는 멀쩡한데 클라이언트가
latin1로 받아서 그렇다(실측 — 같은 행을 두 방식으로 읽어 `????`와 `탈퇴회원`을 각각 확인했다).
이것을 복원 실패로 오해하지 않는다. 의심되면 `SELECT HEX(nickname)`으로 바이트를 직접 본다.

```bash
mysql -u root -p --default-character-set=utf8mb4 -e "
SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'morak_restore';
SELECT COUNT(*) AS ledger, (SELECT SUM(point_balance) FROM morak_restore.member) AS points
  FROM morak_restore.point_ledger;
SELECT nickname FROM morak_restore.member ORDER BY id LIMIT 1;"
```

행 수만 맞춰 보는 것보다 확실한 방법이 있다. 원본과 복원본의 체크섬을 맞대 본다.

```bash
for t in member point_ledger store_order session_participant; do
  mysql -u root -p -N -e "CHECKSUM TABLE morak.$t; CHECKSUM TABLE morak_restore.$t;"
done
```

실측 결과: 24개 테이블 전부 행 수가 일치했고, 위 네 테이블의 체크섬이 원본과 같았다.
한글 닉네임·이모지(`🔥`)·`DATETIME(6)` 마이크로초가 모두 그대로 돌아왔다.

---

## 10. 뜬 뒤 확인 절차

### 10-1. 로그

정상 기동이면 마지막 두 줄이 아래와 같다.

```
Started MorakServerApplication in 3.3 seconds (process running for 3.6)
매칭 조건 잠금 행 시드 완료 — 조건 4종, 신규 4행
```

재기동에서는 `신규 0행`이 정상이다. 기동 로그에 SQL은 찍히지 않고 비밀값도 남지 않는다(주입한 9개 값이 로그에 한 번도 나타나지 않는 것을 확인했다).

### 10-2. 호출

```bash
# 소셜 키가 아직 없으면 401 INVALID_SOCIAL_TOKEN 이 정상이다
curl -s -X POST https://<도메인>/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"provider":"KAKAO","authorizationCode":"x","agreements":[]}'
# → {"error":{"code":"INVALID_SOCIAL_TOKEN", ...}}  HTTP 401

curl -s -o /dev/null -w '%{http_code}\n' https://<도메인>/api/store/products   # → 401
curl -s -o /dev/null -w '%{http_code}\n' https://<도메인>/api/dev/clock        # → 404
```

401·401·404면 프로필·인증 게이트·개발 API 이중 스위치가 모두 의도대로 걸린 것이다. 넷째로 **브라우저에서** 프론트를 열어 카메라 권한 요청이 뜨는지 본다 — 뜨지 않으면 HTTPS가 제대로 걸리지 않은 것이다(§7).

### 10-3. DB

```sql
SELECT COUNT(*) FROM match_lock;   -- 4
```

`ddl-auto: validate`라서 엔티티와 스키마가 어긋나면 애초에 기동하지 않는다. 뜬 것 자체가 스키마 정합성 확인이다.

### 10-4. 재시작

배포 중에 한 번은 하게 되므로 무엇이 살아남는지 알고 한다. 진행 중인 6인 세션과 열린 자리비움을
둔 채 실제로 껐다 켜 봤다.

```bash
sudo systemctl restart morak
sudo journalctl -u morak -n 20
```

- **다시 응답하기까지 4초.** 기동 자체는 3.1초, 첫 요청까지 포함해 4초였다.
- **진행 중이던 세션이 그대로 남는다.** 상태 `LIVE`, 참가자 6명, 적어 둔 할 일(이모지 포함)까지
  그대로였다. `/api/members/me`의 `activeSession`도 같은 값을 계속 준다 — DB에 있기 때문이다.
- **재시작 뒤에도 B1이 그 세션을 정상 종료시킨다.** 완주 판정과 포인트 지급이 평소대로 됐다.
- 시드는 `신규 0행`이 정상이다(§10-1).

사라지는 것은 **프로세스 메모리에만 있는 재접속 유예 90초**뿐이다(§12). 그 순간 끊겨 있던
사람은 유예가 초기화되므로, **세션이 도는 시간대는 피해서 재시작한다.** 진행 중인 세션은
이렇게 확인한다.

```sql
SELECT COUNT(*) FROM live_session WHERE status = 'LIVE';   -- 0일 때 올린다
```

---

## 11. 관리자 계정 만들기

관리자 역할은 **어떤 API로도 부여되지 않는다**(AU-1에 `role` 파라미터가 없다). 운영에서는 DB 직접 수정뿐이다.

`api-spec.md` DEV 절에 있는 시드 방법은 **개발 프로필 전용이다.** 그 절차의 마지막 단계가 `provider=DEV`로 로그인하는 것인데, 운영 프로필에는 `DevSocialClient` 빈이 없어 401로 떨어진다(실측 확인). 운영에서는 다음 순서로 한다.

1. 실제 소셜 키를 넣고 기동한다.
2. 관리자가 될 사람이 앱에서 평소대로 로그인한다. 이때 `member` 행이 만들어진다.
3. 그 행을 승격한다.

   ```sql
   SELECT id, nickname, provider, provider_user_id, created_at
     FROM member ORDER BY id DESC LIMIT 10;

   UPDATE member SET role = 'ADMIN' WHERE id = <대상 id>;
   ```

4. 재로그인은 필요 없다. JWT에는 회원 번호만 들어 있고 역할은 요청마다 DB에서 읽는다.

**소셜 키가 없는 동안에는 관리자 계정을 만들 수 없다.** 회원 행을 만들 경로 자체가 없기 때문이다.

---

## 12. 알려진 제약

### 단일 인스턴스 전제

서버가 한 대뿐이라 지금 구조로 맞지만, 늘리면 아래 둘이 깨진다.

- **배치가 인스턴스마다 돈다.** `@Scheduled`에 분산 잠금이 없다. 중복 지급은 `uk_pl_dedup` 같은 UNIQUE가 막지만 그 방어선에 기대는 상태다. 급하면 한 대만 `morak.scheduling.enabled=true`로 두고 나머지를 끈다.
- **재접속 유예 창이 프로세스 메모리에 있다.** 대수를 늘리면 A 서버에서 끊긴 사람을 B 서버가 모른다. 같은 이유로 **재기동하면 진행 중이던 90초 유예가 전부 사라진다** — 세션이 없는 시간대에 올리는 편이 낫다.

### 외부 키가 없는 동안의 정상 동작

카카오·LiveKit·토스 키가 아직 없으므로 그 자리는 전부 거절하는 기본 구현(`RejectingSocialClient`·`RejectingPgClient`)이 채운다. **아래는 고장이 아니라 설계된 동작이다.**

- AU-1 로그인 → 401 `INVALID_SOCIAL_TOKEN`
- PY-2 결제 승인 → 409 `PAYMENT_NOT_APPROVED`

통과시키는 스텁을 두지 않은 이유는 그 하나로 인증 없는 로그인과 결제 없는 적립이 함께 열리기 때문이다. 로그인이 막히므로 **키가 들어오기 전까지는 사실상 모든 API를 쓸 수 없다.**

### 스키마 변경

`ddl-auto: validate`라 운영 DB 스키마를 자동으로 바꾸지 않는다. 엔티티에 컬럼이 늘면 **`docs/db-schema.md`와 운영 DB에 같은 변경을 직접 넣어야 하고, 그 전에는 기동하지 않는다.**

```
SchemaManagementException: Schema validation: missing column [current_streak] in table [member]
```

이 게이트가 실제로 동작하는 것을 확인했다(컬럼을 하나 지우고 기동해 위 메시지로 실패). 배포 전에 엔티티 변경분이 DDL에 반영됐는지 확인하는 것이 순서다.

### 헬스체크 엔드포인트가 없다

Actuator를 넣지 않아 `/actuator/health`가 없고(404), 인증 없이 200을 돌려주는 경로도 없다. 서버가 한 대라 로드밸런서가 붙지 않으므로 당장은 `systemd`의 `Restart=on-failure`로 충분하다. 밖에서 죽었는지 보려면 `/api/store/products`가 401을 돌려주는지 확인한다 — 응답이 온다는 것 자체가 서블릿과 인증 게이트가 살아 있다는 뜻이다.

---

## 13. 로컬에서 같은 제약 재현하기

8/18 전에는 실기기가 없으므로 도커로 같은 제약을 건다. 이 문서의 수치가 전부 이 방법으로 나왔다.

**실측 소요 — 이미지가 받아져 있으면 전체 1분이 안 걸린다**(2026-08-15, macOS 14 / Docker 28.3.3).

| 단계 | 실측 |
|---|---|
| `./gradlew clean bootJar` | 2초(빌드 캐시). 캐시가 없으면 1~3분 |
| MySQL 컨테이너 기동 → `mysqld is alive` | **6초** |
| `schema.sql` 생성 + 주입 + 테이블 24개 확인 | 1초 미만 |
| 앱 컨테이너 기동 → `Started MorakServerApplication` | **5초**(앱 자체는 3.2초) |
| §10-2·§10-3 확인(401·401·404·`match_lock` 4) | 1초 미만 |

**당일 절차와 같은 것은 4~9번(스키마 → 기동 → 확인)이다.** 서버 준비(1~3)와 nginx·인증서(10)는
도커로 대신할 수 없다. 그래도 이 예행으로 **스키마 주입과 기동 확인을 미리 한 번 해 보는 값이
크다** — 당일에 처음 겪으면 원인을 찾는 데만 시간이 간다.

> **포트 3306이 이미 잡혀 있는 경우가 흔하다.** 로컬에 MySQL이나 MariaDB가 떠 있으면 아래
> `docker run`이 실패한다(실제로 그랬다). 호스트 쪽 숫자만 바꾸면 된다 — `-p 3316:3306`.
> 컨테이너끼리는 `morak-net` 안에서 3306으로 통하므로 앱의 `MORAK_DB_URL`은 그대로 둔다.

```bash
docker network create morak-net

# MySQL — 2코어 공유, §4-1 설정 적용
# 호스트 포트: 로컬에 이미 MySQL·MariaDB가 떠 있으면 3306이 잡혀 있어 이 명령이 실패한다.
# 그때는 왼쪽 숫자만 비어 있는 포트로 바꾼다(-p 3316:3306). 컨테이너끼리는 morak-net 안에서
# 3306으로 통하므로 앱 쪽 MORAK_DB_URL은 바꾸지 않아도 된다.
docker run -d --name morak-mysql --network morak-net \
  --cpuset-cpus=0,1 --memory=1500m --memory-swap=1500m \
  -e MYSQL_ROOT_PASSWORD=rootpw -e MYSQL_DATABASE=morak \
  -e MYSQL_USER=morak -e MYSQL_PASSWORD=morakpw \
  -p 3306:3306 mysql:8.4 \
  --character-set-server=utf8mb4 --collation-server=utf8mb4_0900_ai_ci \
  --performance_schema=OFF --innodb_buffer_pool_size=256M \
  --innodb_buffer_pool_instances=1 --max_connections=50
```

MySQL이 뜨는 데 15초쯤 걸린다. `docker exec morak-mysql mysqladmin ping -uroot -prootpw`가
`mysqld is alive`를 돌려주면 준비된 것이다.

**스키마를 먼저 넣어야 앱이 뜬다.** `ddl-auto: validate`라 테이블이 없으면 기동에 실패한다.
§4-3으로 `schema.sql`을 만든 뒤 컨테이너 안으로 흘려 넣는다 — 서버에서 쓰는
`mysql -u morak -p morak < schema.sql`은 컨테이너에는 그대로 쓸 수 없다.

```bash
docker exec -i morak-mysql mysql -umorak -pmorakpw morak < schema.sql

# 24가 나와야 한다
docker exec morak-mysql mysql -umorak -pmorakpw -N \
  -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='morak';"
```

```bash
# 앱 — 같은 2코어를 나눠 쓴다. 리눅스에서 도는 것도 함께 확인된다.
docker run -d --name morak-app --network morak-net \
  --cpuset-cpus=0,1 --memory=1200m --memory-swap=1200m \
  -v "$PWD/build/libs/morak-server-0.0.1-SNAPSHOT.jar:/app/app.jar:ro" \
  -e SPRING_PROFILES_ACTIVE=prod -e SERVER_PORT=8110 -e TZ=Asia/Seoul \
  -e MORAK_DB_URL="jdbc:mysql://morak-mysql:3306/morak?sessionVariables=innodb_lock_wait_timeout=5" \
  -e MORAK_DB_USERNAME=morak -e MORAK_DB_PASSWORD=morakpw \
  -e MORAK_JWT_SECRET="로컬검증용-32바이트-이상-키-0123456789" \
  -e MORAK_SOCIAL_HASH_PEPPER=x -e MORAK_LIVEKIT_HOST=wss://x \
  -e MORAK_LIVEKIT_API_KEY=x -e MORAK_LIVEKIT_API_SECRET=x \
  -e MORAK_PG_SECRET_KEY=x \
  -p 8110:8110 eclipse-temurin:21-jre \
  java -Xms256m -Xmx512m -jar /app/app.jar
```

`MORAK_DB_URL`의 `sessionVariables`는 §6의 운영 URL과 같아야 한다. 이전 판에는 이 부분이 빠져
있었는데, 그러면 **재현 환경만 잠금 대기 50초로 돌아가** §8에서 막으려는 상황을 그대로 재현하지
못한다. 실제로 이 차이 하나로 무관한 조회가 200/2초에서 500/30초로 갈렸다.

### 키가 오기 전에 여정을 끝까지 확인하는 법

§10-2까지는 "거절이 제대로 되는지"만 본다. 그런데 §12대로 **운영 프로필에서는 로그인이 401이라,
가입부터 주문까지가 실제로 이어지는지를 확인할 방법이 문서에 없었다.** 소셜 키가 8/18에 함께
오지 않으면 서버가 멀쩡한지 아닌지를 모른 채 기다리게 된다.

확인하려면 **DB는 운영 그대로 두고 소셜·결제 자리만 여는 조합**으로 한 번 더 띄운다.
프로필을 `dev,prod` 순으로 주면 뒤에 온 `prod`가 값 충돌을 이기므로 **MySQL 접속과
`ddl-auto: validate`는 운영 설정 그대로**이고, `dev`가 활성이라 `DevSocialClient`·`DevPgClient`와
`/api/dev/*`(시계 조작·배치 수동 실행)만 살아난다.

```bash
# 운영과 같은 MySQL·같은 스키마. 다른 것은 로그인·결제 스텁과 dev API뿐이다.
docker run -d --name morak-app-smoke --network morak-net \
  --cpuset-cpus=0,1 --memory=1200m --memory-swap=1200m \
  -v "$PWD/build/libs/morak-server-0.0.1-SNAPSHOT.jar:/app/app.jar:ro" \
  -e SPRING_PROFILES_ACTIVE=dev,prod -e SERVER_PORT=8111 -e TZ=Asia/Seoul \
  -e MORAK_DEV_ENABLED=true \
  -e MORAK_DB_URL="jdbc:mysql://morak-mysql:3306/morak?sessionVariables=innodb_lock_wait_timeout=5" \
  -e MORAK_DB_USERNAME=morak -e MORAK_DB_PASSWORD=morakpw \
  -e MORAK_JWT_SECRET="로컬검증용-32바이트-이상-키-0123456789" \
  -e MORAK_SOCIAL_HASH_PEPPER=x -e MORAK_LIVEKIT_HOST=wss://x \
  -e MORAK_LIVEKIT_API_KEY=x -e MORAK_LIVEKIT_API_SECRET=x -e MORAK_PG_SECRET_KEY=x \
  -p 8111:8111 eclipse-temurin:21-jre \
  java -Xms256m -Xmx512m -jar /app/app.jar
```

기동 로그에 `Database dialect: MySQLDialect`와 `신규 0행`이 보이면 운영 DB를 그대로 쓰고 있는
것이다. 이 상태에서 `authorizationCode`가 그대로 회원 식별자가 되므로 여섯 명을 만들어
매칭·세션·완주·주문까지 HTTP로 밟을 수 있고, 시계는 `POST /api/dev/clock`으로 민다.

```bash
curl -s -X POST localhost:8111/api/dev/clock -H 'Content-Type: application/json' \
  -d '{"fixedAt":"2026-08-20T09:00:00"}'
curl -s -X POST localhost:8111/api/auth/login -H 'Content-Type: application/json' \
  -d '{"provider":"KAKAO","authorizationCode":"smoke-1","agreements":[
       {"type":"TOS","agreed":true},{"type":"PRIVACY","agreed":true}]}'
curl -s -X POST localhost:8111/api/dev/batches/B1   # 배치를 기다리지 않고 바로 돌린다
```

**확인이 끝나면 이 컨테이너를 반드시 지운다.** 이 조합은 인증 없는 로그인과 결제 없는 적립이
동시에 열린 상태다 — 공개된 포트에 이대로 두면 누구나 아무 계정으로 로그인해 포인트를 채울 수
있다. 실서비스로 여는 것은 언제나 `SPRING_PROFILES_ACTIVE=prod` 하나뿐이고,
`MORAK_DEV_ENABLED`는 운영 `/etc/morak/env`에 **절대 넣지 않는다.**

```bash
docker rm -f morak-app-smoke
```

**컨테이너를 지워도 DB에 남는 것이 있다.** `dev` 프로필에는 `ProductSeeder`가 있어 기동만 해도
개발용 상품 4종이 들어가고, 확인하며 만든 회원도 그대로 남는다(실측).

```
1  편의점 5,000원 금액권   5500  42  ON_SALE
2  카페 아메리카노 교환권   4900   1  ON_SALE
3  합격을 부르는 공부법    14000   0  SOLD_OUT
4  출간 예정 제휴 도서     12000  20  HIDDEN
```

**그러므로 이 확인은 실서비스를 열기 전에 한다.** 순서를 지키면 뒷정리가 필요 없다 —
스모크로 여정을 확인하고, DB를 지우고 §4-3으로 스키마를 다시 만든 다음, 운영 프로필로 올린다.

```bash
# 스모크가 끝난 뒤 DB를 원래대로 (운영 시작 전에만 한다)
mysql -u root -p -e "DROP DATABASE morak;
  CREATE DATABASE morak CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"
mysql -u morak -p morak < schema.sql
```

이미 서비스가 돌기 시작한 뒤라면 DB를 지울 수 없으므로 남은 것만 골라 지운다. 상품은
사용자에게 보이므로 특히 그렇다.

```sql
DELETE FROM product WHERE name IN ('편의점 5,000원 금액권','카페 아메리카노 교환권',
                                   '합격을 부르는 공부법','출간 예정 제휴 도서');
SELECT id, nickname, provider_user_id FROM member WHERE provider_user_id LIKE 'smoke-%';
```

### 자원 보기

`--cpuset-cpus=0,1`이 두 컨테이너를 같은 2코어에 묶는 부분이 핵심이다. 메모리·CPU 사용량은 이렇게 본다.

```bash
docker stats --no-stream --format "{{.Name}} mem={{.MemUsage}} cpu={{.CPUPerc}}" morak-app morak-mysql
```

한계는 알고 쓴다 — 도커 컨테이너는 호스트 커널을 공유하고 `free`가 호스트 메모리를 보여 준다. **JVM은 cgroup 제한을 읽으므로 힙 계산은 실제와 같지만, OS가 쓰는 몫은 이 방법으로 재지지 않는다.** §2의 OS 몫을 추정값으로 적어 둔 이유다.

---

## 14. 확인한 것과 확인하지 못한 것

2코어·메모리 제한을 건 리눅스 컨테이너에서 MySQL 8.4.11과 함께 띄워 확인한 것:

- 문서 DDL로 만든 스키마가 `validate`를 통과한다(24개 테이블).
- 환경변수 9개를 하나씩 빼면 각각 기동에 실패한다.
- 비밀값이 로그에 남지 않는다.
- 소셜 로그인 거절(401), 개발 API 404, 인증 게이트 401.
- 앱 513MB + MySQL 181MB로 4GB 안에 넉넉히 들어간다.
- B1이 50세션 300명을 1.4초에 처리한다.
- 동시 30 조회에서 2코어가 포화하지만 1,000요청이 전부 200이다.
- 시각이 타임존 차이로 밀리지 않고 `DATETIME(6)` 마이크로초가 보존된다.
- mysqldump 덤프와 복원이 한글까지 그대로 왕복한다.
- CORS 헤더가 붙지 않는다(그래서 §7의 리버스 프록시가 필요하다).

**2차 리허설에서 MySQL로 확인한 것** (네 여정을 전부 HTTP 호출로 다시 밟았다):

- **여정 4종이 MySQL에서 전부 통과한다.** 가입→목표→매칭(6인)→세션→완주→포인트→주문,
  퇴출→이의→인용→환급, 7일 목표 달성(자정을 넘기는 날 포함), 탈퇴→철회→재신청→파기.
  H2에서 통과하고 MySQL에서 깨진 자리는 없었다.
- 대소문자 무시 정렬이 §8이 지목한 세 컬럼 전부에서 실제로 중복 판정을 낸다
  (`store_order.idempotency_key`·`point_charge.pg_tid`·`member.provider_user_id`).
  주문 멱등키를 대문자로 바꿔 다시 보내면 `DUPLICATE_ORDER`로 막힌다 — 막는 쪽으로 실패한다.
- 길이 제한이 코드포인트 기준이다. 한글 30자·이모지 30자가 `VARCHAR(30)`에 들어가고 31자는
  거절된다. Bean Validation이 항상 DB보다 먼저 막는다(이모지 40자는 `VALIDATION_FAILED`,
  32자는 통과 — 통과한 값은 DB에도 들어간다).
- 전 컬럼이 utf8mb4이고 한글·이모지가 API→DB→덤프→복원까지 그대로 왕복한다.
- 잠금 대기 5초 설정이 실제로 동작하고, 없을 때와 200/2초 대 500/30초로 갈린다(§8).
- 재시작이 진행 중인 세션을 보존한다(§10-4).
- 유휴 3분간 로그 증가가 0줄이다. 대신 잠금 실패 1건이 15KB를 남긴다(§8).
- 백업·복원이 실제로 된다. 24개 테이블 행 수 일치, 표본 4개 테이블 체크섬 일치.

확인하지 못한 것:

- **가비아 클라우드 실기기.** OS 이미지·디스크 성능·방화벽 기본값을 모른다. 8/18에 서버를 받으면 §10을 그대로 한 번 돌려 본다.
- OS가 실제로 쓰는 메모리(§13의 한계).
- 외부 연동 3종(카카오·LiveKit·토스)의 실제 동작. 키가 없어 거절 경로만 확인했다.
- HTTPS·도메인·nginx 구성. 도메인이 없어 문서상 구성만 적었다.
- 6인이 실제로 캠을 켠 상태의 부하. LiveKit 미디어는 서버를 거치지 않으므로 API 서버 부하와는 별개다.
- **`systemd`·`cron`으로 도는 형태.** 리허설은 도커 컨테이너로 했다. §6의 유닛 파일과 §9의
  crontab 자체는 실행해 보지 못했고, 그 안의 명령만 따로 확인했다.
- **10일치 실제 누적.** 로그·디스크 증가는 분당 측정값에서 계산한 값이다.
