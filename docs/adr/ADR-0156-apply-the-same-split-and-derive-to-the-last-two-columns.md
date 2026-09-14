---
id: ADR-0156
status: 채택됨
date: 2026-09-15
applies_to:
  - "src/main/java/com/stockscreener/pipeline/datasource/us/SecEdgarColumn.java"
  - "src/main/java/com/stockscreener/pipeline/datasource/us/SecEdgarFacts.java"
---

# ADR-0156. 「제외」 태그 둘에도 갈라 들고 유도를 적용한다

## 결정

ADR-0154의 처방(**뜻이 다른 후보를 컬럼으로 갈라 들고 유도**)을 남은 둘에 적용한다.

- **영업활동현금흐름** = 총액 태그 → 없으면 **계속영업분 + 중단영업분** → 없으면 첫값
- **세전이익** = 지분법 포함 태그 → 없으면 **지분법 제외분 + 지분법손익** → 없으면 첫값

`CASH_AND_EQUIVALENTS`는 **안 한다** — 잔여가 가장 크지만(다리 태그가 없을 때 47.7%가 실제로
다르다) **지금 어느 지표도 안 읽는다.** 틀린 값이지만 **닿지 않는다.**

## 측정 — 두 수를 갈라 본다

표본 47(`.factcache/`) · **답을 아는 칸**에 같은 산수를 태워 쟀다. **유도가 실제로 도는 칸은
답을 모른다**(모르니까 유도한다).

| | 항등식 | 개선 : 악화 | 절대오차 총합 | 발화(폴백 종목) |
|---|---|---|---|---|
| 영업활동현금흐름 | **81/82 (98.8%)** | **67 : 1** | 50,530M → 2,928M (**−94.2%**) | 17 |
| 세전이익 | **62/75 (82.7%)** | **60 : 13** | 48,981M → 10,773M (**−78.0%**) | 24 |

## 새로 알게 된 것 — 「제외」 태그가 실제로 제외하지 않는다

**악화 칸의 정체가 둘 다 같다.** 넓은 태그와 좁은 태그의 값이 **이미 같은데** 다리를 더해서
생긴 오차다.

| | 두 태그 값이 이미 같은 칸 | 그 종목 |
|---|---|---|
| 세전이익 | **14.7%** (악화 13 중 **11**) | `F` · `GM` · `CACI` · `PNR` · `WEC` |
| 영업활동현금흐름 | **18.3%** (악화 1 중 1) | `AIG` 2012 |

**`IncomeLossFromContinuingOperationsBeforeIncomeTaxes…AndIncomeLossFromEquityMethodInvestments`가
이름과 달리 지분법을 이미 포함해 있다.** `…ContinuingOperations`도 같다.
**이름이 계약이 아니다.**

**그래도 넣는다** — 「몇 칸이 나빠지나」가 아니라 **총오차가 어디로 가나**로 판단한다.
악화 칸의 오차는 다리 값 크기이고, 개선 칸의 오차는 다리 값 크기 **곱하기 60배 더 많은 칸**이다.

## 규칙

- MUST: **직접 낸 값이 언제나 이긴다**(ADR-0104). 유도는 없을 때만이다.
- MUST: **어느 갈래에서도 값을 잃지 않는다** — 유도가 안 되면 예전과 똑같이 그 컬럼의 첫값이
  나간다(ADR-0126 §결정이 입력 값을 버리는 것을 금지한다).
- MUST: **다리 컬럼 둘을 `reportEvidence`에서 뺀다**(`OPERATING_CASH_FLOW_DISCONTINUED` ·
  `EQUITY_METHOD_INCOME`). 세전이익 갈래 둘도 뺀다 — **부모 `PROFIT_BEFORE_TAX`가 이미
  빠져 있어서**, 갈래만 남기면 세금만 든 얕은 보고서가 reporter가 되어 ADR-0143이 닫은 자리가
  다시 열린다.
- MUST NOT: **`CASH_AND_EQUIVALENTS`를 「잔여가 크니까」로 함께 열지 않는다.** 읽는 쪽이 없다.
  **닿는 것부터 고친다.**

## 검증

```bash
# 유도 재료가 저장 컬럼으로 새지 않는다
rg "equityMethod|discontinued|DISCONTINUED|EQUITY_METHOD" \
  src/main/java/com/stockscreener/pipeline/datasource/RawFinancialReport.java

# 다리 컬럼이 reporter 자격을 갖지 않는다
rg -n -A 12 "private boolean derivationMaterial" \
  src/main/java/com/stockscreener/pipeline/datasource/us/SecEdgarColumn.java \
  | rg -c "OPERATING_CASH_FLOW_DISCONTINUED|EQUITY_METHOD_INCOME"   # 2가 나와야 한다
```

## 버린 대안

- **악화가 있으니 안 넣는다** — 악화 칸 수만 보는 판단이다. 총오차는 78~94% 준다.
- **두 태그 값이 같으면 유도를 건너뛴다** — **유도가 도는 조건에서는 넓은 태그가 없어서**
  비교할 값이 아예 없다. 그 판정은 원리적으로 불가능하다.
- **`CASH_AND_EQUIVALENTS`도 함께 연다** — 읽는 지표가 0이다. 열면 **검증 비용만 든다.**

## 결과

(재적재 뒤에 채운다)
