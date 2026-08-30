---
id: ADR-0056
status: 채택됨
date: 2026-08-13
applies_to:
  - "src/main/java/com/stockscreener/pipeline/datasource/**"
  - "src/main/java/com/stockscreener/pipeline/collector/FinancialWriter.java"
  - "src/main/java/com/stockscreener/pipeline/collector/FullStatementCollector.java"
  - "src/main/java/com/stockscreener/pipeline/batch/FullStatementRefillJob.java"
  - "src/main/java/com/stockscreener/indicator/calculator/OperatingCashFlow5ySumCalculator.java"
  - "src/main/java/com/stockscreener/screening/store/ScreenDefinitionValidator.java"
---

# ADR-0056. 전체 재무제표를 둘째 재무 포트로 붙이고 H-05를 필수 게이트로 건다

## 결정

넷을 함께 정했다.

1. **전체 재무제표(`fnlttSinglAcntAll`)를 `FullStatementDataSource`라는 둘째 재무 포트로 붙인다.**
   기존 `FinancialDataSource`(주요계정)를 교체하지 않는다.
2. **그 포트의 쓰기는 `FinancialWriter.WriteMode.FILL_WITHOUT_REVISION`으로만 간다.** 정정공시를
   만나면 개정본을 쌓지 않고 채우지 않은 채 센다.
3. **조건 개수 상한을 20 → 25로 올린다.**
4. **H-05(5년 누적 영업현금흐름 > 0)를 `SWING_MIDTERM_KR`의 필수 조건으로 건다.**

> **2026-08-24 — 이 결정에는 대상이 없다.** `SWING_MIDTERM_KR`은 2026-08-14에 폐기됐고
> (ADR-0061) 후계자 `SWING_TREND_KR`도 2026-08-20에 폐기됐다(ADR-0074). 지금 `OCF_SUM_5Y`를
> 쓰는 곳은 `LOWVOL_QUALITY_KR`의 **배점 1점** 하나뿐이고(`PresetCatalog.java`의
> `lowVolQualityKr()`), `is_required = true`인 유일한 저장 행은 폐기된 화면이다.
> **그것은 아래 §버린 대안이 기각해 둔 형태다.** 어느 쪽이 옳은지는 여기서 정하지 않는다 —
> 프리셋의 출처가 원본 명세에서 문헌으로 갈려(ADR-0061) 판정 근거 자체가 바뀌었다.

## 규칙

- **MUST: 새 포트의 쓰기 경로에 `IndicatorInvalidationPort`를 부르는 가지를 만들지 않는다.**
  그 호출은 `trade_date >= periodStart`인 **그 종목의 전 지표**를 지우고, 사업보고서면 기준일이
  회계연도 1월 1일이라 보존 창 전량이다. 발화 조건이 "소스 공시일이 저장값보다 하루라도 늦다"
  하나뿐이라 표본으로는 안전을 말할 수 없다 — 판정할 (종목, 연도) 쌍이 2,100개다.
- **MUST: 채우지 못한 (종목, 기간)을 수로 남긴다.** `batch_execution.note`의 `정정보류`가 그것이다.
  "채울 것이 없었다"와 "정정공시라 못 채웠다"가 구분되지 않으면 결손이 조용히 남는다.
- **MUST: 두 어댑터가 같은 보고서에서 같은 `publishedAt`을 유도한다**(`DartReportPeriods`).
  갈리면 나중에 온 쪽이 정정공시로 읽힌다. 테이블에 `rcept_no` 컬럼이 없어 판별 근거가
  `published_at` 하나뿐이기 때문이다.
- **MUST: 새 행의 `period_start`·`period_end`를 기존 규약(합성 `01-01 ~ 12-31`)으로 쓴다.**
  전체 재무제표는 `thstrm_dt`로 실제 기간을 주지만 쓰지 않는다 — 새 행만 다른 규약을 가지면
  `ORDER BY period_end` 정렬과 연차 연속성 판정이 그 종목에서만 어긋난다.
- **MUST NOT: 분기 보고서의 영업활동현금흐름을 저장하지 않는다.** 3개월인지 누적인지
  실측되지 않았다(`dart-financial.md`의 「전체 재무제표」 절). 개발가이드가 [3개월]이라 밝힌 것은
  **(포괄)손익계산서뿐**이다.
- **MUST NOT: 분기 보고서를 전 종목에 요청하지 않는다.** 잡이 티커 없는 `--quarters`를 거절한다.
  분기가 필요한 것은 이익잉여금 결손 종목뿐이고 전 종목이면 호출이 4배다.
- **MUST NOT: `account_id`를 부분일치로 맞추지 않는다.** `ifrs-full_Equity`가
  `ifrs-full_EquityAttributableToOwnersOfParent`에 걸려 지배주주지분을 자본총계로 읽는다.
- **MUST NOT: `account_id` 매칭이 낸 값으로 이미 차 있는 컬럼을 덮지 않는다.** §P10이 "덮을지는
  이 phase에서 정하고 기록하라"고 남긴 자리이며, **덮지 않는 쪽으로 정했다.** 계정명 매칭과
  다른 값이 나왔을 때 어느 쪽이 옳은지는 이 phase가 답할 수 없고, 덮으면 350종목의 기존 값이
  되돌릴 수단 없이 한 번에 바뀐다. 어긋남은 `batch_execution.note`의 `불일치=`가 답한다 —
  **로그에만 두면 세션이 끝나는 순간 사라진다**(전량 적재 때 실제로 그래서 그 수를 잃었다).
- **MUST: 영업활동현금흐름을 계정명으로 찾을 때 `창출`을 뺀다.** `영업활동에서 창출된 현금`은
  이자·법인세 차감 전 소계이고, 삼성전자 2022가 그 둘을 나란히 갖고 있었다
  (창출 1,882,897,371,147 / 총계 1,575,355,826,731).
- **MUST: 상한을 바꾸면 네 곳이 함께 간다** — `ScreenDefinitionValidator` ·
  `wire-contract.json`의 `constants.maxConditions` · `frontend/src/api/limits.ts` ·
  `PresetCatalogTest`의 여유 단언.

## 검증

```bash
# 새 쓰기 경로가 무효화를 부르지 않는다 — 대조군(FILL_MISSING은 부른다)과 함께 단언한다.
./gradlew test --tests "*FinancialWriterTest*"
```

```bash
# 두 재무 포트가 같은 시장에서 공존한다. 어댑터가 @ConditionalOnApiKey라
# 키 없는 CI에서는 둘 다 등록되지 않아 스텁으로만 잴 수 있다.
./gradlew test --tests "*DataSourceRegistryTest*"
```

```bash
# 상한 넷의 동기화. 픽스처가 허브다(ADR-0020).
./gradlew test --tests "*WireContractTest*" --tests "*PresetCatalogTest*" && ./gradlew frontendTest
```

```premise H-05 산출 가능 종목
psql -d stock_screener -At -c "WITH r AS (SELECT stock_id, operating_cash_flow, period_end,
  row_number() OVER (PARTITION BY stock_id ORDER BY period_end DESC) rn
  FROM financial_statement f JOIN stock s ON s.id=f.stock_id
  WHERE f.is_latest AND f.fiscal_period='FY' AND s.is_active),
a AS (SELECT stock_id, count(*) n, count(operating_cash_flow) ocf FROM r WHERE rn<=5 GROUP BY 1)
SELECT CASE
  WHEN (SELECT count(*) FROM a) = 0 THEN '잴 것이 없었다 — 연간 재무가 하나도 없다'
  WHEN count(*) FILTER (WHERE n=5 AND ocf=5) >= 250 THEN 'OK ' || count(*) FILTER (WHERE n=5 AND ocf=5) || '종목'
  ELSE '5년치를 가진 종목이 ' || count(*) FILTER (WHERE n=5 AND ocf=5) || '으로 줄었다(착수 시 283) — LOWVOL_QUALITY_KR의 OCF_SUM_5Y 배점이 그만큼 판정불가로 떨어진다'
  END FROM a"
```

**모집단은 활성 유니버스 편입 종목이다.** `universe`를 함께 조인하는 것이 그 강제이며,
빼면 **비활성 유니버스의 편입 행이 들어온다** — `US_LISTED`가 비활성인데 편입 8,504건을
들고 있어, 미국 재무를 적재하자 이 전제가 1,163종목으로 울었다(2026-08-30). 그 결손은
H-05가 DART 전체재무제표로 채우는 것이라 **EDGAR 종목에는 애초에 걸리지 않는다.**
P20 축②가 *「편입 행은 유니버스의 활성 여부와 무관하게 존재한다」*로 경고한 자리다.

```premise 정정공시라 채우지 못한 연간 결손
psql -d stock_screener -At -c "SELECT CASE
  WHEN count(*) = 0 THEN '잴 것이 없었다 — 편입 활성 종목이 없다'
  WHEN count(*) FILTER (WHERE miss > 0) = 0 THEN 'OK 결손 0종목'
  ELSE 'OCF 결손 종목이 ' || count(*) FILTER (WHERE miss > 0) || '이다 — 정정공시로 단정하지 마라. 먼저 그 행의 revision_no를 본다: 0이면 리밸런싱 재편입이라 티커를 준 full-statement-refill 한 번이 닫고(2026-08-25에 넷이 그랬다), 1 이상이면 그때가 정정이라 batch-load skill의 128콜 스윕이다'
  END FROM (SELECT s.id, count(*) FILTER (WHERE f.operating_cash_flow IS NULL) miss
    FROM stock s JOIN universe_membership m ON m.stock_id=s.id AND m.effective_to IS NULL
    JOIN universe u ON u.code = m.universe_code AND u.is_active
    JOIN financial_statement f ON f.stock_id=s.id AND f.is_latest AND f.fiscal_period='FY'
      AND f.fiscal_year BETWEEN 2021 AND 2025
    WHERE s.is_active GROUP BY 1) t"
```

## 배경

주요계정 API는 `sj_div`가 `BS`·`IS` 둘뿐이라 **현금흐름표를 아예 주지 않는다.**
`financial_statement.operating_cash_flow`가 10,957행 전량 NULL이었던 것이 그 결과이고,
원본 명세의 H-05가 여기서 막혀 있었다. 같은 전환이 §8의 이익잉여금 결손도 연다 —
오리온·오리온홀딩스·에스티큐브는 **행 자체가 없어** 계정명 후보군으로는 열리지 않았다.

**교체가 아니라 나란히 둔 이유**는 이미 차 있는 FY 2,162행 전체가 새 파서 결과로 갈리기
때문이다. 응답이 30배 크고(229~349행 대 7~15행) 계정 매핑도 회사마다 갈려, 일일 갱신이
필요하지도 않은 비용을 매번 치르게 된다.

**적재 실측**(350종목, 2,714콜 = 보수 예산 10,000의 27%):

| 무엇 | 값 |
|---|---|
| H-05 산출 가능 | **283종목** / 활성 356 |
| 정정보류 | 5건 (고려아연 4개 연도 · SK가스 1개 연도) |
| `indicator_value` | **3,634,662행 불변** — 적재 전후로 한 행도 지워지지 않았다 |
| 백필 후 | `OCF_SUM_5Y` 50,905행 / 283종목 / 180일자, `ALTMAN_Z` **+3종목**(§8) |
| 백필 멱등 | 단일 거래일 2회 실행의 두 번째가 **0행** |

**금융업 재무는 소스에 FY2023부터만 있다.** KB금융·신한지주·삼성생명의 FY2021·2022가 두
엔드포인트 모두에서 `status 013`이고, 같은 해에 비금융(나노신소재·아이티센글로벌·태성)은
정상 응답이다. 즉 A그룹 28종목의 결손은 **상장 이력이 아니라 소스 정책**이 만든 것이며
적재로 메울 수 없다. 다만 그 25종목은 `ALTMAN_Z`가 금융업 제외라 **이미 다른 필수 게이트에서
떨어진다**(P8) — H-05가 새로 자르는 자리가 아니다.

**H-05 필수의 실제 대가는 7종목이다.** 다른 필수 게이트(U-05·Altman Z)를 통과하면서 값이
없는 것이 그 전부다.

| 종목 | 사유 |
|---|---|
| 고려아연 | 정정공시라 보류 — **적재로 메울 수 있는 종류**다 |
| 태성 · 보로노이 · HPSP | FY2022부터 (2022년 상장) |
| SK스퀘어 · 기가비스 · 이수스페셜티케미컬 | FY2023부터 (2023년 상장) |

**조건 상한 20의 근거는 이미 죽어 있었다.** 주석이 *"실제 전략이 쓰는 조건 수(최대 6~8)보다
넉넉하다"*고 적었는데 스윙 프리셋이 20을 다 쓴다. 25는 그 추정을 갱신한 것이 아니라
**측정 위에 둔 값**이다 — 21조건 프리셋이 350종목에서 **475 ms**(재기동 직후 첫 실행,
JIT 미가열)이고, `SCREENING_RUN` 107건의 중앙값은 49 ms다. 25조건이 지연 벽에서 멀다.

이 phase 뒤 `SWING_MIDTERM_KR`이 처음으로 종목을 낸다 — **오리온 1종목**이다. 0건의 원인이
조건도 시장 국면도 아니라 **데이터**(§8의 이익잉여금 결손)였고, Phase 7 판정 기준이 요구한
세 갈래 가르기가 그대로 적용된 결과다.

## 버린 대안

- **주요계정을 전체 재무제표로 전면 교체** — 이미 차 있는 2,162 FY행이 새 파서 결과로 갈리고,
  `published_at`이 갈리는 순간 정정 경로가 350종목에 터진다. §P10의 *"이미 차 있는 것을 흔들지
  않는가"*에 정면으로 걸린다.
- **한 어댑터가 두 소스를 위임 합성** — `FinancialDataSource.fetchReports(SecurityRef, int)`에
  보고서 코드를 넘길 자리가 없어 연간 전용 경로를 표현할 수 없다.
- **비교표시 컬럼(`bfefrmtrm_amount`)으로 콜 줄이기** — 사업보고서 1콜이 3개 회계연도를 주므로
  2콜로 5년을 덮을 수 있다(2,100 → 700). **쓰지 않는다.** FY2025 보고서의 전전기(FY2023) 값은
  FY2023 보고서보다 `published_at`이 늦어 정정공시로 읽히고, 위 결정 2가 막으려는 바로 그
  가지로 떨어진다. 재작성 값과 원보고 값이 한 테이블에 섞이는데 그 사실을 담을 컬럼도 없다.
  예산에 73% 여유가 있어 이 위험을 살 이유가 없다.
- **H-05를 가중 조건으로** — 원본 명세 §4가 Layer 1 게이트로 두므로 Layer 2 이동은 정본
  이탈이다. 가중이면 판정불가 종목이 0점을 받고 **결과에 남아** 이력이 짧은 종목이 살아남는다.

  > **2026-08-24 — 이 기각이 뒤집힌 채로 실행되고 있다.** `LOWVOL_QUALITY_KR`이 `OCF_SUM_5Y`를
  > 배점 1점으로 들고 있다. 첫째 근거(*"원본 명세 §4가 Layer 1 게이트로 둔다"*)는 그 명세가
  > 프리셋 정본에서 내려오면서(ADR-0061) 더는 서지 않는다. **둘째 근거(판정불가가 0점을 받아
  > 결과에 남는다)는 이 배점 자리에서 재보지 않았다.**
- **자본변동표 이익잉여금을 추측으로 고르기** — 기초·증감·기말이 흩어져 있어 기초를 고르면 한
  해 늦은 값이 그럴듯하게 저장된다. 대신 대조군을 뒀다: 둘 다 가진 삼성전자에서 재무상태표
  값과 이 규칙이 고른 값이 402,135,600,000,000으로 일치했고, 오리온 2024의 값이 2025의
  기초자본과 같았다.
- **`account_id`를 부분일치로 찾기**(탐침이 쓰던 방식) — `ifrs-full_Equity`가
  `EquityAttributableToOwnersOfParent`에 걸린다.
- **상한을 21로 딱 맞추기** — 다음 지표 하나에 네 파일을 다시 열게 된다.

## 결과

- **쉬워지는 것**: 요약본이 주지 않는 항목을 결손 보정 경로로 받을 수 있다. 그 경로는 개정본을
  쌓지 않으므로 지표를 지우지 않는다. 조건 상한에 여유가 4 생겼다.
- **어려워지는 것**: 재무 포트가 둘이라 "재무를 어디서 받는가"에 답이 둘이다. 계정 매핑이
  회사마다 갈려 새 항목을 열 때마다 실측이 필요하다. `maxFinancialYears`가 2 → 5가 되어 분기
  읽기가 12 → 24행이 됐다(종목·일자당 1회라 쿼리 수는 그대로다).
- **정정보류 5건은 그날 안에 해소했다**(고려아연 · SK가스). `financial-refill`로 정정을 반영해
  공시일을 맞춘 뒤 이 잡을 다시 돌리는 순서다 — **뒤집으면 또 보류로 남는다.** 그때 무효화가
  22,586행을 지웠고 보존 창 전량 백필로 되채웠다. H-05 산출 가능 종목이 283 → **285**가 됐다.
  - **그 정정은 결손이 아니라 오류였다.** 고려아연 2026 Q1의 자산총계·자본총계·이익잉여금이
    재작성돼 `ALTMAN_Z`·`DEBT_RATIO`·`PBR`·`ROE`가 낡은 기준으로 계산돼 있었다. 백분위는
    유니버스 전체에서 나오므로 그 오차가 **다른 종목의 순위에도 섞여 있었다.**
- **정정공시를 정기적으로 발견하는 경로는 없다.** 재무를 받는 잡 셋이 전부 수동 CLI이고
  스케줄러·화면 어디에도 없다. 위 정정이 걸린 것은 **P10 적재를 돌렸기 때문**이지 탐지가
  작동해서가 아니다. 그 사이 고려아연의 지표는 틀린 채로 있었다.
  - **찾는 값은 싸다.** 다중회사 주요계정(`fnlttMultiAcnt`)이 회사별 `rcept_no`를 주므로
    100종목 묶음으로 훑을 수 있다. **처음에 「9기간 × 4묶음 = 36콜」로 적었으나 그 범위는 정정을
    놓친다** — `fiscal_period='Q1'`·`fiscal_year=2019`인데 `published_at`이 2021-08-31인 행이
    실재한다. 구현(`DartRestatementProbeLiveTest`)은 **연도 8개 × 보고서 4종 × 4묶음 = 128콜**이고
    그 클래스가 36콜 레시피를 명시적으로 금지한다. 2026-08-13 실측으로 2종목 9건이었다.
    레시피는 `batch-load` skill에 있다. **잡으로 만들지는 않았다** — 다른
    엔드포인트라 어댑터·테스트가 붙고 그것은 phase 크기의 결정이다.
- **이 무효화는 §P10의 "정정공시 분기로 떨어져 `indicator_value`가 지워지는 일이 없다"를
  되돌리지 않는다.** 그 기준은 **P10이 새로 만든 쓰기 경로**에 걸린 것이고, 그래서 그 경로는
  가지 자체를 없앴다. 정정 반영은 `FILL_MISSING`을 쓰는 결손 보정 잡의 **선언된 책임**이며
  (`WriteMode` javadoc), 그 잡이 지운 것은 백필이 되채운다. 둘을 섞으면 다음 세션이 P10을
  미충족으로 되돌린다.
- **되돌리려면**: `FullStatementDataSource`와 그 어댑터·콜렉터·잡, `WriteMode`의 셋째 값과
  `writeOne`의 그 가지, `OCF_SUM_5Y` 계산기와 프리셋의 21번째 조건, 상한 넷을 함께 걷는다.
  적재된 `operating_cash_flow`는 남아도 무해하다 — 그것을 읽는 지표가 사라질 뿐이다.
