---
id: ADR-0040
status: 채택됨
date: 2026-08-10
applies_to:
  - "src/main/java/com/stockscreener/pipeline/collector/FinancialWriter.java"
  - "src/main/java/com/stockscreener/pipeline/collector/FinancialCollector.java"
  - "src/main/java/com/stockscreener/pipeline/batch/FinancialRefillJob.java"
---

# ADR-0040. 결손 컬럼만 채우는 좁은 재수집 경로를 둔다

## 결정

`FinancialWriter`에 `WriteMode.FILL_MISSING`을 둔다. 같은 공시일의 재수집이면 **NULL인 금액
컬럼만** 소스 값으로 채우고, **값이 있는 컬럼은 절대 덮지 않는다.** 이 모드는 새 잡
`financial-refill`에서만 켜지며 일일·초기 적재는 `APPEND_ONLY` 그대로다.

## 규칙

- **MUST: 컬럼을 더하거나 매핑을 고친 뒤의 첫 채우기는 창을 `maxFinancialYears()`까지 준다**
  (ADR-0145). **이 모드는 「NULL인 컬럼을 채운다」이지 「모든 해를 본다」가 아니다** —
  `fromFiscalYear`보다 앞선 회계연도는 이 잡이 **한 행도 열지 않으므로** 새 컬럼이 영원히
  NULL로 남는다. 손으로 준 창이 지표 창(7년)보다 좁아서 실제로 넷이 그렇게 남았다.
- MUST: **공시일이 정확히 같을 때만** 채운다. 더 이른 판으로 채우면 행 하나가 두 공시의
  혼합이 되어 "그 시점에 공개되어 있던 값"을 물을 수 없게 된다 — 이 클래스의 존재 이유가
  그 질문이다.
- MUST NOT: 값이 있는 컬럼을 덮지 않는다. 다르면 `WARN`으로 남긴다. 조용히 넘기면 소스의
  오류인지 우리 파싱의 오류인지 영영 알 수 없다.
- MUST: 값 비교는 `BigDecimal.compareTo`다. 컬럼이 `numeric(24,2)`라 저장값은 scale 2로
  돌아오는데 `DartAccountMapper.parseAmount`는 scale 0을 준다. **`equals`로 비교하면 같은 값이
  전부 "달라졌다"로 잡혀** 경고가 수만 줄 나오고 가짜 신호가 된다.
- MUST: 채움 대상 컬럼을 `FinancialWriter.Amount` 한 곳에만 적는다. `SELECT`와 `SET`이 둘 다
  거기서 만들어지므로 한쪽만 늘어 어긋나는 일이 생기지 않는다.
- MUST NOT: `FILL_MISSING`을 일일·초기 적재 경로에 켜지 않는다. 그 경로의 계약은
  "재수집은 아무것도 하지 않는다"이고, 그것이 정정공시 판별의 전제다.
- MUST: **무효화 생략은 채운 컬럼의 소비 지표에 저장된 값이 없을 때만 성립한다.** 아래
  §무효화를 읽고 **매 실행마다 다시 답한다.** 이 잡의 다음 용도로 예정된 것이
  `operating_cash_flow`인데(H-05), 그때는 전제가 다를 수 있다.

## 말하지 않으면 사라지는 전제

**이 설계 전체가 "재수집 시 `publishedAt`이 기존과 같다" 위에 선다.** 참인 이유는
`DartFinancialAdapter.publishedAt`이 `rcept_no` 앞 8자리에서 유도하는 **결정적** 값이기
때문이다. 만약 그것이 `LocalDate.now()`류였다면 재수집이 전부 정정공시 경로를 타서 개정본을
쌓고 `IndicatorInvalidationPort`를 종목마다 부른다 — **이 결정이 통째로 뒤집힌다.**
소스나 어댑터를 바꿀 때 이 성질이 유지되는지 먼저 본다.

## 무효화 — 부르지 않는다. 그리고 그것이 더 안전하다

이유가 둘이고 각각 단독으로 충분하다.

1. **지울 낡은 값이 없다.** 금액을 소비하는 지표는 전부 null 전파형이다 —
   `FinancialMath.sumTrailingFourQuarters`는 한 분기라도 null이면 null을 내고, 계산기는 그때
   행을 아예 쓰지 않는다(ADR-0002). 즉 채우는 것은 "틀린 값 → 다른 틀린 값"이 아니라
   **"행 없음 → 행 생김"** 이다.
2. **부르면 오히려 파괴적이다.** `StoredIndicatorInvalidationPort`는 지표를 가리지 않고
   **그 종목의 `trade_date >= fromDate` 전체**를 지운다. 회계기간 시작일(2023-01-01 등)로
   전 종목에 부르면 지표 33종의 보존 창이 통째로 날아가고, **백분위는 날짜별로만 계산되므로
   단일 거래일 재계산으로 되살아나지 않는다.**

## 검증

```bash
./gradlew test --tests "*FinancialWriterTest*"
```

`결손 채움` 중첩 클래스가 여섯을 고정한다 — 같은 공시일의 NULL을 채우는지, 값이 있는 컬럼을
그대로 두는지, **scale만 다른 같은 값을 불일치로 세지 않는지**, 더 이른 공시본으로는 채우지
않는지, 채울 것이 없으면 행을 건드리지 않는지, 기존 행이 없으면 모드와 무관하게 INSERT하는지.

`FILL_MISSING`이 결손 보정 잡 밖으로 새지 않았는지 — `FinancialRefillJob` 한 곳만 나와야 한다:

```bash
rg -n 'FILL_MISSING' src/main/java
```

```premise 최신 분기 재무에 유동자산이 채워져 있다
psql -d stock_screener -At -c "WITH q AS (SELECT max(f.period_end) AS pe FROM financial_statement f JOIN stock s ON s.id = f.stock_id WHERE s.market_code IN ('KOSPI','KOSDAQ') AND f.is_latest AND f.fiscal_period <> 'FY' AND f.period_end <= CURRENT_DATE), s AS (SELECT count(*) AS n, count(fs.current_assets) AS filled FROM financial_statement fs JOIN stock s ON s.id = fs.stock_id, q WHERE s.market_code IN ('KOSPI','KOSDAQ') AND fs.is_latest AND fs.fiscal_period <> 'FY' AND fs.period_end = q.pe AND fs.period_end <= CURRENT_DATE) SELECT CASE WHEN n = 0 THEN '최신 분기 재무 행이 0건이다 — 재무 수집이 비었거나 period_end가 잡히지 않는다' WHEN filled = 0 THEN '최신 분기 ' || n || '행의 유동자산이 전량 NULL이다 — 결손 보정이 돌지 않았다(ALTMAN_Z가 0건이 된다)' WHEN filled::numeric / n < 0.5 THEN '최신 분기 ' || n || '행 중 ' || filled || '행만 채워졌다 — 금융업 결손만으로는 설명되지 않는다' ELSE 'OK 최신 분기 ' || n || '행 중 ' || filled || '행에 유동자산이 있다' END FROM s"
```

**분기를 못 박지 않고 쿼리가 최신 분기를 스스로 고르는 것이 요점이다.** `fiscal_year=2026 AND
fiscal_period='Q1'`으로 고정하면 다음 분기가 들어와도 계속 낡은 분기를 재고 `OK`를 낸다 —
전제가 답해야 할 질문("아직 옳은가")과 정반대가 된다.

**0의 갈래를 셋으로 가른다.** ① 그 분기 행이 0건 ② 행은 있는데 전량 NULL ③ 일부만 채워짐.
①과 ②를 묶으면 "재무가 통째로 없다"와 "컬럼만 안 채워졌다"가 같은 문장이 되는데, 처방이 다르다
(전자는 `initial-load`, 후자는 `financial-refill`).

임계 0.5의 근거: **금융업은 유동/비유동을 구분하지 않는 재무상태표를 내므로 NULL이 정상이다**
(`docs/api-spec/dart-financial.md`의 「단일회사 주요계정」 절). 유니버스에서 그 비중은 소수이므로 절반 미만이면 다른
원인이 있다는 뜻이다. 보정 직후 실측 기준선은 아래 §결과에 적는다.

## 배경

V16이 `current_assets`·`current_liabilities`·`retained_earnings` 세 컬럼을 더하면서 **"값은
재수집 때 채워진다"** 를 전제했다. 그 전제가 틀렸다 — `writeOne`이 같은 공시일을 재수집으로
판정해 **아무것도 하지 않고 0을 돌려주기** 때문이다. 결과로 10,882행 전부가 NULL로 남았고,
`AltmanZCalculator`가 셋 중 하나라도 NULL이면 빈 목록을 내므로 `indicator_value`에 `ALTMAN_Z`가
**한 행도 없었다.** 그것이 `SWING_MIDTERM_KR`의 필수 조건이라 전 종목이 판정불가로 빠져
프리셋이 0건을 냈다(ADR-0038: 판정불가 = 필수의 미충족).

`--reload-existing=true`로 DART를 1만 콜 넘게 태워도 한 행도 바뀌지 않는다는 것이 이 결함의
성질이다 — 잡은 성공으로 끝나고 로그에도 흔적이 없다.

## 버린 대안

- **행을 지우고 재적재한다** — 가장 단순하다. 그러나 `is_latest`·`revision_no`가 담고 있는
  "그 시점에 공개되어 있던 값"이 파괴되고, 그것은 어떤 마이그레이션으로도 복구할 수 없다.
  지금은 전 행이 `revision_no = 0`이라 잃을 이력이 없어 보이지만, **잃을 것이 없다는 사실이
  규칙을 깨는 근거가 되면 다음에는 잃을 것이 있을 때 같은 판단을 한다.**
- **`published_at`을 밀어 정정공시로 위장한다** — 코드 변경이 0에 가깝다. 공시일을 거짓으로
  만들어 정정공시 판별의 근거 자체를 오염시키고, 가짜 개정본이 쌓인다.
- **잡 안에서 직접 `UPDATE`를 날린다** — `FinancialWriter`를 건드리지 않는다. 그러나 쓰기
  로직이 두 곳이 되고, 두 곳은 반드시 어긋난다(`CLAUDE.md`).
- **재수집이 언제나 덮게 한다** — 위 `MUST NOT` 자체다. 값이 달라진 것을 조용히 덮으면
  소스의 오류와 우리 파싱의 오류를 영영 구분할 수 없다.
- **`initial-load`에 플래그를 하나 더 단다** — 잡을 새로 만들지 않아도 된다. 그러나 그 잡의
  대상 선정은 `NOT EXISTS(price_daily)`이고 시세를 함께 받는다. 결손 보정은 대상도 단계도
  다르고, 섞으면 "지금 무엇을 하는 잡인가"를 플래그 조합으로 읽어야 한다.

## 결과

- **쉬워지는 것**: 재무 컬럼을 더한 뒤 값을 채우는 경로가 생긴다. 이것은 마지막이 아니다 —
  영업활동현금흐름(H-05)이 이미 같은 자리를 기다린다.
- **어려워지는 것**: 같은 회계기간에 대해 쓰기 경로가 둘이 되었다. `publishedAt` 비교가
  세 갈래(이전 / 같음 / 이후)로 늘었고, 그 셋을 테스트가 고정한다.
- **화면에서 보이지 않는 것**: `financial-refill`은 `AdminJobLauncher` 카탈로그에 넣지 않았다
  (그 클래스가 `InitialLoadJob`을 뺀 이유와 같다 — DART 쿼터를 태우는 잡을 버튼 하나로 두지
  않는다). 그래서 **실행 이력 표에는 뜨지만 잡 필터 드롭다운에서는 고를 수 없다.**
  `initial-load`가 이미 그 상태다.
- **되돌리려면**: `WriteMode`와 `fillMissing`, `FinancialCollector`의 3-인자 오버로드,
  `FinancialRefillJob`, `BootstrapRunner`의 분기를 함께 걷는다. 저장된 데이터는 그대로 남는다.

### 보정 직후 실측 기준선 (2026-08-10)

전 종목 1회 실행: **350종목 · 약 5,600콜 · 10분 10초 · 실패 0 · 불일치 0.**

| 잰 것 | 값 |
|---|---|
| 최신 분기 `is_latest` 행 | 354 |
| 그중 `current_assets`가 채워진 행 | **322 (91.0%)** |
| 그중 `retained_earnings`가 채워진 행 | 345 (97.5%) |
| 결손으로 남은 32행의 정체 | **금융업 26 + 유니버스 미편입 6** |
| `ALTMAN_Z`가 계산되는 종목 | 306 (편입 350 중 미계산 44) |

**premise의 임계 0.5는 실측 0.91에서 멀리 떨어져 있다.** 절반이 무너져야 발화하므로 잡음이
아니라 "통째로 안 돌았다"만 잡는다 — 의도한 감도다.

**불일치 0건이 `compareTo` 비교의 증거다.** `equals`였다면 저장값(scale 2)과 소스값(scale 0)이
전부 달라 보여 수만 건이 나왔을 것이다.

**유니버스 미편입 6종목은 이 잡의 대상이 아니다.** 대상 선정이 `universe_membership` 기준인데
프리셋의 모집단은 `stock.is_active`(356)라 **여섯 종목이 구조적으로 보정 밖에 있다.** 지금은
그 종목들이 지표 부족으로 어차피 결과에 못 들어오지만, 두 집합이 다르다는 사실 자체가 함정이다.

**재무가 한 행도 없던 6종목은 채움이 아니라 신규 INSERT로 들어왔다**(87행). `findLatest`가
null을 내면 모드와 무관하게 `insert()`로 가기 때문이며, 로그가 `신규=`와 `채움=`을 나눠 세는
이유가 그것이다.
