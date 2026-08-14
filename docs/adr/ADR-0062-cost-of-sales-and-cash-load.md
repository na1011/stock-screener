---
id: ADR-0062
status: 채택됨
date: 2026-08-14
applies_to:
  - "src/main/java/**/pipeline/datasource/kr/DartFullAccountMapper.java"
  - "src/main/java/**/pipeline/datasource/kr/DartFullStatementAdapter.java"
  - "src/main/resources/db/migration/V27__*.sql"
---

# ADR-0062. 매출원가와 현금및현금성자산을 적재한다 — 새 엔드포인트 없이

## 결정

`financial_statement`에 `cost_of_sales`·`cash_and_equivalents` 두 컬럼을 더하고,
**이미 쓰고 있는** `fnlttSinglAcntAll`(전체 재무제표) 응답에서 읽는다. ADR-0059가 「3단계
착수 판단이 남았다」고 적어 둔 그 단계다.

매출총이익률과 **총이익성(GP/A)** 이 여기에 걸린다. 한국 시장에서 수익성 이상현상의 재현율이
5%인 반면 총이익성은 유의하게 나왔고(ADR-0061), 그래서 프리셋의 수익성 축이 이 값에 기댄다.

## 규칙

- **MUST: 현금및현금성자산을 `sj_div='BS'`에서만 읽는다.** 현금흐름표에도 같은 이름의 계정이
  있고(기초·기말 잔액) 그쪽이 먼저 잡히면 **기초 잔액을 현재 보유 현금으로 저장**한다.
  실측에서 KB금융이 정확히 그랬다.
- **MUST NOT: `account_id`를 부분일치로 찾지 않는다.** `ifrs-full_Equity`가
  `ifrs-full_EquityAttributableToOwnersOfParent`에 걸려 지배주주지분을 자본총계로 저장한
  전례가 있다.
- **MUST NOT: 매출원가에 계정명 폴백을 두지 않는다.** 회사마다 이름이 다양해 폴백이
  「매출원가성 비용」류를 집을 위험이, 표준코드 미사용분(2~9%)을 메우는 이득보다 크다.
- **MUST NOT: 매출원가가 비어 있는 것을 곧바로 결손으로 읽지 않는다.** 이 계정은
  **없는 것이 정상인 유일한 자리**다 — 금융업·서비스업은 개념 자체가 없다. 실측:
  NAVER는 FY2021에는 있고 FY2022부터 없으며, **업종만이 아니라 연도에 따라서도 갈린다.**
- **MUST: 커버리지를 셀 때 `universe_membership.effective_to IS NULL`을 건다.** 그것이 없으면
  **과거에 편입됐다 빠진 이력까지 세어** 결손이 부풀려진다. 실제로 08-05~08-07만 편입됐던
  6종목이 그렇게 섞여 결손이 37이 아니라 43으로 보였다.
- **MUST: 결손의 원인 셋을 가른 뒤에만 「충분한가」를 판정한다.** ① 업종에 개념이 없다
  ② 정정공시라 보류(`FILL_WITHOUT_REVISION`은 정정을 만나면 채우지 않고 센다)
  ③ 실제 결손. 셋이 한 수에 섞이면 커버리지가 무엇을 말하는지 알 수 없다.
- **MUST NOT: 표본으로 「열렸다」를 판정하지 않는다.** 8개사에서 6개가 온 것은
  「닫히지 않았다」까지만 말한다. **커버리지는 350종목 재적재만 답한다.**
- **MUST: 컬럼을 더하면 여섯 파일이 함께 간다** — DTO(`RawFinancialReport`) · 매퍼 ·
  어댑터 둘의 조립부 · `FinancialWriter`의 `Amount` enum과 INSERT ·
  `FinancialFigures` · `IndicatorDataReader`의 SELECT와 매핑. 빠뜨리면 그 컬럼이
  **영원히 안 채워질 뿐 에러는 없다.**

## 검증

```premise 매출원가가 채워진 종목 수가 재적재 직후에서 줄지 않았다
psql -d stock_screener -At -c "SELECT CASE
  WHEN count(*) = 0 THEN '잴 것이 없었다 — is_latest 연간 재무가 한 행도 없다'
  WHEN count(*) FILTER (WHERE cost_of_sales IS NOT NULL) = 0
    THEN '매출원가가 한 종목도 없다 — 컬럼만 생기고 재적재가 돌지 않았다'
  WHEN count(DISTINCT stock_id) FILTER (WHERE cost_of_sales IS NOT NULL) >= 200
    THEN 'OK ' || count(DISTINCT stock_id) FILTER (WHERE cost_of_sales IS NOT NULL)
       || '종목 (연간 행 ' || count(*) FILTER (WHERE cost_of_sales IS NOT NULL) || ')'
  ELSE '매출원가 보유 종목이 '
       || count(DISTINCT stock_id) FILTER (WHERE cost_of_sales IS NOT NULL)
       || '으로 줄었다 — 정정공시가 값을 지웠거나 매퍼가 회귀했다' END
FROM financial_statement WHERE is_latest AND fiscal_period = 'FY'"
```

`sj_div` 분기를 지우지 않았는지 — 아래가 어댑터의 두 조립부에서 각각 나와야 한다.

```
rg -n "ID_CASH_AND_EQUIVALENTS" src/main/java/com/stockscreener/pipeline/datasource/kr/DartFullStatementAdapter.java
```

## 배경

ADR-0059가 탐침으로 여기까지 왔다 — 매출원가·법인세비용·주당이익은 8개사에서 왔고
감가상각비는 오지 않았다(그래서 EBITDA는 여전히 닫혀 있다). 남은 것은 **착수 판단**이었고,
프리셋 개편(ADR-0061)이 총이익성을 수익성 축으로 삼기로 하면서 착수 이유가 생겼다.

**콜 비용이 판단을 바꾸지 않았다.** 연간만 받으면 종목당 6콜, 350종목이면 2,100콜이고
`fnlttSinglAcntAll` 보수 예산(10,000)의 21%다. **다만 그것은 하한이다** — 연결이 없어
별도로 다시 부르는 종목이 있어 상한은 그 두 배이고, 그 비율은 미실측이다.

## 버린 대안

- **현금으로 EV를 만들어 EV/EBIT을 열기** — Acquirer's Multiple의 재료다. 그러나 EV는
  현금만으로 서지 않고 **총차입금**이 필요한데, 실측에서 무엇을 더할지가 회사마다 달랐다.
  「총차입금」은 소스가 주는 값이 아니라 **우리가 만드는 값**이 되므로 열지 않는다.
  현금 컬럼은 추가 콜이 0이라 같은 재적재에 얹어 받아만 둔다.
- **분기까지 받기** — `--quarters=true`는 티커와 함께여야 하고, 전 종목이면 콜이 네 배가
  된다. 총이익성의 원식이 연간이므로 필요가 없다.
- **매출총이익(`ifrs-full_GrossProfit`)을 직접 받기** — 한 계정으로 끝난다. 그러나 그 계정을
  내지 않고 매출원가만 내는 회사가 있어 **둘 중 하나만 고르면 커버리지가 준다.**
  매출 − 매출원가는 우리가 계산할 수 있으므로 원재료 쪽을 받는다.

## 실측 (2026-08-14 재적재 직후)

현재 편입 350종목 기준. **콜은 2,714회**로 하한(2,100)과 상한(4,200) 사이였다.

| 항목 | 값 |
|---|---|
| 매출원가 | **288 / 350 (82.3%)** |
| 현금및현금성자산 | 331 / 350 |
| 결손 | 37 — 그중 **34가 업종**(게임 10 · 바이오 10 · 금융 9 · 통신 3 · 인터넷 2) |
| 정정보류 | 1 |

**업종으로 설명되지 않는 셋 중 둘은 표시 방식이다** — 호텔신라는 FY2019~2025 전 연도에
매출원가가 없고, 롯데렌탈도 같다. **엘앤에프 하나만 FY2021~2024에는 있고 FY2025만 빈다** —
그 한 해에 계정 표시가 바뀌었을 가능성이 있고 아직 확인하지 않았다.

## 결과

- **총이익성(GP/A)과 매출총이익률이 열린다.** 스윙 원본 명세의 S-04(해자 프록시)가
  요구하는 「5년 평균 매출총이익률」도 이 컬럼 위에 설 수 있다 — 다만 ROIC가 따로 필요해
  그 조건 전체가 열리는 것은 아니다.
- **EBITDA는 여전히 닫혀 있다**(감가상각비). **EV/EBIT도 절반만 열렸다**(총차입금).
- 기존 행은 NULL로 남고, 채우는 것은 `full-statement-refill` 재적재의 몫이다.
  **재적재만으로는 화면이 바뀌지 않는다** — 그 잡은 `indicator_value`를 건드리지 않으므로
  **지표 백필이 뒤따라야 하고, 빠뜨리면 에러도 경고도 없이 아무 일도 일어나지 않는다.**
