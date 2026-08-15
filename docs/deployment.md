# MoLock 서버 배포 안내

운영 프로필(`prod`)로 실제 서버에 올리는 절차다. 이 문서의 명령과 확인 절차는 MySQL 8.4.11 + JDK 21/24 조합에서 실제로 실행해 확인한 것이다.

정본 참조: 스키마는 `docs/db-schema.md`, API 계약은 `docs/api-spec.md`.

---

## 1. 준비물

| 항목 | 요구 | 비고 |
|---|---|---|
| 빌드 JDK | 21 | `build.gradle`의 툴체인이 21로 고정돼 있다. 다른 버전으로는 빌드가 21을 찾거나 받아온다 |
| 실행 JRE | 21 이상 | 클래스 파일이 major 65(Java 21)다. 24.0.1에서도 기동을 확인했다 |
| DB | MySQL 8, InnoDB, utf8mb4 | 8.4.11에서 확인. 문자셋 `utf8mb4`, 정렬 `utf8mb4_0900_ai_ci` |
| 메모리 | 1GB 이상 권장 | 유휴 상태 RSS 실측 약 384MB(힙 사용 58MB + 메타스페이스 92MB) |

서버 OS 타임존은 맞추지 않아도 된다. 애플리케이션 시각은 `morak.timezone`(Asia/Seoul)으로 고정된 `Clock` 빈에서만 나오고, DB에 쓰는 `LocalDateTime`은 JDBC가 타임존 변환 없이 그대로 넣는다. JVM을 UTC로, DB를 UTC로 두고 왕복시켜도 값이 밀지 않는 것을 확인했다.

---

## 2. 환경변수

**9개 전부 필수다.** 하나라도 없으면 기동하지 않는다(폴백을 두지 않은 설계다 — 폴백이 있으면 운영에서 변수를 빠뜨려도 개발용 비밀값으로 조용히 뜬다).

| 변수 | 의미 |
|---|---|
| `MORAK_DB_URL` | JDBC 접속 URL. `jdbc:mysql://호스트:3306/morak` |
| `MORAK_DB_USERNAME` | DB 계정 |
| `MORAK_DB_PASSWORD` | DB 비밀번호 |
| `MORAK_JWT_SECRET` | JWT 서명 키. HS256이므로 **32바이트 이상**이어야 한다 |
| `MORAK_SOCIAL_HASH_PEPPER` | 탈퇴자 소셜 해시(`blocked_social_hash`)에 섞는 값. **바뀌면 기존 차단 등재가 전부 무효가 된다** |
| `MORAK_LIVEKIT_HOST` | LiveKit 서버 주소(`wss://…`). 비밀값은 아니지만 환경마다 갈려 같은 방식으로 주입한다 |
| `MORAK_LIVEKIT_API_KEY` | LiveKit 접속 토큰 서명용 키 |
| `MORAK_LIVEKIT_API_SECRET` | 같은 용도의 시크릿 |
| `MORAK_PG_SECRET_KEY` | 결제(토스) 시크릿 키 |

그 밖의 정책값(포인트 액수, 매칭 대기 시간, 경고 임계 등)은 `application.yml`에 있고 환경변수가 아니다.

기동 실패 메시지는 변수에 따라 친절함이 다르다. `MORAK_` 접두사 6종(JWT·PEPPER·LIVEKIT 3종·PG)은 `Could not resolve placeholder 'MORAK_JWT_SECRET'`처럼 이름을 말해 준다. **DB 3종은 그렇지 않다** — 값이 `${MORAK_DB_PASSWORD}` 문자열 그대로 전달돼, 비밀번호 누락이 `Access denied for user 'morak'@'…'`로 보인다. DB 접속이 거부되면 비밀번호가 틀린 것인지 변수가 안 들어온 것인지 먼저 확인한다.

---

## 3. 빌드

```bash
./gradlew clean bootJar
```

결과물은 `build/libs/morak-server-0.0.1-SNAPSHOT.jar`(약 70MB, 실행 가능 fat jar)다. 같은 디렉터리에 생기는 `-plain.jar`는 실행용이 아니다.

테스트까지 돌리려면 `./gradlew build`를 쓴다.

---

## 4. 최초 배포: DB 준비

### 4-1. 데이터베이스와 계정

```sql
CREATE DATABASE morak CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER 'morak'@'%' IDENTIFIED BY '<비밀번호>';
GRANT ALL ON morak.* TO 'morak'@'%';
FLUSH PRIVILEGES;
```

정렬을 `utf8mb4_0900_ai_ci`로 두는 것은 MySQL 8 기본값이라 그렇다. 이 정렬은 **대소문자를 구분하지 않는다**(§8 참조).

### 4-2. 스키마 생성

DDL 정본은 `docs/db-schema.md`다. 문서 안의 sql 코드 블록을 위에서 아래 순서대로 실행하면 된다(FK 대상이 항상 먼저 오고, 순환하는 FK 2건은 문서 말미 `ALTER`로 분리돼 있다). 총 24개 테이블이다.

문서에서 DDL만 뽑아 한 파일로 만들려면 아래를 `morak-server` 디렉터리에서 실행한다.

````bash
python3 - <<'EOF' > schema.sql
import re
src = open('docs/db-schema.md', encoding='utf-8').read()
blocks = re.findall(r'```sql\n(.*?)```', src, re.S)
print('\n'.join(b for b in blocks if re.search(r'CREATE TABLE|ALTER TABLE', b)))
EOF

mysql -h <호스트> -u morak -p morak < schema.sql
````

적용 후 테이블이 24개인지 확인한다.

```sql
SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'morak';
```

### 4-3. match_lock 조건 행

수동 작업이 필요 없다. `MatchLockSeeder`가 기동할 때마다 `morak.match.target-minutes-options`(60·120·180·240)에 해당하는 잠금 행을 채우고, 이미 있으면 넘어간다. 회원별 잠금 행(`member:{id}`)은 가입 트랜잭션이 함께 넣는다.

---

## 5. 실행

```bash
export SPRING_PROFILES_ACTIVE=prod
export SERVER_PORT=8080
export MORAK_DB_URL="jdbc:mysql://127.0.0.1:3306/morak"
export MORAK_DB_USERNAME=morak
export MORAK_DB_PASSWORD='...'
export MORAK_JWT_SECRET='...'                  # 32바이트 이상
export MORAK_SOCIAL_HASH_PEPPER='...'
export MORAK_LIVEKIT_HOST='wss://...'
export MORAK_LIVEKIT_API_KEY='...'
export MORAK_LIVEKIT_API_SECRET='...'
export MORAK_PG_SECRET_KEY='...'

java -Xms256m -Xmx512m -jar morak-server-0.0.1-SNAPSHOT.jar
```

`-Xmx`를 명시하는 이유는 JVM 기본 최대 힙이 물리 메모리의 1/4이라 서버 사양에 따라 값이 널뛰기 때문이다. 소형 서버(1~2GB)에서는 512m가 무난하다.

### systemd 예시

비밀값을 유닛 파일에 직접 적지 않고 별도 파일(`/etc/morak/env`, 권한 600)로 분리한다.

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

`EnvironmentFile`은 `KEY=value` 형식이고 따옴표를 값의 일부로 읽으므로 감싸지 않는다.

---

## 6. 뜬 뒤 확인 절차

### 6-1. 로그

정상 기동이면 마지막 두 줄이 아래와 같다.

```
Started MorakServerApplication in 2.9 seconds (process running for 3.1)
매칭 조건 잠금 행 시드 완료 — 조건 4종, 신규 4행
```

재기동에서는 `신규 0행`이 정상이다(이미 시드돼 있다는 뜻).

기동 로그에 SQL은 찍히지 않고 비밀값도 남지 않는다. 실제로 주입한 9개 값이 로그에 한 번도 나타나지 않는 것을 확인했다.

### 6-2. 호출

```bash
# 소셜 키가 아직 없으면 401 INVALID_SOCIAL_TOKEN 이 정상이다
curl -s -X POST localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"provider":"KAKAO","authorizationCode":"x","agreements":[]}'
# → {"error":{"code":"INVALID_SOCIAL_TOKEN", ...}}  HTTP 401

# 인증 게이트가 살아 있는지
curl -s -o /dev/null -w '%{http_code}\n' localhost:8080/api/store/products
# → 401

# 개발 전용 API가 운영에서 닫혀 있는지
curl -s -o /dev/null -w '%{http_code}\n' localhost:8080/api/dev/clock
# → 404
```

세 개가 각각 401·401·404면 프로필·게이트·이중 스위치가 모두 의도대로 걸린 것이다.

### 6-3. DB

```sql
SELECT COUNT(*) FROM match_lock;   -- 4
```

`ddl-auto: validate`라서 엔티티와 스키마가 어긋나면 애초에 기동하지 않는다. 뜬 것 자체가 스키마 정합성 확인이다.

---

## 7. 관리자 계정 만들기

관리자 역할은 **어떤 API로도 부여되지 않는다**(AU-1에 `role` 파라미터가 없다). 운영에서는 DB 직접 수정뿐이다.

`api-spec.md` DEV 절에 있는 시드 방법은 **개발 프로필 전용이다.** 그 절차의 마지막 단계가 `provider=DEV`로 로그인하는 것인데, 운영 프로필에는 `DevSocialClient` 빈이 없어 401로 떨어진다(실측 확인). 운영에서는 다음 순서로 한다.

1. 실제 소셜 키를 넣고 기동한다.
2. 관리자가 될 사람이 앱에서 평소대로 로그인한다. 이때 `member` 행이 만들어진다.
3. 그 행을 승격한다.

   ```sql
   SELECT id, nickname, provider, provider_user_id, created_at
     FROM member ORDER BY id DESC LIMIT 10;   -- 대상 확인

   UPDATE member SET role = 'ADMIN' WHERE id = <대상 id>;
   ```

4. 재로그인은 필요 없다. JWT에는 회원 번호만 들어 있고 역할은 요청마다 DB에서 읽는다.

**소셜 키가 없는 동안에는 관리자 계정을 만들 수 없다.** 회원 행을 만들 경로 자체가 없기 때문이다. 그 기간에는 신고 처리·이의 심사 같은 관리자 API(AD-1~9)를 쓸 수 없다.

---

## 8. 알려진 제약

### 단일 인스턴스 전제

지금 구조는 서버를 여러 대로 늘리면 깨진다. 늘리기 전에 아래 둘을 먼저 해결해야 한다.

- **배치가 인스턴스마다 돈다.** `@Scheduled`에 분산 잠금이 없어, 2대면 세션 종료·매칭 만료·충전 만료 배치가 같은 시각에 양쪽에서 실행된다. 포인트 중복 지급은 `uk_pl_dedup` 같은 UNIQUE 제약이 막지만, 그 방어선에 기대는 상태이고 로그에는 경합 경고가 계속 쌓인다. 급하면 한 대만 `morak.scheduling.enabled=true`로 두고 나머지는 `false`로 끄는 방법이 있다.
- **재접속 유예 창이 프로세스 메모리에 있다.** 90초 유예를 추적하는 레지스트리가 인메모리라 대수를 늘리면 A 서버에서 끊긴 사람을 B 서버가 모른다. 같은 이유로 **재기동하면 진행 중이던 유예가 전부 사라진다** — 무중단 배포가 아니라면 세션이 없는 시간대에 올리는 편이 낫다.

### 외부 키가 없는 동안의 정상 동작

카카오·LiveKit·토스 키가 아직 없으므로 그 자리는 전부 거절하는 기본 구현(`RejectingSocialClient`·`RejectingPgClient`)이 채운다. **아래는 고장이 아니라 설계된 동작이다.**

- AU-1 로그인 → 401 `INVALID_SOCIAL_TOKEN`
- PY-2 결제 승인 → 409 `PAYMENT_NOT_APPROVED`

통과시키는 스텁을 두지 않은 이유는 그 하나로 인증 없는 로그인과 결제 없는 적립이 함께 열리기 때문이다. 로그인이 막히므로 **키가 들어오기 전까지는 사실상 모든 API를 쓸 수 없다.**

### 스키마 변경

`ddl-auto: validate`라 운영 DB 스키마를 자동으로 바꾸지 않는다. 엔티티에 컬럼이 늘면 **`docs/db-schema.md`와 운영 DB에 같은 변경을 직접 넣어야 하고, 그 전에는 기동하지 않는다.** 실패 메시지는 원인을 정확히 짚어 준다.

```
SchemaManagementException: Schema validation: missing column [current_streak] in table [member]
```

이 게이트는 실제로 동작하는 것을 확인했다(컬럼을 하나 지우고 기동해 위 메시지로 실패하는 것을 확인). 배포 전에 엔티티 변경분이 DDL에 반영됐는지 확인하는 것이 순서다.

### 헬스체크 엔드포인트가 없다

Actuator를 넣지 않아 `/actuator/health`가 없고(404), 인증 없이 200을 돌려주는 경로도 없다. 프로세스 감시는 systemd로 되지만, **로드밸런서나 모니터링을 붙이려면 헬스체크 경로가 필요하다.** 그때까지의 임시 확인은 `/api/store/products`가 401을 돌려주는지 보는 것이다(응답이 온다는 것 자체가 서블릿과 인증 게이트가 살아 있다는 뜻이다). 다만 401을 정상으로 읽어 주는 LB 설정이 필요하다.

---

## 9. 운영에서 눈여겨볼 값

### 잠금 대기 50초

MySQL의 `innodb_lock_wait_timeout` 기본값은 **50초**다. 개발용 H2는 `LOCK_TIMEOUT=3000`(3초)이라 여기서 16배 차이가 난다. 매칭·세션 종료 경로가 `SELECT … FOR UPDATE`로 행을 잡으므로, 경합이 나면 요청 하나가 최대 50초 동안 톰캣 스레드와 DB 커넥션을 함께 붙들고 있다가 503 `LOCK_ACQUISITION_FAILED`로 떨어진다.

커넥션 풀이 10개(HikariCP 기본값)이므로 이런 요청이 10개만 겹쳐도 풀이 비고, 뒤따르는 요청은 커넥션을 못 받아 30초 뒤 실패한다. H2에서는 3초 만에 풀려 눈에 띄지 않던 구간이다.

값을 낮추려면 코드를 고치지 않고 JDBC URL에 붙이면 된다. 아래가 실제로 세션 변수에 반영되는 것을 확인했다.

```
jdbc:mysql://호스트:3306/morak?sessionVariables=innodb_lock_wait_timeout=5
```

### 로그

운영 로그는 조용하다. 매분 도는 배치 3종(세션 종료·매칭 만료·충전 만료)은 처리 건수가 0이면 아무것도 남기지 않고, 1초마다 도는 재접속 유예 스위퍼는 메모리만 보므로 DB를 건드리지도 로그를 남기지도 않는다. 기동 완료 후 2분간 관찰했을 때 추가 로그가 한 줄도 없었다.

따라서 로그가 쌓인다면 그것은 실제 처리 건수이거나 오류다. 배치 실패는 처음 두 번은 `WARN`(경합으로 보고 다음 회차에 맡긴다), 같은 대상이 3회 연속 실패하면 `ERROR`로 올라온다 — **`ERROR`로 올라온 배치 로그는 다음 회차가 알아서 해결해 주지 않는다는 뜻이므로 사람이 봐야 한다.**

로그 레벨은 따로 지정하지 않아 Spring Boot 기본값(`INFO`)이고 파일 출력 설정도 없다. systemd로 띄우면 journald가 받는다. 파일로 남기려면 `logging.file.name`을 준다.

### 커넥션 풀

HikariCP 기본값 그대로 10개이며, 기동 직후 10개를 모두 열어 둔다. MySQL 기본 `max_connections`는 151이라 단일 인스턴스에서는 여유가 있다. 인스턴스를 늘리면 대수 × 10으로 늘어난다.

### 정렬 규칙이 대소문자를 구분하지 않는다

`utf8mb4_0900_ai_ci`에서 `'AbCdEf' = 'abcdef'`가 참이다. UNIQUE 제약도 같은 기준이라 대소문자만 다른 두 값이 중복으로 걸린다(실측: `Duplicate entry` 발생). H2는 구분하므로 개발에서 통과한 값이 운영에서 중복 판정될 수 있다.

영향을 받는 자리는 `store_order.idempotency_key`(클라이언트 생성 UUID), `point_charge.pg_tid`(PG 거래번호), `member.provider_user_id`다. 실제로 부딪히려면 대소문자만 다른 값이 발급돼야 해서 확률은 낮고, 부딪히더라도 중복 주문·중복 적립을 막는 쪽으로 실패한다. 다만 정상 건이 거절될 수 있다는 것은 알고 있어야 한다. 구분이 필요해지면 해당 컬럼만 `utf8mb4_0900_as_cs`로 바꾼다.

### 길이 제한은 코드포인트 기준

MySQL의 `VARCHAR(30)`은 바이트가 아니라 30자다. 한글 30자(90바이트)도 이모지 30자(120바이트)도 들어간다. 자바 `String.length()`는 UTF-16 단위라 이모지 30자를 60으로 세므로, Bean Validation이 DB보다 엄격하다. 검증을 통과한 값은 DB에도 들어간다.

---

## 10. 확인한 것과 확인하지 못한 것

실제로 MySQL 8.4.11을 띄우고 운영 프로필로 기동해 확인한 것:

- 문서 DDL로 만든 스키마가 `validate`를 통과한다(24개 테이블).
- 환경변수 9개를 하나씩 빼면 각각 기동에 실패한다.
- 비밀값이 로그에 남지 않는다.
- 소셜 로그인 거절, 개발 API 404, 인증 게이트 401.
- 시각이 타임존 차이로 밀리지 않고 `DATETIME(6)`의 마이크로초가 보존된다.
- 유휴 상태에서 로그가 늘지 않는다.

확인하지 못한 것:

- 실제 배포 서버의 사양과 OS(부트캠프 제공분이 정해지지 않았다). 위 메모리 수치는 개발 장비 실측이다.
- 외부 연동 3종(카카오·LiveKit·토스)의 실제 동작. 키가 없어 거절 경로만 확인했다.
- 부하 상태의 잠금 경합. 50초 대기는 DB 수준에서 재현했고, 애플리케이션 경로로는 재현하지 않았다.
