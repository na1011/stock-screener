---
id: ADR-0120
status: 채택됨
date: 2026-09-01
applies_to:
  - ".claude/settings.json"
  - ".claude/hooks/check-premises.sh"
---

# ADR-0120. 상한은 앱이 아니라 psql 채널에 건다

## 결정

`statement_timeout=30s`와 `idle_in_transaction_session_timeout=60s`를 **libpq 채널에만** 건다. 에이전트 쪽은 `.claude/settings.json`의 `env`가, 사람이 손으로 치는 쪽은 `~/.zshrc`의 `alias psql`이 건다. **앱(pgjdbc)과 전역 서버 설정은 건드리지 않는다.**

## 규칙

- MUST NOT: `postgresql.conf`나 `ALTER ROLE`로 상한을 걸지 않는다. 앱과 에이전트가 **같은 롤 `na1011`**을 쓰므로 전역 설정은 앱까지 건다. 그러면 `IndicatorRetentionPolicy`의 수백만 행 `DELETE USING`과, **트랜잭션 안에서 60초 HTTP를 기다리는 수집기**(`PriceCollector`·`GovernanceCollector`·`MarketCalendarCollector`)가 먼저 깨진다.
- MUST NOT: 사람 채널을 `export PGOPTIONS`로 바꾸지 않는다. `vacuumdb`·`reindexdb`·`clusterdb`는 `pg_dump`·`pg_restore`와 달리 **자기 `statement_timeout`을 안 푼다**(바이너리 `strings` 실측 0건). `export`하면 그 셋에도 실려 `REINDEX INDEX CONCURRENTLY`가 30초에 끊기고, 그때는 **INVALID 인덱스가 남아 손으로 `DROP INDEX`를 해야 한다.** alias가 비대화형 셸에 안 실리는 것이 여기서는 결함이 아니라 의도다.
- MUST NOT: `check-premises.sh`의 지역 `export PGOPTIONS`(`statement_timeout=6s`)를 "전역에 생겼으니 중복"이라며 지우지 않는다. 그 파일의 `per_limit > PGCONNECT_TIMEOUT + statement_timeout` 부등식이 **11 > 4 + 30**으로 뒤집혀 전제 전부가 무판정이 된다.
- 일부러 오래 걸릴 작업(`REINDEX CONCURRENTLY`·`EXPLAIN ANALYZE`)은 `PGOPTIONS='-c statement_timeout=0' psql ...`로 **명시적으로** 푼다. 푸는 것이 의식적 행위로 기록에 남는 것이 이 설계의 의도다.

## 검증

```premise 고아 psql 백엔드가 남아 있지 않다
psql -d stock_screener -At -c "SELECT CASE
  WHEN count(*) FILTER (WHERE backend_type = 'client backend') = 0
    THEN '잴 것이 없었다 — 붙어 있는 클라이언트 백엔드가 하나도 없다'
  WHEN count(*) FILTER (WHERE application_name = 'psql' AND state = 'active'
        AND now() - query_start > interval '60 seconds'
        AND query !~* '^\s*(REINDEX|VACUUM|CREATE INDEX|CLUSTER|ANALYZE)') = 0
    THEN 'OK 60초 넘게 도는 psql 백엔드 0개 / 클라이언트 백엔드 '
         || count(*) FILTER (WHERE backend_type = 'client backend')
  ELSE '상한을 벗어난 psql 백엔드가 있다: ' || string_agg(
         pid || ' ' || date_trunc('second', now() - query_start) || ' ' || left(query, 40), ' / ')
         FILTER (WHERE application_name = 'psql' AND state = 'active'
                 AND now() - query_start > interval '60 seconds'
                 AND query !~* '^\s*(REINDEX|VACUUM|CREATE INDEX|CLUSTER|ANALYZE)')
  END FROM pg_stat_activity"
```

**설정값이 아니라 결과를 재는 이유:** 펜스는 `check-premises.sh` 안에서 도는데 그 훅이 자기 `PGOPTIONS`로 `statement_timeout=6s`를 덮는다. `SHOW statement_timeout`은 그래서 영원히 `6s`를 내고 전역 설정을 못 본다. **상한이 걸려 있는지가 아니라 상한을 벗어난 것이 남아 있는지를 묻는다.**

**모집단에서 뺀 것과 그 근거:** `REINDEX`·`VACUUM`·`CREATE INDEX`·`CLUSTER`·`ANALYZE`는 위 규칙이 **일부러 상한을 푸는** 작업이라 세면 매 세션 거짓 발견이 난다. **이 목록이 자라는 자리는 위 「규칙」의 마지막 항목이다** — 거기에 새로 푸는 작업을 더하면 여기도 함께 더한다.

**전제 예산:** 이 펜스는 `pg_stat_activity` 한 번 조회라 수 ms다. 추가 시점의 예산은 27초/30초였다.

## 배경

2026-09-01, 이전 세션이 Bash로 실행한 psql 진단 쿼리가 **4시간 동안 코어 하나를 99%로 태웠다.** 120초 타임아웃으로 백그라운드에 이관되고 세션이 끝나 셸과 psql 클라이언트는 죽었지만, **postgres 백엔드는 살아남았다** — Postgres는 결과를 돌려보내려 할 때에야 끊긴 클라이언트를 알아채는데 그 쿼리는 첫 행도 못 내놓았다. 쿼리는 `count(*) FILTER (...)` 안에 상관 서브쿼리가 들어가 SubPlan이 조인 행마다 재실행됐다(서브플랜 1회 48.7ms × 2,225,919행 ≈ 3.4년, 플래너 비용 155,555,041,912).

**이 DB는 사용자의 노트북에서 돈다.** 그리고 한 번이 아니다 — 트랜스크립트 163개 / Bash 호출 16,803건에서 타임아웃→백그라운드 이관 21건, 그중 psql이 3건이고 셋 다 큰 테이블 위의 임시 분석 쿼리였다.

**부수적으로 드러난 것:** 그 고아가 도는 동안 세션 시작 전제 검사가 `참 96 · 발견 3 · 판정 불가 12`(예산 30/30초 소진)였는데, 취소 직후 같은 검사가 `참 108 · 발견 4 · 판정 불가 0`(27초)이 됐다. **무판정 12개의 원인이 CPU 포화였다.**

## 버린 대안

- **전역 `postgresql.conf` / `ALTER ROLE`** — 앱과 롤이 같아 배치가 먼저 깨진다(위 규칙).
- **앱(JDBC) 채널에도 상한** — 폭주 후보였던 `BarGapReader.read()`를 인자 없이 재보니 **162ms**(`count(*) FROM scoped`) / **1,086ms**(`LIMIT 2001`까지)다. 폭주 근거를 못 찾아 넓히지 않았다. 애초에 pgjdbc는 `PGOPTIONS`를 읽지도 않는다 — jar 553엔트리에 문자열 0건이고 `PGEnvironment`가 참조하는 것은 `PGPASSFILE`·`PGSERVICEFILE`·`PGSYSCONFDIR`뿐이다.
- **`~/.psqlrc`** — `SET`이 stdout에 명령 태그를 내면 `-At -F'|'` 출력을 파싱하는 `ops/status.sh`와 `check-premises.sh`가 조용히 틀린 답을 낸다. `-X`가 `.psqlrc`를 건너뛰는 것도 걸린다(psql 호출 5,007건 중 46건).
- **`PGAPPNAME=claude-psql`** — 에이전트 psql을 이름으로 가르려 했으나 **`pg_dump`의 `application_name`까지 덮는다**(실측: `PGAPPNAME=probe-appname pg_dump`가 `probe-appname | COPY public.price_daily`로 뜬다). psql과 덤프가 한 이름으로 뭉개져 진단이 나빠진다.
- **`PGCONNECT_TIMEOUT`을 전역에 두기** — `ops/lib.sh`가 3을, `check-premises.sh`가 4를 이미 쓴다. 그리고 방향이 반대다 — 접속이 느려지는 상황이 정확히 이 설계가 겨냥한 포화 상황인데, 상한을 두면 "느리지만 붙는다"가 "안 붙는다"가 된다.
- **남은 백엔드를 취소하는 `PostToolUse` 스윕 훅** — 사용자가 뺐고 측정도 같은 쪽이다. 상한이 걸리면 스윙할 대상이 30초 넘게 안 남는다. 대가로 **「즉시 kill」이 아니라 「30초 뒤 자동 사망」**이 된다.
- **`SessionEnd` 훅** — `harness-hooks.md`가 그 이벤트에 알림 계층을 만드는 것을 MUST NOT으로 막았다.
- **쿼리 모양을 미리 경고하는 규칙·스킬** — `harness-core.md`의 배치표가 닫아 놨다. 트리거가 「명령 실행」이라 `paths` glob은 발화하지 않고, 제약을 skill에 두는 것은 MUST NOT이며, `PreToolUse`의 `additionalContext`도 tool result 자리에 꽂혀 「미리」가 성립하지 않는다. **대신 상한 자체가 교육 신호를 겸한다** — 30초 뒤 오류가 tool result에 꽂히는 것이 발화가 보장된 유일한 자리다.

## 결과

같은 실수를 해도 **4시간이 아니라 30초**에 끝난다. 저장소가 실제로 치는 가장 무거운 조회가 1.09초(`indicator_value` 25M 전량 `GROUP BY`는 693ms)라 상시 방해는 없다.

**막지 못하는 것을 아는 채로 남긴다.** ① 에이전트가 자기 `PGOPTIONS`를 붙이면 상속 상한을 잃는다 — psql이 든 Bash 호출 3,386건 중 263건이 자기 값을 붙이고 그중 **48건에 `statement_timeout`이 없다.** ② 각 30초 미만인 psql을 도는 루프는 안 걸린다 — 상한은 문장 하나의 길이만 묶지 개수를 안 묶는다. ③ JDBC 채널(앱·DBeaver·IntelliJ)은 libpq 환경변수로 원리적으로 못 덮는다. ④ 고아를 만드는 기제(백그라운드 이관 → 턴 종료 → 클라이언트 사망)는 그대로이고, 상한은 그 창을 30초로 줄일 뿐이다. ⑤ `log_min_duration_statement`가 `-1`이라 **상한에 걸려 죽은 문장이 무엇이었는지 서버 로그로 되짚을 길이 없다.**

되돌리려면 `.claude/settings.json`의 `env` 절, `~/.zshrc`의 alias, `check-premises.sh`의 주석 셋을 함께 건드린다.
