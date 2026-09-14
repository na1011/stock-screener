---
id: ADR-0147
status: 채택됨
date: 2026-09-14
applies_to:
  - "src/main/java/**/datasource/FinancialDataSource.java"
  - "src/main/java/**/datasource/FinancialFetch.java"
  - "src/main/java/**/datasource/FinancialColumnAbsence.java"
  - "src/main/java/**/collector/FinancialColumnAbsenceWriter.java"
  - "src/main/java/**/indicator/IndicatorUnavailabilityConfig.java"
  - "src/main/resources/db/migration/V53__financial_column_absence.sql"
---

# ADR-0147. 「소스가 그 회사에 안 준다」를 종목별 사실로 저장하고 화면이 그것으로 말한다

## 결정

**적재가 이미 아는 「우리 후보로는 이 컬럼이 안 온다」를 버리지 않고 표 하나에 남긴다**
(`financial_column_absence`). 그리고 **상태를 둘로 가른다.**

| 상태 | 뜻 | 화면 |
|---|---|---|
| `ABSENT` | 후보 태그가 소스의 팩트 목록에 **이름으로도 없다** | **사유로 나간다** |
| `FILTERED` | 태그는 있는데 **우리 필터**(단위·폼·필드)가 다 걸렀다 | **안 나간다** |
| (행 없음) | **모른다** — 안 훑었거나 그 소스가 이 축을 안 본다 | 침묵 |

포트 반환형을 `List<RawFinancialReport>`에서 `FinancialFetch(reports, absences)`로 넓힌다.

## 규칙

- **MUST NOT: 행 없음을 「소스에 없다」로 읽지 않는다.** 행 없음은 **모른다**다.
  지금 DART는 이 축을 안 보므로 국내에는 행이 하나도 없다.
- **MUST NOT: `FILTERED`를 화면 사유로 내보내지 않는다.** 그것은 그 회사에 대한 사실이 아니라
  **우리 쪽 한계**이고, 「소스에 없습니다」로 말하면 `IndicatorUnavailabilityConfig`가 금지한
  **그럴듯한데 틀린 사유**가 된다. 그 구분은 `phase-criteria.md` §P22 축①이 팩트 수준에서
  이미 요구하던 것이고 **컬럼 수준에서는 안 지켜지고 있었다.**
- **MUST: 쓰기는 (종목, 소스) 범위의 delete-then-insert다.** UPSERT만 하면 후보를 더해 컬럼이
  채워진 뒤에도 **옛 부재 행이 살아남아 화면이 거짓을 말한다.** 지우는 범위를 소스로 좁히는
  이유는 **부재 축을 안 보는 소스도 빈 목록을 내기** 때문이다 — 그것으로 지우면 다른 소스가
  남긴 사실이 사라진다.
- **MUST: 이 사유는 최신 일자에만 붙인다.** 표가 `(종목, 컬럼)` 하나로 **오늘의 관측**만
  들고 있어서, 과거 기준일 조회에 그대로 쓰면 **그때는 값이 있던 종목에도 오늘의 판정이
  붙는다**(ADR-0128이 같은 병을 이미 이름 지었다). `StockController`가 `asOfResolver.latest`와
  견줘 최신일 때만 싣는다.
- **MUST: 판정은 「그때의 후보 목록」의 함수다.** 후보를 바꾸면 **다시 적재해야 뜻이 맞는다** —
  `observed_at`만 보는 감시로는 그 낡음을 못 잡는다.
- **MUST: 두 절을 한 문장에 둔다 — 규칙을 둘로 가르되 술어를 배타적으로 둔다.**
  `reasonFor`가 `String` 하나를 내므로 앞 규칙이 이기면 뒤 문장은 **화면에 영영 안 나온다.**
  업종 절은 `isFinancialDivision`의 참/거짓으로 **배타 분기**한다(ADR-0111 §규칙).

## 검증

```premise 소스 부재 표에 값이 있는 자리의 부재 행이 남아 있지 않다
psql -d stock_screener -At -c "
-- 부재 행과 실제 값이 동시에 있으면 그 행은 거짓이다. delete-then-insert가 지켜지는가를
-- 관측일이 아니라 **모순의 유무**로 묻는다 — 재적재가 관측일만 새로 찍어도 통과하면 안 된다.
WITH a AS (SELECT stock_id, column_name FROM financial_column_absence WHERE state='ABSENT'),
bad AS (
  SELECT a.stock_id, a.column_name FROM a
  JOIN financial_statement f ON f.stock_id = a.stock_id AND f.is_latest
  WHERE (a.column_name='cost_of_sales' AND f.cost_of_sales IS NOT NULL)
     OR (a.column_name='gross_profit'  AND f.gross_profit  IS NOT NULL)
     OR (a.column_name='current_liabilities' AND f.current_liabilities IS NOT NULL)
     OR (a.column_name='operating_income'    AND f.operating_income    IS NOT NULL)
     OR (a.column_name='revenue'             AND f.revenue             IS NOT NULL)
  GROUP BY 1,2)
SELECT CASE
  WHEN (SELECT count(*) FROM a) = 0
    THEN '잴 것이 없었다 — 부재 관측이 한 행도 없다(적재가 아직 안 돌았다)'
  WHEN (SELECT count(*) FROM bad) = 0
    THEN 'OK 부재 ' || (SELECT count(*) FROM a) || '행에 값과 모순되는 것이 없다'
  ELSE (SELECT count(*) FROM bad) || '행이 값과 모순된다 — 후보 목록을 바꾼 뒤 재적재가'
       || ' 빠졌거나 쓰기가 delete-then-insert가 아니다(ADR-0147)'
  END"
```

```bash
./gradlew test --tests "*StockDetail*" --tests "*Unavailability*"
```

## 배경

결측이 전부 같은 얼굴이라 화면이 **업종이라는 프록시**로밖에 말하지 못한다.
실측(2026-09-14 · `US_TOP1000`)에서 `GROSS_MARGIN` 결측 **380 중 금융 업종으로 설명되는 것이
143(37.6%)**뿐이고, 그 설명력은 이 저장소의 **가장 약한 기존 사유 규칙(`PSR` 64%)보다도
낮다** — 그 자리에는 이미 *"설명력이 낮다"*가 적혀 있다.

**그런데 그 사실을 적재가 이미 알고 버린다.** `SecEdgarFacts.collect`가 컬럼마다
`column.candidates()`를 훑어 팩트 맵을 만드는데, 그것이 비면 「우리 후보로는 그 컬럼이 안
온다」는 뜻이고 그 값이 쓰이고 사라진다.

**380 전수 탐침(약 4,500콜)이 그 축의 크기를 냈다** — (a) 소스 부재 **239** · (b) 후보 부족
**64** · 태그는 최신인데 저장이 빈 것 **75**. 즉 **가장 큰 덩어리가 (a)이고, 그것이 지금
화면에서 「데이터 없음」과 구분되지 않는다.**

**`current_liabilities`에서는 더 뚜렷하다.** 결손 141종목에 `LiabilitiesCurrent`를 전수로
물으니 **133(94.3%)이 404**다. 「금융업은 유동/비유동을 안 가른다」가 문서에서 물려받은
주장이었는데 **이제 종목별 실측이다.** 남는 8(`AMG`·`BEN`·`ENVA`·`FRHC`·`IVZ`·`JEF`·`OWL`·
`SOFI`)은 소스는 주는데 우리가 못 담은 것이다.

## 버린 대안

**업종 프록시만으로 끝낸다.** 설명력 37.6%이고 **237종목이 침묵으로 남는다.** 그리고 층을
가르면 업종 규칙의 잔여 모집단이 102가 되는데 **그중 금융은 10(9.8%)뿐**이고 그 10은
**과거에 그 계정을 실제로 신고한 회사**라 「업종이라 없다」가 거짓이 된다.

**한 상태로만 담는다(「부재」 하나).** `byTag`가 비는 원인이 둘이고 **사용자가 할 일이 다르다.**
`readUnits`가 단위(3자 ISO만)·폼(`10-K`·`10-Q`·`20-F`·`40-F`만)·필드를 거른 **뒤에** 맵이
만들어지므로, 하나로 담으면 **우리가 못 쓴 것에 「소스에 없습니다」를 말하게 된다.**

**원자료 표 안의 센티널 행으로 푼다(거버넌스 방식).** `GovernanceReportRow.noData`가 그
형태이고 ADR-0081 §규칙이 *"소스가 「데이터 없음」이라 답한 것도 행으로 남긴다"*로 원칙을
이미 채택했다. **재무는 부재가 「행」이 아니라 「컬럼」의 성질이라** 기간 행 안에 담을 자리가
없다. 그것이 별도 표를 고른 이유다.

**국내(DART)도 함께 연다.** `DartFullAccountMapper.find`가 후보 `account_id`가 손익 행에 하나도
없을 때 null을 내므로 **구조가 같고 추가 콜이 0**이다. 그리고 국내 활성 348 중
`gross_profit`도 `cost_of_sales`도 없어 값이 안 나는 것이 **54**로, 미국 380과 같은 상황이다.
**이번에 안 열었다** — 포트는 이미 넓혔으므로 DART 구현을 채우는 것만 남았고, 그때
`api-spec/dart-financial.md`도 함께 간다. **ADR-0111 §규칙 둘째(두 문장이 사용자가 할 일을
가른다)가 그때까지 국내 54에 대해 안 지켜진다는 것을 여기 적어 둔다.**

## 결과

**ADR-0111 §규칙 하나가 이 결정으로 무너진다.** *"MUST NOT: 지표 하나만 없는 경우에 사유를
붙이지 않는다 … `Context`가 그것을 가르지 못한다"*의 기각 근거가 **`Context`가 못 가른다**
하나였는데, 이제 `Context`가 (종목, 컬럼) 부재를 싣는다. **그 ADR 본문을 고쳤다.**

**닿지 않는 자리 셋을 한계로 적는다.**
- **스크리닝 진단 패널에는 안 닿는다.** 이 과제가 시작된 화면은 0건 스크리닝의
  「데이터 없음 380」인데 그쪽은 `conditionTallies.notEvaluable`이라 **조건 인덱스 단위
  집계이고 종목별 사유를 원리적으로 안 싣는다**(ADR-0142). 종목 하나를 열어야 갈린다.
- **이 표를 사람이 볼 화면이 없다.** 관리자 화면에 표 단위 뷰가 없어 `psql`과 위 펜스가
  유일한 감시다.
- **응답 시점 조회가 하나 는다.** ADR-0060이 *"사유 규칙이 늘면 그 판정이 응답 시점 비용이
  된다"*를 적어 뒀고, 그때의 「지금은 중분류 하나뿐」이 이 결정으로 둘이 됐다.

**ADR-0092 §버린 대안의 재개 조건이 충족됐다 — 다만 이번에 안 좁힌다.**
*"미국 지표가 생기면 그때 KR과 같은 방법으로 `SIC` 제외 집합을 좁힌다"*의 그 데이터가 생겼다:
`GROSS_MARGIN`이 나오는 비율이 **US 금융 25/168(14.9%) 대 비금융 595/832(71.5%)**다.
그것은 `ALTMAN_Z`의 **정의역** 문제이고 이 축(재료 결손)이 아니라 범위를 넘는다.

**첫 적재 실측(2026-09-14 · 미국 1,000종목).** **`ABSENT` 7,048행 · `FILTERED` 5행.**

| 컬럼 | `ABSENT` | `FILTERED` |
|---|---|---|
| `gross_profit` | 462 | 0 |
| `cost_of_sales` | 273 | 0 |
| `operating_income` | 153 | 0 |
| `current_liabilities` | **133** | 0 |
| `revenue` | 22 | 1 |

**`current_liabilities`의 133이 탐침이 직접 잰 수와 정확히 같다** — 결손 141종목에
`LiabilitiesCurrent`를 전수로 물었을 때 404가 **133**이었다. 적재의 판정과 소스 직접 질의가
독립적으로 같은 수를 냈다는 것이 이 기제의 대조군이다.

**`FILTERED`가 5행뿐이다.** 탐침이 낸 「태그는 최신인데 저장이 빈 것 75」는 **여기 안 잡힌다** —
그 75는 팩트가 단위·폼 필터를 **통과**하고 기간 창 맞추기에서 떨어진 것이라 맵이 안 빈다.
**즉 이 표는 「컬럼을 통째로 못 얻었다」만 답하고 「그 기간을 못 덮었다」는 안 답한다.**
그 축은 별개이고 크기가 75다.
