---
id: ADR-0044
status: 채택됨
date: 2026-08-11
applies_to:
  - "src/main/java/com/stockscreener/indicator/SectorRelativeCalculator.java"
  - "src/main/java/com/stockscreener/indicator/SectorMetricConfig.java"
  - "src/main/java/com/stockscreener/indicator/calculator/AltmanZCalculator.java"
  - "src/main/java/com/stockscreener/pipeline/datasource/CompanyProfileDataSource.java"
  - "src/main/java/com/stockscreener/pipeline/datasource/kr/DartCompanyProfileAdapter.java"
  - "src/main/resources/db/migration/V17__stock_industry.sql"
---

# ADR-0044. 업종 축을 열며 무엇을 모집단으로 삼고 무엇을 판정불가로 두는가

## 결정

KSIC 업종 코드(`company.json`의 `induty_code`)를 `stock.industry_code`에 원문으로 담고,
**업종 상대 지표의 모집단을 "활성 유니버스 전체 ∩ 같은 업종"으로 정한다.** 유니버스로
나누지 않으므로 **ADR-0036이 정한 모집단을 다시 여는 결정이다.**

표본이 부족하거나 업종이 비어 있으면 **값을 쓰지 않는다** — 유니버스 백분위로 폴백하지 않는다.

## 규칙

- MUST: 업종 판정은 `industry_code`·`industry_group`·`industry_division`만 본다.
  **`stock.sector`는 표시 전용 라벨이며 판정에 쓰지 않는다.** 라벨과 코드가 갈려도 결과가
  바뀌지 않아야 한다.
- MUST: 업종 그룹은 **소분류(앞 3자리)를 먼저 쓰고, 표본이 5 미만이면 중분류(앞 2자리)로
  올라간다.** 중분류도 5 미만이면 **행을 쓰지 않는다.**
- MUST: 두 층의 순위표를 **각각** 만들고 종목이 쓸 층을 고른다. 한 파티션에 소분류 기준
  종목과 중분류 기준 종목을 섞으면 모집단이 뜻을 잃는다.
- MUST: 같은 배치에서 **`SECTOR_PEER_COUNT`(그 종목이 실제로 비교된 표본 수)를 함께 쓴다.**
  임계 5는 n<5만 막고 **n=5와 n=40의 차이는 값에 아무 흔적을 남기지 않는다.**
- MUST NOT: 표본이 부족할 때 유니버스 백분위나 절대 기준을 **같은 지표 코드로** 채우지
  않는다(ADR-0039 — 뜻이 다른 값을 같은 이름으로 부르지 않는다).
- MUST NOT: 금융업 판정에 5자리를 전제하지 않는다. **앞 2자리**로 한다 — `induty_code`가
  3자리와 5자리로 섞여 오므로 5자리 전제는 3자리 종목을 통째로 놓치고, 결과는
  "그 종목만 조용히 안 걸린다"로 나타난다.
- MUST NOT: `_SECTOR_REL`(중앙값 배수)에 `indicator.source_indicator_code`를 걸지 않는다.
  거는 순간 카탈로그가 백분위 안내를 내고 화면이 "상위 20% = `<= 0.2`" 버튼을 만드는데,
  **1.0이 중앙인 배수 값에 0.2는 뜻이 없다.**

## 승인된 예외 — `SWING_MIDTERM_KR`의 0건

**`SWING_MIDTERM_KR`은 지표를 늘려 보기 위한 테스트베드 프리셋이고 종목을 추천받으려고 쓰는
것이 아니다**(2026-08-11 사용자 결정). 그래서 **이 프리셋에 한해 결과 0건은 실패가 아니다.**

`docs/phase-criteria.md` Phase 7의 *"프리셋이 실제로 종목을 낸다"* 는 이 예외를 가리킨다.

- **예외의 범위는 이 프리셋 하나다.** `VALUE_KR`·`QVG_KR`·`MOMENTUM_KR`에는 걸리지 않는다.
- **MUST NOT: 0건을 임계값을 풀 근거로 읽지 않는다.** 원인을 ① 조건 ② 데이터 ③ 시장 국면으로
  가른 뒤 숫자만 보고한다. 손잡이를 푸는 것은 사용자 결정이다.

## 검증

업종 판정이 표시 라벨에 기대지 않는지 — 모집단을 가르는 키가 무엇인지로 묻는다:

```bash
rg -n "PARTITION BY|GROUP BY|JOIN stock" src/main/java/com/stockscreener/indicator/SectorRelativeCalculator.java
```

전부 `industry_group` 또는 `industry_division`이어야 한다. **`sector`가 나오면 표시 라벨이
판정에 새어 들어간 것이다.** 낱말이 아니라 이 자리로 묻는 이유는, javadoc이 `stock.sector`를
설명으로 언급하는 것은 정상이라 낱말 검색이 거짓 경보를 내기 때문이다.

```premise 업종 코드가 활성 편입 종목에 들어와 있다
psql -d stock_screener -At -c "SELECT CASE WHEN count(*) = 0 THEN '잴 것이 없었다 — 활성 유니버스에 종목이 하나도 없다' WHEN count(*) FILTER (WHERE s.industry_code IS NOT NULL) = 0 THEN '업종이 전량 비어 있다(' || count(*) || '종목) — company-profile 잡이 아직 돌지 않았다. 업종 조건은 전 종목 판정불가다' WHEN count(*) FILTER (WHERE s.industry_code IS NULL) > 0 THEN '업종이 ' || count(*) FILTER (WHERE s.industry_code IS NULL) || '/' || count(*) || '종목에서 비어 있다 — 신규 편입 뒤 company-profile을 돌리지 않았다. 그 종목은 업종 필수 조건에서 조용히 빠진다' ELSE 'OK 활성 편입 ' || count(*) || '종목 전부에 업종이 있다' END FROM stock s JOIN universe_membership m ON m.stock_id = s.id AND m.effective_to IS NULL JOIN universe u ON u.code = m.universe_code AND u.is_active" 
```

**`count(*) = 0`과 "전량 비어 있음"을 따로 답하는 이유**는 둘 다 조용히 참이 되기 때문이다 —
유니버스가 비어도, 잡을 한 번도 안 돌려도 업종 조건은 똑같이 전 종목 판정불가가 된다.

```premise Altman Z가 금융업 종목에 값을 내지 않는다
psql -d stock_screener -At -c "WITH d AS (SELECT max(trade_date) td FROM indicator_value), f AS (SELECT s.id, s.name FROM stock s JOIN universe_membership m ON m.stock_id = s.id AND m.effective_to IS NULL WHERE s.industry_division IN ('64','65','66')) SELECT CASE WHEN (SELECT count(*) FROM f) = 0 THEN '잴 것이 없었다 — 업종이 아직 없거나 금융 중분류 종목이 편입되어 있지 않다' WHEN (SELECT count(*) FROM d) = 0 THEN '잴 것이 없었다 — indicator_value가 비어 있다' WHEN (SELECT count(*) FROM indicator_value iv, d WHERE iv.trade_date = d.td AND iv.indicator_code = 'ALTMAN_Z' AND iv.stock_id IN (SELECT id FROM f)) = 0 THEN 'OK 금융 중분류 ' || (SELECT count(*) FROM f) || '종목에 ALTMAN_Z 행이 없다' ELSE '금융 중분류 종목에 ALTMAN_Z가 ' || (SELECT count(*) FROM indicator_value iv, d WHERE iv.trade_date = d.td AND iv.indicator_code = 'ALTMAN_Z' AND iv.stock_id IN (SELECT id FROM f)) || '건 남아 있다 — 계산기는 고쳤는데 기존 행을 지우지 않았다. 필수 조건이 옛 값으로 계속 통과한다' END"
```

**기존 행을 따로 묻는 것이 요점이다.** `IndicatorRunner`는 값을 못 내면 건너뛸 뿐이고
`IndicatorValueWriter`는 UPSERT만 하므로, **계산기를 고쳐도 이미 저장된 행은 그대로 남는다** —
에러도 경고도 없이 제외가 무효가 된다.

## 배경

원본 명세(`docs/external/screener-spec-midterm-swing.md`, ADR-0041)의 H-02·S-01·S-02가 업종
상대 비교를 요구하는데 `stock.sector`가 활성 350종목 전부 비어 있어 절대 기준과 유니버스
백분위로 치환되어 있었다(ADR-0039). 2026-08-10 실측으로 DART 기업개황이 `induty_code`를
종목당 1콜에 준다는 것이 확인됐다.

그러나 **소스가 열린 것과 업종 비교가 성립하는 것은 다르다.** 유니버스가 350종목이라 업종당
2~3개로 흩어질 수 있고, 그러면 "업종 중앙값"이 사실상 그 종목 자신이 된다 — 값이 나오므로
화면에서 드러나지 않는다. 그래서 최소 표본과 그룹 수준을 **데이터를 받기 전에** 정했다
(`docs/phase-criteria.md` P8). 분포를 본 뒤에 정하면 기준이 아니라 결론이다.

**최소 표본 5와 계층 폴백은 우리가 지어낸 값이 아니다** — 원본 §2-2 P-04가 정한 것이다.
다만 원본은 4자리 SIC 기준 4→3→2의 **3단**인데 우리는 **2단(3→2)**이다. `induty_code`가
3자리(소분류)와 5자리(세세분류)로만 오고 그 사이 4자리 층을 소스가 주지 않기 때문이며,
5자리를 그대로 쓰면 3자리로 온 종목이 어느 그룹에도 못 들어간다.

## 버린 대안

- **모집단을 유니버스별로 나눈다(ADR-0036 유지)** — 350종목을 유니버스 2 × 업종 40으로 쪼개면
  표본이 거의 남지 않아 업종 비교 자체가 성립하지 않는다. ADR-0036이 막으려던 것은
  "거르는 모집단과 순위의 모집단이 다른 것"인데, 업종 조건은 애초에 유니버스로 좁히는 축과
  다른 축이라 그 병이 생기지 않는다.
- **표본이 부족하면 유니버스 백분위로 폴백한다** — 값이 나오므로 화면에서 드러나지 않고,
  같은 컬럼이 종목마다 다른 뜻을 갖게 된다. ADR-0039가 금지한 그것이다.
- **표본이 부족하면 원본 H-02의 절대 폴백(D/E ≤ 1.0)을 지표 안에서 대신 쓴다** — 원본이
  명시한 규칙이지만, 그러면 하나의 지표 값이 "업종 대비 배수"와 "절대 비율" 두 뜻을 갖는다.
  원본은 그 경우 플래그 D-03을 붙여 구분하는데 **우리에게 플래그 도메인이 없다**(모듈 B,
  ADR-0039). 구분 수단 없이 뜻만 겹치는 쪽보다 판정불가가 낫다.
- **`stock.sector`에 KSIC 코드를 그대로 넣는다** — 마이그레이션이 필요 없지만, 그 컬럼은
  종목 상세 화면 헤더에 그대로 표시되므로 사용자가 "264"를 보게 된다.

## 결과

- **쉬워지는 것**: 업종 상대 조건이 평범한 지표 행이 되어 조건 번역기·프론트가 그대로 쓴다.
  `SECTOR_PEER_COUNT`가 결과 표에 있어 "무엇과 비교됐는가"를 사용자가 직접 본다.
- **어려워지는 것**: 같은 백분위 컬럼의 두 값이 서로 다른 크기의 모집단에서 나온다.
  표본 수를 함께 보지 않으면 n=5의 순위와 n=40의 순위가 같은 무게로 읽힌다.
- **지표 계약이 한 칸 열린다.** `IndicatorContext`가 종목 속성(`industryDivision`)을 받는다.
  ADR-0039 §별도 phase가 T-08의 비용 근거로 든 "코어 넷" 중 셋이 여기서 먼저 열리므로,
  **T-08의 비용 판정은 이 결정 이후 다시 재야 한다.**
- **되돌리려면**: `SectorMetricConfig`의 선언을 비우고 `PresetCatalog.swingMidtermKr()`의
  업종 조건 셋을 되돌린다. `stock`의 컬럼과 적재 잡은 남아도 무해하다.
