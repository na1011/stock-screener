---
id: ADR-0081
status: 채택됨
date: 2026-08-23
applies_to:
  - "src/main/java/com/stockscreener/pipeline/datasource/Governance*.java"
  - "src/main/java/com/stockscreener/pipeline/datasource/kr/DartGovernance*.java"
  - "src/main/java/com/stockscreener/pipeline/collector/Governance*.java"
  - "src/main/java/com/stockscreener/pipeline/batch/Governance*.java"
  - "src/main/java/com/stockscreener/indicator/Governance*.java"
  - "src/main/java/com/stockscreener/indicator/calculator/Governance*.java"
  - "src/main/java/com/stockscreener/indicator/DataRequirement.java"
  - "src/main/java/com/stockscreener/indicator/IndicatorContext.java"
---

# ADR-0081. 거버넌스 축을 열며 원자료와 판정을 표 둘로 가른다

## 결정

DART 정기보고서 주요정보 여덟을 받아 **원문 표 하나와 유도 표 하나**에 나눠 담고, 지표 계층에는
**채널 하나**를 연다. 새 분류 `GOVERNANCE`에 지표 열셋을 세운다.

**spec의 범위 밖에서 시작한다.** `docs/stock-screener-project-spec.md`에 「거버넌스」·「지배구조」·
「ESG」가 한 번도 나오지 않는다. **범위를 넓히는 승인은 2026-08-21 사용자 프롬프트**이고, 완료
판정은 코드보다 먼저 `docs/phase-criteria.md` §P18에 적혔다. 이 문단이 없으면 다음 세션이 그
절을 「기준이 조용히 늘어난 자리」로 읽는다.

## 규칙

- **MUST: 원문을 그대로 담는다.** 콤마도 센티널 `-`도 개행이 든 기수 표기도 정리하지 않는다.
  해석은 유도 단계가 하고, 그래야 매핑을 고칠 때 **재적재가 아니라 재유도**로 끝난다.
- **MUST: 소스가 「데이터 없음」이라 답한 것도 행으로 남긴다.** 안 남기면 「미수집」과 「그 회사에
  그 표가 없음」이 구별되지 않고, 커버리지 결손의 원인을 업종·구조·수집으로 가를 수 없다.
- **MUST: `receipt_no`를 `NOT NULL`로 둔다.** 데이터 없음 행은 빈 문자열이다 — NULL이면
  PostgreSQL UNIQUE가 기본 `NULLS DISTINCT`라 **제약이 아무것도 막지 못하고 실행 횟수만큼 행이
  쌓인다.** 커버리지 판정이 그 수 위에 선다.
- **MUST NOT: 같은 회계연도의 여러 판을 하나로 접지 않는다.** 접으면 계단 함수가 무력화된다 —
  FY2024 배당은 2025년 3월 보고서가 이미 말했는데 2026년 보고서에도 전기 열로 실리므로,
  접으면 **그 값을 2026년 3월에야 알게 된 것**이 되어 저장 창 앞부분이 통째로 빈다.
- **MUST NOT: 회계연도로 읽기 상한을 두지 않는다.** 창 앞에서는 최신 회계연도가 아직 공시 전이라
  기준 해가 뒤로 밀리고, 3년 차분이 실제로는 다섯째 해까지 본다. 자르지 않아도 싸다 —
  종목당 평균 64행이다.
- **MUST: 감사의견 판정을 관측된 문자열의 완전일치 허용목록으로 한다.** `contains("적정")`으로
  물으면 **`부적정의견`이 「적정」으로 분류된다.** 목록 밖은 **판정불가 + 로그**다.
- **MUST: 표를 가로지르는 나눗셈은 분모 후보 둘을 대조하고 어긋나면 값을 내지 않는다.**
  소스가 발행주식총수를 10<sup>6</sup>배로 낸 회사가 실재한다.
- **MUST NOT: `GovernanceMetric`에서 상수를 빼거나 이름을 바꾸지 않는다.** `governance_fact`에
  CHECK가 없고 리더가 모르는 키를 건너뛰므로 **컴파일도 테스트도 초록인 채** 그 지표만 전 종목
  판정불가가 되고, 화면은 등록된 결손 사유를 그대로 띄워 거짓 이유를 말한다.
- **MUST NOT: 벤더 이름을 `collector`·`batch` 패키지의 코드에 노출하지 않는다**(ADR-0006).
  유도 구현은 `datasource/kr`에 **package-private**으로 두고 포트만 내보낸다.
- **MUST: 방향은 문헌이 지지하는 것에만 준다.** 나머지는 `NEUTRAL`이고 `interpretation`에
  「방향을 재본 적이 없다」를 적는다 — NEUTRAL을 「중립이 좋다」로 읽히게 두면 화면이 재보지 않은
  판정을 그린다.

## 검증

```bash
./gradlew test --tests "*DartGovernanceFactMapperTest*"   # 행 선택 규칙을 실측 응답으로
./gradlew test --tests "*IndicatorContextCopyTest*"       # 사본이 필드를 하나도 안 빠뜨리는가
./gradlew test --tests "*WireContractTest*"               # 새 분류가 와이어 계약에 실렸는가
```

`IndicatorContextCopyTest`는 **리플렉션으로 필드를 전수로 돈다.** 손으로 적은 목록은 다음에 필드가
늘 때 함께 늘지 않고, **안 늘어났다는 사실이 드러나지 않는 것**이 정확히 이 결함의 형태다.

```premise 거버넌스 지표가 최신 거래일에 값을 갖는다
psql -d stock_screener -At -c "WITH latest AS (SELECT max(trade_date) d FROM indicator_trade_date WHERE market_code IN ('KOSPI','KOSDAQ')), g AS (SELECT count(*) n FROM indicator WHERE category = 'GOVERNANCE' AND is_active), c AS (SELECT count(DISTINCT v.indicator_code) n FROM (SELECT iv0.* FROM indicator_value iv0 JOIN stock s0 ON s0.id = iv0.stock_id WHERE s0.market_code IN ('KOSPI','KOSDAQ')) v JOIN indicator i ON i.code = v.indicator_code, latest WHERE i.category = 'GOVERNANCE' AND v.trade_date = latest.d) SELECT CASE WHEN (SELECT n FROM g) = 0 THEN '잴 것이 없었다 — 활성 거버넌스 지표가 하나도 없다' WHEN (SELECT d FROM latest) IS NULL THEN '잴 것이 없었다 — indicator_value가 비어 있다' WHEN (SELECT n FROM c) = (SELECT n FROM g) THEN 'OK 거버넌스 지표 ' || (SELECT n FROM g) || '개가 최신 거래일에 값을 낸다' ELSE '거버넌스 지표 ' || (SELECT n FROM g) || '개 중 ' || (SELECT n FROM c) || '개만 최신 거래일에 값이 있다 — 적재나 유도가 멈췄는지 본다(governance-load → governance-derive → backfill)' END"
```

```premise 유도된 지표 키를 코드가 전부 안다
psql -d stock_screener -At -c "WITH k AS (SELECT DISTINCT metric_key FROM governance_fact) SELECT CASE WHEN (SELECT count(*) FROM k) = 0 THEN '잴 것이 없었다 — governance_fact가 비어 있다' WHEN (SELECT count(*) FROM k WHERE metric_key NOT IN ('ISSUED_SHARES_COMMON','TREASURY_SHARES_COMMON','PROFIT_RETIRED_CUM','DPS_COMMON','CASH_DIVIDEND_TOTAL','EXEC_PAY_TOTAL','DIRECTOR_COUNT','OUTSIDE_DIRECTOR_COUNT','DIRECTOR_DISMISSED','DIRECTOR_RESIGNED','LARGEST_HOLDER_SHARES_COMMON','MINORITY_SHARES','MINORITY_TOTAL_SHARES','CONTROL_TRANSFERS_3Y','AUDIT_OPINION_ADVERSE','AUDIT_EMPHASIS_PRESENT','AUDITOR_CHANGED')) = 0 THEN 'OK metric_key ' || (SELECT count(*) FROM k) || '종을 코드가 전부 안다' ELSE '코드가 모르는 metric_key가 남아 있다: ' || (SELECT string_agg(metric_key, ', ') FROM k WHERE metric_key NOT IN ('ISSUED_SHARES_COMMON','TREASURY_SHARES_COMMON','PROFIT_RETIRED_CUM','DPS_COMMON','CASH_DIVIDEND_TOTAL','EXEC_PAY_TOTAL','DIRECTOR_COUNT','OUTSIDE_DIRECTOR_COUNT','DIRECTOR_DISMISSED','DIRECTOR_RESIGNED','LARGEST_HOLDER_SHARES_COMMON','MINORITY_SHARES','MINORITY_TOTAL_SHARES','CONTROL_TRANSFERS_3Y','AUDIT_OPINION_ADVERSE','AUDIT_EMPHASIS_PRESENT','AUDITOR_CHANGED')) || ' — GovernanceMetric에서 뺐거나 이름을 바꿨다. 그 지표는 전 종목 판정불가다' END"
```

**이 목록은 손으로 적은 사본이다** — SQL이 Java enum을 못 읽는다. 상수를 더하면 여기도 더한다.

**셋째 펜스가 「두 표가 맞는가」가 아니라 「안 맞을 때 값이 안 나가는가」를 묻는 이유**는 앞엣것이
**이미 깨져 있는 전제**이기 때문이다 — 실측으로 그런 종목이 있고 그것은 소스의 문제라 우리가 못
고친다. 그것을 묻는 펜스는 매 세션 같은 발견을 내고, 그러면 진짜 발견이 그 소음에 묻힌다.
**결정이 보장하는 것은 「어긋난 해에 값을 안 낸다」이고 그것은 우리가 지킬 수 있다.**

**MUST: 「지금 쓰이는 회계연도」로 좁힌다.** 종목 단위로 「한 해라도 어긋났는가」를 물으면
**과거의 어긋남 때문에 지금 멀쩡한 종목이 위반으로 잡힌다** — 실제로 그렇게 잡혔다(456160은
FY2024가 어긋나고 FY2025는 맞는데, 지표가 쓰는 것은 FY2025다).

```premise 단위가 어긋난 회계연도에는 표를 가로지르는 지표가 값을 내지 않는다
psql -d stock_screener -At -c "WITH latest AS (SELECT max(trade_date) d FROM indicator_trade_date WHERE market_code IN ('KOSPI','KOSDAQ')), eff AS (SELECT f.stock_id, max(f.fiscal_year) y FROM governance_fact f, latest WHERE f.metric_key = 'MINORITY_TOTAL_SHARES' AND f.published_at <= latest.d GROUP BY f.stock_id), a AS (SELECT f.stock_id, f.value v FROM governance_fact f JOIN eff ON eff.stock_id = f.stock_id AND eff.y = f.fiscal_year WHERE f.metric_key = 'ISSUED_SHARES_COMMON'), b AS (SELECT f.stock_id, f.value v FROM governance_fact f JOIN eff ON eff.stock_id = f.stock_id AND eff.y = f.fiscal_year WHERE f.metric_key = 'MINORITY_TOTAL_SHARES'), bad AS (SELECT a.stock_id FROM a JOIN b ON b.stock_id = a.stock_id WHERE abs(a.v - b.v) > b.v * 0.5), leaked AS (SELECT count(*) n FROM bad JOIN (SELECT iv0.* FROM indicator_value iv0 JOIN stock s0 ON s0.id = iv0.stock_id WHERE s0.market_code IN ('KOSPI','KOSDAQ')) v ON v.stock_id = bad.stock_id AND v.indicator_code = 'LARGEST_HOLDER_STAKE', latest WHERE v.trade_date = latest.d) SELECT CASE WHEN (SELECT count(*) FROM eff) = 0 THEN '잴 것이 없었다 — 소액주주 총수 사실이 하나도 없다' WHEN (SELECT count(*) FROM bad) = 0 THEN 'OK 지금 쓰이는 회계연도에 단위가 어긋난 종목이 없다' WHEN (SELECT n FROM leaked) = 0 THEN 'OK 어긋난 ' || (SELECT count(*) FROM bad) || '종목에 최대주주 지분율이 안 나간다' ELSE '어긋난 종목 ' || (SELECT n FROM leaked) || '개에 최대주주 지분율이 나가고 있다 — 대조 가드가 뚫렸다' END"
```

**주식 종류 어휘는 관측으로만 안다.** 소스가 같은 뜻을 `보통주`·`보통주식`·`의결권 있는 주식`으로
내고, 주식이 한 종류뿐인 회사는 `-`로 낸다. **허용목록이 좁으면 그 종목의 지표가 통째로 판정불가가
되는데 화면에는 「값 없음」으로만 보인다** — 실제로 005850이 배당성향 39.77%와 빈 배당수익률을
나란히 띄웠고 그런 종목이 22개였다. **그 무증상을 깨는 펜스는 ADR-0082에 있다** — 여기 있던 것은
값이 든 행만 세어 **무배당 결손을 원리적으로 못 잡았다.**

## 배경

버핏·린치 프리셋이 기업의 「질」을 재는데 **지배구조를 재는 지표가 하나도 없었다.** 스크리너가
답하지 못한 물음은 「이 회사의 지배구조가 주주 이익과 정렬돼 있는가」이고, 갈라 보면 넷이다 —
주주환원 · 이사회 독립성 · 소유 집중 · 회계 신뢰.

**ADR-0039와 어긋나지 않는다. 정확한 문장이 필요하다.** 그 문서가 모듈 B를 걷은 근거 셋 중
①(원본 명세가 지배하던 프리셋이 폐기됨)·②(계산 가능한 여덟은 이미 지표로 있음)는 소스가 열리든
말든 그대로 서고, ③(*공시·수급·소유구조 소스 전무*)도 **원본 명세의 32개 플래그**에는 대체로
옳다 — 카테고리 G는 G-01~G-05가 전부 미국 공시 기반이다. **정확한 문장은 「그 32개에 대한
판정이지 국내 거버넌스 데이터 일반에 대한 판정이 아니며, 32개 중 둘은 지금 닿는다」**다. 그 둘이
이 결정이 만드는 `AUDITOR_CHANGE_3Y`(G-04 감사인 교체)와 `DIRECTOR_TURNOVER_3Y`(G-01 경영진
교체)다. **앞 절반만 적으면 같은 문서가 자기 반례를 설명하지 못한다.**

**표를 가른 이유는 값 집합을 모르기 때문이다.** 개발가이드와 실제가 어긋난 자리가 넷이었다
(`alotMatter`의 `se` 설명이 다른 엔드포인트 것 · `hyslrChgSttus`가 누적 · `hmvAuditAllSttus`가
3개년이 아님 · `change_on`이 한국어 날짜). 원문을 담아 두면 그런 정정이 **수천 콜이 아니라
외부 호출 0인 잡 하나**로 끝난다.

**「지표 추가 비용 1파일」은 그대로다.** `IndicatorExtensibilityTest`와 Phase 1 판정이 그것을
단언한다. 채널을 여는 것은 **지표 추가가 아니라 원자료 축 추가**이고 — 업종(ADR-0044)·시장
지수(ADR-0053)가 같은 형태였다 — 축을 연 뒤에는 거버넌스 지표 추가가 다시 파일 하나다.

## 버린 대안

- **엔드포인트당 표를 여덟 만든다.** 응답 모양이 여덟 다 다르니 자연스러워 보인다. 그러나
  **값이 전부 콤마 섞인 문자열이거나 `-`**라 타입 컬럼으로 받아도 파싱을 앞당길 수 없고,
  값 집합이 미관측인 상태에서 스키마를 여덟 개 못박으면 정정마다 마이그레이션이 붙는다.
- **보통주 행만 `financial_statement`에 조인해 컬럼 몇 개를 더한다.** 고칠 파일이 훨씬 적다.
  그러나 주기가 다르고(분기 대 연 스냅샷) `se` 축이 접히며 **공시 시점 필터를 걸 자리가 없다** —
  이듬해 3월에 나온 값을 회계연도 말일에 알았던 것처럼 쓰게 된다.
- **채널을 선언 없이 항상 읽는다**(업종·지수의 선례). `DataRequirement`를 안 건드려 싸다.
  그러나 **ADR-0080이 그 형태를 이미 결손으로 적어 두었다** — 시장 지수가 선언이 없어
  「값이 없는 것」과 「입력을 못 읽은 것」을 못 가르고, 지수가 비면 `BETA_104W`·`EXCESS_RETURN_*`의
  옛 행이 지워진다. **같은 결손을 셋째 축으로 늘리지 않는다.**
- **유도 구현을 `collector`에 두고 벤더 클래스를 public으로 올린다.** 패키지 경계가 단순해진다.
  그러나 `Dart`가 그 패키지 코드에 등장해 ADR-0006의 MUST NOT을 어기고, **그 문서의 검증 grep이
  대문자 D로 시작하는 이름을 못 잡아 위반이 성립해도 통과한다.** 그 grep을 `[Dd]art`로 넓혔다.
- **최신 값 하나를 창 전체에 편다**(`FinancialMath.spreadOverTradeDates`). P19의 할인율 셋이
  그 형태다. 그러나 사업보고서는 이듬해 3월에 나오므로(실측 공시일 중앙값 2026-03-18)
  **공시 전 구간이 미래를 알고 계산한 값**이 된다. 계단 함수는 그 look-ahead를 원리적으로 없애고,
  대신 회계연도를 여러 해 받아야 창 앞부분이 찬다.
- **자사주 금액을 수량 × 주가로 만들어 총주주환원수익률을 합산한다.** Boudoukh 외(2007)의
  구성에 가장 가깝다. 그러나 소스가 금액을 안 주고(`tesstkAcqsDspsSttus`가 수량만이다)
  **「매입가 ≈ 현재가」를 재본 적이 없으며**, `phase-criteria` P14가 *「자사주로 배당을 대신 세지
  않는다」*를 명시로 건다. **배당과 자사주를 따로 두어 조건 빌더에서 함께 걸 수 있게** 했다.

## 결과

- **쉬워지는 것**: 유도 규칙이 틀린 것을 알았을 때 `--job=governance-derive` 한 번으로 끝난다
  (외부 호출 0). 새 거버넌스 지표는 계산기 파일 하나다.
- **어려워지는 것**: 표가 둘이라 「원문은 있는데 사실이 없다」는 상태가 가능하다 — 유도 잡을
  안 돌리면 지표가 조용히 빈다. 그리고 **적재는 화면에 버튼이 없다**(전량 8,050콜이 보수 예산의
  절반을 넘어 `AdminJobLauncher`가 `InitialLoadJob`을 뺀 근거에 걸린다).
- **워치리스트가 연 1회 계단으로 흔들린다.** `DailyUpdateJob`이 매일 판정을 돌리는데 거버넌스
  값은 사업보고서 공시일에 한꺼번에 바뀐다 — **공시 시즌(3월)에 판정이 무더기로 뒤집힐 수 있다.**
  **재보지 않았다** — 첫 시즌이 2027-03이라 지금은 관측할 수단이 없다.
- **되돌리려면**: 계산기 열셋을 지우고 `governance_*` 두 표를 드롭한다. 분류는 **enum에서 빼지
  않는다** — 비활성 행 하나로 카탈로그 조회가 통째로 터진다(ADR-0057).
