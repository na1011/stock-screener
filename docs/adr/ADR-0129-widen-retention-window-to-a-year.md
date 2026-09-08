---
id: ADR-0129
status: 채택됨
date: 2026-09-08
applies_to:
  - "src/main/java/com/stockscreener/indicator/IndicatorRetentionPolicy.java"
  - "src/main/java/com/stockscreener/indicator/IndicatorRegistry.java"
  - "src/main/java/com/stockscreener/indicator/calculator/TradingDays750Calculator.java"
---

# ADR-0129. 보존 창을 1년으로 넓히고, 한 판이 그 창을 채우게 남은 지표 하나를 고친다

## 결정

**둘이다.**

1. `IndicatorRetentionPolicy.RETAINED_TRADING_DAYS`를 **180 → 255**로 올린다. 그 값은
   **삭제 창이자 읽기 창의 성분**이고(`readWindowBars() = maxEffectiveLookback() + 이 값`,
   ADR-0079) 읽기 창이 1,441 → **1,516봉**이 된다.
2. **`TradingDays750Calculator`가 창 안의 산출 일자마다 값을 낸다**(`min(i + 1, 750)`).
   그전에는 마지막 봉 하나만 냈다.

**닫는 축과 안 닫는 축을 갈라 적는다.** 이 결정이 닫는 것은 **창**이다. 재무 시점성은
ADR-0128이 닫았다. **유니버스 축은 열린 채다** — `universe_membership` 이력의 하한이
KR `2026-08-05` · US `2026-08-31`이라 **새로 여는 75일자는 전부 그보다 1년 이상 앞이고
후보 집합·백분위 모집단이 오늘 명부다.** 그 크기는 우리 데이터로 못 잰다(생존편향 ·
`stock`에 비활성 종목 0행).

## 왜 — 과거 기준일 조회가 8.5개월에서 멎어 있었다

기준일 선택기가 보존 창 전체로 열려 있고(`AsOfResolver` → `IndicatorCalendar` →
`indicator_trade_date`), 그 요청이 저장 값 한 장으로 종목 선정·점수·상세 지표표를 정한다.
ADR-0128이 그 값을 봉마다 그 시점의 재무로 내게 고쳐 **값은 시점 재현이 됐는데 창이 1년을
못 덮었다.** 도달점은 국내 `2025-08-21~` · 미국 `2025-09-02~`다.

## 규칙

- **MUST: 늘릴 때는 도는 앱을 백필보다 먼저 갈아끼운다.** `DailyUpdateJob`이
  `retentionPolicy.purge()`를 부르므로 **옛 상수를 든 jar가 방금 채운 구간을 지운다.**
  증상은 「백필이 SUCCESS인데 창이 안 늘었다」 하나뿐이다.
- **MUST: 창을 움직이면 전 코드 스냅숏 대조를 밟는다**(ADR-0079 §규칙 승계). 대상은 일봉 재귀
  넷이 아니라 **전 코드**다 — 주봉 평활은 시드까지의 거리가 봉 수가 아니라 그 1/5쯤인 주 수라
  잔차가 저장 자릿수 위로 올라온다.
- **MUST: 창을 움직이면 시점 재현 저장소의 판이 갈린다.** `PitFingerprint.forCalculators`가
  `readWindowBars()`와 `com/stockscreener/indicator/**.class` 전 바이트를 해시하므로 이 상수
  하나로 **512 매니페스트가 전부 낡는다.** `-Dpit.recheck=calculator`(실측 5분 30초)로 값을
  재서 판정하고, **던지면 사람이 정한다** — 조용히 다시 만들면 3시간 41분이 나간다.
- **MUST: `TRADING_DAYS_750`의 과거 일자 값은 `maxEffectiveLookback() >= 750`에 얹혀 있다.**
  계산기 자신이 `priceBars(750)`을 선언해 산식으로 참이고(`maxEffectiveLookback()`은 전 코드
  최댓값이다) `IndicatorReadWindowTest`가 그 최댓값을 등식으로 못박는다. **그 수가 750 아래로
  내려가면 값이 조용히 틀린다.**
- **MUST NOT: 그 자리에 새 펜스를 세우지 않는다.** 위 두 기제가 이미 지키므로, 더하면
  ADR-0079가 이 지표를 자기 펜스에서 뺀 이유(**아무도 떨어뜨릴 수 없는 펜스**, ADR-0074가 금지한
  형태)를 그대로 만든다.
- **MUST: ADR-0109의 「보존 창 전체」가 이제 255를 뜻한다는 것을 안다. 박혀 있는 임계값은 그대로
  둔다** — 그 값들은 180거래일 창에서 나왔고(그 문서가 날짜로 적어 뒀다), 다시 재는 것은 프리셋
  임계를 움직이는 별도 결정이다. **백테스트로 고치지 않는다**(ADR-0071·0072).
- **MUST: 채움을 코드×날짜가 아니라 종목×날짜로 잰다.** 앞 grain은 이 결정이 닫은 결손에
  **구조적으로 눈감는다** — 아래 §결과의 수가 그 증거다.

## 검증

```bash
./gradlew test --tests "*IndicatorRetentionTest*" --tests "*IndicatorReadWindowTest*" \
               --tests "*MarketScopedTradeDateTest*" --tests "*SwingIndicatorsTest*"
```

**`premise` 블록을 새로 만들지 않는다** — 전제 예산 관측이 45/45초와 31.5초로 갈려 여유를
단정할 수 없고, ADR-0011·0045·0079의 premise 셋이 이미 창을 잰다. 아래는 손으로 도는 검증이다.

```bash
# ① 창이 실제로 255인가 — 시장마다 센다
psql -d stock_screener -At -F'|' -c "SELECT market_code, count(*), min(trade_date) FROM indicator_trade_date GROUP BY 1 ORDER BY 1"

# ② 격자가 창을 덮는가 — 종목×날짜 grain. 코드별 일자 수로는 안 보인다
psql -d stock_screener -At -F'|' -c "
WITH act AS (SELECT DISTINCT m.stock_id FROM universe_membership m JOIN universe u ON u.code=m.universe_code WHERE m.effective_to IS NULL AND u.is_active),
poss AS (SELECT s.market_code, count(*) n FROM price_daily p JOIN act ON act.stock_id=p.stock_id JOIN stock s ON s.id=p.stock_id
         JOIN indicator_trade_date itd ON itd.market_code=s.market_code AND itd.trade_date=p.trade_date GROUP BY 1),
have AS (SELECT s.market_code, count(*) n FROM indicator_value iv JOIN act ON act.stock_id=iv.stock_id JOIN stock s ON s.id=iv.stock_id
         WHERE iv.indicator_code='TRADING_DAYS_750' GROUP BY 1)
SELECT poss.market_code, have.n, poss.n, round(100.0*have.n/poss.n,1) FROM poss JOIN have ON have.market_code=poss.market_code ORDER BY 1"

# ③ 원본은 있는데 파생층이 없는 거래일 — 이 자리를 재는 펜스가 따로 없다
psql -d stock_screener -At -c "
WITH d AS (SELECT market_code, trade_date FROM indicator_trade_date),
pct AS (SELECT DISTINCT s.market_code, iv.trade_date FROM indicator_value iv JOIN stock s ON s.id=iv.stock_id WHERE iv.indicator_code LIKE '%_PCTL')
SELECT CASE WHEN count(*)=0 THEN 'OK 파생층이 빠진 거래일이 없다'
            ELSE '파생층이 통째로 빠진 거래일 ' || count(*) || '건: ' || string_agg(d.market_code||' '||d.trade_date, ', ' ORDER BY d.trade_date) END
FROM d LEFT JOIN pct ON pct.market_code=d.market_code AND pct.trade_date=d.trade_date WHERE pct.trade_date IS NULL"
```

②의 기대는 **세 시장 다 100%**다. 하나라도 내려가면 결함이다.

## 버린 대안

| 대안 | 왜 버렸나 |
|---|---|
| **날짜 루프로 재백필한다** | 실측 **7.5~9.3시간**(`batch_execution` id 310이 184일자 × 1,350종목 = 248,400작업으로 162분에 29%). `run`이 읽기 창 전체를 쓰므로 base는 **한 판**이면 채워진다(ADR-0079) |
| **파생층을 전 날짜 재계산한다** | 기존 일자의 백분위를 **오늘 명부**로 덮는다. 새로 든 날짜만 돌린다 — 다만 두 시장의 하한이 달라 국내 4거래일이 겹쳐 다시 계산된다(재계산 차 평균 0.37%p) |
| **`TRADING_DAYS_750`의 빈 칸만 SQL 한 판으로 채운다** | `min(row_number(), 750)`이라 몇 초면 된다. **기각 근거는 비용이 아니라 같은 로직의 두 번째 구현**이라는 것이고, 창을 움직일 때마다 다시 든다 |
| **그 결손을 사실로 기록하고 넘긴다** | **ADR-0079 §버린 대안이 같은 형태를 2026-08-21에 사용자 판단으로 기각했다**(*"드러내기만 한다 — 172일을 빈 채로 두고 0건의 이유를 화면에 말한다"*) |
| **`IndicatorReadWindowTest`에 `>= 750` 단언을 새로 세운다** | 계산기 자기 선언이 산식으로 보증하고 기존 등식 단언이 더 엄격하다. 세우면 **떨어질 수 없는 펜스**가 된다 |
| **ADR-0123의 시점 재현 저장소를 폐기한다** | 그 저장소의 근거 둘이 이 결정으로 무너지지만 **남는 둘이 있다** — 보존 창은 매일 미끄러져 고정 구간이 유지되지 않고, 엔진 왕복 비용은 재보지 않았다. **별도 결정이다** |

**ADR-0123·0071이 이 길을 기각해 뒀고 근거 넷 중 둘이 무너졌다.** ①`LATEST`의 look-ahead는
ADR-0128이 닫았다. ②「재백필 10~25시간·중단 불가」는 날짜 루프 전제였다. 남은 둘은 답했다 —
③ADR-0036의 「구간 분할·중단 복구 금지」는 §결과가, ④저장 부피는 아래가 답한다.

## 결과

**실측 (2026-09-08 · 변경 전)**

| 무엇 | 값 |
|---|---|
| `indicator_value` | 30,315,472행 · 본체 2,531MB · 총 8,779MB · DB 9,357MB |
| 예상 순증 | 약 12.5M행(+41%) → 약 12.4GB(+3.6GB) · 디스크 여유 311GiB |
| 인덱스 밀도 | 백만행당 206MB(ADR-0045 펜스 임계 250 · REINDEX 직후 기준선 101) |
| 지수 이력 | KOSPI/KOSDAQ 1,640봉 · US 1,762봉 → 읽기 창 1,516을 덮는다(여유 124 / 246) |
| 봉 깊이 ≥1,516 활성 종목 | KOSPI 173/200 · KOSDAQ 113/150 · US 883/1000 (≥1,441 대비 **18종목 감소**) |
| 새로 억제 창에 드는 미보정 기업행위 | **1종목**(`036830` · 2020-08-06 `UNSUPPORTED`) |

**`TRADING_DAYS_750`의 결손은 새로 생긴 것이 아니라 이미 있던 것이다.** 종목×날짜 grain으로
재니 변경 전이 이랬다 — **코드×날짜 grain은 145코드 전부를 「창을 덮는다」로 신고했다.**

| 시장 | 저장된 칸 | 가능한 칸 | 채움 |
|---|---|---|---|
| KOSPI | 35,048 | 35,925 | 97.6% |
| KOSDAQ | 26,076 | 26,591 | 98.1% |
| **US** | **111,490** | **179,637** | **62.1%** |

즉 이 결정은 기존 일자에 **약 +69,539행**을 더한다(US +68,147). 새 75일자는 그 위에 얹힌다.

**등가성은 선언이 아니라 값으로 쟀다.** 마지막 봉만 내던 시절에 쌓인 활성 유니버스
**172,614행**이 `min(rank, 750)`과 **차 0**이고 창을 반영한 식과도 **차 0**이며, 그중
**9,545칸이 비포화**라 그 갈래가 실제로 태워졌다. 항등식이 아니다.

### 실행 결과 (2026-09-09)

| 축 | 값 |
|---|---|
| 창 | KOSPI/KOSDAQ **255**(`2025-08-21~`) · US **255**(`2025-09-02~`) — 예측 하한과 일치 |
| base 백필 | 전 종목 단일 날짜 1회 · **31.5분** · 10,776,521행(id 340) |
| 파생층 | 84거래일 · **1.9분**(id 341). 새로 채운 것은 시장마다 75일자 |
| 부피 | 30,315,472 → **42,297,877행**(+39.5%) · 본체 2,531 → **3,012MB** · 총 8,779MB → **11GB** · DB **13GB** |
| 인덱스 밀도 | 백만행당 206 → **188MB**. **옛 날짜 쪽 삽입이 밀도를 깎을까 걱정했는데 반대였다** — 새 행이 처녀 키 공간에 들어가 기존의 churn보다 촘촘하다 |
| 코드 커버리지 | 255에 못 미치는 코드 **0개**(145 전부) |
| 파생층 결손 | 빠진 거래일 **0** |
| `TRADING_DAYS_750` 격자 | KOSDAQ **100%** · US **62.1% → 100%** · KOSPI **98.4%** |
| 멱등 | 같은 인자로 두 번 더 돌려 **갱신 행 0**(id 342 → 343). **id 340 → 342가 311행이었던 것은 비결정성이 아니다** — 그 사이 파생층 실행(id 341)이 `--tickers=005930`을 다른 `asOf`로 돌아 그 종목의 `asOf` 민감 지표를 옮겨 놓은 것이고, 다음 실행이 되돌렸다(전수 확인: 311행 전부 `005930`의 주봉 재귀 셋 + `RETAINED_TO_MARKET_4Y`) |
| 화면 | 기준일 `2025-09-05`(옛 창 밖)에서 코스닥 150종목 전부 판정 · 「데이터 없음」 0. `2025-08-02`은 빈 결과가 아니라 **거절**이고 화면이 구간을 말한다 — *"보존 구간(2025-08-21 ~ 2026-09-07) 밖이다"* |

**KOSPI 98.4%는 결함이 아니다.** 빠진 **827칸이 전부 미보정 기업행위 넷의 억제**다(ADR-0055) —
`007340` 255 · `010060` 206 · `207940` 193 · `005440` 173이고, 각 결측 구간이 그 효력일의
750봉 창과 맞는다.

**전 코드 스냅숏 대조** — 사라진 행 **20**(업종 상대 계열, 겹친 4거래일의 모집단 변화),
더해진 행 **11,982,513**(KOSDAQ 1,371,854 · KOSPI 1,894,030 · US 8,716,629).
값이 달라진 것은 넷뿐이다.

| 계열 | 행 | 최대 차 | 읽는 법 |
|---|---|---|---|
| 주봉 재귀 셋(`MACD_V_W`·`MACD_V_HIST_W`·`MACD_V_HIST_CHG_W`) | 350,762 | **5.0e-5**(값 크기 최대 545) | 수렴 오차. **부호가 뒤집힌 행 0**이라 ADR-0119의 히스토그램 교차 축에 안 닿는다 |
| `*_PCTL`·업종 상대 | 약 10,300 | 백분위 0.66 | 겹친 국내 4거래일(`2025-12-11~16`)을 오늘 명부로 다시 매긴 것. §버린 대안이 그 대가를 미리 적었다 |
| `ICHI_*` 여섯 | 48 | 이산값 | ADR-0079의 전례(`ICHI_MOD_C` 14행)와 같은 모양 |
| **`RETAINED_TO_MARKET_4Y`** | **140 · 12종목** | **20.4** | **아래** |

**`RETAINED_TO_MARKET_4Y`의 javadoc이 실측에 뒤집혔다.** 그것이 *"앵커는 날짜로 찾으니 읽기
창이 넓어져도 값은 물론 커버리지도 안 는다"*로 단정하고 있었는데, 창을 넓히니 **12종목 140행이
움직였다** — 전부 **10~11월 결산 미국 종목**이고 구간이 **옛 미국 창 앞머리 `2025-12-17`에서
시작**한다. `anchorIndex`가 창 앞머리보다 이른 FY−4 기말을 못 잡는 자리다.
**둘 중 어느 값이 옳은지는 안 쟀다** — javadoc의 그 문장을 고치고 `docs/progress.md`에 올린다.

**전제 전/후** — 참 118 → **117** · 발견 5 → **6** · **판정 불가 0 유지**(33.6초 load 1.96 →
36.4초 load 2.54 · 상한 45초). **표가 +39.5% 커졌는데 무판정이 안 늘었다.**

- **새 발견은 이 결정이 연 것이다.** ADR-0126의 출력 축 펜스가 `BMNR 2025-11-21 154배`를
  잡았는데 그 날짜는 **새로 든 구간 안**이다. 그 문서가 *"이 펜스가 못 보는 것 넷"*에
  **「보존 창 180거래일 밖」**을 적어 뒀고, 이 결정이 그 하나를 닫았다. `BMNR`은 아래
  §굳히는 축에서 센 미국 13종목 중 하나다.
- **ADR-0067·0109의 수가 움직인 것은 회귀가 아니다** — 셋 다 `rn % 20 = 1`로 보존 창을
  표본하므로 표본이 9일 → 13일이 되고 창이 `2025-08-21`까지 내려갔다.
  `KOSPI_TOP200 MARKET_CAP` 99.1% → 98.1%, ADR-0109가 2조건(6.4·5.2%p) → 4조건(6.5·6.4·5.5·5.5%p).
  **렌즈가 바뀐 것이고 처방은 그 문서가 이미 적은 「다시 잰다」다.**

**어려워지는 것**

- **깊이가 모자란 18종목**은 창 앞쪽에서 워밍업 1,261짜리 지표를 못 낸다. 결함이 아니라 그
  종목의 시세가 거기까지라는 사실이다.
- **`FinancialMath.sharesOf`의 기준점 오류가 새 구간에도 실린다**(ADR-0126 · 활성·미수리).
  공시일이 새로 드는 구간에 든 판이 **26건 · 미국 13종목**이다(`ANET APG BMNR ETR FAST GSAT
  IBKR ORLY PANW PEGA RLI SNEX TSCO`). **그것을 고칠 때 다시 백필할 범위가 이 수다** —
  원자료가 아니라 계산 결과라 base 한 판이면 되돌아온다.
- **ADR-0036·0052 전제가 `indicator_value`를 전수로 훑어 더 느려진다.** 실행 후 다시 센다.

**되돌리려면** 상수를 180으로 되돌리고 일일 갱신을 한 번 돌린다 — `purge()`가 늘어난 구간을
지운다. **원자료도 유니버스 편입 이력도 안 건드리므로 잃는 것은 인덱스 밀도뿐**이고, 그것은
`REINDEX INDEX CONCURRENTLY`가 걷는다. 계산기 쪽을 되돌리면 US 62.1% 상태로 돌아간다.
