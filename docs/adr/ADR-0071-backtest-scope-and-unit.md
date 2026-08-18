---
id: ADR-0071
status: 채택됨
date: 2026-08-18
applies_to:
  - "src/main/java/com/stockscreener/indicator/IndicatorDataReader.java"
  - "src/test/java/com/stockscreener/probe/**"
  - "docs/phase-criteria.md"
---

# ADR-0071. 백테스트를 범위에 들이되, 묻는 단위를 포트폴리오에서 조건으로 내린다

## 결정

**`docs/stock-screener-project-spec.md`의 범위를 넓혀 「그렇게 고르면 버는가」를 묻는다.**
정본에 「백테스트」가 한 번도 나오지 않으므로 이것은 범위 확대이며, **승인은 2026-08-18
사용자 프롬프트**다(CLAUDE.md 「기획 정본을 에이전트 판단으로 고치지 않는다」가 걸리는 자리).

**묻는 단위는 프리셋 포트폴리오가 아니라 조건이다.** `BUFFETT_QUALITY_KR`의 통과 14종목으로
수익률을 재는 것은 **표본이 답할 수 없는 물음**임을 착수 전에 실측으로 확인했다. 대신 각
조건이 유니버스를 가른 두 집단의 사후 수익률 차를 묻는다.

**백테스트는 `indicator_value`를 쓰지도 읽지도 않는다.** as-of마다 지표 엔진을 인메모리로
돌려 마지막 봉의 값만 취한다.

## 규칙

- **MUST: 시점 판정은 `published_at <= asOf`로 한다.** `period_end`만으로 자르면 공시 지연만큼
  미래를 본다 — 실측 중앙값이 분기 45일·연간 80일이고 p90이 152일이다. as-of 2026-01-01에서
  7개년 확보 종목이 `period_end` 기준 **267**, `published_at` 기준 **0**이다. 참조 구현이 이미
  있다: `PerMedian5yCalculator.ttmKnownOn`.
- **MUST: 정정본을 as-of 시점 판으로 고른다.** `is_latest`는 **오늘의** 최신이라 과거 as-of에
  정정 후 값이 샌다(실측 32행·8종목).
- **MUST NOT: 저장된 `indicator_value`를 백테스트가 읽지 않는다.** **이것이 이 결정에서 가장
  어기기 쉬운 규칙이고, 어겨도 결과가 나온다.** 기존 스크리닝 경로로 과거 일자에 게이트 6개를
  걸면 2025-11-19·2026-02-13·2026-05-15·2026-08-13에서 **11·13·16·14종목**이라는 그럴듯한
  시계열이 나오는데 **전부 가짜다** — 그 181일자 행의 `updated_at`이 한 행도 빠짐없이 오늘이다.
  `FinancialMath.spreadOverTradeDates`가 최신 재무 값 하나를 읽기 창 전 봉에 찍고 일일 갱신이
  매일 다시 쓰기 때문이며, 실제로 고려아연은 7번째 FY 행의 `published_at`이 2026-08-13인데
  `trade_date=2025-11-19`의 `PROFIT_STREAK_Y`가 7이다.
- **MUST NOT: 시점 경로가 라이브 경로를 바꾸지 않는다.** 기존 쿼리를 고치지 않고 경로를
  **더한다.** 라이브는 asOf=오늘이라 결과가 같지만, 저장된 `period_end = 2026-12-31` 1행의
  취급이 달라진다.
- **MUST: as-of는 `price_daily`에 있는 거래일로만 준다.** 휴장일을 주면 지표가 0행이 되어 필수
  조건이 전부 판정불가가 되고 통과 0이 나온다 — **게이트 탓으로 오독하기 쉬운 0이다.**
- **MUST NOT: 백테스트 결과로 임계값을 조정하지 않는다.** ADR-0061의 *"결과 수를 근거로
  임계값을 풀지 않는다"*와 ADR-0044의 *"손잡이를 푸는 것은 사용자 결정이다"*를 **수익률
  결과에도 확장한다. 어느 방향으로도 하지 않는다.**
- **MUST NOT: 이 측정 하나로 프리셋을 폐기하지 않는다.** 원인 후보가 넷이고(이식 결함·데이터
  결함·시장 차이·방법론 자체) 표본이 그것을 가르지 못한다. 4~12개 기간으로 원인을 특정하지
  못한 채 죽이면 **죽인 이유를 다음 세션이 알 수 없고 되살릴 근거도 남지 않는다.**
- **MUST: 결과를 다음에 프리셋을 문헌에서 다시 짤 때의 사전정보로 쓴다.** ADR-0061이 외국
  문헌의 재현율로 조건을 고른 바로 그 자리에, 이번엔 **우리 유니버스에서 잰 값**이 들어간다.
  용도를 이렇게 못박지 않으면 「기록만 한다」가 장식이 된다.
- **MUST: 생존 편향의 크기와 미보정 기업행위 제외 건수를 결과 보기 전에 재서 결과에 붙인다.**
  나쁜 결과가 나온 뒤에 데이터 탓을 하면 그것은 판정이 아니라 변명이다.
- **MUST NOT: 백테스트 엔진을 짓지 않는다.** 답하려는 물음이 도구의 크기를 정한다. 실측 기준선
  (350종목 × 180일자 = 1시간 51분)으로 단일 날짜 전 종목이 **≈37초**이고 17개 리밸런스 날짜면
  약 11분이다. 이 저장소에는 아무도 안 쓰는 Spring Batch가 0행으로 남아 있는 전례가 있다.
- **MUST: 재무 적재 전에 탐침을 먼저 돌린다 — 되돌릴 수 없는 것 앞에 되돌릴 수 있는 것을
  둔다.** `financial-refill --financial-years=11`은 FY2015에 닿기 위해 **FY2019~2026도 다시
  받는다**(`FinancialRefillJob:79`가 `fromFiscalYear`부터 올해까지 돈다 — 356 × 12년 × 보고서
  4종 ≈ **17,100콜**, 보수 예산 10,000/일의 171%). 그 재수집에서 **더 늦은 공시일이 오면
  정정으로 판정되고 `FinancialWriter:215`가 `invalidate`를 불러 그 종목 지표를 보존 창
  전체에서 지운다.** 복구는 백필이고 350종목이면 1시간 51분이다.
  순서: **36콜 정정 탐침**(다중회사 주요계정의 `rcept_no` 앞 8자리를 저장된 `published_at`과
  대조 — `batch-load` skill) → **32콜 옛 연도 계정 탐침**(FY2015~2018의 IFRS 태그가 다를 수
  있고 재본 적이 없다) → `ops/dump-db.sh` → `financial-refill` → `full-statement-refill` →
  **무효화된 종목만** `backfill`.
- **MUST NOT: 재무를 열어 창이 늘어난 것을 시세가 늘어난 것으로 읽지 않는다.** `price_daily`가
  2020-01-02부터라 7개년이 2022-04에 열려도 그보다 앞으로는 못 간다. 그 시점에 시세가 있는
  종목은 **313/356**이다.
- **MUST NOT: 린치 프리셋으로 넓히지 않는다.** 통과 집합이 7·9라 표본 문제가 더 심하고, 린치는
  분류기라 유형 게이트가 먼저 서므로(ADR-0070) 조건 단위로 내리는 이 설계가 그대로 안 옮겨진다.

## 검증

시점 판정 경로가 **술어로** 들어갔는가 — 주석이 아니라 `WHERE`절이어야 한다.

```bash
rg 'published_at\s*<=' src/main/java/com/stockscreener/indicator/IndicatorDataReader.java
```

백테스트 경로가 저장된 지표를 읽지 않는가.

```bash
rg 'indicator_value' src/test/java/com/stockscreener/probe/
```

기준일=오늘로 돌린 as-of 재계산의 통과 **집합**이 화면과 같은가(수가 아니라 집합이다).

```bash
./gradlew test --tests "*AsOfRecomputeTest*"
```

---

**아래 넷은 이 결정이 아직 옳은지를 묻는다.** 넷 다 실측이 근거이고, 값이 움직이면 결론의
어느 부분이 바뀌는지가 각 라벨에 있다.

```premise 백테스트 창의 시작이 아직 2026-03-09이다
psql -d stock_screener -At -c "WITH seven AS (SELECT stock_id, max(published_at) p7 FROM (SELECT stock_id, fiscal_year, published_at, row_number() OVER (PARTITION BY stock_id ORDER BY fiscal_year DESC) rn FROM financial_statement WHERE fiscal_period = 'FY' AND is_latest) t WHERE rn <= 7 GROUP BY stock_id HAVING count(*) = 7) SELECT CASE WHEN (SELECT count(*) FROM financial_statement WHERE fiscal_period = 'FY') = 0 THEN '잴 것이 없었다 — 연간 재무가 한 행도 없다' WHEN (SELECT count(*) FROM seven) = 0 THEN '7개년을 갖춘 종목이 없다 — PROFIT_STREAK_Y가 전 종목 판정불가이고 백테스트 창이 0이다' WHEN (SELECT min(p7) FROM seven) = DATE '2026-03-09' THEN 'OK 백테스트 창 시작 2026-03-09 · 7개년 확보 ' || (SELECT count(*) FROM seven) || '종목' ELSE '창 시작이 ' || (SELECT min(p7) FROM seven) || '로 움직였다(계획 기준 2026-03-09) — 재무 적재가 돌았거나 정정이 공시일을 바꿨다' END"
```

```premise 저장된 지표에 시간축이 없다 — 백테스트가 그것을 읽으면 안 되는 이유
psql -d stock_screener -At -c "SELECT CASE WHEN count(*) = 0 THEN '잴 것이 없었다 — PROFIT_STREAK_Y 행이 하나도 없다' WHEN max(updated_at)::date - min(updated_at)::date <= 2 THEN 'OK 저장된 지표 ' || count(DISTINCT trade_date) || '거래일이 ' || max(updated_at)::date || ' 한 번에 쓰였다 — 시간축이 없다' ELSE '저장된 지표의 updated_at이 ' || min(updated_at)::date || '~' || max(updated_at)::date || '로 갈렸다 — 시간축이 생겼는지 확인하고 백테스트 금지 규칙을 다시 판단한다' END FROM indicator_value WHERE indicator_code = 'PROFIT_STREAK_Y'"
```

```premise 시점 유니버스가 DB에 여전히 없다 — 생존편향 판정의 근거
psql -d stock_screener -At -c "SELECT CASE WHEN count(*) = 0 THEN '잴 것이 없었다 — universe_membership이 비었다' WHEN min(effective_from) = DATE '2026-08-05' THEN 'OK 유니버스 이력이 여전히 2026-08-05부터다 — 그 이전 시점 유니버스는 DB에 없다(구간 ' || count(DISTINCT effective_from) || '개)' ELSE '유니버스 이력의 시작이 ' || min(effective_from) || '로 바뀌었다 — 과거 편입을 넣는 경로가 생겼다면 생존편향 판정을 다시 한다' END FROM universe_membership"
```

```premise 수익률에서 제외할 종목이 아직 7이다
psql -d stock_screener -At -c "WITH bad AS (SELECT DISTINCT stock_id FROM corporate_action WHERE status IN ('OBSERVED','UNSUPPORTED') AND effective_date >= DATE '2020-01-01') SELECT CASE WHEN (SELECT count(*) FROM corporate_action) = 0 THEN '잴 것이 없었다 — 기업행위 원장이 비었다' WHEN (SELECT count(*) FROM bad) = 0 THEN 'OK 미보정 기업행위가 없다 — 수익률 제외 목록이 비었다' WHEN (SELECT count(*) FROM bad) = 7 THEN 'OK 미보정 기업행위 7종목(계획 기준) — 수익률 제외 목록이 그대로다' ELSE '미보정 기업행위가 ' || (SELECT count(*) FROM bad) || '종목이다(계획 기준 7) — 수익률 제외 목록이 낡았다' END"
```

## 배경

P16·P16-B가 답한 것은 「버핏처럼 골랐는가」다. 「그렇게 고르면 버는가」는 묻지도 않았고,
백테스트가 없으며 그 자리를 대신하는 것도 없다.

**반대 방향의 증거가 이미 저장소 안에 있다.** ADR-0061이 한국거래소 2000-01~2019-06 표본에서
**수익성 이상현상의 재현율 5.0%**(가치 69.2 · 모멘텀 66.7 · 거래마찰 48.1과 대비)를 근거로
`ROE`를 조건에서 뺐다. 그런데 이 프리셋의 필수 게이트 여섯 중 셋(`ROE_MIN_3Y`·`ROIC_PRETAX`·
`PROFIT_STREAK_Y`)이 전부 수익성이다. **가장 안 재현된 범주 위에 서 있고, 그것이 긍정적
결과를 먼저 의심할 이유이기도 하다.**

### 왜 포트폴리오가 아니라 조건인가 — 실측 둘

**어떤 조건과 수익률의 실제 관계도 보지 않고, 귀무분포만 쟀다.** 무작위로 가른 집단의 분산은
조건을 모르는 값이라 답을 미리 보는 것이 아니다.

- **무작위 14종목 400회**의 6개월 초과수익(유니버스 평균 대비) SD가 **10.0~13.5%p**(2025-08
  시작 구간은 36.3%p), 5~95% 구간이 **±20%p**다. 평균의 표준오차를 2.5%p까지 내리려면 독립
  6개월 기간이 **23개 ≈ 11.5년** 필요하다. 지금 쓸 수 있는 기간은 **0개**이고 재무를 4년 더
  적재해도 **8개**(SE 4.2%p)다. **어떤 적재를 해도 답이 안 나온다.**
- **유니버스 무작위 절반 300회**의 6개월 수익률 차 SD는 **4.3~5.6%p**로 절반 이하다. 8개
  기간이면 SE ≈ 1.8%p, 즉 **3.5%p/6개월을 2σ로 잡는다.** 이상현상 문헌의 효과 크기가 그
  언저리이므로 **답이 나오는 물음이다.**

**단위를 내린 세 번째 이유는 식별이다.** 나쁜 결과의 원인 후보가 넷인데 표본이 그것을 가르지
못한다. 그런데 **개별 조건은 이식 결함의 영향을 받지 않는다** — 「`ROIC_PRETAX >= 15`인 종목이
국내에서 더 벌었는가」는 우리가 원저의 다른 조건을 못 옮긴 것과 무관한 물음이다. 개별 조건은
방향이 맞는데 결합이 안 되면 **구조·이식** 쪽, 개별도 안 맞으면 **시장 차이** 쪽이고 후자는
ADR-0061의 5.0%와 **출처가 다른 두 증거가 겹친다.** 그것이 이 설계가 얻을 수 있는 가장 강한
형태의 결론이다. **방법론 자체가 틀렸을 가능성은 이 측정이 답하지 못한다.**

### 인샘플 경계와 오염의 상한

임계값은 2026-08-13 단면으로 보정됐다(ADR-0067·0068). **그 보정은 「그날 몇 종목이 통과하는가」
만 봤고 수익률은 한 번도 보지 않았다** — 오염된 것은 임계값이 2026년 단면의 어느 분위에
앉는가이지 임계값과 수익률의 관계가 아니다. 이것이 측정이 아예 무의미해지지 않는 이유다.

**아웃오브샘플 경계를 2025-04-01로 둔다.** 그 이전 as-of의 판정은 FY2023 이하 재무로 이뤄지고,
보정이 본 단면을 만든 FY2024(2025-04 공시)·FY2025(2026-04 공시)와 겹치지 않는다.
조건 15개를 재므로 **게이트 6 / 배점 9 두 가족에 각각 Holm 보정**을 사전에 정한다.

### 무엇이 재현되지 않는가

**시점 유니버스는 DB로도 소스로도 복원되지 않는다. 이유가 셋이고 성질이 다르다.**
(i) `UniverseReconciler`가 실행일을 `effective_from`으로 찍고 현재 편입분과의 차집합만 계산해
이력이 **2026-08-05부터**다. (ii) `MarketCapUniverseAdapter`는 과거 기준일을 재현하지만 편입
판정에 히스테리시스가 있어(`RETENTION_BAND = 1.1`) **경로 의존적**이다 — as-of 1콜로는 그날의
실제 멤버십이 나오지 않는다. (iii) 복원해도 데이터가 없다 — `stock`·`price_daily` 모두 정확히
356종목이고, **편출 6종목의 마지막 봉이 전부 `effective_to` 당일**이다(`StockRegistrar`가
`effective_to IS NULL`로 대상을 잡으므로 편출 즉시 수집이 멈춘다).

→ 탐침으로 **하한만** 잰다. **「복원했다」로 적지 않는다.**

**수익률의 정본에도 구멍이 있다.** 2020 이후 원장 사건 91건 중 `UNSUPPORTED` 7 · `REJECTED` 3.
`UNSUPPORTED` 7종목은 인적분할·감자처럼 비율을 낼 수 없어 원장이 포기한 것이라 `adj_close`가
실제로 끊겨 있다 — 아시아나항공 **+327.6%**, SK텔레콤 **−81.3%**, DN오토모티브 **−81.2%**,
삼성바이오로직스 +46.5%, 현대지에프홀딩스·OCI홀딩스·솔브레인홀딩스 −40.3/−31.2/−37.6%.
`REJECTED` 3은 휴리스틱 오탐이므로 실제 주가 변동이며 **제외 대상이 아니다.**

## 버린 대안

- **저장된 `indicator_value`로 재기** — 가장 싸고 **결과도 나온다**(11·13·16·14종목). 그래서
  버린다. 보존 창이 181일자뿐인 것은 부차적이고, 진짜 이유는 **그 값들에 시간축이 없다**는
  것이다. 창을 늘려도 안 풀린다.
- **보존 기간을 늘려 과거 지표를 쌓기** — `RETAINED_TRADING_DAYS`를 올리면 `IndicatorRunner`가
  경계 밖 쓰기를 버리는 것도 함께 풀린다. 그러나 **쌓이는 값이 여전히 오늘 재무로 계산된
  것**이라 문제를 크게 만들 뿐이다. ADR-0045가 `MUST NOT: 경계를 asOf에서 역산하지 않는다`로
  그 우회를 이미 막아 두었다.
- **포트폴리오 수익률을 그래도 내고 참고로 쓰기** — 숫자가 나오면 그것이 결론처럼 읽힌다.
  귀무 SD가 10~13.5%p인 통계를 「참고」로 두는 자리가 실무에 없다.
- **`PROFIT_STREAK_Y`를 빼고 6개월 창을 늘리기** — 게이트 여섯 중 이것만 7개년을 요구하므로
  빼면 창이 2022-03으로 열린다. 그러나 **측정 도구에 맞춰 프리셋을 고치는 것**이고, 그렇게
  잰 것은 `BUFFETT_QUALITY_KR`이 아니다. 대신 재무 이력을 4년 여는 쪽을 골랐다(사용자 결정).
- **`fnlttMultiAcnt` 어댑터를 지어 192콜로 적재하기** — 기존 잡의 17,100콜보다 40배 싸다.
  그러나 새 외부 연동이라 계정 매핑이 기존 어댑터와 같은지 먼저 재야 하고, **적재가 목적인
  phase에서 어댑터를 늘리면 그것이 목적이 된다.** 1차는 기존 잡을 쓴다.

## 결과

**쉬워지는 것** — 조건 하나를 프리셋에 넣기 전에 「국내에서 방향이 맞는가」를 물을 수 있다.
ADR-0061이 외국 문헌 재현율로 답하던 자리에 우리 유니버스에서 잰 값이 들어간다.

**어려워지는 것** — 재무를 읽는 경로가 둘이 된다(라이브·시점). **둘이 어긋나면 화면과
백테스트가 다른 종목을 고르는데 어느 쪽도 에러를 내지 않는다.** 그래서 「기준일=오늘로 돌린
통과 집합이 화면과 같은 집합인가」를 회귀 검사로 둔다.

**되돌리려면** — `IndicatorDataReader`에 더한 경로와 `src/test/java/com/stockscreener/probe/`의
하네스를 지우면 된다. 라이브 경로를 건드리지 않았으므로 되돌림의 반경이 좁다. 다만 **적재된
FY2015~2018 재무는 되돌리지 않는다** — 원자료는 지우지 않는다(ADR-0011).
