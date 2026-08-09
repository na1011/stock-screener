---
id: ADR-0036
status: 채택됨
date: 2026-08-09
applies_to:
  - "src/main/java/**/indicator/PercentileCalculator.java"
  - "src/main/java/**/indicator/PercentileSpec.java"
---

# ADR-0036. 백분위는 유니버스 안에서 매긴다

## 결정

`*_PCTL` 지표의 모집단을 **유니버스 하나**로 한정한다. 코스피200과 코스닥150은 각자의 모집단이고, 순위를 합쳐 매기지 않는다.

## 규칙

- MUST: `PERCENT_RANK()`를 `PARTITION BY universe_code`로 나눈다. 모집단을 좁히는 술어만 두고 파티션을 빼면 "유니버스 한정"이 **필터 조건으로만 성립하고 순위는 합쳐진다** — 그 상태는 값이 그럴듯해 화면에서 드러나지 않는다.
- MUST: 모집단이 2종목 미만이면 행을 쓰지 않는다. `PERCENT_RANK()`가 단일 원소에 0을 주는데, 0은 "가장 싸다"로 읽혀 조건을 충족시킨다.
- MUST NOT: 한 종목을 두 활성 유니버스에 동시에 편입시키지 않는다. `indicator_value`에 유니버스 축이 없어 종목당 값이 하나뿐이므로 둘 중 어느 순위를 남길지 고를 근거가 없다.
- MUST NOT: 겹침을 만났을 때 한쪽을 골라 조용히 넘어가게 고치지 않는다. 지금은 JOIN이 행을 늘려 `ON CONFLICT DO UPDATE`가 같은 행을 두 번 건드리고 PostgreSQL이 배치를 죽인다. **그 시끄러움이 이 설계가 기대는 안전장치다.** 겹침을 허용해야 한다면 저장 구조에 유니버스 축을 더하는 것이 먼저다.

## 검증

```bash
./gradlew test --tests "*PercentileCalculatorTest*"
```

`유니버스마다 따로 매긴다`가 파티션을, `한 종목이 두 유니버스에 걸리면`이 겹침 거부를, `모집단이 하나면`이 단일 원소 배제를 각각 고정한다. 첫 번째는 `PARTITION BY`를 빼면 실제로 깨지는 것을 확인했다.

저장된 값으로도 물을 수 있다 — 유니버스별로 매겼다면 **모든 활성 유니버스에서** 최소 0·최대 1이 나온다:

```sql
SELECT m.universe_code, min(iv.value), max(iv.value)
FROM indicator_value iv
JOIN universe_membership m ON m.stock_id = iv.stock_id AND m.effective_to IS NULL
WHERE iv.indicator_code = 'PER_PCTL' AND iv.trade_date = (SELECT max(trade_date) FROM indicator_value)
GROUP BY 1;
```

## 배경

모집단 필터가 `universe_code`를 보지 않아 두 지수가 한 모집단이었다. 실측이 그것을 그대로 보여줬다 — 2026-08-06 기준 `PER_PCTL`이 코스닥150에서만 0과 1을 갖고 코스피200은 0.0038~0.9962였다.

세 가지가 어긋나 있었다. `PercentileCalculator`의 javadoc은 "코스피200 내 상위 20%"라고 말했고, `ScreeningRepository.screen`은 `universeCode`로 대상을 걸렀으며, 순위만 합쳐져 있었다. **거르는 모집단과 순위를 매기는 모집단이 다르면** 사용자가 코스피 화면에서 본 "하위 20%"는 코스닥을 포함한 순위다. `screen_condition` 16개 중 6개가 백분위라 프리셋 결과에 직접 걸렸다.

미국이 붙으면(v1.1, ADR-0010) 한국 종목이 미국 종목과 섞여 매겨진다. spec §7이 시장 간 계산 방식의 차이를 없애라고 요구하는데, 그 요구는 계산식뿐 아니라 모집단에도 걸린다.

## 버린 대안

- **지금 동작을 정본으로 삼고 문서를 고친다** — 결과가 바뀌지 않아 재계산이 필요 없다. 버린 이유는 v1.1에서 같은 논의를 다시 하게 되고, 그때는 미국 종목이 이미 섞인 값으로 사람이 판단한 뒤라는 것이다. 화면이 "코스피200 내 상위 20%"라고 말할 근거도 사라진다.
- **`indicator_value`에 유니버스 축을 더한다** — 겹침을 정면으로 푸는 유일한 방법이지만 PK와 EAV 접근 경로 전부가 바뀐다(ADR-0002). 겹치는 종목이 실제로 0개인 지금 그 비용을 치를 이유가 없고, 겹치면 시끄럽게 깨지므로 필요해지는 시점을 놓치지 않는다.
- **겹칠 때 `min(universe_code)` 같은 규칙으로 하나를 고른다** — 배치는 안 죽지만 어느 모집단의 순위인지가 값에 남지 않는다. 조용히 틀린 값이 저장되는 쪽이라 이 프로젝트에서 가장 피하려는 형태다.

## 결과

- **쉬워지는 것**: 화면의 "코스피200 내 상위 20%"가 사실이 된다. 미국이 붙어도 순위가 시장을 넘지 않는다.
- **어려워지는 것**: 저장된 백분위를 다시 계산해야 한다(`indicator-backfill`, 외부 API를 부르지 않아 쿼터는 들지 않는다). 유니버스를 새로 만들 때 "종목이 겹치지 않는가"가 새 제약이 된다.
- **되돌리려면**: `PercentileCalculator.UPSERT_PERCENTILES`의 `PARTITION BY`와 `population >= 2`, 그리고 위 세 테스트.
