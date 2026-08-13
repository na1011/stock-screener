---
id: ADR-0052
status: 채택됨
date: 2026-08-13
applies_to:
  - "ops/dump-db.sh"
  - "ops/lib.sh"
  - "src/main/java/**/pipeline/batch/AdjustmentRebuildJob.java"
  - "src/main/java/**/pipeline/batch/IndicatorBackfillJob.java"
  - ".claude/hooks/guard-rebuild-without-dump.sh"
---

# ADR-0052. 재구축을 CLI에만 두고, 그 앞에 덤프를 세우고, 정정을 종목 단위로 싸게 만든다

## 결정

`rebuild()`의 호출자를 만들면서 넷을 함께 정했다.

- **재구축 잡을 런처 카탈로그와 스케줄러 양쪽에서 뺀다.** 경로는 CLI 하나다.
- **덤프 수단을 `ops/dump-db.sh`로 만들고 결과를 저장소 밖(`$DEPLOY/dumps`)에 둔다.**
- **`PreToolUse` 훅이 덤프가 *하나도 없을 때만* 재구축을 막는다.** 신선도 창을 걸지 않는다.
- **`IndicatorBackfillJob`에 `--tickers`를 붙인다.** 백분위·업종 상대값 호출은 좁히지 않는다.

## 규칙

- **MUST NOT: `AdjustmentRebuildJob`을 `AdminJobLauncher`의 카탈로그에 넣지 않는다.** 다른 잡은
  잘못 눌러도 다시 누르면 되지만 이것은 **누른 뒤에 백필이 따라와야 끝나는** 유일한 잡이다 —
  보정된 종목의 `indicator_value`가 보존 창 전체에서 지워진다(`StoredIndicatorInvalidationPort`의
  `>=`). 실측으로 **62종목 360,471행**이 한 번에 사라졌다. `AdminJobLauncherTest`가 카탈로그를
  이름으로 못박고 있어 넣으면 그 테스트가 함께 깨진다.
- **MUST: 재구축 대상의 기본값을 활성 유니버스 전량으로 둔다.** `rebuild()`는 적용 대상이 없으면
  **계수를 1로 되돌리기도** 하므로, 전량을 도는 것이 곧 *"원장을 거치지 않은 계수가 없다"*의
  검증이다. 원장에 없는 종목은 갱신 0행으로 끝나 무효화도 일어나지 않는다.
- **MUST: 무효화가 일어났으면 알림 본문에 이어서 돌릴 명령을 적는다.** 무효화는 지우기만 하므로
  백필이 따라오지 않으면 그 종목이 화면에서 통째로 사라지는데 **에러도 경고도 나지 않는다.**
  이 잡의 `SUCCESS`는 "일이 끝났다"가 아니다.
- **MUST: 덤프를 `.tmp`에 쓴 뒤 원자적으로 옮기고, `pg_restore -l`로 TOC를 실제로 읽는다.**
  아래 훅이 "덤프가 있는가"를 **파일 존재**로 판정하므로 중간에 죽은 `pg_dump`가 남긴 파일이
  그 가드를 통과한다 — 그 파일로는 복원이 성립하지 않는다.
- **MUST NOT: 오래된 덤프를 자동으로 지우지 않는다.** 총 크기와 가장 오래된 것만 알린다.
  삭제는 무엇이 사라졌는지 나중에도 알기 어렵다(CLAUDE.md).
- **MUST NOT: 훅에 신선도 창(예: 6시간)을 걸지 않는다.** ADR-0049가 요구하는 것은 *"첫 실행
  전에"*이고, 시간 규칙은 **정상 재구축과 되돌리기 리허설까지** 막는다. 재구축은 원장에서 매번
  다시 짓는 멱등 연산이고 되돌리기가 `judge` + 재실행으로 성립하므로(ADR-0049 §쉬워지는 것),
  그것을 막는 것은 `harness-core.md`가 금지하는 **"선택지를 줄이는 기제"**다.
- **MUST NOT: `guard-destructive.sh`에 이 판정을 넣지 않는다.** 그 파일은 `~/.claude/hooks/`에
  사본이 있어 고치면 전역까지 함께 고쳐야 하는데(`harness-hooks.md`), 이 판정은 `$DUMP_DIR`와
  `--job=adjustment-rebuild`를 아는 **이 저장소 전용**이다.
- **MUST: `BootstrapRunner`의 `switch`에 잡을 더하면 `.claude/hooks/lib/load-process.sh`의
  `--job=` 목록에도 더한다.** 빠지면 그 잡은 어느 훅에도 안 잡힌다.
- **MUST NOT: `IndicatorBackfillJob`의 `percentileCalculator`·`sectorRelativeCalculator` 호출을
  `targets`로 좁히지 않는다.** 둘은 **거래일만 받고** `indicator_value`를 활성 유니버스 전량과
  조인하므로(`PercentileCalculator`의 `universe_membership` · `universe(is_active)` 조인) 종목
  루프를 좁혀도 순위의 모집단은 그대로다. 좁히면 대상 밖 종목의 순위가 조용히 낡는데 값이
  그럴듯해 화면에서 드러나지 않는다. `IndicatorBackfillTickerScopeTest`가 그것을 못박고,
  **그 테스트는 변이 둘로 항등식이 아님을 확인했다** — 티커를 무시하게 만들면 `target_count`
  단언이, 백분위 호출을 빼면 대상 밖 종목의 `PER_PCTL` 단언이 각각 깨진다.
- **MUST: 세션 쿠키로 판정을 넣을 때 값을 `curl --config`로만 넘긴다.** 명령줄에 두면 `ps`와
  트랜스크립트에 남는다. 파일은 저장소 밖 `$DEPLOY/admin-session`(`600`)이다.

## 검증

```bash
./gradlew test --tests "*AdjustmentRebuildJob*" --tests "*IndicatorBackfillTickerScope*" \
               --tests "*AdminJobLauncher*" --tests "*AdjustmentRecalculator*"
bash .claude/hooks/lib/regression.sh     # 실패 0 · 건너뜀 0
```

재구축 잡이 화면에 **없는지** — 낱말이 아니라 이 자리로 묻는다:

```bash
rg -n "AdjustmentRebuildJob" src/main/java/com/stockscreener/pipeline/batch/AdminJobLauncher.java \
                             src/main/java/com/stockscreener/pipeline/scheduler/
```

아무것도 나오지 않아야 한다. 나오면 버튼 한 번이 지표를 지운다.

```premise 덤프가 하나라도 있고 복원 가능한 형태다
if ! compgen -G "$HOME/Library/Application Support/stock-screener/dumps/*.dump" >/dev/null 2>&1; then
  echo "덤프가 하나도 없다 — ops/dump-db.sh를 돌려라. 재구축 가드가 다음 실행을 막는다."
else
  d=$(ls -t "$HOME/Library/Application Support/stock-screener/dumps"/*.dump | head -1)
  n=$(/opt/homebrew/bin/pg_restore -l "$d" 2>/dev/null | grep -c 'TABLE DATA' || true)
  if [ "$n" -ge 20 ]; then echo "OK 최근 덤프에 테이블 데이터 ${n}개 ($(basename "$d"))"
  else echo "최근 덤프의 TOC에 테이블 데이터가 ${n}개뿐이다 — 잘렸다. 다시 뜬다."; fi
fi
```

```premise 무효화된 종목이 지표를 다시 갖고 있다
psql -d stock_screener -At -c "WITH ev AS (
  SELECT DISTINCT stock_id FROM corporate_action
  WHERE status IN ('APPROVED','APPLIED') AND type <> 'UNKNOWN')
SELECT CASE
  WHEN count(*) = 0 THEN '잴 것이 없었다 — 적용 대상 사건이 있는 종목이 없다'
  WHEN count(*) FILTER (WHERE dates = 0) = 0
    THEN 'OK 보정 대상 ' || count(*) || '종목 전부 지표를 갖고 있다(최소 ' || min(dates) || '일자)'
  ELSE count(*) FILTER (WHERE dates = 0)
       || '종목의 지표가 비어 있다 — 재구축이 지운 뒤 백필이 따라오지 않았다. 그 종목은 화면에서 통째로 사라지고 에러도 경고도 나지 않는다.'
  END FROM (
    SELECT ev.stock_id, (SELECT count(DISTINCT trade_date) FROM indicator_value v
                         WHERE v.stock_id = ev.stock_id) AS dates FROM ev) w"
```

## 배경

ADR-0049가 `rebuild()`를 만들면서 **호출자를 일부러 두지 않았다** — 그 첫 호출이 저장된 시세를
소급해 다시 쓰는 유일한 지점이고 그 앞에 덤프가 필요한데 `ops/`에 그 수단이 없었다. 이 결정이
그 자리를 채운다.

**덤프가 실제로 싸다는 것이 설계를 바꿨다.** 1,237 MB DB가 `-Fc -Z6`으로 **33 MB · 3초**다
(`price_daily` 523,963행 · `indicator_value` 2,061,176행이 데이터행까지 확인된다). 그래서
"되돌릴 수 없는 지점 앞에 한 번"이 아니라 **부담 없이 여러 번** 뜰 수 있고, 신선도 창으로
강제할 이유도 그만큼 줄었다.

**`--tickers`가 기다림을 없앴다.** 처음 계획은 늦게 오는 공시를 기다려 착수를 이틀 미뤘다.
근거는 *"판정을 나중에 고치면 180거래일 × 350종목을 다시 돌아야 한다"*였는데, 백분위·업종이
**날짜 스코프**라 그 전제가 성립하지 않았다. 필터를 붙이니 한 종목 정정이 **재구축 1초 + 백필
몇 초**가 되었고, 되돌리기 리허설도 그 덕에 실데이터에서 돌 수 있었다.

## 버린 대안

- **재구축을 관리자 API·화면에 넣는다** — 다른 잡과 대칭이 되고 Phase 6의 *"잡을 화면에서 띄울
  수 있다"*와도 맞는다. 그러나 버튼 한 번이 25만~36만 행의 지표를 지우고, 그 상태는 **백필이
  따라올 때까지 종목이 사라진 채**로 있다. 되돌리기가 성립하는 것과 실수하기 쉬운 것은 다른 문제다.
- **훅에 신선도 창을 건다(6시간·24시간)** — "덤프가 낡았다"까지 막을 수 있다. 그러나 정상
  재구축과 복구 경로를 함께 막아 기제가 선택지를 줄인다. 좁게 막으면 실제로 되돌릴 수 없는
  **첫 호출 하나**에만 걸린다.
- **덤프를 저장소 안에 둔다** — 경로가 짧아진다. 그러나 저장소는 개발 트리라 `gradlew clean`·
  `mv`·`git add`의 사정거리 안이다(ADR-0034).
- **오래된 덤프를 N개만 남기고 지운다** — 디스크가 는다. 33 MB짜리를 자동 삭제할 이유가 없고,
  삭제는 무엇이 사라졌는지 나중에 알기 어렵다.
- **백필의 백분위 호출까지 `targets`로 좁힌다** — 일관돼 보이고 더 빠르다. 그러나 phase 기준이
  금지하는 사고 그 자체이고, 어느 쪽도 에러를 내지 않는다.
- **판정용 CLI 잡을 만든다(`--job=corporate-action-judge --file=…`)** — OAuth 없이 무인으로 돌 수
  있다. 그러나 ADR-0050이 만든 관리자 API가 **실데이터로 한 번도 안 타 본 상태**로 남고, 새 코드가
  같은 도메인 메서드에 두 번째 진입점을 만든다. 세션 쿠키 + `curl --config`로 31건이 한 번에 들어갔다.
- **낱말이 오는 반기보고서를 기다린다(2026-08-15 이후)** — 커버가 2026-06-30까지 늘어난다.
  그러나 §배경의 대조군 검증이 기다림을 대체했다 — 공시가 값을 준 무상증자 **50건**에 증거-단독
  유도를 대조하니 후보가 하나로 좁혀진 **41건에서 `ratio`·`listing_date`가 전부 정확히 일치했고
  어긋난 것이 0건**이었다. 기다림의 근거가 추측이면 기다리고, 측정이 그것을 대체하면 기다리지 않는다.

## 결과

- **쉬워지는 것**
  - 판정 하나를 고치는 비용이 **몇 초**다. 오탐을 실제로 고치게 된다.
  - 되돌리기가 실데이터에서 검증된다 — 강등 → 재구축(계수 전량 1) → 재승인 → 재구축(복귀)을
    한 종목으로 왕복시켰다.
  - 덤프가 싸서 되돌릴 수 없는 지점 앞뿐 아니라 아무 때나 뜬다.
- **어려워지는 것**
  - **재구축과 백필이 짝이다.** 하나만 돌면 종목이 화면에서 사라진다. 알림이 그것을 말하지만
    알림을 안 보면 끝이다.
  - **재구축 잡만 화면에 없다.** 다음 세션이 "왜 이것만"으로 읽고 넣을 수 있어 javadoc과 이
    ADR이 그 자리를 지킨다.
  - **판정을 넣는 경로가 사람의 브라우저 세션에 묶인다.** 무인 실행에서는 그 단계가 멈춘다.
- **되돌리려면**: `AdjustmentRebuildJob`과 `BootstrapRunner`의 case, `load-process.sh`의 항목,
  훅과 `settings.json` 등록, `ops/dump-db.sh`와 `DUMP_DIR`, 백필의 `tickers` 인자를 걷는다.
  **원장과 계수는 이 결정이 아니라 ADR-0049가 답한다.**
