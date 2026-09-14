---
id: ADR-0150
status: 채택됨
date: 2026-09-14
applies_to:
  - "src/main/java/com/stockscreener/pipeline/datasource/us/SecEdgarColumn.java"
  - "src/main/java/com/stockscreener/pipeline/datasource/us/SecEdgarFacts.java"
  - "src/main/java/com/stockscreener/pipeline/datasource/FinancialColumnAbsence.java"
---

# ADR-0150. 태그의 뜻이 매핑 계약의 일부다

## 결정

**`SecEdgarColumn`에 「그 컬럼의 부분집합인 태그」를 둔다**(`partialCandidates`). 후보가 아니라
**뜻의 선언**이고, 그 태그만 있으면 부재 상태가 `ABSENT`가 아니라 **`PARTIAL_ONLY`**다
(ADR-0147).

## 규칙

- **MUST NOT: 부분 개념 태그를 후보로 승격하지 않는다.** 실측이 그 위험을 이름 지었다 —
  `CostOfServices`는 **39종목이 갖는데 38이 상품원가 태그를 함께 가진다**(용역 쪽 절반이라는
  뜻이다). `Selection.LARGEST`(ADR-0146)에서 후보로 넣으면 어느 해에는 **전체 대신 조각이
  이긴다.** `CostOfReimbursableExpense`도 같다(9종목 중 6은 그것만 있다).
- **MUST NOT: 낱말(`Excluding`)로 자동 분류하지 않는다.** 문자열 분류는 가짜 구성원을 만든다.
  목록은 탐침이 낸 태그를 **하나씩 정의로 판정해 손으로** 적는다.
- **MUST NOT: D&A 조각과 더해 전체를 만들지 않는다 — 재보지 않았다.**
  `DepreciationDepletionAndAmortization`은 **판관비에 들어간 몫까지 포함한 총액**이라 그냥
  더하면 원가가 과대해진다. **그 오차를 실측하기 전에는 만들지 않는다.**
- **MUST: 목록을 고치면 재적재한다.** 부재 상태는 **우리 후보·부분 목록의 함수**이고, 저장된
  행은 **관측 시점의 판정**이다(ADR-0147).

## 검증

```bash
./gradlew test --tests "*SecEdgarFinancialAdapterTest.reportsPartialOnlyWhenSourceGivesASubsetTag*"
./gradlew test --tests "*GrossProfitPremiseReasonTest.partialOnlyIsNotAbsent*"
```

픽스처가 **부분 태그만** 있는 회사를 주고, 시험이 셋을 나란히 단언한다 —
`cost_of_sales`는 `PARTIAL_ONLY` · `gross_profit`은 `ABSENT` · **그 태그는 컬럼을 안 채운다.**

```premise 부분 개념만 내는 종목이 「안 낸다」를 듣고 있지 않다
psql -d stock_screener -At -c "SELECT CASE
  WHEN (SELECT count(*) FROM financial_column_absence) = 0 THEN '잴 것이 없었다 — 부재 표가 비었다'
  WHEN count(*) = 0 THEN 'OK 한 (종목,컬럼)이 두 상태를 갖는 것 0건'
  ELSE '한 (종목,컬럼)이 상태를 둘 이상 갖는 것 ' || count(*) || '건 — 화면 문구가 등록 순서로 갈린다'
  END FROM (
  SELECT stock_id, column_name FROM financial_column_absence
  GROUP BY 1,2 HAVING count(DISTINCT state) > 1) t"
```

## 배경

후보 목록은 **「태그가 그 컬럼에 속하는가」만** 담았고 **그 태그의 뜻이 컬럼의 전체인지 부분인지는
어디에도 없었다.** 그래서 「우리 후보가 없다」와 「소스가 부분 개념만 낸다」가 `ABSENT` 하나로
뭉쳤다.

**전수 탐침(2026-09-14 · `GROSS_MARGIN` 결측 380종목)이 갈래를 냈다.**

| 갈래 | 종목 |
|---|---|
| 후보 여덟이 전부 없다 | 240 |
| **후보 밖 태그만 있다** | 29 |
| **후보 밖 태그가 더 최신이다** | 35 |
| 우리 후보가 최신이거나 동률 | 76 |

그 **(b) 64**를 만든 태그가 다섯이다.

| 종목 | 태그 | 판정 |
|---|---|---|
| 51 | `CostOfGoodsAndServiceExcludingDepreciationDepletionAndAmortization` | 부분 — 정의상 D&A를 뺀 원가 |
| 6 | `CostOfReimbursableExpense` | 부분 — 청구대행 비용 |
| 3 | `CostOfServicesExcludingDepreciationDepletionAndAmortization` | 부분 |
| 3 | `CostOfServices` | 부분 — 39종목 중 38이 상품원가 태그를 함께 가진다 |
| 1 | `CostOfGoodsSoldExcludingDepreciationDepletionAndAmortization` | 부분 |

**그중 20종목이 지금 화면에서 *"이 회사는 매출원가·매출총이익을 공시에 내지 않습니다"*를 받고
있었고 그 첫 문장은 거짓이다**(둘째 절 *"우리가 아는 계정 이름으로는"*은 참이다). 나머지 31은
일부 연도에 값이 있어 사유가 안 붙는다.

## 버린 대안

- **부분 태그를 `fallbackCandidates`로 넣는다** — 후보 하나도 없을 때만 쓰이므로 안전해 보이지만
  **뜻이 다른 값을 컬럼에 앉힌다.** D&A를 뺀 원가로 매출총이익을 내면 체계적으로 과대해지고,
  그것은 이 결정이 닫으려는 병 자체다.
- **문구만 고친다**(*"우리가 아는 계정 이름으로는 안 옵니다"*로 통일) — 두 시장·두 갈래에서 참인
  문장이 되지만 **사용자가 할 일이 안 갈린다.** 한쪽은 기다려도 안 오고 한쪽은 값이 있는데
  우리가 안 쓰는 것이다.
- **`ABSENT`에 사유 코드 컬럼을 더한다** — 상태가 아니라 주석이 되어, 화면 규칙이 그것을 읽을
  계약이 생기지 않는다. 상태로 두면 `ck_fca_state`가 오타를 막는다.

## 결과

**닫힌 것** — 20종목이 받던 거짓 문장. 그리고 매핑 계약이 **뜻**을 담게 됐다 — 다음에 같은
질문(어느 태그가 부분인가)이 오면 목록에 한 줄 더하면 된다.

**어려워지는 것** — **목록이 손으로 유지된다.** 새 부분 태그가 생기면 아무 데서도 안 드러나고,
그 종목은 조용히 `ABSENT`로 돌아간다. **탐침을 다시 돌리는 것이 유일한 발견 수단**이고
그 사실을 여기 적어 둔다.

**남은 것 — D&A 조각 합산.** 조각을 함께 내는 종목이 있고 합치면 값이 열리지만, **`D&A` 태그가
판관비 몫까지 포함한 총액**이라 그냥 더하면 원가가 과대해진다. **그 오차를 아직 안 쟀다** —
재려면 「조각 둘과 신고 `gross_profit`이 다 있는 종목」에서 항등식을 보면 된다.
