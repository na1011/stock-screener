---
id: ADR-0089
status: 채택됨
date: 2026-08-29
applies_to:
  - "src/main/java/com/stockscreener/pipeline/datasource/us/SecEdgarFacts.java"
  - "src/main/java/com/stockscreener/pipeline/datasource/us/SecEdgarColumn.java"
  - "src/main/java/com/stockscreener/pipeline/datasource/us/SecEdgarFinancialAdapter.java"
  - "src/main/java/com/stockscreener/pipeline/datasource/us/SecTickerCikCache.java"
  - "src/main/java/com/stockscreener/pipeline/datasource/us/SecEdgarResponse.java"
  - "src/main/java/com/stockscreener/pipeline/batch/InitialLoadJob.java"
  - "src/main/java/com/stockscreener/pipeline/datasource/http/DataSourceProperties.java"
  - "docs/api-spec/sec-edgar-financial.md"
---

# ADR-0089. 미국 재무를 EDGAR로 받되 기간을 팩트에서 재구성하고, 파일럿만 적재한다

## 결정

미국 재무를 SEC EDGAR `companyfacts`로 받는다. 결정은 넷이다.

1. **포트 하나만 구현한다.** `FinancialDataSource`만 만들고 `FullStatementDataSource`는
   만들지 않는다 — 한 콜에 재무상태표·손익·현금흐름이 함께 온다.
2. **기간·회계연도·정정을 우리가 재구성한다.** 소스의 `fy`/`fp`를 라벨로 쓰지 않는다.
3. **시세를 건너뛰는 적재 갈래를 연다**(`--skip-price`). 재무만 받아야 하는 상태가 실재한다.
4. **파일럿 10종목만 적재한다.** 전량은 따로 정한다.

> **사용자가 고른 문장(2026-08-29):** *"파일럿 11종목만"*.
> **그에 대한 내 해석 — 갈라 적는다:** 11 중 CIK에 붙는 것이 10이라 **적재된 것은 10종목**이다.
> 사용자가 닫은 것은 *범위*이고, *무엇이 안 붙는가*는 탐침이 답했다.

## 규칙

- **MUST: 회계연도를 12개월 팩트가 만드는 창에서, 분기 번호를 그 창 안의 위치에서 정한다.**
  소스의 `fy`/`fp`는 **그 사실을 실은 보고서**를 가리킨다 — 실측에서 라벨 70개가 전부 종료일을
  둘 이상 갖고 최대 12개였고, 1월 결산 회사는 연속한 두 회계연도에 같은 `fy`가 붙었으며,
  AT&T는 7~9월 기간에 `fp=Q2`가 붙었다.
  - **MUST: 같은 라벨에 기간이 둘이면 `MALFORMED`로 끝낸다.** 저장 키에 기간이 없어
    (`uq_fs_latest`) 겹치면 **제약이 걸리는 것이 아니라** 앞 기간의 최신 표시가 조용히
    내려간다 — `FinancialWriter`가 개정 갈래에서 `UPDATE`를 `INSERT`보다 먼저 돌리기 때문이다.
    **어댑터의 자기 단언이 유일한 방어선이다.**
- **MUST: 값이 달라지지 않은 재보고를 정정으로 내지 않는다.** 소스는 한 기간을 이후 보고서마다
  다시 싣고 공시일이 매번 늦다 — 실측에서 112개 중 107개가 가짜 정정이 될 자리였다. 그 판정은
  그 종목의 지표를 보존 창 전체에서 지운다. **저장 계층은 공시일만 보고 값을 비교하지 않으므로
  어댑터가 접지 않으면 아무도 안 접는다.**
  - **MUST: 컬럼마다 태그를 기간 단위로 하나 고정한다.** 보고서마다 다른 후보 태그로 내는 일이
    실재해(현금이 `…AtCarryingValue`와 `…RestrictedCash…`로 갈린다) **값이 아니라 태그가
    바뀐 것**이 정정이 된다.
  - **MUST: 한 기간의 판을 공시일 오름차순으로 낸다.** 뒤집으면 더 이른 판이 버려져 원본이 사라진다.
  - **MUST: 폴백 체인을 고치는 것 자체가 정정을 만든다는 것을 알고 고친다.** 예전엔 null이던
    뒤 보고서의 값이 값으로 바뀌면 접기가 「달라졌다」로 읽고, **소스는 그대로인데 우리 코드
    변경으로** 무효화가 발화한다.
- **MUST: Q4를 유도한다.** TTM이 분기 넷을 `period_end` 역순으로 뽑으므로 Q4가 없으면 그 넷이
  **다섯 분기에 걸친 구멍 난 합**이 된다. 손익만 차감하고 잔액은 기말 값을 그대로 쓴다.
  **MUST: 라벨이 아니라 기간 포함관계로 분기를 고른다** — 1월 결산 회사에서 시작이 끝보다
  뒤인 행이 나와 그 종목의 적재가 통째로 죽는다.
- **MUST: 통화를 3자 ISO 단위 키에서만 뽑는다.** `units` 키에 `pure`·`shares`·`segment`가
  섞여 있고 `currency_code`가 `character(3)`다 — 그대로 담으면 **그 종목의 적재가 통째로
  롤백된다.** 환산하지 않는다.
- **MUST: 자본총계를 비지배지분 포함 태그에서 먼저 본다.** 국내가 `자본총계`를 쓰므로 같은
  것을 골라야 시장 간 계산 방식이 안 갈린다(spec §7). 뒤집으면 `자산 = 부채 + 자본`이 깨지고
  차이가 비지배지분이라 값이 그럴듯하다.
- **MUST NOT: 403을 `AUTH`로 접지 않는다.** 스로틀 시의 코드를 **재지 않았다** — 재려면
  차단을 유발해야 하고 그러면 남은 측정이 전부 막힌다. 모르는 채로 고를 때 대가가 대칭이
  아니다: `AUTH`는 `fatal()`이라 배치 전체가 죽는다. **신원 표시 부재는 호출 전 검사가 덮는다.**
- **MUST NOT: 오류 본문을 JSON으로 받지 않는다.** 404 본문이 XML이라 상태코드를 보기도 전에
  파싱이 터져 **404가 `TRANSIENT`**가 된다.
- **MUST NOT: 우선주를 `.PR<X>` → `-P<X>`로 붙이지 않는다.** 365종목이 더 붙지만 그 CIK가
  보통주와 같아 **발행사 재무가 우선주 행에 실린다.**
- **MUST NOT: SIC를 `stock.industry_code`에 넣지 않는다**(ADR-0086 승계). 갈 자리는
  `stock.sic_code`다(ADR-0092).
- **MUST NOT: `--skip-price`를 스케줄러·`AdminJobLauncher`에 걸지 않는다.**

## 검증

```bash
./gradlew test --tests "*SecEdgar*" --tests "*InitialLoad*" --tests "*BootstrapArgumentGuard*"
```
라벨을 길이에서 잡는지 · 값이 같은 재보고를 접는지 · 태그 교체를 정정으로 안 읽는지 ·
3자 아닌 단위 키가 통화로 안 새는지 · Q4 유도 · 403이 `AUTH`가 아닌지가 여기서 걸린다.

```bash
./gradlew liveTest --tests "*SecEdgarProbe*" --tests "*SecEdgarLive*"
```
소스가 아직 그대로인지를 지킨다. 실측값의 정본은 `docs/api-spec/sec-edgar-financial.md`다.

미국 재무가 어디까지 들어와 있는가 — **파일럿을 전량으로 오독하지 않게 사람이 확인한다:**

```bash
psql -d stock_screener -At -c "
SELECT '미국 재무: ' || (SELECT count(DISTINCT f.stock_id) FROM financial_statement f
                          JOIN stock s ON s.id = f.stock_id WHERE s.market_code = 'US')
       || '종목 / US_LISTED 구성 '
       || (SELECT count(*) FROM universe_membership
            WHERE universe_code = 'US_LISTED' AND effective_to IS NULL) || '종목'"
```

```premise 미국 재무의 필수 컬럼이 통째로 비어 있지 않다
psql -d stock_screener -At -c "
WITH us AS (SELECT f.* FROM financial_statement f JOIN stock s ON s.id = f.stock_id
             WHERE s.market_code = 'US' AND f.is_latest)
SELECT CASE
  WHEN (SELECT count(*) FROM us) = 0 THEN '잴 것이 없었다 — 미국 재무가 한 행도 없다'
  WHEN x.empty = '' THEN 'OK 미국 재무 ' || (SELECT count(*) FROM us) || '행 · 필수 다섯이 전부 채워지는 자리가 있다'
  ELSE '미국 재무에서 통째로 비어 있는 컬럼: ' || x.empty || ' — 폴백 체인이 모집단에서 샜다'
END
FROM (SELECT array_to_string(array_remove(ARRAY[
   CASE WHEN NOT EXISTS (SELECT 1 FROM us WHERE revenue IS NOT NULL) THEN 'revenue' END,
   CASE WHEN NOT EXISTS (SELECT 1 FROM us WHERE net_income IS NOT NULL) THEN 'net_income' END,
   CASE WHEN NOT EXISTS (SELECT 1 FROM us WHERE total_assets IS NOT NULL) THEN 'total_assets' END,
   CASE WHEN NOT EXISTS (SELECT 1 FROM us WHERE total_equity IS NOT NULL) THEN 'total_equity' END,
   CASE WHEN NOT EXISTS (SELECT 1 FROM us WHERE shares_outstanding IS NOT NULL) THEN 'shares_outstanding' END
], NULL), ', ') AS empty) x"
```

**이 전제가 묻는 것은 결측률이 아니라 매핑이 끊겼는가다.** 폴백 체인이 표본 19종목에서
나왔으므로 모집단에서 새는 것은 **컬럼 하나가 통째로 비는 모양**으로 먼저 드러난다.
컬럼별 NULL 비율을 국내와 나란히 재는 것은 **전제로 만들지 않았다** — 국내 쪽이 일부러
안 담은 컬럼이 셋이라(분기 현금흐름·capex·주식수) 그대로 대조하면 의도한 동작에 운다.

## 배경

미국 축은 유니버스(ADR-0086)·시세(ADR-0088)까지 열려 있었고 재무가 다음이었다.

**착수 전 탐침이 잰 것 중 계획을 실제로 바꾼 것이 넷이다.**

**첫째, `fy`/`fp`.** 개발가이드는 응답 필드를 적었지만 그 둘이 *사실의 기간이 아니라 그것을
실은 보고서*를 가리킨다는 것은 적지 않았다. 라벨 70개가 전부 종료일을 둘 이상 갖는다.

**둘째, 한 보고서가 자기 종료일에 기간 길이를 둘 이상 싣는다**(72개 중 48개). 회계연도만
앵커에서 가져오고 기간 구분은 길이에서 가져오게 만든 근거다.

**셋째, 가짜 정정의 규모.** 한 기간을 보고서 112개가 다시 실었고 값이 실제로 달라진 것은
5개였다. 접기가 없으면 **107번의 무효화**가 나고 그때마다 그 종목의 지표가 보존 창째 지워진다.

**넷째, `units` 키.** 44종이 3자를 넘는다. `currency_code`가 `character(3)`라 그대로 담으면
그 종목의 적재가 통째로 롤백된다.

**구현 중에 셋이 더 드러났고 전부 실측이 잡았다** — 표지 주식수가 앵커를 밀어 AAPL이 0건이
된 것 · 그 주식수로 「보고했다」를 판정해 FY 하나에 판이 열이 된 것 · 1월 결산 회사에서
시작이 끝보다 뒤인 행이 나온 것. 셋 다 **에러 없이 「데이터가 없다」거나 「값이 그럴듯한」**
모양이었다.

**전량이 29GB다.** 표본 19종목이 65.2MB였고 한 종목이 78KB~7.9MB로 100배 차이 난다.
종목당 1콜로 전량을 받는 것은 벌크 파일과 견줘 판단할 문제이지 자명한 선택이 아니다.

## 버린 대안

- **`FullStatementDataSource`도 구현한다** — DART가 포트를 둘로 가른 이유는 주요계정
  엔드포인트가 현금흐름표를 아예 안 주기 때문인데, EDGAR는 한 콜에 셋이 함께 온다.
  포트를 둘로 두면 같은 응답을 두 번 받는다.
- **`--skip-price` 없이 기존 경로로 적재한다** — 넷을 다 재봤고 전부 안 됐다.
  `--tickers`만 주면 유니버스 경로가 봉 있는 종목을 걸러내 **대상 0건으로 성공**하고,
  `--reload-existing=true`는 `priceFrom = asOf−2년`이라 **최근 봉이 들어와** ADR-0088이
  파일럿을 2024년으로 옮겨 피한 것이 되살아나며, `--price-years=0`은 생성자가 0을 허용해
  `priceFrom = asOf`가 되어 하루치를 실제로 부르고, `financial-refill`은 활성 유니버스를
  조인해 비활성 종목을 하나도 내지 않는다.
- **`resolveUniverseTargets`의 재개 기준을 「재무가 없는 종목」으로 바꾼다** —
  `InitialLoadResumeTest`가 *"실패 없이 재무가 0행인 종목은 대상이 아니다 — 재개와 데이터
  감사를 섞지 않는다"*로 이미 기각해 둔 형태다. 바꾸면 EDGAR가 정당하게 0건을 주는 종목이
  **매 실행 다시 잡혀 재개가 수렴하지 않는다.**
- **`InitialLoadJob`과 별개인 미국 전용 잡** — ADR-0088이 §버린 대안에서 기각했다.
- **소스의 `fp`로 분기 번호를 정한다** — 처음에 그렇게 짰고 라벨 충돌이 세 종목에서 났다.
- **애노테이션을 넓혀 `user-agent` 프로퍼티를 따로 둔다** — `@ConditionalOnApiKey`는 어댑터
  열둘이 쓰는 공용 기제다. 호출자 하나 때문에 고치는 대신 그 슬롯의 뜻을 문서로 넓혔다.

## 결과

**쉬워지는 것**: 미국 재무를 종목당 1콜로 받는다. 포트가 하나라 `FinancialCollector`·
`FinancialWriter`·`InitialLoadJob`이 그대로 미국을 처리하고 **마이그레이션이 없다** —
스키마가 이미 미국을 전제하고 있었다.

**어려워지는 것**

- **기간 해석이 우리 코드에 있다.** 소스가 바뀌면 값이 아니라 **라벨**이 흔들리고, 그 흔들림은
  제약이 아니라 조용한 덮어쓰기로 나타난다.
- **폴백 체인을 고치는 것이 곧 정정이다.** 태그를 하나 더하는 변경이 그 종목의 지표를
  보존 창째 지울 수 있다.
- **`--skip-price`가 「초기 적재」의 갈래를 하나 더 만들었다.** 자동 실행에 걸리면 시세가
  조용히 빠진 채 성공한다.
- **파일럿 10종목이다.** 전량은 응답 크기(29GB)를 보고 따로 정한다.
- **`US_LISTED`는 여전히 못 켠다.** 기업행위·거버넌스·업종이 아직 비어 있다.

**되돌리려면**: `datasource/us/SecEdgar*`를 지우고 `application.yaml`의 `sec-edgar:` 블록과
`DataSourceProperties`의 필드를 되돌린다. 적재된 재무는 `financial_statement`에서 시장으로
지운다 — 그것을 참조하는 지표가 없는 동안에만 깨끗하다.
