---
id: ADR-0124
status: 채택됨
date: 2026-09-04
applies_to:
  - "src/main/java/com/stockscreener/pipeline/datasource/us/SecEdgarFacts.java"
  - "src/test/java/com/stockscreener/pipeline/datasource/us/SecEdgarFinancialAdapterTest.java"
  - "src/test/java/com/stockscreener/pipeline/datasource/us/SecEdgarLabelDiffProbeLiveTest.java"
  - "src/test/resources/fixtures/sec-edgar/companyfacts-two-labels-before-load-window.json"
  - "src/test/resources/fixtures/sec-edgar/companyfacts-fiscal-year-crosses-new-year.json"
  - "src/test/resources/fixtures/sec-edgar/companyfacts-late-january-fiscal-year.json"
  - "src/test/resources/fixtures/sec-edgar/companyfacts-trailing-twelve-months-in-10q.json"
---

# ADR-0124. 기간 재구성의 결함이 셋이었다 — 판정 범위·회계연도 번호·「12개월 = 회계연도」

## 결정

`SecEdgarFacts`가 SEC의 팩트에서 기간을 재구성할 때 **서로 독립인 결함 셋**을 고친다.
ADR-0101 §버린 대안과 ADR-0099 §미룬 문이 이것을 「`labelOf`의 회계연도 배정」이라는
**하나의 문**으로 예약해 뒀는데, **재보니 셋이었고 가장 큰 것은 `labelOf`가 아니었다.**

1. **판정 범위** — 던지는 `labelPeriods`가 `fromFiscalYear` 필터보다 먼저 돌아
   **저장하지도 않을 옛 연도의 충돌이 종목 전체를 버렸다.** 판정을 적재 창 안으로 좁힌다.
2. **회계연도 번호** — `end.getYear()`를 그대로 써서 **신년을 넘는 결산의 두 해가 같은
   번호**를 받았다. `NEW_YEAR_DRIFT_DAYS = 4`만큼 물러서서 읽는다.
3. **「12개월 = 회계연도」** — `annualWindows`가 길이만 보고, `labelOf`의 연간 갈래는
   창을 아예 안 봤다. **회계연도 창은 연차보고서에서만** 만들고, 인정되지 않은 12개월
   창은 **명시적으로 버린다.**

**「145종목을 회수한다」가 결정이 아니다.** 수는 *지금 이 적재 창에서 이 순회 순서로 처음
만난 충돌*의 수일 뿐이고, 고친 것은 위 셋의 원리다.

## 규칙

- **MUST: 창 안의 라벨 충돌은 그대로 던진다.** 이 변경은 방어선을 낮추는 것이 아니라
  **지키려던 범위에 맞추는 것**이다. ADR-0089 §규칙의 *"어댑터의 자기 단언이 유일한
  방어선이다"*는 그대로다.
- **MUST: 회계연도 번호를 `fiscalYearOf`로 읽는다.** `end.getYear()`를 직접 쓰지 않는다.
- **MUST NOT: `NEW_YEAR_DRIFT_DAYS`를 재지 않고 넓히지 않는다.** 4는 여유가 아니라
  **빈 구간이라서 고른 값**이다. 넓히면 1월말 결산 회사(NVDA·WMT·RH)의 라벨이 조용히
  밀리고, 그 회사들은 스스로 그 해를 「fiscal 2023」이라 부르므로 **지금 라벨이 맞다.**
  좁으면 그 종목이 계속 거절될 뿐이라 **방향은 좁은 쪽이 안전하다.**
- **MUST: `ANNUAL_FORMS`를 `PERIODIC_FORMS`에서 빼서 유도한다.** 손으로 박으면 그쪽에만
  폼을 더한 사람이 그 제출자의 회계연도를 통째로 지운다. **반대로 중간보고 폼을 더할 때는
  `INTERIM_FORMS`에도 넣어야 한다** — 안 넣으면 유도가 그것을 연간으로 분류해 이 결함을
  되연다.
- **MUST: 인정되지 않은 12개월 창은 명시적으로 `null`로 끝낸다.** `labelOf`에는 `!annual`
  가드가 없어 조건만 붙이고 `else`를 빠뜨리면 **아래 분기 로직으로 흘러**
  `round(364/91.31)=4`가 되고, 12개월 값이 `Q4`로 저장된다. 그때
  `deriveFourthQuarters`가 「소스가 Q4를 직접 줬다」로 읽어 유도를 포기하므로
  **예외도 경고도 안 난다.**
- **MUST: 라벨 규칙을 바꾼 뒤 재적재하기 전에 옛 행을 지운다 — 술어로 좁히지 말고
  종목 단위로.** 새 라벨이 빈 키면 `FinancialWriter`가 INSERT하고 **옛 행이
  `is_latest`인 채 남아** 같은 `period_end`를 라벨 둘이 물어, `IndicatorDataReader`의
  TTM이 같은 분기를 두 번 더한다. **술어로 좁히면 `projectYear`가 만든 라벨을 놓친다** —
  실측으로 `KBR`·`KLC`·`SHIM`·`SVV`에 FY 행이 없는 `2027` 라벨이 8행 있었고, 그 8만
  새로 매핑되는 것이 없어 자동 강등도 안 된다.
- **MUST: `--financial-years`를 키워 재적재하면 그 실행에서 막힌 종목을 센다.**
  결함 1 이후 **`fromFiscalYear`가 `MALFORMED` 판정의 인자**라 기본 경로는 안전하지만
  (`initial-load`·`financial-refill` 둘 다 기본 3년) 연수를 키우면 창 안에 들어오는 라벨이
  늘어 같은 종목이 다시 막힐 수 있다.
  **다만 실측으로 발화하지 않았다** — 2026-09-14에 `financial-refill --universe=US_TOP1000
  --financial-years=11`(FY2015~2026)을 돌린 결과 **`MALFORMED` 0건**이고 미국 1,000종목이
  전부 재무를 가졌으며 연도별 행 수가 FY2015~2019 전 구간에서 늘었다.
  **MUST NOT: 이 경고를 「창을 키우지 마라」로 읽지 않는다** — 창을 좁게 두면 그 앞
  회계연도가 옛 규칙의 값으로 고정된다(ADR-0145). 키우고 나서 세는 것이 규칙이다.

## 검증

```bash
./gradlew test --tests "*SecEdgarFinancialAdapterTest*"
```

**대조군을 넷 다 실제로 되돌려 확인했다** — 그때마다 **정확히 하나씩만** 붉는다.

| 되돌린 것 | 붉는 시험 |
|---|---|
| 결함 1의 `label.fiscalYear() >= fromFiscalYear` | 「적재 창 밖의 라벨 충돌은 종목을 거절하지 않는다」 |
| `NEW_YEAR_DRIFT_DAYS`를 0으로 | 「신년을 넘는 결산의 두 해는 다른 라벨이다」 |
| `NEW_YEAR_DRIFT_DAYS`를 25로 **넓히면** | 「1월 말 결산 회사의 라벨은 안 바뀐다」 |
| 결함 3의 form 게이트 **또는** 연간 갈래 (각각) | 「10-Q의 12개월 열은 회계연도가 아니다」 |

**세 번째가 양방향 펜스의 한쪽이다.** 그 픽스처를 **01-25 결산**으로 만든 것은 그것이
**관측된 가장 이른 1월 결산**이기 때문이고, 따라서 이 대조군의 민감도가 25다.
1월 말(01-31)로 만들면 4에서 24까지 넓혀도 안 붉어 **펜스가 헐거워진다.**

**네 번째는 갈래가 둘인데 시험이 하나다** — 어느 쪽만 되돌려도 붉는 것이 그 시험의 값이다.
form 게이트만 걸면 롤링 창이 연간 갈래에서 여전히 `FY`를 만들고, 연간 갈래만 고치면
분기 번호가 롤링 창 기준으로 계속 틀린다.

```premise 같은 기간이 라벨 둘로 저장돼 있지 않다
psql -d stock_screener -At -c "SELECT CASE
  WHEN (SELECT count(*) FROM financial_statement f JOIN stock s ON s.id = f.stock_id
         WHERE s.market_code = 'US' AND f.is_latest AND f.fiscal_period <> 'FY') = 0
    THEN '잴 것이 없었다 — 미국 분기 행이 하나도 없다'
  WHEN count(*) = 0 THEN 'OK 같은 period_end를 라벨 둘로 든 종목 0'
  ELSE '같은 기간이 라벨 둘로 저장됐다: ' || count(*) || '건 — 라벨 규칙이 바뀐 뒤 옛 행을
        안 걷은 것이다. TTM이 같은 분기를 두 번 더하고 값이 그럴듯하다'
END
FROM (SELECT f.stock_id, f.period_end
        FROM financial_statement f JOIN stock s ON s.id = f.stock_id
       WHERE s.market_code = 'US' AND f.is_latest AND f.fiscal_period <> 'FY'
       GROUP BY 1, 2 HAVING count(*) > 1) d"
```

**MUST: 모집단을 `fiscal_period <> 'FY'`로 좁힌다.** 그 조건이 없으면 **오늘 이미
4,555를 낸다** — `deriveFourthQuarters`가 유도 Q4에 `fy.periodEnd()`를 그대로 쓰므로
**FY와 그 Q4는 언제나 `period_end`를 공유한다.** 분기 행끼리로 좁힌 기준선이 0이고
그것이 이 펜스가 성립하는 이유다. **의도한 동작에 우는 펜스는 침묵보다 나쁘다.**

## 배경

`AMZN`이 `US_TOP1000`에 없다는 물음에서 시작했다. 사슬은 `batch_execution_failure`에
그대로 있었다 — `같은 라벨에 기간이 둘이다: 2019/Q4 → 2019-01-01~2019-03-31 vs
2019-07-01~2019-09-30` → 재무 0행 → 주식수 없음 → `excluded.noShares` → 미편입.

**거기서 멈추면 증상만 고친다.** 145종목의 실패 메시지를 전수로 갈라 보니 원인이
셋이었고, 그중 **116종목은 저장되지도 않을 연도 때문에 죽고 있었다.** 그리고 거절되지
않은 종목에도 같은 뿌리의 오염이 있었다 — `KBR`은 회계연도 라벨이 한 칸씩 밀려
**FY2024가 통째로 비어 있었고**, `StockFinancialsAssembler`의 전년비교가 거기서
조용히 실패하고 있었다.

**결함 3의 가설은 소스로 확인했다.** `SecEdgarLabelDiffProbeLiveTest`가 회계연도 창이
어느 `form`에서 오는지 찍는데, `AMZN`은 창 69개 중 **52개가 10-Q**에서 왔고 그중
`2019-03-31`·`2019-09-30`이 그 종목을 거절시킨 두 창이다.

## 버린 대안

- **회계연도 번호를 기간 중간점의 연도로 낸다** — MSFT·NVDA가 한 칸씩 밀린다. 6월 결산이
  경계에 앉아 원리적으로 불안정하다.
- **1월 종료를 무조건 앞 해로 접는다** — NVDA·WMT·RH·HD·LOW가 전부 밀린다.
- **충돌하면 앞 창을 한 해 뒤로 민다** — 순서 의존이라 비결정적이고, 새 회계연도가 붙는
  것만으로 과거가 재번호된다.
- **충돌하면 던지지 말고 그 라벨만 버린다(`dropped++`)** — **145가 보인 유일한 이유가
  그 `throw`다.** 조용한 드롭으로 바꾸면 다음 결함은 아무도 못 본다.
- **저장 키에 `period_end`를 넣는다** — ADR-0101 §버린 대안이 마이그레이션까지 썼다가
  되돌렸다. `StockFinancialsAssembler`가 라벨을 키로 쓴다.
- **`STEX`(시작일이 같고 종료일만 1일 다른 쌍)까지 연다** — ADR-0101 §규칙이
  *"관용의 축을 「종료일이 같은가」로 둔다"*로 정한 것을 뒤집는 별도 결정이다.

## 결과

**쉬워지는 것**: 145종목의 재무가 열린다(그중 `AMZN`·`JNJ`가 ADR-0099 §검증의 대조군
안에 있다). ADR-0099 §미룬 문의 `companyconcept` 주식수 폴백과 ADR 판별축이 필요
없어진다.

**어려워지는 것**

- **안 열리는 것이 남고, 그것이 옳다.** 145 중 `C 5`는 회계연도 변경(`AYTU`·`PHUN`·
  `XCUR`·`RBC`)과 `STEX`이고, 152 중 나머지 7(`ETB`·`HEQ`·`HPF`·`HPI`·`HPS`·`HTD`·
  `JHS`)은 소스가 `{}`를 주는 폐쇄형 펀드라 **어떤 수정으로도 안 열린다.**
- **결함 3의 시끄러운 기여는 작다.** 대상 8 중 다섯(`ARTW`·`CVLG`·`HOFT`·`LAKE`·`SYM`)이
  **둘 다 진짜 회계연도인 결산월 변경**이라 form 게이트가 어느 쪽도 못 지운다. 이 결함을
  고치는 이유는 수가 아니라 **원리**다 — 「12개월이면 회계연도」가 남아 있으면 새 종목이
  들어올 때마다 같은 오인이 재생산되고, **롤링 열과 6월 결산 회계연도는 기간이 글자
  그대로 같아** `form` 말고는 가를 수단이 없다.
- **재적재가 따라온다.** 코드만 고치면 DB는 안 바뀐다. 그리고 라벨이 바뀐 종목은
  **재적재 전에 행을 걷어야** 한다(§규칙).
