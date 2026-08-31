---
id: ADR-0060
status: 채택됨
date: 2026-08-14
applies_to:
  - "src/main/java/com/stockscreener/indicator/IndicatorCatalogSynchronizer.java"
  - "src/main/java/com/stockscreener/indicator/IndicatorUnavailabilityConfig.java"
  - "src/main/java/com/stockscreener/indicator/IndicatorMeta.java"
  - "frontend/src/components/stock/IndicatorCard.vue"
---

# ADR-0060. 종목 상세의 카드를 무엇으로 고르고 어떤 순서로 세우는가

## 결정

셋을 함께 정했다.

1. **값이 없는 이유를 말할 수 있는 지표는 카드로 남긴다** — `—`와 사유를 함께 낸다.
   규칙은 `IndicatorUnavailabilityConfig`에 **데이터로** 선언한다.
2. **카드 실익을 기준 넷으로 판정하고, 걸리는 것은 `detail_default = false`로 감춘다.**
3. **파생 지표의 `sort_order`를 원본에서 유도한다** — 저장값은 애너테이션 값 × 10이고
   파생이 1~5를 더해 **원본 바로 뒤**에 선다.

## 왜 — 카드 목록을 사람이 읽는 기준으로 정한 적이 없었다

종목 상세는 `indicator` 테이블의 `detail_default`·`sort_order`가 시키는 대로 그린다.
그런데 그 둘은 **조건 빌더의 그룹핑 기준과 계산기 추가 순서**가 남긴 값이었다. 결과가
한 종목에 **카드 57장**이고, 그중 일곱은 종목에 대해 아무것도 말하지 않으며,
`PER`과 `PER 백분위` 사이에는 재무 지표 아홉이 끼어 있었다.

## 카드 실익 — 기준 넷과 실측

| 기준 | 걸린 것 | 실측 |
|---|---|---|
| **① 이 종목의 값이 아니다** | `PER_SECTOR_MEDIAN` · `*_SECTOR_N` ×3 | 중앙값은 **같은 업종이 전부 같은 수**를 본다. 표본 수는 값의 신뢰도다 |
| **② 거의 모든 종목이 같은 값이다** | `TRADING_DAYS_750` | 345종목 중 **91%가 750**. `min(size, 750)`이라 포화한다 |
| **③ 주가를 다시 보여준다** | `ADJ_CLOSE` | 종가와의 상관 **1.000**, 347/347이 헤더 종가와 동일 |
| **④ 순서가 없는 분류값이다** | `ICHI_DIVERGENCE` | 값이 `−1/0/1/2`이고 308종목 중 **두 값에 97%**가 몰린다 |

**기준 ③에 걸리는데도 남긴 것이 다섯 있다** — `SMA_20/60/120/200`(종가 상관 0.981~0.997)과
`ATR_14`(0.956). **2026-08-14 사용자 결정**이며, 그 측정을 여기 남기는 이유는 다음 세션이
같은 상관을 다시 재고 같은 제안을 다시 하지 않게 하기 위해서다.

- **MUST: `ADJ_CLOSE`가 기준 ③에 걸리는 근거를 "지금 값이 같다"로 적지 않는다.**
  수정주가는 **과거를 소급 조정**하므로 가장 최근 봉에서 원주가와 같아지는 것이 구조다.
  상세는 기준일 하나만 보므로 이 카드는 **원리적으로** 정보가 0이고, 그 값이 우연히 같은
  것이 아니다. 시계열에서는 여전히 뜻이 있다.
- **MUST NOT: 기준 ④에 걸린 값에 구간(bands)을 붙여 해결하지 않는다.** 구간은 구조적으로
  순서다(ADR-0023 — 범위를 미터로 그린다). `ICHI_DIVERGENCE`의 `interpretation`이 스스로
  *"2가 1보다 좋다는 뜻이 아니다"*라고 적는데, 구간을 붙이면 **화면이 그 문장이 금지한 읽기를
  만든다.** 구간 없이 숫자만 두는 것도 `frontend.md`가 막으므로 남는 답은 감추는 것이다.
- **감춤은 폐기가 아니다**(ADR-0058). 일곱 다 `default_column`이 `false`라 결과 표는 그대로고,
  조건 빌더와 「내 조건」에도 나온다 — `ADJ_CLOSE`는 세 프리셋이, `TRADING_DAYS_750`은
  스윙 프리셋이 **필수 조건**으로 쓴다.

### 계산기 없는 파생은 애너테이션으로 감출 수 없었다

`PER_SECTOR_MEDIAN`과 `*_SECTOR_N` 셋은 `@IndicatorMeta`가 없다. 그 행을 쓰는
`upsertDerived`가 `detail_default`를 **리터럴 `true`로 박고 `ON CONFLICT DO UPDATE SET`에서도
빠뜨리고 있었다.**

- **MUST: 노출 정책을 파라미터로 받고 `DO UPDATE SET`에 함께 넣는다.** 하나만 하면
  **이미 있는 행이 갱신되지 않아 "감췄다"가 조용히 거짓이 된다** — 에러도 로그도 없다.
  같은 함정을 형제 메서드의 javadoc이 `category`에 대해 이미 적어 두었다.
- 회귀 테스트가 `false`로 심어 둔 행이 동기화 뒤 `true`가 되는지를 본다. **이름 갱신을
  대조군으로 둔 것이 요점이다** — 이름만 바뀌고 정책이 안 바뀌면 컬럼 하나가 빠진 것이다.

## 값이 없는 이유 — 계산 시점이 아니라 응답 시점에 유도한다

사유를 아는 것은 계산기인데 **그 출력에 실을 자리가 없다.** `IndicatorPoint`는 생성자가
null 값을 거부하고, `indicator_value`에 사유 컬럼이 없으며, `calculate`의 반환형을 바꾸면
`IndicatorCalculator`의 *"새 지표는 클래스 하나"* MUST가 71개 계산기에서 깨진다.

그래서 `UnadjustedActions`(ADR-0055)·`SectorContext`(ADR-0044)와 **같은 길**을 쓴다 —
원인 데이터를 응답 시점에 다시 보고 문구를 만든다.

- **MUST: 사유를 말할 수 있는 것만 카드로 남긴다.** 전부 남기면 빈 카드가 스무 장 깔리고
  "값이 없어 표시하지 못한 지표 N개" 고지가 뜻을 잃는다.
- **MUST NOT: 추측을 문구로 쓰지 않는다.** 그럴듯한데 틀린 사유는 **침묵보다 나쁘다** —
  사용자가 없는 결손을 믿는다. 실제로 `ALTMAN_Z`가 값을 못 내는 42종목 중 **27은 유동자산
  결손이라는 부작용**인데 어느 계정이 비었는지는 이 계층이 모르므로 규칙을 두지 않았다.
- **MUST: 문구를 서버가 소유한다.** 프론트가 자기 문장을 들면 두 곳이 갈리고, 갈린 쪽은
  화면에서 드러나지 않는다.
- **MUST: 「내 조건」 구획에도 같은 사유를 넘긴다.** 그 구획이 카드 객체를 **자기 가지에서
  따로** 만들기 때문에, 빠뜨리면 같은 화면 위아래에 사유 있는 `—`와 없는 `—`가 함께 뜬다.

## 표시 순서 — 파생을 원본에서 유도한다

저장값 = `@IndicatorMeta.sortOrder × 10`, 파생은 거기에
`_PCTL` +1 · `_SECTOR_PCTL` +2 · `_SECTOR_REL` +3 · `_SECTOR_MEDIAN` +4 · `_SECTOR_N` +5.

- **MUST: ×10을 원본에도 건다.** 간격이 있어야 파생이 앉을 자리가 생긴다. 애너테이션 값
  1~315가 전부 유일하므로 충돌이 **구조적으로** 사라진다.
- **MUST: 애너테이션 javadoc이 "여기 적은 값이 그대로 저장되지 않는다"를 말한다.** 안 적으면
  다음 사람이 DB 값을 보고 애너테이션을 되짚다가 10배 어긋난다.
- **MUST NOT: `screen_condition.sort_order`와 헷갈리지 않는다.** 이름이 같은 다른 컬럼이다.

### ADR-0057의 판단을 뒤집는다

그 ADR은 `sortOrder` 충돌 검사를 **두지 않기로** 정했고 이유를 적었다 —
*"판정에 분류가 함께 필요한데 그 조합을 단정으로 쓰면 구획을 옮길 때마다 거짓 발견이 난다."*

아래 premise는 **같은 구획 안으로 좁혀** 그 이유를 해소한다. 다만 **번복은 번복이므로**
여기 남긴다 — 그 ADR만 읽고 "검사를 두지 않기로 했다"고 인용하면 어긋난다.

## 검증

```bash
./gradlew test --tests "*IndicatorExtensibilityTest*" --tests "*ReadApiTest*" \
                --tests "*VolumeIndicatorsTest*"
./gradlew frontendTest
```

```premise 적자 종목의 PER 부재가 화면에서 설명된다
psql -d stock_screener -At -c "WITH d AS (SELECT max(trade_date) t FROM indicator_trade_date WHERE market_code IN ('KOSPI','KOSDAQ')), x AS (SELECT s.id, max(iv.value) FILTER (WHERE iv.indicator_code='PER') per, max(iv.value) FILTER (WHERE iv.indicator_code='EPS_TTM') eps FROM stock s JOIN universe_membership m ON m.stock_id=s.id AND m.effective_to IS NULL JOIN universe u ON u.code=m.universe_code AND u.is_active LEFT JOIN (SELECT iv0.* FROM indicator_value iv0 JOIN stock s0 ON s0.id = iv0.stock_id WHERE s0.market_code IN ('KOSPI','KOSDAQ')) iv ON iv.stock_id=s.id AND iv.trade_date=(SELECT t FROM d), d GROUP BY s.id) SELECT CASE WHEN count(*) FILTER (WHERE eps IS NOT NULL) = 0 THEN '잴 것이 없었다 — EPS_TTM이 하나도 없다(백필 미실행)' WHEN count(*) FILTER (WHERE per IS NULL AND eps > 0) > 0 THEN 'PER이 없는데 EPS가 양수인 종목이 ' || count(*) FILTER (WHERE per IS NULL AND eps > 0) || ' — 적자 규칙이 그 종목에 거짓 사유를 붙인다' ELSE 'OK 적자로 설명되는 종목 ' || count(*) FILTER (WHERE per IS NULL AND eps <= 0) || ', 설명 못 하는 것 ' || count(*) FILTER (WHERE per IS NULL AND eps IS NULL) || '(재무 부족)' END FROM x"
```

**이 전제는 국내로 좁혀져 있다.** 재는 축 자체는 시장에 무관하지만 미국 지표가 아직
0행이라 **미국 쪽을 오늘 검증할 수 없어** 넓히지 않았다 — 못 재는 보호막을 보증하지
않으려는 것이다. **여는 조건은 미국 지표 백필**이고, 그때 시장별 최신일로 넓힌다.


**"EPS가 양수인데 PER이 없다"를 위반으로 세는 것이 요점이다.** 그 종목에는 규칙이 **틀린 사유**를
붙이게 되고, 화면에 그럴듯한 문장이 뜨므로 드러나지 않는다. 착수 시점 실측은 0이다(적자 68 ·
설명 못 하는 것 13).

```premise 파생 지표가 원본 바로 뒤에 선다
psql -d stock_screener -At -c "WITH d AS (SELECT i.code, i.sort_order, i.category, regexp_replace(i.code, '(_PCTL|_SECTOR_PCTL|_SECTOR_REL|_SECTOR_MEDIAN|_SECTOR_N)$', '') AS src FROM indicator i WHERE i.is_active AND i.code ~ '(_PCTL|_SECTOR_REL|_SECTOR_MEDIAN|_SECTOR_N)$'), j AS (SELECT d.code, d.sort_order, d.category, s.code scode, s.sort_order ssort, s.category scat FROM d LEFT JOIN indicator s ON s.code = d.src AND s.is_active) SELECT CASE WHEN count(*) = 0 THEN '잴 것이 없었다 — 접미사로 식별되는 파생이 없다' WHEN count(*) FILTER (WHERE scode IS NULL) > 0 THEN '원본을 찾지 못한 파생이 있다(접미사 규칙이 낡았다): ' || string_agg(code, ', ') FILTER (WHERE scode IS NULL) WHEN count(*) FILTER (WHERE category IS DISTINCT FROM scat) > 0 THEN '원본과 다른 구획에 있는 파생: ' || string_agg(code, ', ') FILTER (WHERE category IS DISTINCT FROM scat) || ' — 구획이 다르면 옆에 서지 않는다' WHEN count(*) FILTER (WHERE sort_order - ssort NOT BETWEEN 1 AND 9) > 0 THEN '원본에서 멀어진 파생: ' || string_agg(code || '(' || sort_order || ' vs 원본 ' || ssort || ')', ', ') FILTER (WHERE sort_order - ssort NOT BETWEEN 1 AND 9) ELSE 'OK 파생 ' || count(*) || '개가 전부 원본과 같은 구획에서 바로 뒤(+1~9)에 있다' END FROM j"
```

**`source_indicator_code`가 아니라 코드 접미사로 원본을 찾는 것이 요점이다.** 업종 배수·중앙값·
표본 수는 그 링크를 **일부러 걸지 않으므로**(ADR-0044) 링크로 재면 13개 중 7개가 조용히 빠진다.
**구획이 같은지도 함께 본다** — 순서가 붙어 있어도 구획이 다르면 화면에서는 떨어져 보인다.

```premise 표시 순서가 조용히 갈리지 않는다
psql -d stock_screener -At -c "SELECT CASE WHEN count(*) = 0 THEN 'OK 같은 구획 안에 sort_order 충돌이 없다' ELSE '구획 안에서 순서가 겹쳐 코드 알파벳순으로 갈린다: ' || string_agg(category || ':' || sort_order || '(' || codes || ')', ', ') END FROM (SELECT category, sort_order, string_agg(code, '/') codes FROM indicator WHERE is_active AND detail_default GROUP BY 1, 2 HAVING count(*) > 1) t"
```

착수 전 실측으로 `900`에 5개·`910`에 4개·`911`에 3개가 겹쳐 있었다.

## 버린 대안

- **계산기가 사유를 돌려주게 한다** — 가장 곧다. 그러나 `calculate`의 반환형이 바뀌어
  71개 계산기가 걸리고, `IndicatorCalculator`가 MUST로 건 *"지표 추가 비용 1파일"*이 깨진다.
- **값 없는 지표를 전부 카드로 남긴다** — 규칙 표가 필요 없다. 그러나 빈 카드 스무 장이
  깔리고, 그중 어느 것이 설명 가능한지 사용자가 가릴 수 없게 된다.
- **`interpretation`에 사유를 넣는다** — 필드가 늘지 않는다. 그러나 그것은 **지표 카탈로그의
  정본 문장**이고 사유는 **종목별 사실**이라, 한 필드가 두 뜻을 가지면 캐시·재사용이 어긋난다.
- **파생에 원본과 같은 `sort_order`를 준다** — 산술이 없다. 그러나 구획 안 순서가
  `ORDER BY sort_order, code`의 알파벳순으로 정해져 `_SECTOR_MEDIAN`이 `_SECTOR_PCTL`보다
  앞서는 등 뜻 없는 배열이 된다.
- **애너테이션 `sortOrder`를 전부 10배로 고쳐 적는다** — 저장값과 선언이 같아진다. 그러나
  71개 파일을 건드리고, 다음에 간격 규칙이 바뀌면 또 71개를 건드린다.

## 결과

- **쉬워지는 것**: 카드가 57 → 50이 되고 파생이 원본 옆에 선다. 값이 없는 이유가 화면에
  남아 "왜 이 종목이 결과에 없나"에 답할 자리가 하나 더 생겼다.
- **어려워지는 것**: `sort_order`의 저장값과 선언값이 다르다 — 애너테이션만 보고 DB를
  추측하면 10배 어긋난다. 사유 규칙이 늘면 그 판정이 응답 시점 비용이 된다(지금은 이미 읽은
  값과 종목 속성만 보므로 추가 조회가 중분류 하나다).
- **되돌리려면**: 순서는 `SORT_ORDER_SCALE`과 오프셋 다섯을 걷고 상수 900·910·911로 되돌린다.
  감춤은 `detailDefault` 선언을 지우면 되고, 사유는 `IndicatorUnavailabilityConfig`를 비우면
  카드가 다시 사라진다. **셋 중 되돌리기 어려운 것은 순서 하나**다 — 나머지는 선언이다.
