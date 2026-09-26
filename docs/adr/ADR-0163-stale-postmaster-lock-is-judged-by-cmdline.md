---
id: ADR-0163
status: 채택됨
date: 2026-09-26
applies_to:
  - "ops/**"
  - "src/main/resources/application.yaml"
---

# ADR-0163. stale한 postmaster 락은 명령줄로 판정한다

## 결정

**`ops/`가 postgres의 stale 락을 스스로 판정해 걷는다.** `ops/start.sh`는 앱을 띄우기 전에
`ensure_postgres_up`을 태우고, 그 안의 `classify_postmaster_lock`이 `postmaster.pid`를
`NONE`·`UNREADABLE`·`LIVE`·`STALE`로 가른다. **판정 기준은 1행 PID의 명령줄이 이 datadir의
`postgres`인가 하나뿐이다.**

이 파일 머리가 우리 앱·터널에 대해 이미 적은 판정과 **같은 것이다**(ADR-0043 §규칙 —
"살아 있는지는 그 PID의 명령줄이 우리 것인가까지 확인한다"). postgres 락 파일만 그 밖에 있었다.

## 규칙

- **MUST: 살아 있는 PID를 만나면 명령줄까지 본다.** `postgres` + `-D <같은 datadir>`이면
  `LIVE`, 아니면 `STALE`이다. PID가 살아 있다는 것만으로 `LIVE`로 보내면 postgres 자신의
  판정을 그대로 베끼는 것이라 아무것도 고치지 못한다.
- **MUST NOT: 부팅 시각(락 3행 epoch)으로 stale을 판정하지 않는다.** 그 대조는 「부팅 뒤에
  죽은 postmaster」를 **원리적으로 못 잡는다** — 2026-09-20이 그 형태였다(부팅 없이 죽어
  132분 동안 stale 락이 있었다). 이 기계는 부팅 후 약 70분이면 PID 공간이 한 바퀴 돌아
  (실측 약 24 PID/초) 그 창 안에서도 재사용이 일어난다. **명령줄 대조가 양방향으로 면역이고
  부팅 시각은 사정거리만 좁힌다.**
- **MUST: 판정하지 못한 것은 지우지 않는다.** 1행이 숫자가 아니거나 2행이 없으면
  `UNREADABLE`이고, 그것은 `STALE`이 아니다. 판정 못 한 것을 지우는 쪽으로 보내면 살아 있는
  DB의 락을 지우고 **같은 datadir에 두 번째 인스턴스가 떠 데이터를 부순다.**
- **MUST: 산술·비교 전에 1행이 숫자인지 검사한다.** 기동 중 락은 8행이 `starting`이고 잘린
  파일은 1행이 문자열인데, bash 산술은 문자열을 변수명으로 읽어 `set -u`에서 죽는다 —
  검사 없이 쓰면 이 함수가 `start.sh`를 그 자리에서 끝낸다.
- **MUST NOT: 락을 걷은 뒤 `brew services start`를 부르지 않는다.** `KeepAlive=true`라
  launchd가 10초마다 스스로 재시도하고(사고 당일 89회가 그 증거다) 락이 걷히면 다음 시도가
  뜬다. 게다가 **이미 bootstrap된 라벨에 다시 bootstrap하면 `Bootstrap failed: 5`로 brew가
  비0으로 죽어**, `set -e`인 `start.sh`가 폴링에 도달하기 전에 끝난다.
- **MUST: `pg_isready`는 `pg_bin`으로 찾고 종료코드를 가른다.** postgresql@16은 keg-only라
  **깨끗한 PATH에 없다.** 0 수락 · 1 거부(기동·복구 중) · 2 무응답이며, **1과 2를 뭉치면
  정상 복구를 사고로 신고한다** — 크래시 뒤 redo는 크기에 따라 분 단위가 된다.
- **MUST: 로컬 DB에만 적용한다.** `DB_URL`이 `localhost`/`127.0.0.1`이 아니면 락 파일은 다른
  기계에 있고 여기서 보는 것은 무관한 파일이다.

## 검증

```bash
# 판정 함수가 아홉 갈래를 가르는가. **양성 대조군의 1행은 「지금 살아 있는 비-postgres PID」여야
# 한다** — 사고 당시 PID를 그대로 쓰면 다음 부팅 뒤 「PID 없음」 갈래로 떨어져 초록인 채
# 사고 갈래를 안 태운다.
. ops/lib.sh
classify_postmaster_lock /opt/homebrew/var/postgresql@16/postmaster.pid   # LIVE
printf '%s\n/opt/homebrew/var/postgresql@16\n' $$ > /tmp/t && \
  classify_postmaster_lock /tmp/t                                        # STALE  ← 사고 갈래
printf 'starting\n' > /tmp/t && classify_postmaster_lock /tmp/t          # UNREADABLE (죽지 않는다)
: > /tmp/t && classify_postmaster_lock /tmp/t                            # UNREADABLE
classify_postmaster_lock /tmp/없는파일                                    # NONE

# DB가 떠 있으면 즉시 통과해야 한다 (실측 25ms).
ensure_postgres_up test && echo OK

# 상태 화면이 DB를 독립 항목으로 답하는가.
ops/status.sh | grep -E '^DB      :|^DB자동기동:'
```

**MUST: 이 검증은 「지우지 않는 쪽」이 더 중요하다.** `LIVE`·`UNREADABLE` 둘 중 하나라도
`STALE`로 새면 살아 있는 DB가 깨진다. 판정이 **어느 방향으로 망가져도** 위 아홉 중 하나는
붉도록 양성·음성을 함께 둔 것이 그 이유다.

```premise postgres가 마지막에 깨끗이 닫혔다
L=/opt/homebrew/var/log/postgresql@16.log
# 강제 종료는 postmaster.pid를 남기고, 그것이 다음 부팅에서 PID 재사용을 만나면 기동이 막힌다.
# ADR-0163은 그 결과를 무해화할 뿐 원인(앱이 DB보다 늦게 죽는 것)은 아직 안 닫혔다.
if [ ! -r "$L" ]; then
  echo "잴 것이 없었다 — postgres 로그를 읽을 수 없다"
else
  last=$(grep -aE 'database system is shut down|unexpected postmaster exit' "$L" | tail -1)
  if [ -z "$last" ]; then
    echo "잴 것이 없었다 — 로그에 종료 기록이 하나도 없다"
  elif echo "$last" | grep -q 'is shut down'; then
    echo "OK 마지막 종료가 정상이다 (${last%% KST*})"
  else
    echo "마지막 종료가 강제였다 — 원인은 아직 안 닫혔다(§결과의 「열어 둔 것」): ${last%% KST*}"
  fi
fi
```

## 배경

2026-09-26 부팅 뒤 `ops/start.sh`가 실패했다. 사슬이 여덟 단계였고 전부 실측으로 확정했다.

1. 09-25 19:45:14 postgres가 **smart shutdown**을 받았다(plist가 `postgres -D ...` 직접
   실행이라 launchd의 SIGTERM = smart로 고정된다).
2. smart shutdown은 클라이언트 연결이 다 끊길 때까지 기다리는데, 앱의 HikariCP 커넥션
   **12개**가 열려 있었다.
3. 앱은 **19:46:15.604**에야 graceful shutdown을 시작해 19:46:17.687에 풀을 닫았다. 그
   1.1초 전인 19:46:14.483에 postmaster가 이미 강제 종료됐다. **앱이 DB보다 나중에 죽는다.**
4. 강제 종료라 `postmaster.pid`가 남았다(마지막 줄 `stopping`).
5. 09-26 14:01:08 부팅. 14:01:30 launchd가 **PID 799를 iCloudNotificationAgent에 재할당**했다.
6. 14:01:32 postgres 기동 → 락의 PID 799가 살아 있음 → "다른 postmaster가 돈다"고 오판 →
   `FATAL: lock file "postmaster.pid" already exists`. KeepAlive로 10초마다 **89회** 반복.
7. 14:02:17 앱 기동 → Flyway가 5432 연결 실패 → **2.1초** 만에 사망.
8. `start.sh`는 이미 죽은 프로세스를 **60초** 기다린 뒤 "로그를 봐라"만 남겼다.

**락이 남는 것 자체는 crash 뒤의 정상 동작이고 postgres는 그것을 스스로 치우도록 설계돼
있다. 진짜 결함은 그 stale 판정이 PID 재사용 앞에서 실패하는 것이다** — postgres는
`kill(pid,0)`이 성공하면 거기서 끝내고 공유메모리 검사까지 가지 않는다.

4개월 로그 전수: smart shutdown 20회 중 **17회는 즉시(0.1~1.5초) 정상 종료**, **3회가
실패**했다(08-30·09-20·09-26). 실패 3회 모두 커넥션이 9~12개 열려 있었고, 성공 17회는
앱이 안 떠 있던 때다. **08-30·09-20에 사고가 안 난 것은 그 두 번의 다음 기동이 부팅
직후가 아니어서 PID가 안 겹쳤기 때문이다.**

## 버린 대안

- **부팅 시각(락 3행 epoch) 대조** — 「이번 부팅에 시작된 postmaster인가」로 가른다.
  **거짓양성은 안 만들지만 거짓음성을 못 막는다.** 09-20처럼 부팅 없이 죽으면 3행 > 부팅
  시각이라 `LIVE`로 빠지고, 그 창이 실측 **132분**이었다. 부팅 후 약 70분이면 PID가 한
  바퀴 돌므로(약 24 PID/초) 그 안에서도 재사용이 닿는다. **명령줄 대조가 이미 양방향으로
  면역이라 이 대조는 안전을 더하지 않고 사정거리만 좁힌다.** 게다가 `sysctl -n kern.boottime`
  파싱이 틀리는 방향에 따라 **판정이 「무조건 STALE」로도 망가진다**(실측: `sed -n 's/.*sec =
  \([0-9]*\).*/\1/p'`는 greedy `.*`가 `usec`을 먹어 369397을 내고, `tr -dc '0-9'`는 28자리를 낸다).
- **공유메모리(락 7행 key/id)를 `ipcs`로 대조** — PostgreSQL 자신이 `PGSharedMemoryIsInUse`로
  쓰는 값이라 부팅 세션과 무관하다. **그러나 key는 datadir에서 나와 재기동해도 같고**,
  판별하는 것은 `NATTCH`/`CPID`인데 **크래시 상태에서 그 값이 어떻게 떨어지는지 재보지
  않았다**(살아 있는 DB를 죽이지 않고는 못 잰다). 명령줄 대조만으로 아홉 갈래가 갈리므로
  재보지 않은 값을 판정에 넣지 않는다. **무너지면 여기부터 다시 연다.**
- **plist를 wrapper로 바꿔 SIGTERM을 fast shutdown(SIGINT)으로 번역** — `brew services
  start`가 formula의 `service do` 블록으로 plist를 **매번 재생성한다**(실측: plist mtime이
  `start` 시각과 같다). 다른 라벨로 직접 `launchctl bootstrap`하면 brew가 안 덮지만, 그것은
  postgres를 brew 관리에서 걷어내는 별개 결정이라 여기서 하지 않는다.
- **`ExitTimeOut`을 늘려 앱이 먼저 죽을 시간을 준다** — **안 듣는다.** 현재 plist에 이미
  `ExitTimeOut=120`이 있는데 `launchctl print`는 **`exit timeout = 60`**을 낸다. 별도
  LaunchAgent에 120을 넣어 bootstrap해도 60이 나왔다(실측). **launchd가 사용자 LaunchAgent의
  이 값을 60으로 깎는다.** 사고의 실측 예산이 정확히 60.0초(19:45:14 → 19:46:14.483)이고
  앱이 풀을 닫은 것은 63.1초라 **3.1초가 모자랐다.** 이 경로는 막혔다.
- **락을 걷은 뒤 `brew services start`를 부른다** — 불필요하고 해롭다. §규칙에 근거가 있다.
- **「재부팅 전에 `ops/stop.sh`를 돌려라」를 절차로 적는다** — 결함에 우회를 처방하는 것이다.
  사람의 기억에 거는 순간 그것은 조치가 아니다.

## 결과

- **쉬워지는 것**: 오늘의 사고 형태가 `ops/start.sh` 한 번으로 닫힌다. 실패해도 **무엇이
  막고 있는지 이름이 나온다**(`NONE`/`UNREADABLE`/`LIVE`). 죽은 프로세스를 60초 기다리던
  것이 사라졌다 — DB가 살아 있을 때 추가 비용은 **25ms**다. `ops/status.sh`가 `DB :` 줄로
  독립 판정을 찍고, `DB자동기동:` 줄이 **사고 당일 89회 재시도의 주체를 보이게** 한다
  (그 전에는 자동기동 검사가 세 경로만 열거해 "등록이 하나도 없다"를 찍었다).
- **어려워지는 것**: `ops/`가 postgres의 내부 파일 형식에 의존하게 됐다. `postmaster.pid`의
  1·2행 뜻이 바뀌면 판정이 조용히 틀린다 — PG16 `pidfile.h`의 `LOCK_FILE_LINE_PID`·
  `LOCK_FILE_LINE_DATA_DIR`이 그 계약이고, 메이저 업그레이드 때 확인할 자리다.
- **원인(사슬 2)도 같은 날 닫았다 — `application.yaml`에 `minimum-idle: 0`**(사용자 승인
  2026-09-26, 같은 날 배포). 그 값이 없으면 Hikari 기본이 `minimumIdle = maximumPoolSize`라
  앱이 12를 상시 물고, smart shutdown은 그 12가 끊길 때까지 기다린다.
  - **MUST: 「몇 개로 줄었나」가 아니라 「0이 되나」로 잰다.** smart shutdown은 연결이
    **하나라도** 남으면 막히므로 개수가 준 것만으로는 아무것도 안 닫힌다.
  - **실측(2026-09-26)**: 적용 전 `idle` **12개**이고 `now()-state_change > 10분`이 **0건**
    — 즉 영영 안 걷혔다. 적용 후 조용히 두면 **약 2분 내 0개**가 된다
    (15:13:57 유휴 6초 → 15:14:34 43초 → 15:15:04 73초 → **15:15:34 0개**).
  - **측정할 때 `ops/status.sh`나 `curl /actuator/health`를 쓰지 않는다** — 그것이 커넥션을
    만들어 관측을 오염시킨다. 실제로 첫 관측이 그래서 1개를 셌다. `psql`로만 묻는다.
  - **MUST NOT: 이 결정과 `maximum-pool-size`를 따로 다루지 않는다.** `minimum-idle`을 지우면
    Hikari가 다시 `maximum-pool-size`만큼 상시 물고, **앱은 정상 기동하며 테스트도 통과한다.**
    증상은 **다음 재부팅에서 DB가 안 뜨는 것**으로만 나온다.
  - **사정거리: 배치가 도는 중의 종료는 이걸로 안 막는다** — 그때는 활성 커넥션이라
    `idle-timeout`의 대상이 아니다. 그 갈래는 위 락 판정이 결과를 받는다. 관측된 사고 3회는
    **모두 배치가 안 돌던 시각**이라 이 처방의 사정거리 안이지만, 3표본이다.
  - 위 `premise`는 **그대로 둔다.** 그 갈래가 남아 있고, 강제 종료가 다시 나면 그것이 신호다.
- **되돌리려면**: `ops/lib.sh`의 `classify_postmaster_lock`·`ensure_postgres_up`과
  `ops/start.sh`의 호출 한 곳, `ops/status.sh`의 `DB`·`DB자동기동` 두 줄을 걷는다.
