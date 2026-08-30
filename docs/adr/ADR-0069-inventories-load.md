---
id: ADR-0069
status: 채택됨
date: 2026-08-18
applies_to:
  - "src/main/java/**/pipeline/datasource/kr/DartFullAccountMapper.java"
  - "src/main/java/**/pipeline/datasource/kr/DartFullStatementAdapter.java"
  - "src/main/resources/db/migration/V30__financial_inventories.sql"
  - "src/test/java/**/DartInventoryProbeLiveTest.java"
---

# ADR-0069. 재보지 않은 채 결손으로 적혀 있던 재고자산을 재서 적재하고, 무형자산은 갈라 재기만 한다

## 결정

**재고자산을 적재한다.** 저장소가 이 계정을 한 번도 재본 적이 없으면서 `phase-criteria.md`가
*"재고자산이 없어 값이 안 난다"*라고 **원자료의 부재로** 적고 있었다. 재보니 열려 있다
(`dart-financial.md`의 「계정별 가용성」 절). `financial_statement.inventories` 컬럼을 더하고
`full-statement-refill`로 채운다. **새 엔드포인트가 아니다** — 이미 쓰고 있는
`fnlttSinglAcntAll` 응답 안에 있었고 매퍼가 읽지 않았을 뿐이다(ADR-0062·0067과 같은 형태).

**같은 응답에서 무형자산 표준코드 둘과 영업권을 갈라 쟀지만 적재하지 않는다.** §13이 둘을 한
칸에 묶어 8/8이라는 과대 판정을 내고 *"갈라 다시 잰다"*는 MUST를 미이행으로 남겨 두었다. 추가
API 콜이 0이므로 함께 쟀고, 결과와 합산 규칙은 §14에 있다. **적재는 3.6(유형자본)을 열 때 한다**
— ADR-0067이 3.6을 미루기로 이미 결정했다.

**지표는 만들지 않는다.** 린치 3.14(재고 증가율 대 매출 증가율)의 원자료가 놓였을 뿐이고,
**적재만으로는 화면이 한 글자도 바뀌지 않는다**(ADR-0040).

## 규칙

- **MUST NOT: 결손을 「소스가 주지 않는다」로 적기 전에 재보지 않은 것이 아닌지 확인한다**
  (ADR-0067 승계). 이 ADR은 그 MUST에 걸린 **두 번째** 사례다. 자본적지출과 재고자산이 연달아
  같은 자리에서 열렸다 — **재서 닫은 판정과 재보지 않은 추정이 같은 문장 형태로 앉으면 다음
  세션은 둘을 구별하지 못한다.**
- **MUST: 상태를 셋으로 가른다.** 「재보지 않았다」·「재서 닫혔다」·**「재서 열려 있으나 적재하지
  않았다」**. 셋째가 이번에 생겼고(무형자산 둘·영업권), 이름이 없으면 다음 세션이 그것을 결손으로
  읽는다. 표는 §14에 있다.
- **MUST: 재고자산을 재무상태표에서만 읽는다.** 실측이 무는 것을 셋 보여 줬다 — 현금흐름표의
  `재고자산의 감소(증가)`는 **증감분**이라 잔액으로 저장하면 「재고 증가율」이 *증감의 증감*이
  되고, 같은 표의 `재고자산평가손실`은 손익 조정이며, 재무상태표의 `상품권`(이마트 1.7조)은
  재고와 무관한데 **계정명 부분일치에 걸린다.**
- **MUST NOT: 계정명 폴백을 두지 않는다.** 위 마지막 행이 그 이유다. 표준코드는 일곱 회사가
  전부 `ifrs-full_Inventories` 하나였고 폴백이 메울 것이 관측되지 않았다.
- **MUST: 분기 잔액 행에도 담는다.** 재고자산은 **저량**이라 `toFourthQuarterBalance`가 채우는
  자리이며(현금및현금성자산·이익잉여금과 같다), 유량인 `capex`와 갈리는 지점이다. 빠뜨리면
  **4분기 행만 조용히 NULL이 된다.**
- **MUST NOT: capex의 「크기로 읽는다」를 여기 복사하지 않는다.** 그 처리는 「취득」이 **한 방향
  항목**이라 안전했던 것이다. 재고자산은 저량이라 **음수가 원리적으로 없고**, 음수가 오면 그것은
  표기가 아니라 **이상**이다.
- **MUST NOT: 결손을 0으로 대체하지 않는다.** 금융업은 이 계정이 원리적으로 없어 NULL이
  정상이다. 0으로 두면 **재고가 없는 회사**가 되어 값이 그럴듯하게 틀린다.
- **MUST NOT: 한 계정의 업종 부재 범위를 다른 계정에 복사하지 않는다.** 매출원가는 금융·서비스
  **둘**이 없고(§12), 재고자산은 서비스업도 낸다 — **NAVER가 202.4억을 낸다.**
  「업종상 없을 것」을 결손 사유로 미리 적으면 이런 자리에서 틀린다.
- **MUST NOT: 금융 중분류를 「재고자산이 없는 업종」으로 세지 않는다.** 전수에서 **192행 중
  107행(55.7%)이 채워졌다** — KSIC 64가 금융업과 **지주회사**를 겸하고, 지주회사는 연결에 사업
  자회사가 들어와 재고자산이 있다. **코드로도 못 가른다**(있는 쪽과 없는 쪽이 `649`·`64992`를
  함께 쓴다). 커버리지 펜스가 금융 중분류를 **모집단에서 빼되 결손으로 단정하지 않는** 이유다.
- **MUST NOT: 두 무형자산 표준코드를 더하지 않는다.** 배타적이다(동시 출현 0/8). 합산 규칙은
  §14에 있고, 한 칸에 묶으면 **나눠 내는 회사에서 영업권이 통째로 빠져** 유형자본이 과대해진다.

## 검증

```bash
rg -n "결손이 아닌 유일한 계정" src/main/java/com/stockscreener/pipeline/datasource/kr/DartFullAccountMapper.java
```
아무것도 반환하지 않아야 한다 — 매출원가 javadoc이 자기를 **유일한** 사례라고 부르던 문장은
재고자산이 둘째가 되면서 거짓이 됐다. **낱말 하나가 거짓이 되는 자리만 본다**(ADR-0067이
`name = "…오너"`에서 같은 형태를 쓴 이유다 — 낱말을 통째로 세면 부정문이 전부 발견으로 잡혀
펜스가 처음부터 빨갛고, 시끄러운 펜스는 곧 무시된다).

```bash
rg -n "ID_INVENTORIES" src/main/java/com/stockscreener/pipeline/datasource/kr/DartFullStatementAdapter.java
```
**두 줄**이어야 한다 — `toReport`와 `toFourthQuarterBalance` 둘 다. 한 줄이면 저량을 유량처럼
다룬 것이고 **4분기 행이 조용히 빈다.**

**모집단을 국내로 좁힌다.** 금융업 제외가 `industry_division`(KSIC)으로 되어 있고 미국은
그 컬럼이 NULL이라(체계가 SIC다 — ADR-0092) `ksic IS NULL` 갈래로 **전부 모집단에 들어온다.**
미국 재무를 적재하자 커버리지가 44%로 떨어져 이 전제가 울었다(2026-08-30) — 국내 계정
매핑은 그대로인데 분모만 바뀐 것이다. **미국 재고자산을 재려면 SIC 기준으로 별도 전제를 만든다.**

```premise 재고자산이 재무제표에 채워져 있고 그 결손이 금융업에 몰려 있다
psql -d stock_screener -At -c "
WITH fy AS (
  SELECT f.inventories, s.industry_division AS ksic
    FROM financial_statement f JOIN stock s ON s.id = f.stock_id
   WHERE f.fiscal_period = 'FY' AND f.is_latest AND f.fiscal_year >= 2021
     AND s.market_code IN ('KOSPI', 'KOSDAQ')),
pop AS (SELECT * FROM fy WHERE ksic IS NULL OR ksic NOT IN ('64','65','66'))
SELECT CASE
  WHEN (SELECT count(*) FROM fy) = 0
    THEN '잴 것이 없었다 — FY2021 이후 재무제표 행이 하나도 없다'
  WHEN (SELECT count(*) FROM pop) = 0
    THEN '잴 것이 없었다 — 금융업을 뺀 모집단이 비었다(업종 코드가 안 채워졌다)'
  WHEN (SELECT count(inventories) FROM pop) = 0
    THEN '컬럼만 생기고 재적재가 돌지 않았다 — full-statement-refill이 필요하다(ADR-0062의 함정)'
  WHEN (SELECT count(inventories)::numeric / count(*) FROM pop) < 0.70
    THEN '금융업을 뺀 재고자산 커버리지가 '
         || (SELECT round(100.0 * count(inventories) / count(*)) FROM pop)
         || '%로 70% 아래다 — 계정 매핑이나 재적재를 의심한다'
  ELSE 'OK 금융업 제외 '
       || (SELECT round(100.0 * count(inventories) / count(*)) FROM pop) || '% ('
       || (SELECT count(inventories) FROM pop) || '/' || (SELECT count(*) FROM pop)
       || '행). 금융 중분류는 '
       || (SELECT count(inventories)||'/'||count(*) FROM fy WHERE ksic IN ('64','65','66'))
       || '행인데 절반이 지주회사라 정상이다'
END"
```

```premise 재고자산이 음수로 오지 않는다
psql -d stock_screener -At -c "
SELECT CASE
  WHEN count(*) FILTER (WHERE inventories IS NOT NULL) = 0
    THEN '잴 것이 없었다 — 재고자산이 채워진 행이 하나도 없다'
  WHEN count(*) FILTER (WHERE inventories < 0) = 0
    THEN 'OK 음수 0행 (' || count(*) FILTER (WHERE inventories IS NOT NULL) || '행을 봤다)'
  ELSE count(*) FILTER (WHERE inventories < 0)
       || '행이 음수다 — 재고자산은 저량이라 표기 관례가 아니라 이상이다(capex와 반대다)'
END FROM financial_statement WHERE is_latest"
```

```premise Q4 유도가 사업보고서의 재고자산을 잃지 않았다
psql -d stock_screener -At -c "
WITH cur AS (SELECT DISTINCT um.stock_id FROM universe_membership um
               JOIN universe u ON u.code = um.universe_code
              WHERE u.is_active AND um.effective_to IS NULL),
p AS (SELECT f.stock_id, f.fiscal_year,
        max(f.inventories) FILTER (WHERE f.fiscal_period = 'FY') fy,
        max(f.inventories) FILTER (WHERE f.fiscal_period = 'Q4') q4,
        count(*) FILTER (WHERE f.fiscal_period = 'Q4') q4rows
      FROM financial_statement f JOIN stock s ON s.id = f.stock_id
      JOIN cur ON cur.stock_id = f.stock_id
      WHERE f.is_latest AND f.fiscal_year >= 2021
        AND s.market_code IN ('KOSPI', 'KOSDAQ') AND f.fiscal_period IN ('FY', 'Q4')
      GROUP BY 1, 2)
SELECT CASE
  WHEN (SELECT count(*) FROM p WHERE q4rows > 0) = 0
    THEN 'OK 분기 잔액 경로(--quarters=true)가 아직 안 돌아 잴 것이 없다'
  WHEN (SELECT count(*) FROM p WHERE q4rows > 0 AND fy IS NOT NULL AND q4 IS NULL) = 0
    THEN 'OK 사업보고서에 재고가 있는 ' || (SELECT count(*) FROM p WHERE q4rows > 0 AND fy IS NOT NULL)
         || '쌍이 Q4에도 있다'
  ELSE (SELECT count(*)::text FROM p WHERE q4rows > 0 AND fy IS NOT NULL AND q4 IS NULL)
       || '쌍이 FY에는 있는데 Q4에 없다 — toFourthQuarterBalance의 재고자산 자리를 의심한다'
END"
```

**개수를 견주지 않고 「같은 (종목, 연도)에서 FY에 있던 값이 Q4에 있는가」를 묻는다.** 처음에는
`count(inventories) < count(cash_and_equivalents)`로 셌는데 **114행이 영구 거짓 발견이었다** —
**재고 개념이 없는 업종**(금융업 등)이 있는 한 그 부등호는 언제나 참이기 때문이다.
실측(2026-08-31)으로 재고 채움 비율이 **FY 92.2% · Q4 92.2%로 완전히 같아**, 유도는 원본을
하나도 잃지 않고 있었다.

**모집단은 「현재 편입 중인 종목」이다.** `full-statement-refill`의 대상이 현재 편입 종목이라
(`resolveTargets`) **이탈 종목의 분기는 애초에 받지 않으며 그것이 설계대로다.** 좁히지 않으면
리밸런싱이 돌 때마다 빠진 종목이 거짓 발견으로 뜬다 — 실측으로 분기에 전체본이 통째로 안 닿은
14종목이 **전부 유니버스 밖**이었다(`effective_to`가 2026-08-21·08-28의 두 리밸런싱).

**시장을 가르는 것이 셋째 펜스의 일부다.** 이 펜스가 재는 것은 `toFourthQuarterBalance`,
곧 **DART 어댑터의 코드 경로**다. 미국은 Q4를 다른 자리에서 유도하고(`SecEdgarFacts`,
ADR-0089) 재고가 원리적으로 없는 업종이 표본에 섞여 있어, 좁히지 않으면 **의도한 동작에
우는 펜스**가 된다 — 실제로 미국 재무를 넣자 국내 기여가 0인데 차이가 10행으로 붉어졌다
(ADR-0013의 `listed_shares` 펜스가 같은 이유로 같은 모양이다). **미국 쪽 결손은
ADR-0089의 전제가 따로 받는다.**

**셋째 펜스가 절대수가 아니라 형제 저량과의 대조인 이유**는 그렇지 않으면 **영원히 빨갛기**
때문이다. 둘 다 0일 때 `OK`를 내는 것도 같은 이유이며 **침묵을 건강으로 위장하는 것이 아니다** —
이 펜스가 묻는 것은 「재고자산이 형제보다 덜 채워졌는가」이고, 둘 다 0이면 그 답은 **참**이다.
사유를 문장에 담아 두어 사람이 읽으면 이유를 알 수 있게 했다. Q4 잔액 행은 `--quarters=true`일 때만 유도되는데 전 종목 분기는 콜이 4배라 돌지
않는다 — 실제로 `cash_and_equivalents`(V27)도 **0/1,604**이다. 절대수로 물으면 이 펜스는 우리가
안 돌린 잡을 결함으로 신고하고, **시끄러운 펜스는 곧 무시된다**(ADR-0066이 `SHARES_GROWTH_3Y`에서
밟은 함정). 대조로 물으면 **매핑이 실제로 빠졌을 때만** 운다.

**임계 70%는 결과를 보기 전에 정했고 capex(ADR-0067)와 같은 값이다.** 범위를 안 정하면 값이
움직였을 때 다음 세션이 **전제 대신 펜스를 고치는 쪽**으로 간다. **모집단에서 금융업을 뺀 것이
이 펜스의 핵심**이다 — 안 빼면 원리적 부재가 커버리지를 끌어내려 정상이 실패로 보인다.

## 배경

**저장소가 한 번도 재보지 않은 것을 결손으로 적고 있었다.** 탐침 표 §7(4개사)·§12(8개사)·
§13(8개사) 어디에도 재고자산이 없고 `src/`에 후보 ID조차 없었는데, `phase-criteria.md`가
*"재고 대 매출 증가율은 `IndicatorRef`로 쓸 수 있지만 **재고자산이 없어 값이 안 난다**"*라고
적었다. 같은 파일이 바로 그 구분을 요구한다 — *"도구의 한계인지 원자료의 부재인지 갈렸다.
둘은 다르다 — 앞은 우리에게 열리고 뒤는 안 열린다."*

**§12·§13과 같은 8종목·8콜로 재보니 7/8이 왔다.** 빠진 하나가 KB금융이고 **그 회사 재무상태표에는
재고 계열 행이 아예 없다** — 결손이 아니라 업종이다. 표준코드는 일곱이 전부
`ifrs-full_Inventories`이고 계정명만 갈린다(`재고자산`·`유동재고자산`). 부호는 일곱 다 양수다.

**「우리가 안 찾은 것 아닌가」에 축 둘로 답했다.** 코드 축(BS에서 `account_id`에 `Inventor`가 든
행 전부)이 **여덟 회사 모두 한 줄뿐**임을 보였다 — 후보 부족이 아니라 이 엔드포인트가 내놓는
재고 표준코드가 애초에 하나다. 이름 축(전 `sj_div`)은 좁히지 않았을 때 무는 것을 보여 줬다.

**적재까지 간 이유는 이 저장소에서 「측정만 하고 저장하지 않으면 재검증이 원리적으로
불가능하다」는 것이다.** `check-premises.sh`가 `docs/adr/`만 훑으므로 실측 결과를 api-spec에만
적으면 **그 수가 언제 낡았는지 아무도 모른다.** capex의 커버리지는 ADR-0067의 `premise`가 매
세션 다시 재고 있다. 덧붙여 소비 지표가 **증가율**이라 2개년이 필요한데, 연도별 안정성은
재적재만 답한다.

**ADR-0067이 법인세비용을 적재하지 않은 사유는 「소비 지표가 없어서」가 아니다** — `ROIC_PRETAX`는
그 phase 안에 있었다. 그 문서가 적은 실제 사유는 *"세후로 바뀌어도 순위가 크게 달라지지 않기
때문"*과 *"세전이라는 것이 이름에 있으므로 나중에 승격해도 거짓이 남지 않는다"*였다. 재고자산은
둘 다 반대편이다 — 프리셋에서 **통째로 빠진 조건**이고, 미적재를 말하는 문구를 그대로 두면 다음
세션이 **또 재본다.**

**전수가 표본이 답할 수 없던 것을 답했다.** 금융 중분류를 뺀 커버리지가 **91.6%**(1,310/1,430,
capex의 88%와 같은 수준)이고 **음수는 0행**이며 FY2021~2025에서 비율이 흔들리지 않는다 — 소비
지표가 증가율이라 2개년이 동시에 있어야 하는데 그 분포가 그것을 답한다. **표본이 못 본 것이
하나 나왔다** — 금융 중분류의 55.7%가 채워진다(지주회사). 표본의 KB금융은 순수 금융이라
그 갈래가 보이지 않았다.

**무형자산은 §13의 오진을 정정했을 뿐 열지 않았다.** 갈라 재니 `IntangibleAssetsOtherThanGoodwill`
1/8, `IntangibleAssetsAndGoodwill` 7/8, `Goodwill` 1/8이고 **두 코드가 배타적**이라 유형자본
합산 규칙이 나왔다. §13의 8/8은 그 둘의 합이었다.

## 버린 대안

- **적재하지 않고 350콜짜리 전수 탐침으로 커버리지만 재기** — 재적재의 6분의 1 비용으로 전수
  답을 얻는 것처럼 보였다. 셋이 깼다. ① 소비 지표가 **증가율**이라 2개년이 필요한데 단년
  탐침은 교집합의 상한만 준다(2개년이면 700콜이고 6년 2,100콜과 자릿수가 같다). ② 결과가
  **표준출력에만 남아** `premise` 펜스를 놓을 자리가 없다. ③ 그 스위치로 쓰려던
  `-DprobeTickerFile`이 **테스트 JVM에 도달하지 않는다** — `liveTest`에 `systemProperty` 설정이
  한 줄도 없어 **8콜을 돌면서 초록으로 끝난다.**
- **무형자산 둘을 함께 적재하기** — 재적재는 컬럼 수와 무관하게 2,100콜이라 같이 태우는 것이
  싸 보인다. 그러나 소비처인 3.6을 ADR-0067이 이미 미뤘고, **컬럼 하나가 여섯 자리를 함께
  움직인다**(ADR-0062). 합산 규칙이 §14에 적혔으므로 그때 재측정 없이 붙일 수 있다.
- **이자비용을 같은 탐침에서 함께 재기** — 콜이 0 추가이고 §7의 `1/4`이 **4개사 표본**에만
  근거해 후보였다. `Target.sjDiv`가 **단일 값**이라 `IS`+`CIS` 둘을 못 보는데, 손익이 `IS`에만
  있다고 가정하면 안 된다(§11). `IS`로만 좁히면 1~2건이 나오고 그것이 **"1/4이 재확인됐다"로
  오독된다** — 넣은 이유가 정확히 뒤집힌다. §14에 다음 후보로 이름만 남겼다.
- **재고자산 구성 계정(상품·제품·재공품·원재료)을 후보에 넣기** — 총계 없이 구성만 내는 회사가
  「열렸다」로 잡히면 **합산 규칙이 회사마다 갈린다.** 나열로만 다뤘고, 표본에서 구성을 내는
  회사가 없었다.
- **`phase-criteria.md:449`를 고치기** — 완료된 phase의 판정 기준이라 현황에 맞춰 고치면
  **기준이 조용히 느슨해진다.** ADR-0067의 탐침 커밋도 같은 이유로 그 파일을 건드리지 않았다
  (그 파일 455행은 FCF가 열린 뒤에도 결손으로 적혀 있다). 현재 사실은 §14와 이 ADR이 답한다.

## 결과

린치 3.14의 **원자료가 놓였다.** 지표는 아직 없으므로 **화면은 한 글자도 바뀌지 않는다** —
`IndicatorRef` 조건 하나로 표현되려면 계산기와 프리셋 조건이 따로 필요하다.

**§13의 미이행 MUST가 닫혔다** — 3.6은 여전히 안 열지만, 열 때 **재측정 없이** §14의 합산
규칙만 쓰면 된다.

**닫힌 채로 남는 것**은 이자비용(§7의 4개사 표본 판정이 재검증되지 않았다)과 감가상각비
(§12 실질 2/8)이며, 그 둘이 3.2의 이자보상배율·순부채/EBITDA를 계속 막는다.

**분기 잔액 행은 아직 비어 있다.** 코드는 담지만 `--quarters=true`가 돈 적이 없어
Q4 1,604행이 전부 NULL이다 — 같은 저량인 `cash_and_equivalents`도 같은 상태다. 분기가 필요한
지표가 생기면 그때 함께 채운다.

되돌리려면 매퍼의 `ID_INVENTORIES`와 조립부 둘을 지운다. **`inventories` 컬럼은 되돌리지
않는다** — 원자료이고, 지우면 §14의 측정과 재적재를 다시 태워야 한다.
