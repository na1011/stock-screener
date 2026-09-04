---
id: ADR-0125
status: 채택됨
date: 2026-09-04
applies_to:
  - ".claude/hooks/guard-rebalance-on-dirty-rank.sh"
  - "src/main/java/com/stockscreener/pipeline/datasource/us/SecEdgarColumn.java"
  - "src/main/java/com/stockscreener/pipeline/datasource/us/SecEdgarFacts.java"
  - "src/test/java/com/stockscreener/pipeline/datasource/us/SecEdgarSharesProbeLiveTest.java"
---

# ADR-0125. 상장주식수 오염이 유니버스 순위에 닿는 것을 먼저 막는다

## 결정

**어댑터의 값 선택은 이번에 바꾸지 않고, 오염된 순위 축으로 리밸런싱하는 것을 가드로 막는다.**
`guard-rebalance-on-dirty-rank.sh`가 `--job=universe`를 `deny`하고, 최신 주식수가 자기 이력
중앙값의 50배를 넘는 종목을 수와 함께 보인다.

**어댑터를 안 고치는 것은 미루는 것이 아니라 실측 결과다** — 게이트로 쓸 축을 찾아 두 번
재봤고 둘 다 오염과 정상을 못 갈랐다(§버린 대안). 근거 없는 임계를 넣는 것보다 오염이
**순위에 닿는 경로**를 막는 편이 낫다. 시총 = 주식수 × 종가이므로 그 경로가 유일하게
되돌릴 수 없는 자리다.

## 규칙

- **MUST NOT: 「종목 안 중앙값 대비 N배」를 어댑터의 값 게이트로 쓰지 않는다.** 그것은
  **가드의 축이지 어댑터의 축이 아니다.** 가드는 사람에게 보여 판단을 부르지만, 어댑터가
  같은 축으로 값을 버리면 **정당한 대규모 희석을 조용히 지운다** — 분포에 절단점이 없다
  (아래 §측정).
- **MUST NOT: 「같은 보고서 안 태그 간 배수」로 오염을 판정하지 않는다.** 실측으로 기각됐다 —
  `AAPL`(1,033배)·`NVDA`(1,023배)가 그 축에서 오염 종목과 같은 자리에 서는데 **둘 다 저장된
  값은 정상**이다. 옛 보고서가 일부 태그를 천 주 단위로 태깅한 것이고, 현재 코드는 그
  보고서에서 이미 옳은 쪽을 고르고 있다.
- **MUST: 주식수 결손을 「소스가 안 준다」와 「우리가 못 고른다」로 갈라 다룬다.** 165종목의
  전량 NULL은 대부분 전자다 — 복수 클래스 발행사는 주식수를 `StatementClassOfStockAxis`로
  분해해 보고하고 **`companyfacts`는 축이 붙은 팩트를 뺀다.** `STZ`·`GEF`·`GEF-B`는 후보
  태그 넷이 **통째로 없다.** 태그를 더 넣어도 안 열린다.
- **MUST: 가드를 우회해야 하면 그 수를 사용자에게 보이고 승인을 받는다.** 이상치가 0이 되면
  가드는 다시 걸리지 않으므로, 정상 경로는 우회가 아니라 **주식수를 고치는 것**이다.

## 측정

### 오염의 규모 (2026-09-04, DB 전수)

| | 규모 | 순위에 닿는가 |
|---|---|---|
| 최신 주식수가 자기 이력 중앙값의 50배 초과 | **8종목** (`EVER` 1099배 · `TBLA` 966배 · `SENEA`/`SENEB` 702배 · `QXO` 197배 · `EMAT` 86배 · `DVLT` 56배 · `ORBS` 50배) | **닿는다** — `TBLA`가 시총 10위로 편입됐다 |
| 주식수 > 1e12 (1e6 배 오염) | **21행 / 7종목** (`EIX` 362조 · `AJG` 191조 · `AA` 185조 · `HXL` · `CSBR` · `EEFT` · `XHG`) | 전부 **과거 행**이라 안 닿는다 |
| 재무는 있는데 주식수 **전량 NULL** | **165종목** (`BRK.A/B`·`V`·`BIDU`·`STZ`·`GEF`·`PLNT`) | **미편입** — 버크셔(자산 1.26조)와 VISA가 여기 |
| ADS/원주 단위 불일치 | `AKTX`(원주 1,557억 주에 ADS 가격) | **닿는다** — 시총 7위 |

### 원인 (탐침이 소스에서 확정)

`SecEdgarSharesProbeLiveTest`가 답한 것이고, **코드만으로는 어느 것도 확정되지 않았다.**

- **1e6 배수는 제출자가 그렇게 태깅한 것이다.** `EIX`의 `dei:EntityCommonStockSharesOutstanding`이
  최소 3.26억 ~ **최대 362조**인데, 같은 회사의 `us-gaap:CommonStockSharesOutstanding`은
  3.26억 ~ 3.87억으로 정상이다. 우리 코드는 `dei:`를 최우선 후보로 쓴다.
- **부풀림은 폴백이 다른 개념을 집은 것이다.** `TBLA`의 `dei:`는 2.34억~3.36억(정상)인데
  팩트가 넷뿐이라 앵커에 못 맞고, 폴백이 `WeightedAverageNumberOf*`로 내려가
  **최대 344조**를 집었다. `RPAY`(113조)·`BRK`(1.65조)도 같은 모양이다.
- **결손은 `companyfacts`의 구조적 제약이다.** `STZ`·`GEF`·`GEF-B`는 네 태그가 통째로 없고,
  `V`는 `dei:`가 **2건**뿐이다(10-K 1 + 10-Q 1).
- **`Slot(accn, start, end)`가 클래스별 팩트를 덮는다는 가설은 기각됐다** — 표본 전체에서
  충돌 슬롯이 **0**이다.

### 왜 게이트를 못 만들었나 — 두 축을 재고 둘 다 버렸다

**축 ① 종목 안 최대/중앙값.** 분포에 절단점이 없다(US, 팩트 4개 이상):

```
1~2배 3693 · 2~3배 294 · 3~4배 119 · … · 10~11배 19
11~20배 71 · 20~50배 51 · 50~100배 12 · 100~1000배 27 · 1e3~1e4 21 · e5 초과 6
```

연속적이라 어디를 잘라도 정당한 희석을 함께 지운다. **가드에는 쓸 수 있다** — 가드는 값을
버리는 것이 아니라 사람에게 보이는 것이고, 오탐의 대가가 "확인 한 번"이기 때문이다.

**축 ② 같은 보고서 안 태그 간 배수.** 표본 15에서는 갈리는 것처럼 보였다(정상 1.0~1.6,
오염 4,936·1,005,086). **표본을 시총 상위 40으로 넓히니 무너졌다:**

| 종목 | 배수 | 저장된 값 |
|---|---|---|
| `AKTX` | 4,936 | 오염 |
| `AAPL` | **1,033** | **정상**(145.9억 주) |
| `NVDA` | **1,023** | **정상**(241억 주) |
| `PLTR` 이하 37종목 | 1.9 이하 | 정상 |

`AAPL`의 2014년 보고서는 `CommonStockSharesOutstanding=861,745`인데 같은 보고서의
`WeightedAverage`는 8.9억이다 — **옛 태깅이 천 주 단위**이고, 현재 코드는 그 보고서에서
이미 옳은 쪽을 고르고 있다. **정상 종목이 오염 종목과 같은 자리에 서므로 판정축이 못 된다.**

**표본 15에서 「빈 구간」을 보고 멈췄으면 정상 종목의 값을 버리는 게이트를 넣었을 것이다.**

## 검증

```premise 유니버스 순위 축에 이상치가 남아 있지 않다
psql -d stock_screener -At -c "WITH med AS (
  SELECT stock_id, (percentile_cont(0.5) WITHIN GROUP (ORDER BY shares_outstanding))::numeric AS md
  FROM financial_statement WHERE shares_outstanding > 0 GROUP BY 1 HAVING count(*) >= 4),
cur AS (
  SELECT DISTINCT ON (f.stock_id) f.stock_id, f.shares_outstanding AS cur
  FROM financial_statement f WHERE f.shares_outstanding > 0 AND f.is_latest
  ORDER BY f.stock_id, f.period_end DESC)
SELECT CASE
  WHEN (SELECT count(*) FROM cur) = 0
    THEN '잴 것이 없었다 — 최신 주식수를 가진 종목이 하나도 없다'
  WHEN count(*) = 0 THEN 'OK 순위 축 이상치 0 / 모집단 ' || (SELECT count(*) FROM cur)
  ELSE count(*) || '종목이 자기 이력 중앙값의 50배를 넘는다: '
       || string_agg(t, ', ' ORDER BY t)
       || ' — 시총 = 주식수 × 종가이므로 상위 N을 밀어낸다. guard-rebalance-on-dirty-rank.sh가 리밸런싱을 막는다(ADR-0125)'
  END
FROM (SELECT s.ticker || ' ' || round(c.cur / med.md, 0) || '배' AS t
      FROM cur c JOIN med ON med.stock_id = c.stock_id JOIN stock s ON s.id = c.stock_id
      WHERE c.cur / med.md > 50) z"
```

**모집단을 함께 내는 이유**: 주식수가 통째로 비면 이상치도 0이 되어 「깨끗하다」와 구분되지
않는다.

**전제 예산**: 이 펜스는 0.2초다(추가 시점 예산 23.6초/45초).

가드 자체의 동작은 `.claude/hooks/lib/regression.sh`가 아니라 **손으로 재현한다** —
DB 상태에 의존하므로 회귀표의 순수 함수 형태에 안 맞는다. 재현:

```bash
echo '{"tool_input":{"command":"./gradlew bootRun --args=\"--job=universe\""}}' \
  | .claude/hooks/guard-rebalance-on-dirty-rank.sh
```

## 남은 것 — 다음 세션이 다시 재지 않게

- **결손 165종목은 `companyfacts`로 안 열린다.** 여는 길은 `companyconcept` API로 축이 붙은
  팩트를 따로 받거나, 시세 소스(Alpaca)에서 주식수를 받는 것이다. **둘 다 새 외부 연동이라
  별도 ADR이다.** ADR-0099 §버린 대안이 `companyconcept`를 **사용자 결정으로 미뤄** 뒀다 —
  되살리려면 그 결정부터 다시 받는다.
- **`AKTX`의 ADS/원주 불일치는 위 어느 축으로도 안 잡힌다** — 소스 값이 내부적으로 일관되고
  시계열도 매끄럽다. 가르려면 ADS 비율이 필요하고 그것은 `companyfacts`에 없다.
- **`XOM`은 티커 매핑이 새 지주 엔티티를 가리킨다.** SEC의 `company_tickers_exchange.json`이
  `XOM`을 CIK **2115436**("ExxonMobil Holdings Corporation")에 매핑하는데 그 엔티티에는
  팩트가 거의 없다. 실제 Exxon Mobil은 CIK 34088이다. **소스가 그렇게 주므로 우리 캐시의
  결함이 아니다.**
- **`TSM`은 IFRS 제출자다** — `facts` 최상위 키가 `[dei, ifrs-full, srt]`로 **us-gaap이 없다.**
  재무 0행이 정상이며 어떤 태그를 더해도 안 열린다.

## 배경

2026-09-04, SEC EDGAR 기간 재구성 결함 셋을 고치는 작업(ADR-0124)에서 재적재 → 리밸런싱 →
지표 백필을 돌렸다. 그 계획서는 이 오염을 **이미 보고 있었고** 이렇게 적었다 —
*"지금도 상위 30에 `AKTX`·`TBLA`가 조 단위로 잡혀 있다 — 같은 파이프라인의 기존 오염이고
**이번 범위 밖이지만 대조 기준선으로 적어 둔다**."*

**보고는 했고 실측은 안 했다.** 그 결과 오염된 시총이 둘을 상위 10위 안에 편입시켰고,
고치려면 리밸런싱을 다시 돌려야 하는데 **같은 날 두 번 못 돈다**(`ck_membership_period`의
0일 구간 금지) — 하루를 잃고, 편입이 바뀌면 지표 백필도 다시 돌려야 하며,
`universe_membership`의 편입 이력은 **사후 복원이 안 된다**(ADR-0086·0099).

사용자 판정: *"오류에 대한 실측 없이 계획을 확정짓고 백필을 돌려서 복구 비용이 매우
비싸졌음. 발견한 결함에 대해 「내가 작성한 코드 아님」으로 치부하지 말고 그 자리에서 추적을
해야함."*

**그래서 이 ADR의 결정이 「고친다」가 아니라 「순위에 닿는 경로를 막는다」이다** — 축을 두 번
재고 둘 다 기각된 상태에서 어댑터를 고치면, 근거 없는 임계로 정상 값을 버리게 된다.

## 버린 대안

| 안 | 버린 이유 |
|---|---|
| `WeightedAverageNumberOf*`를 후보 태그에서 뺀다 | 개념적으로는 옳다(가중평균 발행주식수 ≠ 기말 상장주식수). **그러나 결손이 얼마나 느는지 안 쟀다** — 재려면 `US_TOP1000` 전수 탐침이 필요하고, 안 재고 빼면 이번에 지적받은 것과 같은 사고다. **다음 세션의 첫 후보이며, 재는 방법은 위 탐침의 두 번째 시험을 태그별 커버리지로 바꾸는 것이다.** |
| 종목 안 중앙값 대비 N배로 어댑터가 값을 버린다 | 분포에 절단점이 없다(§측정 축 ①). 정당한 희석을 조용히 지운다 |
| 같은 보고서 안 태그 대조로 버린다 | `AAPL`·`NVDA`가 정상인데 1,000배가 난다(§측정 축 ②) |
| 절대 상한(예: 1,000억 주 초과 거절) | 임의값이라 시장이 커지면 낡고, `AKTX`처럼 **소스가 원주 기준으로 주는** 정당한 큰 값과 못 가른다 |
| 오염 종목을 손으로 제외 목록에 박는다 | 목록은 반드시 낡고, 새 종목이 들어올 때마다 같은 오인이 재생산된다. 게다가 **왜 제외됐는지가 코드에서 사라진다** |
| 리밸런싱을 오늘 다시 돌려 고친다 | `ck_membership_period`가 0일 구간을 금지해 **실행이 통째로 되돌아간다**(ADR-0099 §결과) |
