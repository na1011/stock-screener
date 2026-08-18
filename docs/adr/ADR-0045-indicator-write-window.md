---
id: ADR-0045
status: 채택됨
date: 2026-08-11
applies_to:
  - "src/main/java/com/stockscreener/indicator/IndicatorRunner.java"
  - "src/main/java/com/stockscreener/indicator/IndicatorRetentionPolicy.java"
  - "src/main/java/com/stockscreener/pipeline/batch/IndicatorBackfillJob.java"
  - "src/main/java/com/stockscreener/pipeline/batch/DailyUpdateJob.java"
---

# ADR-0045. 지표는 보존 경계 밖에 애초에 쓰지 않는다

## 결정

`IndicatorRunner`가 계산 결과를 저장하기 전에 **보존 경계(180거래일, ADR-0011)보다 오래된
포인트를 버린다.** 경계는 `IndicatorRetentionPolicy.writeCutoff()`가 한 곳에서 내고, 잡이
종목 루프 **밖에서 한 번** 구해 러너에 넘긴다.

`IndicatorRetentionPolicy.purge()`는 그대로 둔다. 지우는 쪽과 쓰지 않는 쪽 둘 다 남는다.

## 규칙

- **MUST: 계산은 창 전체로 하고 저장만 자른다.** 재귀 지표(`ATR_14`·`RSI_14`·`MACD`·`MACD_V`)는
  창의 앞쪽이 뒤로 이어져야 마지막 값이 맞다. 입력을 자르면 워밍업이 짧아져 **값 자체가
  달라지는데, 그 값은 그럴듯해 화면으로 드러나지 않는다.**
- **MUST: 필터의 부등호를 `purge()`의 `trade_date <= cutoff`와 같은 방향으로 유지한다.**
  어긋나면 경계일 하루가 매 실행 쓰였다가 매 정리에 지워지고, 그 왕복은 어떤 값도 바꾸지
  않아 증상이 없다.
- **MUST NOT: 경계를 종목마다 묻지 않는다.** 350종목 × 774거래일이면 같은 답을 27만 번 받는다.
- **MUST NOT: 경계를 `asOf`에서 역산하지 않는다.** 보존은 **지금 최신**에서 180거래일이지
  요청한 날짜에서가 아니다. 역산하면 과거 구간 백필이 그 시점 기준으로 보존 밖을 되살린다.
- **긴 백필은 경계일 하루를 되살린다 — 버그가 아니라 위 MUST NOT의 대가다.** 경계를 잡 시작에
  한 번만 구하므로, 실행이 길어 그 사이 일일 갱신이 돌면 **경계가 앞으로 밀렸는데 러너는 옛
  경계로 계속 쓴다.** 되살리는 것은 **재무 기반 지표**다(`PER`·`PBR`·`ROE`·`ALTMAN_Z`…) —
  실행 한 번이 읽기 창 전체에 값을 쓰기 때문이다. **다음 정리가 지우므로 자가 복구된다.**
  2026-08-14에 4시간 28분짜리 백필에서 실제로 그랬고, 그때 `ADR-0011`과 이 ADR의 premise가
  함께 "181거래일"을 알렸다. **premise가 그것을 알리면 정리를 기다린다 — 필터를 고치지 않는다.**
- **MUST NOT: 러너에 경계 없이 도는 경로를 남기지 않는다.** 기존 2인자 `run()`을 지운 이유가
  그것이다 — 남기면 새 호출부가 그쪽을 골라도 아무 증상이 없다.

## 검증

```bash
./gradlew test --tests "*IndicatorRunnerTest*" --tests "*IndicatorRetentionTest*"
```

`storesNothingAtOrBeforeRetentionCutoff`가 부등호를, `retentionCutoffDoesNotChangeComputedValues`가
"자르는 것은 저장뿐"을 지킨다. 뒤엣것은 `ATR_14`를 경계 유무 두 번 계산해 같은 값이 나오는지
본다 — 계산 입력까지 자르는 회귀를 이 테스트만 잡는다.

```premise 보존 경계 밖에 지표가 쌓이지 않는다
psql -d stock_screener -At -c "WITH b AS (SELECT DISTINCT trade_date FROM price_daily ORDER BY trade_date DESC OFFSET 180 LIMIT 1) SELECT CASE WHEN (SELECT count(*) FROM indicator_value) = 0 THEN '지표가 한 행도 없다 — 잴 것이 없었다' WHEN (SELECT count(*) FROM b) = 0 THEN '시세가 180거래일보다 적다 — 경계가 아직 없어 거를 것이 없다' WHEN NOT EXISTS (SELECT 1 FROM indicator_value, b WHERE indicator_value.trade_date <= b.trade_date) THEN 'OK 보존 경계 밖 지표가 없다' WHEN (SELECT count(DISTINCT indicator_value.trade_date) FROM indicator_value, b WHERE indicator_value.trade_date <= b.trade_date) = 1 THEN '1거래일(' || (SELECT min(indicator_value.trade_date) FROM indicator_value, b WHERE indicator_value.trade_date <= b.trade_date) || ')이 경계 밖이다 — 하루면 §긴 백필의 대가이고 다음 정리가 지운다. 필터를 고치지 말고 앱을 띄워 일일 갱신을 돌린다(ADR-0043 — 저절로 돌지 않는다)' ELSE (SELECT count(DISTINCT indicator_value.trade_date)::text FROM indicator_value, b WHERE indicator_value.trade_date <= b.trade_date) || '거래일이 경계 밖이다 — 여럿이면 §긴 백필의 대가가 아니라 경계 필터를 거치지 않은 쓰기다' END" 2>&1
```

**모집단이 비는 경우를 둘로 가른다** — "지표가 통째로 없다"와 "시세가 아직 180거래일에
못 미친다"는 원인이 다르고, 뒤엣것은 초기 적재 중에 정상이다.

**`EXISTS`로 먼저 묻고 개수는 어긋났을 때만 센다.** 정상이면 인덱스에서 첫 행을 못 찾고
끝나므로 전수 집계가 필요 없다 — `count(*)`를 앞에 두면 **정상일 때가 오히려 느리다.**
전제당 상한 8초 안이다.

### 배포와 그 효과 (2026-08-11)

배포 전후로 **`indicator_value`의 거래일 수**를 잰다. purge가 지운 행 수로 재지 않는다 —
그 값은 **경계를 `indicator_value` 자신에서 세기 때문에**(`purge()`) 이 결정이 실린 jar도
같은 값을 지운다. 즉 **판별력이 없다.**

| 시점 | 거래일 | 행 |
|---|---|---|
| 배포 전 (백필들이 보존 밖을 채운 상태) | **774** | 644만 |
| 배포 직전 일일 갱신의 `purge()` 뒤 | 180 | 2,006,860 |
| **배포 후 단일 날짜 백필 1회** | **180** | **2,006,860** |

마지막 줄이 이 결정의 효과다. 그 백필은 `written_row_count=0`으로 끝났다 — 배포 전
코드였다면 같은 한 번이 **~570거래일을 되살렸다**(`readRawData`가 단일 날짜여도 751봉을
읽는다). 전제도 `594거래일이 보존 경계 밖에 있다` → `OK`로 뒤집혔고 백필 뒤에도 `OK`다.

**같은 잡의 배포 전후가 가장 선명한 짝이다** — `indicator-backfill` id 35(수정 전·purge 직후)
**4,806,675행** 대 id 45(수정 후·purge 직후) **0행**.

**MUST NOT: `written_row_count`를 배포 전후 판별에 쓰지 않는다.** 그 값도 상태 의존이다 —
보존 밖 구간이 비어 있으면 전부 INSERT라 437만이 되고, 백필이 이미 채워 두었으면
`IndicatorValueWriter`의 `IS DISTINCT FROM` 가드가 걸러 44만이 된다. 같은 코드가 열 배
차이를 낸다(id 34 대 id 44). 상태에 안 휘둘리는 것은 **거래일 수**와 **잡 구조**뿐이다.

**`count(DISTINCT indicator_value.trade_date)`의 테이블 한정을 지우지 않는다.** `b`에도 같은
이름의 컬럼이 있어 `ambiguous` 오류가 나는데, **전제는 오류를 판정 불가로 삼키므로 이 자리는
침묵으로 보인다.**

```premise 지표 인덱스에 블로트가 다시 쌓이지 않는다
psql -d stock_screener -At -c "WITH i AS (SELECT sum(pg_relation_size(oid)) b FROM pg_class WHERE relname IN ('idx_iv_screen','indicator_value_pkey','idx_iv_stock_date')), r AS (SELECT count(*) n FROM indicator_value) SELECT CASE WHEN (SELECT n FROM r) = 0 THEN '잴 것이 없었다 — indicator_value가 비어 있다' WHEN (SELECT b FROM i) IS NULL THEN '잴 것이 없었다 — 세 인덱스가 조회되지 않는다' WHEN (SELECT b FROM i)/1048576.0 / ((SELECT n FROM r)/1000000.0) <= 250 THEN 'OK 인덱스 ' || round((SELECT b FROM i)/1048576.0) || 'MB / ' || (SELECT n FROM r) || '행 = 백만행당 ' || round((SELECT b FROM i)/1048576.0 / ((SELECT n FROM r)/1000000.0)) || 'MB' ELSE '인덱스가 백만행당 ' || round((SELECT b FROM i)/1048576.0 / ((SELECT n FROM r)/1000000.0)) || 'MB다(총 ' || round((SELECT b FROM i)/1048576.0) || 'MB / ' || (SELECT n FROM r) || '행). REINDEX 직후 기준선은 백만행당 101MB였다 — 블로트가 다시 쌓이고 있다. REINDEX 전에 매 실행 왕복이 되살아났는지 먼저 본다' END"
```

**MUST: 절대 크기가 아니라 행당 크기로 묻는다.** 이 결정 뒤에도 **지표는 계속 늘고**(ADR-0046이
일목 지표를 더했다), 그때 인덱스가 커지는 것은 블로트가 아니라 정상이다. 절대 MB로 물으면 그 순간부터
거짓 발견이 상시화되고, 상시 발견은 곧 무시된다. **기준선은 2026-08-11 REINDEX 직후의
202MB / 2,006,860행 = 백만행당 101MB이고, 임계 250은 거기 2.5배 여유다** — 직전 전례가
하루 만에 199MB→803MB(4배)였으므로 그 크기의 재발은 임계 안에 못 숨는다.

## 배경

**러너는 지표마다 필요한 봉 수를 따로 읽지 않고 전역 최대 하나로 읽는다**
(`IndicatorRunner.readRawData` → `registry.maxEffectiveLookback()`, N+1을 피하려는 설계다).
전역 최대는 `SharesGrowth3yCalculator`의 751봉이다. 그런데 **계산기 다수가 받은 창 전체에
대해 포인트를 낸다** — `AdjCloseCalculator`는 `priceBars(1)`을 선언하고도 창을 통째로 순회한다.

그 결과 **한 종목·한 실행이 최대 751거래일치를 쓰고, 보존이 180이므로 571거래일치가 경계
밖**이다(지표마다 유효 워밍업이 달라 실제 날짜 수는 그보다 적을 수 있다).
`purge()`가 배치 끝에서 그것을 지우므로 최종 상태는 언제나 옳고, 화면에도 흔적이 없다.

2026-08-10 로그가 그 왕복을 실측으로 남겼다:

```
20:03:19  정리했다. 기준일=2025-11-11 삭제=4469244행
20:42:24  정리했다. 기준일=2025-11-12 삭제=4373231행
```

두 정리 사이에 돈 것은 `daily-update` 하나이고 백필은 없었다. **첫 정리가 다 지운 뒤 그
실행 자신이 437만 행을 다시 만들었다.**

**이것이 ADR-0011이 답을 미뤄 둔 "왜 REINDEX 하루 만에 199MB → 803MB로 돌아왔는가"의
답이다.** ADR-0011이 후보로 지목한 반복 갱신 쪽이 맞았다 — 실측 `n_tup_upd`가 **3억 2천만
건**으로 `n_tup_ins`(1,690만)의 19배다. `IndicatorValueWriter`의 `IS DISTINCT FROM` 가드가
같은 값의 재기록을 막지만, **재귀 지표는 창이 하루씩 밀릴 때마다 과거 구간 값이 미세하게
달라져 그 가드를 정당하게 통과한다**(`phase-criteria.md`의 창 겹침). 즉 가드만으로는 닫히지
않는 경로였고, 이 결정이 그 구간을 아예 쓰지 않게 한다.

**잃는 것이 없는 근거**: 스크리닝은 언제나 최신 일자 한 장만 읽고(ADR-0011 §배경), 지표
계산의 입력은 `price_daily`이지 `indicator_value`가 아니며, 원자료는 전량 보관된다. P9의
전 구간 재계산이 다시 쓰는 것도 `price_daily.adj_close`다.

## 버린 대안

- **경고만 내고 계속 쓴다** — 초안의 안이었다. 두 가지로 깨진다. ① 백필 인자의 범위와 실제
  쓰기 범위가 다르므로(`from=to=오늘`이어도 570거래일이 쓰인다) **경고가 "0거래일 경계 밖"을
  찍으며 그 상황을 정상이라고 보증한다.** ② 원인이 백필이 아니라 일일 갱신이라, 백필에 경고를
  붙여도 매 거래일 같은 사이클이 그대로 돈다.
- **`purge()`를 자주 돌린다** — 삭제가 늘 뿐 삽입·갱신이 그대로다. 대량 삭제 직후가 인덱스
  블로트 최대 시점이라 오히려 나쁘다.
- **계산기마다 자기 창만큼만 읽어 준다**(러너가 지표별로 봉을 따로 읽는다) — 원리적으로
  가장 정확하지만 종목당 1회 읽기를 지표당 1회로 바꿔 N+1이 된다. 그 설계를 피한 것이
  `readRawData`의 존재 이유다.
- **계산기가 마지막 포인트만 내게 고친다** — 계산기 수십 개를 전부 고쳐야 하고, "지표 추가
  비용 1파일"에 새 규약이 하나 늘어 다음 계산기가 그것을 잊으면 다시 새어 나간다. 러너
  한 곳에서 자르면 계산기는 아무것도 몰라도 된다.
- **`indicator_value`를 경계 기준으로 삼는다**(purge와 같은 소스) — 지표가 아직 180거래일만큼
  쌓이지 않은 동안 경계가 비어, 초기 적재가 전 구간을 그대로 쓴다. 시세는 먼저 쌓이므로
  `price_daily`가 이 문제를 겪지 않는다.

## 결과

- **쉬워지는 것**: 매 거래일의 444만 행 삽입·삭제 왕복이 사라진다. 백필이 보존 밖을 되살리는
  경로가 원리적으로 닫혀, 백필 뒤에 정리를 신경 쓸 필요가 없다.
- **어려워지는 것**: 보존 밖 구간을 일부러 채울 수 없다. **`IndicatorBackfillJob`이 그 요청을
  거부하지 않고 경고한다** — 구간이 전부 경계 밖이면 돌지 않고, 일부면 몇 거래일이 저장되지
  않는지 말한다. 그 경고가 없으면 몇 시간을 돌고 0행으로 끝나는데 잡은 `SUCCESS`다.
- **인덱스는 저절로 줄지 않는다.** 이 결정은 **더 부풀지 않게** 할 뿐이고, 이미 쌓인 블로트는
  `REINDEX INDEX CONCURRENTLY`가 걷는다. 배포 당일 대량 삭제 직후에 돌려 **1,229MB →
  202MB**가 됐다(2026-08-11). **MUST NOT: 그 축소를 이 결정의 성과로 읽지 않는다** — 줄인
  것은 REINDEX이고, 이 결정이 하는 일은 **다음 배치가 그것을 되돌리지 않는 것**이다.
  귀속은 그렇게 둘로 나눠야 성립한다.
- **되돌리려면**: `IndicatorRunner.run()`의 세 번째 인자와 `retain()`, `writeCutoff()`,
  그리고 두 잡의 호출부. 되돌리는 순간 위 왕복이 다시 시작되고 **아무 증상도 나타나지 않는다.**
