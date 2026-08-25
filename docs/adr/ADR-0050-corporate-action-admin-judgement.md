---
id: ADR-0050
status: 채택됨
date: 2026-08-12
applies_to:
  - "src/main/java/**/pipeline/collector/CorporateActionLedger.java"
  - "src/main/java/**/pipeline/collector/CorporateActionReader.java"
  - "src/main/java/**/pipeline/collector/CorporateActionStatus.java"
  - "src/main/java/**/pipeline/collector/web/**"
  - "src/main/java/**/pipeline/batch/CorporateActionJudgeJob.java"
  - "src/main/java/**/pipeline/datasource/PriceDiscontinuity.java"
  - "src/main/resources/db/migration/V22__corporate_action_source_form.sql"
---

# ADR-0050. 사람이 원장의 값까지 고치고, 증거는 계산하되 고르지 않는다

## 결정

관리자 API가 원장의 **상태만이 아니라 값**(`type`·`ratio`·`effective_date`·`listing_date`)을
고친다. 그리고 판정에 필요한 **증거를 계산해 함께 내되, 후보 중 하나를 골라 제안하지 않는다.**

함께 정한 것이 넷이다.

- **`APPLIED`와 `OBSERVED`는 사람이 만들 수 없다.** 전자는 재구축이, 후자는 수집이 만든다.
- **소스가 준 낱말을 `source_form`에 적는다.** `note`와 합치지 않는다.
- **종류를 확정하지 못한 공시는 값이 아니라 이름만 기여한다** — `record`가 아니라 주석으로 붙는다.
- **잡을 런처 카탈로그에 넣되 스케줄러에는 넣지 않는다.**

## 규칙

- **MUST: 판정의 값 검증을 `CorporateAction`의 컴팩트 생성자로 한다.** `Type.allows`만 부르면
  **부호만 보고 양수를 안 본다** — 양수 검사는 생성자에 있고 V21의 `ck_ca_ratio_sign`은 둘을
  합친 것이다. `ratio = -5`는 `allows`를 통과해 DB 제약에서 터지고, catch-all이 없는 이
  프로젝트에서 그것은 400이 아니라 **500**이다. `numeric(18,10)` 범위도 함께 본다.
- **MUST: `type`과 `ratio`를 함께 고치게 한다.** 하나만 바꾸면 관측된 가격 배수에 종류 딱지가
  붙거나 그 반대가 되고, 그 행은 **승인되는 순간** 틀린 계수가 된다.
- **MUST NOT: 종류를 모르는 행을 승인할 수 있게 하지 않는다.** `findApplicable`이 `UNKNOWN`을
  거르므로 그 조합은 적용도 안 되면서 `OBSERVED` 대기열에서도 사라진다 — 어디에도 없는데
  아무 에러도 나지 않는다.
- **MUST NOT: 사람이 `APPLIED`를 찍을 수 있게 하지 않는다.** 계수는 그대로인데 원장이
  반영됐다고 말하게 되고, **ADR-0049의 불변식은 그 어긋남을 보지 않는다** —
  `adj_close = close × adj_factor`는 원장과 계수의 관계를 묻지 않는다.
- **MUST NOT: 사람이 `OBSERVED`로 되돌릴 수 있게 하지 않는다.** 수집 경로가 `OBSERVED` +
  `HEURISTIC`을 "덮어도 되는 후보"로 읽으므로 다음 실행이 그 판정을 조용히 지운다.
  판정을 미루고 싶으면 **상태를 비운 채 값만 고친다** — 그래서 상태가 선택이다.
- **MUST: 효력일 이동에 근접중복 판정을 함께 건다.** `UNIQUE`는 같은 날짜만 막고, 며칠 어긋난
  두 행이 둘 다 승인되면 재구축이 두 계수를 곱한다. 이동을 열어 두는 이유는
  `relocateCandidate`가 **사람이 판정한 행을 절대 안 옮기기** 때문이다 — 그 자리가 없으면
  일자가 어긋난 행이 영영 못 고쳐진다.
- **MUST NOT: 증거에서 후보 하나를 골라 제안하지 않는다.** 아래 §검증의 측정이 근거다.
- **MUST: 일치 판정에 `explainsPriceGap`(1.35/0.65)을 쓴다.** `supportsShareRatio`(2.0/0.5)로
  재면 **루닛 2026-05-04의 점프 셋이 전부 통과해** 판정력이 0이 된다. 두 폭은 서로 다른 질문
  이고, 후자를 좁히면 아시아나항공 2021-01-15(1.4252)이 잘린다.
- **MUST NOT: 종류를 모르는 공시를 `record`로 보내지 않는다.** 공시가 먼저 도는 것이 계약이라
  (`CorporateActionCollector`) 그 행이 슬롯을 차지하면 뒤에 올 휴리스틱의 **확정된 종류·비율이
  `IGNORED`로 버려진다.** 주석은 값을 건드리지 않아 순서와 무관하다.
- **MUST NOT: `source_form`을 `note`에 합치지 않는다.** 소스가 준 낱말은 사실이고 사람이 쓴
  말은 판단이다. 한 칸에 두면 한쪽이 다른 쪽을 덮고 그 순간 구분이 사라진다.
- **MUST: 창 검증을 `batch_execution` 행을 연 뒤에 한다.** 앞에서 던지면 아무 흔적도 안 남고,
  런처가 비동기로 제출하므로 예외를 받을 호출부도 없다 — 화면에는 202 뒤 아무 일도 안 일어난
  것으로 보인다.
- **MUST NOT: 사람이 판정한 행과의 근접중복을 알림으로 올리지 않는다.** 그 충돌은 영구적이라
  매 실행 같은 알림이 나가고, 며칠 안에 아무도 그 알림을 보지 않게 된다.
- **MUST NOT: `CorporateActionStatus`를 `wire-contract.json`의 `enums`에 넣지 않는다.** 그
  픽스처는 **프론트가 거울로 드는** enum만 담는다(ADR-0020). 화면이 없으므로 넣으면
  `contract.spec.ts`의 "픽스처의 enum을 하나도 빠뜨리지 않았다"가 깨진다.

## 검증

```bash
./gradlew test --tests "*CorporateAction*" --tests "*PriceDiscontinuity*" \
               --tests "*AdminJobLauncher*" --tests "*WireContract*"
./gradlew frontendTest
```

```premise 증거의 일치 판정이 후보를 실제로 가른다
psql -d stock_screener -At -c "WITH t AS (
  SELECT stock_id, trade_date, close, listed_shares,
         lag(close) OVER w AS pc, lag(listed_shares) OVER w AS ps
  FROM price_daily WINDOW w AS (PARTITION BY stock_id ORDER BY trade_date)
), cand AS (
  SELECT stock_id, trade_date AS eff, close::numeric / pc AS pm FROM t
  WHERE pc IS NOT NULL AND close > 0 AND pc > 0
    AND (close / pc > 1.30 OR close / pc < 0.70) AND ps = listed_shares
), j AS (
  SELECT c.stock_id, c.eff, c.pm * (t.listed_shares::numeric / t.ps) AS rel
  FROM cand c JOIN t ON t.stock_id = c.stock_id
   AND t.trade_date > c.eff AND t.trade_date <= c.eff + 75
   AND t.ps IS NOT NULL AND t.ps > 0 AND t.listed_shares IS DISTINCT FROM t.ps
), g AS (
  SELECT stock_id, eff, count(*) FILTER (WHERE rel BETWEEN 0.65 AND 1.35) AS hit
  FROM j GROUP BY 1, 2
)
SELECT CASE
  WHEN count(*) = 0 THEN '잴 것이 없었다 — 권리락 당일 주식수가 그대로인 후보가 없다'
  WHEN count(*) FILTER (WHERE hit = 1) * 2 < count(*)
    THEN '전제가 깨졌다: ' || count(*) FILTER (WHERE hit = 1) || '/' || count(*)
         || '건만 후보가 하나로 좁혀진다. 1.35/0.65가 더는 가르지 못하므로 증거의 폭을 다시 잰다.'
  ELSE 'OK ' || count(*) FILTER (WHERE hit = 1) || '건이 하나로 좁혀지고 '
       || count(*) FILTER (WHERE hit = 0) || '건은 후보 없음, '
       || count(*) FILTER (WHERE hit > 1) || '건이 사람이 볼 것 (전체 ' || count(*) || '건)'
  END FROM g"
```

착수 시점 실측은 **35 / 0 / 2 (전체 37)**이다(2026-08-12). 이 값이 무너지면 "고르지 않는다"의
비용이 사람에게 전가되므로, 그때는 증거를 더 좁힐 근거를 새로 찾아야 한다.

## 배경

`updateStatus`가 `status`와 `note`만 고쳤다. 그래서 **종류를 모르는 행은 사람 손으로도 살아나지
못했고**, 그 모집단이 작지 않다 — 가격제한폭 밖 불연속 **64건 / 62종목** 중 **46건**이 `UNKNOWN`
이다(2026-08-12 실측). 39건은 권리락 당일 주식수가 그대로라 휴리스틱이 종류를 못 내고, 7건은
인적분할이라 주식수 배수가 단순 유리수가 아니다.

`UNSUPPORTED`는 V21이 정의만 하고 아무도 안 썼다. 그것이 `REJECTED`와 뜻이 다르다는 것이
요점이다 — 저쪽은 "그런 일이 없었다"이고 이쪽은 "있었는데 못 고친다"다. 둘을 한 값으로 접으면
phase 기준의 *"적용하지 못한 사건이 무엇인지 이름으로 말할 수 있다"*가 성립하지 않는다.

**증거를 고르지 않기로 한 근거는 루닛 2026-05-04다.** 권리락 이후 75일 창에 주식수 점프가
셋이고(05-15 ×1.27 전환권행사 · 05-26 ×2.0 진짜 신주상장 · 06-19 ×1.001 잡음) 제안값 하나를
내면 그 모호함이 화면에서 사라진다. 고르는 규칙이 틀린 날 사람은 그것을 알 방법이 없다.
ADR-0049가 이미 같은 자리를 경고했다 — *"`기존`은 신주상장 직전 거래일이다 — 권리락일로 읽으면
그 사이에 낀 전환권행사류 때문에 루닛이 2.15·2.27이 나온다."*

**소스 낱말이 로그에만 남는 것이 실제로 위험했다.** 아시아나항공 2021-01-15은 DART가
「무상감자」라 부르는데 ADR-0049는 그것을 범위 밖으로 뒀고, 휴리스틱은 주식수 배수가 1/3로
스냅돼 `REVERSE_SPLIT`으로 올린다. 낱말이 원장에 없으면 사람은 그 행을 깨끗한 병합으로 읽고
승인한다 — 범위 밖으로 둔 사건에 계수가 들어간다.

## 버린 대안

- **제안값 하나를 계산해 내려준다** — 39건이 한 번씩 눌러 끝난다. 위 루닛이 그 대가다.
- **증거를 아예 안 준다** — 46건을 사람이 psql로 푼다. 그러면 이 API가 있으나 마나다.
- **`explainsPriceGap` 대신 `supportsShareRatio`를 재사용한다** — 폭이 하나 줄지만 실측으로
  판정력이 0이다(루닛 세 점프가 전부 통과, 같은 모양이 10건).
- **범위 밖 형태를 `record`로 후보에 올린다** — 공시가 먼저 도는 계약 때문에 휴리스틱의 확정된
  종류·비율이 버려진다. 기존 방어(`unknownDisclosureDoesNotEraseHeuristicObservation`)는
  *휴리스틱이 먼저*일 때만 걸린다.
- **`source_form`을 `note`에 넣는다** — 마이그레이션 하나를 아끼지만 사람이 판정하며 쓴 말이
  그 낱말을 덮는다.
- **분기 크론을 스케줄러에 등록한다** — progress.md가 그렇게 예고했으나 ADR-0043으로 앱이
  사람이 켠 동안만 돌므로 **그 분·그 날에 떠 있어야** 발화한다. 사실상 안 도는데 등록되어
  있으면 "분기마다 모인다"는 거짓 믿음만 남는다. 화면 버튼이 실제 경로다.
- **판정 화면까지 만든다** — ADR-0049가 이미 버렸다. 그 판단이 그대로 유지된다.

## 결과

- **쉬워지는 것**
  - 46건이 실제로 판정 가능해진다. 그것이 P9의 다음 단계(수정거래량·재구축)가 설 땅이다.
  - `UNSUPPORTED`가 살아나 "못 고친 사건"을 이름으로 셀 수 있다(`/summary`).
  - 일자가 어긋난 행을 손으로 옮길 수 있다 — 그 전에는 복구 수단이 SQL뿐이었다.
- **어려워지는 것**
  - **판정해도 계수는 안 바뀐다.** `rebuild()` 호출자가 없는 것이 정상이며(ADR-0049 — 첫 호출
    전에 `pg_dump`가 필요하다), **ADR-0049의 불변식은 원장과 계수의 어긋남을 보지 않는다.**
    지금 그것을 아는 유일한 흔적이 `status`와 `applied_at`의 차이다.
  - **`APPLIED → APPROVED` 강등이 그 어긋남을 만든다.** 이미 반영된 행의 비율을 고치는 정상
    경로가 그것이고, 재구축을 다시 돌리기 전까지 계수는 옛 비율이다.
  - 어댑터가 이름만 아는 사건에도 효력일을 요구하므로, **가격 불연속이 우리 시계열에 없는
    범위 밖 사건**(상장 이전 등)은 여전히 로그로만 남는다. 남는 사각지대는 그 하나다.
- **되돌리려면**: `judge`를 상태만 고치는 형태로 되돌리고 `V22`를 되돌린다. 그러면 46건이 다시
  못 고치는 상태가 되므로, **되돌리기 전에 그 행들이 이미 판정됐는지 본다.**
