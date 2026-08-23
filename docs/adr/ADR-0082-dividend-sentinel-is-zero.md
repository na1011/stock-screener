---
id: ADR-0082
status: 채택됨
date: 2026-08-23
applies_to:
  - "src/main/java/com/stockscreener/pipeline/datasource/kr/DartGovernanceFactMapper.java"
  - "src/main/java/com/stockscreener/indicator/calculator/Dividend*.java"
---

# ADR-0082. 배당 표의 센티널 `-`를 짝과 대조해 0으로 읽는다

## 결정

DART 배당 표(`alotMatter`)에서 **주당 현금배당금과 현금배당금총액이 같은 열에서 둘 다 `-`이면 그 값을
0으로 적재한다.** 한쪽만 `-`면 값을 내지 않는다.

그 전까지는 `-`인 열을 통째로 건너뛰어 **「배당을 안 했다」와 「데이터가 없다」가 같은 상태**였다.
`DPS_COMMON` 사실이 없는 95종목 중 **93은 원문 행을 갖고 있었고**, 진짜 미수집은 구조적 제외
2종목(`088980` 맥쿼리인프라·`950260`)뿐이었다.

## 규칙

- **MUST: 짝을 대표 필드로 쓴다.** §16-A의 *「행에 값이 있으면 `-`는 0이다 / 행 전체가 `-`면 해당
  없음이다」*를 배당에 옮기려면 대표 필드가 필요한데, 배당 행의 숫자 열은 3개년뿐이라 **행 안에는
  그 역할을 할 필드가 없다.** 그래서 **같은 보고서의 짝**이 그것을 한다.
  **MUST NOT: 한쪽만 보고 0으로 읽지 않는다** — 양방향으로 반례가 실재한다(당기 기준 DPS만 `-`
  3행 · 총액만 `-` 3행). 실제로 241710 코스메카코리아는 **총액 3,952백만원·배당수익률 0.50%인데
  주당배당금 칸만 `-`**다. 무조건 0으로 읽으면 그 회사 화면에 **「배당수익률 0.00%」와
  「배당성향 6.83%」가 나란히** 뜬다.
- **MUST: 주식 종류를 안 밝힌 행(`-`)은 보고서 단위로 셋으로 가른다** — 값이 든 행이 **정확히
  하나면** 그것을 쓰고, **하나도 없으면** `ordinal`이 가장 작은 행으로 0을 내며, **둘 이상이면
  값을 내지 않는다.** 전열 `-`인 보고서 131건이 **전부 2행 다 `-`**라 어느 행을 골라도 값이 0이다.
  **MUST NOT: 이것을 「`ordinal` 0이 보통주다」로 읽지 않는다** — 값이 같아 아무거나 되므로 고르는
  것이지 종류를 판정한 것이 아니다.
- **MUST NOT: 후보 게이트를 「완화」하지 않는다.** `hasAnyAmount`를 단순히 느슨하게 만들면 값이 든
  행 0인 131건이 **후보 2개**가 되어 「정확히 하나」가 거짓이 되고 **`DPS_COMMON`이 오히려
  사라진다.** 완화가 아니라 **분기**여야 한다.
- **MUST NOT: 구조적 제외를 0으로 채우지 않는다.** 원문이 `row_ordinal = -1`(데이터 없음)뿐인
  종목은 `derive()`가 이미 `real` 목록에서 걸러내므로 새 가드가 필요 없다. **그 둘은 값이 없는
  것이 맞다** — 「배당 안 함」과 「그 표가 없음」의 구분이 이 결정의 전부다.
- **MUST: 이 결정이 지표 계층의 규칙과 다르다는 것을 안다.** `IndicatorContext`의
  *「MUST NOT: 비어 있는 것을 0으로 접지 않는다」*는 **지표 계층**에 걸린 것이고, 이 결정은
  **유도 계층에서 사실을 만드는** 것이다. 지표 계층은 여전히 없는 것을 0으로 안 접는다.

## 검증

```bash
./gradlew test --tests "*DartGovernanceFactMapperTest*"
```

```premise 원문 행이 있는 종목이 전부 주당배당금 사실을 낸다
psql -d stock_screener -At -c "WITH raw AS (SELECT DISTINCT r.stock_id FROM governance_report_row r WHERE r.endpoint = 'DIVIDEND' AND r.row_ordinal >= 0 AND r.payload->>'se' = '주당 현금배당금(원)'), missing AS (SELECT count(*) n FROM raw WHERE NOT EXISTS (SELECT 1 FROM governance_fact f WHERE f.stock_id = raw.stock_id AND f.metric_key = 'DPS_COMMON')) SELECT CASE WHEN (SELECT count(*) FROM raw) = 0 THEN '잴 것이 없었다 — 주당 현금배당금 원문 행이 하나도 없다' WHEN (SELECT n FROM missing) = 0 THEN 'OK 원문 행이 있는 ' || (SELECT count(*) FROM raw) || '종목이 전부 DPS_COMMON을 낸다' ELSE (SELECT n FROM missing) || '종목이 원문 행은 있는데 DPS_COMMON이 없다 — 센티널을 0으로 안 읽고 있다' END"
```

**이 펜스가 ADR-0081의 것을 대신한다.** 그쪽은 원문 조건을 `thstrm NOT IN ('-','')`로 좁혀
**무배당 결손을 원리적으로 못 잡는다**(그래서 결함이 있는 채로 OK를 냈다). 이 결함이 **무증상이었던
이유**가 그것이다.

```premise 배당을 한 회사에 0이 붙지 않았다
psql -d stock_screener -At -c "WITH t AS (SELECT f.stock_id, max(f.fiscal_year) y FROM governance_fact f WHERE f.metric_key = 'CASH_DIVIDEND_TOTAL' GROUP BY f.stock_id), paid AS (SELECT t.stock_id, t.y FROM t JOIN governance_fact f ON f.stock_id = t.stock_id AND f.fiscal_year = t.y AND f.metric_key = 'CASH_DIVIDEND_TOTAL' AND f.value > 0), bad AS (SELECT count(*) n FROM paid p JOIN governance_fact d ON d.stock_id = p.stock_id AND d.fiscal_year = p.y AND d.metric_key = 'DPS_COMMON' AND d.value = 0) SELECT CASE WHEN (SELECT count(*) FROM paid) = 0 THEN '잴 것이 없었다 — 배당총액이 양수인 종목이 없다' WHEN (SELECT n FROM bad) = 0 THEN 'OK 배당총액이 양수인 ' || (SELECT count(*) FROM paid) || '종목에 주당배당금 0이 붙지 않았다' ELSE (SELECT n FROM bad) || '종목이 배당총액은 양수인데 주당배당금이 0이다 — 짝 대조가 뚫렸다' END"
```

## 배경

거버넌스 축을 프리셋에 접목할지 검토하다가 나왔다. 처음에는 *「무배당인지 데이터가 없는지 구분할
수 없으니 배당을 조건으로 못 쓴다」*로 닫았는데, **사용자가 그 전제를 반박했고 재보니 틀렸다.**
소스는 구분해 준다 — 펄어비스 FY2024 원문은 `stock_knd = 보통주` 행을 **명시적으로 주면서** 값이
`-`이고, 총액·배당성향도 `-`다.

**`-`가 0이라는 것은 실측이 직접 지지한다.** 보통주 행 516개 중 **당기가 `-`인데 전전기에 값이 있는
것이 23행**이고, 열어 보면 전부 배당을 끊은 회사다(삼성SDI FY2025 `- / 1,000 / 1,000`, 현대해상
FY2024 `- / 2,063 / 1,965`, 호텔신라·한화솔루션·SKC). 소스가 안 채운 것이라면 **더 오래된 열이
채워져 있을 이유가 없다.** 현대해상은 다음 해 보고서가 `- / - / 2,063`으로 와서 **두 보고서가 서로
맞는다.**

**같은 자리에 주식 종류 어휘 결함도 있었고 함께 고쳤다** — `COMMON_CLASSES`가 `보통주`·
`의결권있는주식` 둘뿐이라 `보통주식`(32종목)과 `-` 센티널(87종목)을 버렸고, `shareTotals`는 그
목록을 안 쓰고 문자열 `"보통주"`와 완전일치로 비교해 **같은 파일의 두 경로가 어긋나 있었다.**

## 버린 대안

- **열 파싱만 고친다.** 가장 작아 보인다. 그러나 `unspecifiedDpsRows`의 `hasAnyAmount` 게이트가
  전열 `-` 행을 **후보에서 먼저 빼므로** 131개 보고서가 그대로 남는다. 고칠 자리가 둘이다.
- **대표 필드 없이 `-`를 무조건 0으로 읽는다.** 초안이 이것이었다. **반증이 무너뜨렸다** — 241710·
  450080이 배당을 했는데 주당배당금 칸만 `-`라, 이 계획이 없애려는 무증상 결함의 **반대 방향
  판본**이 생긴다.
- **총액을 안 건드리고 주당배당금만 고친다.** 둘이 같은 루프를 쓰므로 갈라 두면 코드가 두 벌이 된다.
  그리고 총액에도 같은 결손이 있었다(사실 없는 93종목 중 91이 원문 행 보유).
- **`ordinal` 0을 보통주로 가정한다.** 가장 짧다. 그러나 **소스의 행 순서가 그렇다는 것을 재본 적이
  없다.** 값이 전부 0이라 아무거나 되는 상황에서만 `ordinal`로 고르고, 그 사실을 코드 주석이 적는다.

## 결과

- **쉬워지는 것**: 무배당이 「0%」로 정직하게 뜬다. `DPS_COMMON` **255 → 347**,
  `CASH_DIVIDEND_TOTAL` **257 → 348**. 348이 아니라 347인 것은 241710 코스메카코리아가 **보고서
  전부에서 주당배당금과 총액이 어긋나** 통째로 빠지기 때문이고, **그 회사의 주당배당금을 우리가
  정말 모르므로 맞다.** 배당 조건이 **판정불가로 조용히 빠지지 않으므로 프리셋 조건으로 쓸 수 있게
  된다**(ADR-0083).
- **어려워지는 것**: `zeroIfDash` 계열이 「센티널」·「필드 부재」·「파싱 실패」를 **구분 없이 0으로
  접는다.** 배당 열은 페이로드에 늘 있어 실질 위험이 파싱 실패뿐이고 그것은 `parseAmount`가 WARN을
  남기지만, **그 병합 자체는 남는다.**
- **알려진 한계 — 보고서를 가로지르는 낡음은 못 막는다.** 판정이 **보고서 단위**라, 옛 보고서에서
  0을 얻고 새 보고서의 주당배당금이 어긋나 빠지면 **그 0이 최신 사실로 남는다.** 실측으로 그런
  종목이 **하나**다(450080 에코프로머티 — FY2024 = 0인데 FY2025에 배당했다). 지어낸 값이 아니라
  **그날 알던 값이 낡은 것**이지만, 화면에는 「배당수익률 0.00%」로 보인다. 1종목이라 기계를 더
  만들지 않고 여기 적는다. **늘어나면 그때 「최신 보고서가 어긋나면 옛 0도 안 쓴다」로 좁힌다.**
- **기존 표시가 바뀌는 것**: `CASH_DIVIDEND_TOTAL`이 0을 얻으면 `GovernanceMath.figuresOn`의 앵커
  회계연도가 옮겨간다. FY2025 총액이 `-`인데 이전 해에 값이 있는 종목이 **14**, 그중 **8은 지금
  화면에 배당성향이 떠 있고 그것이 0.0%로 바뀐다.**
- **되돌리려면**: 매퍼의 `zeroableColumns`와 `unspecifiedDpsRows`의 분기를 되돌리고
  `--job=governance-derive` → `backfill`을 돌린다. **외부 호출 0이다** — 표를 둘로 가른 ADR-0081의
  설계가 이 결정을 싸게 만든다.
