---
id: ADR-0057
status: 채택됨
date: 2026-08-14
applies_to:
  - "src/main/java/**/indicator/**"
  - "frontend/src/lib/indicatorCategory.ts"
  - "src/main/resources/db/migration/**"
---

# ADR-0057. 지표 분류를 사용자가 읽는 다섯 구획으로 만든다

## 결정

`IndicatorCategory`에 `OSCILLATOR`를 더하고 `DERIVED`의 뜻을 **"다른 지표에서 파생된 것(백분위 등)"에서 "지표 둘 이상의 관계에서 나오는 교차·위치 신호"로** 바꾼다. 백분위·업종 상대값 12행은 **원본 지표의 분류를 따라간다**.

## 왜

**분류는 조건 빌더의 그룹핑 기준으로 만들어졌는데 종목 상세가 같은 값을 구획 기준으로 쓴다**(`StockDetailView`). 그 화면에서 넷은 모자랐다 — 이동평균·RSI와 MACD·스토캐스틱·일목 구름이 전부 `TECHNICAL` 하나에 들어가 40개 카드가 구분 없이 늘어섰다.

`DERIVED`의 옛 뜻은 **유도 방식**을 가리켰고 사용자가 읽는 축이 아니었다. "PER 업종 백분위"는 사용자에게 파생 계산이 아니라 재무 지표다. 유도 방식은 이미 `sourceIndicatorCode`·`population`이 들고 있어 분류가 그것을 겸할 이유가 없다.

## 규칙

- **MUST NOT: enum에서 값을 빼지 않는다.** `IndicatorCatalog`의 RowMapper가 `IndicatorCategory.valueOf(rs.getString("category"))`를 그대로 부르고, `describe()`는 `is_active`로 거르지도 않는다. 폐기한 분류가 **비활성 행에 하나라도** 남으면 카탈로그 조회 전체가 터진다. 더하는 것은 안전하고 빼는 것은 안전하지 않다.
- **MUST: 값을 더하면 `wire-contract.json`·`frontend/src/api/types.ts`·`indicatorCategory.ts`의 `Record` 셋을 함께 고친다.** `WireContractTest`와 `contract.spec.ts`는 편집 대상이 아니라 **감시자**이고, `Record<IndicatorCategory, T>` 셋은 컴파일로 누락을 잡는다.
- **MUST: 백분위·업종 상대값의 분류는 원본 지표에서 유도한다.** `IndicatorCatalogSynchronizer`가 원본 계산기의 `@IndicatorMeta.category()`를 읽어 넘긴다. `PercentileConfig`·`SectorMetricConfig`에 `category`를 적지 않는다 — 적으면 원본이 옮겨갈 때 파생만 남아 두 곳이 어긋난다.
- **MUST: 분류를 옮길 때 구획 안 `sortOrder`도 함께 본다.** 유일성을 강제하는 장치가 없고 정렬은 `ORDER BY sort_order, code`뿐이라, 충돌하면 코드 알파벳순으로 **조용히** 갈린다.

## 다섯 구획

| 분류 | 화면 라벨 | 무엇이 들어가는가 |
|---|---|---|
| `FUNDAMENTAL` | 재무 지표 | 재무제표에서 나온 값과 그 백분위·업종 상대값 |
| `TECHNICAL` | 기술적 지표 | 추세·변동성(이동평균, RSI, ATR, 이격도) |
| `OSCILLATOR` | 오실레이터 | 기준선 위아래를 오가는 값(MACD, 스토캐스틱, OBV, 일목 구름의 정량 조건) |
| `DERIVED` | 파생 지표 | 지표 둘 이상의 교차·위치(골든크로스, 전환선 대 기준선) |
| `PRICE` | 가격·규모 | 가격 자체와 규모(종가, 시가총액, 거래대금) |

`TECHNICAL`과 `OSCILLATOR`가 갈리는 지점은 **기준선을 두고 진동하는가**다. 애매하면 `TECHNICAL`에 둔다 — 되돌리는 비용이 애너테이션 한 줄이다(`upsert`가 `category = EXCLUDED.category`를 갱신하므로 마이그레이션이 필요 없다).

## 검증

```premise 폐기된 분류가 DB에 남아 있지 않다
psql -d stock_screener -At -c "SELECT CASE
  WHEN count(*) = 0 THEN 'OK 모든 category가 enum 안에 있다'
  ELSE 'enum에 없는 분류가 남아 있다: ' || string_agg(DISTINCT category, ', ')
  END FROM indicator
  WHERE category NOT IN ('TECHNICAL','FUNDAMENTAL','PRICE','OSCILLATOR','DERIVED')"
```

```premise 백분위·업종상대의 분류가 원본과 같다
psql -d stock_screener -At -c "SELECT CASE
  WHEN count(*) = 0 THEN 'OK 파생 행이 전부 원본 분류를 따른다'
  ELSE '원본과 분류가 갈린 파생: ' || string_agg(d.code, ', ')
  END FROM indicator d JOIN indicator s ON s.code = d.source_indicator_code
  WHERE d.source_indicator_code IS NOT NULL AND d.category <> s.category"
```

`sortOrder` 충돌은 자동 검증 대상이 아니다 — 중복이 곧 결함은 아니고(다른 구획이면 무해하다) 판정에 분류가 함께 필요한데, 그 조합을 단정으로 쓰면 구획을 옮길 때마다 거짓 발견이 난다.
