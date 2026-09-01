---
id: ADR-0113
status: 채택됨
date: 2026-09-01
applies_to:
  - "src/main/java/com/stockscreener/pipeline/datasource/us/AlpacaDividendAdapter.java"
  - "src/main/java/com/stockscreener/pipeline/datasource/us/AlpacaGovernanceFactMapper.java"
  - "src/main/java/com/stockscreener/pipeline/datasource/GovernanceDerivation.java"
  - "src/main/java/com/stockscreener/pipeline/collector/GovernanceFactDeriver.java"
  - "docs/api-spec/alpaca-corporate-action.md"
---

# ADR-0113. 미국 배당을 사건 스트림에서 회계연도 사실로 옮긴다

## 결정

**Alpaca `/v1/corporate-actions`의 `cash_dividends`를 거버넌스 원문으로 담고,
`DPS_COMMON`·`CASH_DIVIDEND_TOTAL`을 그 회사의 **회계연도** 단위로 유도한다.**
표 구조는 안 바꾸고 열 주석만 고친다(V47).

**버킷의 경계가 역년이 아니라 회계연도이고, 공시일이 그 회계연도 연간 재무의 최초 공시일이다.**
이 둘이 이 결정의 전부다 — 나머지는 그 둘을 지키기 위한 가드다.

## 규칙

- **MUST: 회계연도 창은 `[period_start, period_end]`를 그대로 쓴다.** `period_end`의 차분으로
  만들면 결측 회계연도에서 창이 두 해로 벌어지고 최초 회계연도가 미정의가 된다.
  **역년으로 자르면 안 된다** — 미국 편입의 약 24%가 비12월 결산이고, 그때 `DIVIDEND_PAYOUT`의
  분자와 분모가 다른 기간이 되는데 **값은 그럴듯하다.**
- **MUST: 공시일은 그 회계연도 연간 재무의 <b>최초</b> 공시일이다.** 개정판이 있는 (종목,회계연도)
  그룹이 **24.0%**이고 그중 97%가 최신판과 90일 넘게 벌어지므로, `is_latest` 행만 읽으면
  계단이 중앙값 1년 넘게 뒤로 밀린다 — **최신 일자에서는 안 드러난다.**
- **MUST: 완결 판정을 공시일이 아니라 <b>수집일</b>로 한다.** 조회 창이 `process_date` 기준이고
  실측 처리 지연이 **최대 50일**인데 연간 재무의 공시 지연은 **최소 18일**이다 — 즉 **공시일에
  서 있는 것만으로는 그 회계연도의 배당을 다 봤다고 말할 수 없다.** `period_end + 60일 <= 수집일`
  일 때만 사실을 낸다.
- **MUST: 회계연도가 걸친 역년을 전부 관측했을 때만 값을 낸다.** 잡은 역년 단위로 부르는데
  회계연도는 두 역년에 걸칠 수 있고, 한쪽만 받으면 **부분 합**이 된다 — 부분 합은 결손이 아니라
  **「배당이 적다」**로 읽힌다.
- **MUST: 무배당을 0으로 담되 짝을 연간 재무로 세운다.** 짝 없이 「행이 안 왔으니 0」으로 읽는
  것은 **ADR-0082 §버린 대안이 반증으로 무너뜨린 안**이다.
- **MUST: 총액이 재구성값이라는 것을 안다.** `DPS합 × shares_outstanding`이고 소스가 총액을
  주지 않는다. **주식수 온전성 게이트를 통과한 종목만** 총액을 내며, 걸리면 주당배당금만 낸다 —
  소스의 주식수 후보 넷 중 **뒤 둘이 가중평균 주식수**이고 어느 것이 쓰였는지 저장하는 열이 없다.
- **MUST NOT: 국내 대조 결과를 미국 오차의 상한으로 적지 않는다.** 국내 448쌍에서
  `DPS × 유통주식수 / 실제 총액`이 중앙값 1.0000 · 10% 초과 6.0%인데, 그것은 **같은 보고서·같은
  접수번호의 기말 잔고**로 잰 값이다. 미국은 **제출 직전 표지값**(기말 대비 중앙 51일)이다.
  **미국에는 대조군이 없다.**
- **MUST: `special`·`foreign`을 둘 다 더한다.** 실측으로 `foreign`은 **발행사가 외국**이라는
  뜻이고 `rate`는 그대로 USD다(플래그가 붙은 셋의 연 합계를 종가로 나누면 0.66~1.13%로 안 붙은
  대조와 같은 범위다). `special`은 국내 `DPS_COMMON`이 사업보고서 배당표의 주당 현금배당금이라
  특별배당이 그 표에 실리면 함께 들어가므로, **빼면 오히려** 시장마다 다른 것을 재게 된다.
- **MUST: 사건 0건인 해의 행에 접수번호를 <b>숫자로</b> 준다.** 정정본 선택이 접수번호의
  **문자열 비교**라 문자로 시작하면 여덟 자리 날짜 전부보다 크게 앉고, 그러면 **무배당 행이
  실제 배당 행을 이긴다.** 그리고 값을 주는 것 자체가 국내용 스탬프 경로
  (`stampNoData`)와 미스탬프 집계를 안 건드리게 한다 — 둘 다 `receipt_no = ''`로 대상을 잡는다.
- **MUST NOT: 소스가 안 주는 표를 부르지 않는다.** `GovernanceDataSource.supportedEndpoints()`가
  그것을 답하고 잡이 종목(=시장)마다 좁힌다. 안 좁히면 나머지 일곱에 **「그 회사에 그 표가 없다」는
  거짓 행**이 쌓이는데, 그 행은 커버리지 판정의 재료다.
- **MUST NOT: 예산을 한 수로 뭉쳐 세지 않는다.** 잡의 로그가 시장별로 가른다 — 뭉치면 DART
  보수 예산에 Alpaca 호출이 섞여 어느 쪽이 얼마나 남았는지 아무도 모른다.

## 검증

```bash
./gradlew test --tests "*AlpacaGovernanceFactMapperTest*" \
               --tests "*GovernanceUnavailabilityReasonTest*"
./gradlew liveTest --tests "*AlpacaDividendProbeLiveTest*"
```

```premise 미국에서 무배당과 미수집이 갈려 있다
psql -d stock_screener -At -c "WITH win AS ( SELECT DISTINCT stock_id, fiscal_year, period_start, period_end FROM financial_statement WHERE fiscal_period = 'FY') , paid AS ( SELECT r.stock_id, w.fiscal_year, count(*) AS payments FROM governance_report_row r JOIN stock s ON s.id = r.stock_id AND s.market_code = 'US' JOIN win w ON w.stock_id = r.stock_id AND r.published_at BETWEEN w.period_start AND w.period_end WHERE r.endpoint = 'DIVIDEND' AND r.reprt_code = 'CASHDIV' AND r.row_ordinal >= 0 GROUP BY 1, 2) , silent AS ( SELECT count(*) n FROM paid p JOIN governance_fact f ON f.stock_id = p.stock_id AND f.fiscal_year = p.fiscal_year AND f.metric_key = 'DPS_COMMON' AND f.value = 0) SELECT CASE WHEN (SELECT count(*) FROM paid) = 0 THEN '잴 것이 없었다 — 회계연도 창에 든 미국 배당 결제 원문 행이 하나도 없다' WHEN (SELECT n FROM silent) = 0 THEN 'OK 결제 원문이 회계연도 창에 든 ' || (SELECT count(*) FROM paid) || '개 (종목,회계연도)에 주당배당금 0이 붙지 않았다' ELSE (SELECT n FROM silent) || '개 (종목,회계연도)가 회계연도 창 안에 결제 원문을 갖는데 ' || '주당배당금이 0이다 — 무배당과 미수집이 다시 붙었다' END"
```

**회계연도 창으로 버킷한다 — `bsns_year`로 하면 안 된다.** 그 열은 `ex_date`의 연도라
**비12월 결산에서 회계연도와 갈리고**, 붙여 세면 정상 동작이 발견으로 뜬다(실측: `BMNR`은
8월 결산이라 2025-12-08 결제가 FY2026에 드는데 `bsns_year`가 2025다. 그 FY2026은 아직
미완결이라 값을 안 내는 것이 맞다). 지금 1,296개 (종목,회계연도)를 재고 **0.06초**다.

**도달성을 대조군으로 확인했다** — `f.value = 0`을 `>= 0`으로 풀면 1,094개가 잡힌다. 조인이
행에 닿고 있고 0건인 것이 필터 때문이라는 뜻이다.

```premise 총액이 빠진 미국 (종목,회계연도)가 전부 주식수 온전성 게이트로 설명된다
psql -d stock_screener -At -c "WITH g AS ( SELECT f.stock_id, f.fiscal_year, (array_agg(f.shares_outstanding ORDER BY f.published_at) FILTER (WHERE f.shares_outstanding > 0))[1] AS shares, (array_agg(f.net_income / nullif(f.eps, 0) ORDER BY f.published_at) FILTER (WHERE f.net_income IS NOT NULL AND f.eps IS NOT NULL AND f.eps <> 0))[1] AS implied FROM financial_statement f JOIN stock s ON s.id = f.stock_id AND s.market_code = 'US' WHERE f.fiscal_period = 'FY' GROUP BY 1, 2) , gated AS ( SELECT stock_id, fiscal_year FROM g WHERE CASE WHEN implied IS NULL OR implied = 0 THEN shares WHEN abs(shares / implied - 1) > 0.20 THEN NULL ELSE shares END IS NULL) , missing AS ( SELECT d.stock_id, d.fiscal_year FROM governance_fact d JOIN stock s ON s.id = d.stock_id AND s.market_code = 'US' WHERE d.metric_key = 'DPS_COMMON' AND NOT EXISTS (SELECT 1 FROM governance_fact t WHERE t.stock_id = d.stock_id AND t.fiscal_year = d.fiscal_year AND t.metric_key = 'CASH_DIVIDEND_TOTAL')) , unexplained AS ( SELECT count(*) n FROM missing m WHERE NOT EXISTS (SELECT 1 FROM gated x WHERE x.stock_id = m.stock_id AND x.fiscal_year = m.fiscal_year)) SELECT CASE WHEN (SELECT count(*) FROM missing) = 0 THEN 'OK 총액이 빠진 미국 (종목,회계연도)가 하나도 없다' WHEN (SELECT n FROM unexplained) = 0 THEN 'OK 총액이 빠진 ' || (SELECT count(*) FROM missing) || '개 (종목,회계연도)가 전부 주식수 온전성 게이트로 설명된다' ELSE (SELECT n FROM unexplained) || '개 (종목,회계연도)가 주당배당금은 내면서 총액을 안 내는데 ' || '주식수 온전성 게이트로 설명되지 않는다 — 다른 사유가 총액을 조용히 지우고 있다' END"
```

**술어를 `GovernanceFactDeriver`에서 그대로 떠 왔다.** 베낀 것이 문제가 되는 자리이지만,
**베끼지 않으면 「총액이 왜 없는가」에 답할 수단이 없다** — 유도 문맥은 저장되지 않는다.
지금 108개가 그 게이트로 설명되고 **미설명 0**이며 **0.08초**다. 대조군으로 게이트 집합을
비우면 108이 전부 미설명으로 뒤집힌다.

**단위 시험이 규칙을 하나씩 잠근다** — 회계연도 버킷 · 최초 공시일 스탬프 · 무배당 0 ·
미완결 회계연도 거절 · 관측 안 한 역년 거절 · 분할 정규화 · 주식수 없을 때 총액 생략 ·
`special`·`foreign` 합산 · 수집일 없을 때 침묵.

## 배경

### 소스가 주는 것과 지표가 요구하는 것의 모양이 다르다

국내는 사업보고서 **한 장**에 회계연도·공시일·주당배당금·배당총액이 함께 온다. Alpaca는
**결제 하나하나**만 준다(`symbol`·`ex_date`·`rate`·`special`·`foreign`). 나머지 셋은
재무제표와 기업행위 원장에 흩어져 있고, 매퍼가 DB를 읽으면 벤더 경계가 무너지므로(ADR-0081)
읽는 것은 `GovernanceFactDeriver`가 하고 값만 넘긴다(`GovernanceDerivation`).

### 잰 것 — 착수 판단의 근거

`AlpacaDividendProbeLiveTest`가 `US_TOP1000`에서 티커 20에 하나씩 뽑은 **50종목 × 2018년 이후**를
쟀다(889건 · 1페이지). 파일럿 11종목으로 재면 대형 배당주에 치우쳐 지연 분포가 낙관적으로 나온다.

| | 실측 |
|---|---|
| `process_date − ex_date` | 최소 0 · p50 15 · p90 28 · p99 36 · **최대 50일** · 음수 0 · 365일 초과 **0** |
| `foreign` / `special` | 60건(6.7%) / 2건 |
| 배당이 온 종목 | 28 / 50 |
| 필드 결손 | **0** |

**분사에서 관측된 3년 지연(T→WBD)은 배당에는 없다.** 그럼에도 완결 판정을 공시일이 아니라
수집일로 한 이유는 **연간 재무의 최소 공시 지연(18일)이 배당의 최대 처리 지연(50일)을
안 덮기 때문**이다.

### 총액을 어떻게 지을지는 국내 대조가 정했다

소스가 배당 총액을 안 주므로 `DPS합 × 주식수`로 짓는다. 국내에서 같은 곱을 실제 총액과 견줬다
(448쌍):

| 분모로 쓴 주식수 | 중앙값 배수 | 10% 초과 오차 |
|---|---|---|
| 발행주식수 | 1.0092 | **15.0%** |
| **유통주식수**(발행 − 자기주식) | **1.0000** | **6.0%** |

EDGAR의 `shares_outstanding`이 유통주식수 계열이므로 뒤쪽을 쓴다. **다만 그 6.0%가 미국의
오차는 아니다**(§규칙) — 그 자리를 온전성 게이트가 대신한다.

## 버린 대안

- **역년 버킷 + 이듬해 1월 1일 공시일** — 처음 고른 안이다. 날짜를 지어내지 않고 부분 합도 안
  생기지만, **비12월 결산 24%에서 분자와 분모의 기간이 어긋난다.**
- **`is_latest` 행의 공시일을 쓴다** — 개정판이 있는 24%에서 계단이 중앙값 1년 넘게 밀린다.
- **총액을 안 만들고 `DIVIDEND_YIELD`만 연다** — 배당성향이 미국에서 0%로 남는다.
  ADR-0112가 그 둘을 함께 지목했다.
- **`DividendPayoutCalculator`에 분자 폴백을 넣는다**(총액이 없으면 `DPS × 주식수`) — 매퍼가
  총액까지 내면 **계산기·앵커·`GovernanceMath` 사용처 13곳을 하나도 안 건드린다.**
  어댑터 javadoc의 처방도 그것이었다.
- **`AlpacaCorporateActionAdapter`가 배당까지 담는다** — 같은 엔드포인트를 한 번만 부르므로
  종목당 1콜이 싸다. 그러나 반환 타입도 저장 표도 다르고, ADR-0006이 **포트마다 어댑터를
  나눈다**를 MUST로 둔다. 대가는 종목당 겹치는 호출 하나다.
- **국내 배당을 다시 안 받는다** — 이 잡에 `--universe`가 없어 미국만 좁히려면 티커 1,000개를
  나열해야 한다. 국내 700콜은 멱등이고 DART 예산의 7%다.

## 결과

**쉬워지는 것** — 미국 종목이 배당 지표를 갖는다(2026-09-01 적재 실측):

| | 종목 | 사실 | 그중 0(무배당) |
|---|---|---|---|
| `DPS_COMMON` | **991 / 1,000** | 1,712 | **618** |
| `CASH_DIVIDEND_TOTAL` | **956 / 1,000** | 1,604 | 563 |

**0이 618개라는 것이 이 결정의 요점이다** — 무배당이 결손이 아니라 **값**으로 담겼다.
총액이 35종목 적은 것은 **주식수 온전성 게이트가 막은 것**이고, 그 종목들은 주당배당금만 갖는다.

**지표 층에서는 화면 기준일(2026-08-31)에 이렇게 난다:**

| | `DIVIDEND_YIELD` | `DIVIDEND_PAYOUT` |
|---|---|---|
| `US_TOP1000` | **990 / 1,000** | **812 / 1,000** |
| `KOSPI_TOP200` | 199 / 200 | 173 / 200 |
| `KOSDAQ_TOP150` | 148 / 150 | 106 / 150 |

**미국이 0%에서 국내와 같은 자리로 왔다.** ADR-0112가 이 축을 「갈래 A의 요점」으로 지목한 이유가
그 0%였다.

회계연도별로는 FY2025가 991종목 · FY2024가 721종목이다. 차이는 **비12월 결산 종목의 FY2024가
역년 2023에 걸치는데 그 해를 안 받았기 때문**이고, 부분 합을 안 내는 규칙이 그렇게 동작한 것이다.

**적재 실측**: 1,350종목 2,700콜에 **21분 33초**(분당 125콜) · 경고 0 · 실패 0.
원문은 미국 5,709행(결제 4,955 · 사건 0건 754)이다.

**어려워지는 것**

- **국내를 함께 다시 받는다.** 이 잡에 `--universe`가 없어 미국만 좁히려면 티커 1,000개를
  나열해야 한다. 국내 700콜은 멱등이지만 **DART가 그 사이에 낸 새 보고서가 함께 들어온다** —
  실측으로 6종목(태광·프로텍·HDC·티에프이·네패스·SK아이이테크놀로지)이 배당 사실을 새로
  얻어 국내 사실이 24,794 → 24,865행이 됐다. **그 6종목이 가진 사실이 정확히 71개**라
  나머지 342종목은 한 행도 안 바뀌었다.
- **총액은 재구성값이고 미국에는 대조군이 없다.** 온전성 게이트가 그 자리를 대신하지만,
  그것이 재는 것은 「주식수가 다른 것을 세고 있는가」이지 「총액이 맞는가」가 아니다.
  **정확한 원자료는 EDGAR 현금흐름표의 배당지급액이고 그것은 새 재무 컬럼이라 별도 phase다.**
- **매퍼가 둘이 되면서 `GovernanceFactDeriver`가 시장으로 색인한다.** 시장 하나에 매퍼가 둘이면
  기동에 실패하게 해 뒀다 — 조용히 하나가 이기면 그 시장의 사실이 통째로 다른 규칙으로 만들어진다.
- **거버넌스 결손 사유의 축이 (지표, 시장)으로 내려갔다.** 집합 하나로 열셋을 판정하던 구조가
  깨졌고, 앞으로 새 시장에 거버넌스 소스가 **부분적으로** 붙을 때마다 이 표를 고쳐야 한다.

**되돌리려면** — `AlpacaDividendAdapter`·`AlpacaGovernanceFactMapper`를 지우고
`GovernanceFactMapper`의 문맥 인자와 `GovernanceDataSource.supportedEndpoints()`를 되돌린다.
적재된 원문은 지우지 않는다(ADR-0011). **`IndicatorUnavailabilityConfig`의 (지표, 시장) 표는
되돌리지 않는다** — 그 구조가 옳다는 것은 배당과 무관하게 참이다.
