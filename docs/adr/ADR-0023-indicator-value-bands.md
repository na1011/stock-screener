---
id: ADR-0023
status: 채택됨
date: 2026-08-08
applies_to:
  - "src/main/java/**/indicator/IndicatorBand*.java"
  - "src/main/java/**/indicator/IndicatorMeta.java"
  - "src/main/java/**/indicator/calculator/**"
  - "src/main/resources/db/migration/V11__indicator_bands.sql"
---

# ADR-0023. 지표 값 구간을 애너테이션에 선언하고 jsonb 한 컬럼으로 투영한다

## 결정

지표의 값 구간과 그 자연어 라벨("ROE 20% 이상 = 매우 높음")을 계산 클래스의
`@IndicatorMeta(bands = …)`에 선언하고, `indicator.bands` **jsonb 컬럼**으로 투영해
`/api/indicators`가 **그대로 적용 가능한 조건**(연산자·값·상한)으로 내려보낸다.

## 규칙

- MUST: 구간은 계산 클래스의 `@IndicatorMeta`에만 적는다. 다른 메타데이터와 같은 자리다 —
  지표 추가 비용이 파일 1개를 넘으면 안 된다는 spec §7의 성질이 여기에도 걸린다.
- MUST: 구간은 **큰 값에서 작은 값 순**으로 적고 서로 겹치지 않는다. `IndicatorCatalogSynchronizer`가
  기동 시 검사하고 어기면 던진다.
- MUST NOT: **무차원이 아닌 지표에 구간을 붙이지 않는다.** 주가 스케일에 비례하는 지표
  (`ADJ_CLOSE`, `SMA_*`, `ATR_14`, `MACD`)에 "3만원 이상 = 높음"을 달면 종목마다 뜻이 달라
  **거짓말이 된다.**
- MUST NOT: 백분위 지표(`*_PCTL`)에 구간을 붙이지 않는다. `PercentileGuide`가 이미 "상위 20%"의
  경계를 준다. 두 체계가 같은 것을 서로 다르게 말하게 된다.
  - **순위 표현("상위 16%")은 여기 걸리지 않는다.** 금지의 대상은 *구간 라벨*("20% 이상 = 매우
    높음")이고 이유는 두 체계가 되기 때문인데, 순위는 `PercentileGuide`의 체계를 **그대로 쓰는
    것**이다 — 서버가 "상위 20%" 버튼을 `<= 0.2`나 `>= 0.8`로 만드는 규칙과 같은 식이다.
    프론트의 구현은 `lib/band.ts`이며 유리한 방향은 `lib/direction`이 `favorableEnd`에서 뽑아
    둔 것을 쓴다(2026-08-08).
- MUST NOT: **부등호를 프론트가 유도하지 않는다.** 서버가 `from`/`to`를 `GTE`/`LT`/`BETWEEN`으로
  번역해 내린다. `PercentileGuide`가 "상위 20%"의 부등호를 서버에서 정하는 것과 같은 이유이며,
  `.claude/rules/frontend.md`의 "지표 코드로 분기하지 않는다"가 그 규칙이다.
- MUST NOT: `indicator.bands`를 손으로 갱신하지 않는다. 기동 시 애너테이션이 통째로 덮어쓴다.

## 검증

구간이 붙은 지표가 무차원인지 — 주가·통화 스케일 지표에 `bands`가 있으면 안 된다:

```bash
./gradlew test --tests "*IndicatorExtensibilityTest*"
```

`축척에 의존하는 지표에는 구간이 붙지 않는다`가 `value_type IN ('PRICE','CURRENCY')`인 지표 중
구간을 가진 것이 없음을 단언한다.

**파일명으로 재던 것을 값 타입으로 바꿨다**(2026-08-10). 원래 검증은
`rg -l 'bands = \{' … | rg 'Sma|Atr|Macd(?!V)Calculator|AdjClose'`였는데, 이름에 `Sma`·`Atr`가
들어가면서도 **정규화되어 무차원인** 파생 지표(`ATR_PCT_14`, `SMA_200_SLOPE_60`,
`SMA_200_MOM_21`)를 전부 거짓 양성으로 잡았다. `MacdVCalculator`를 `(?!V)`로 예외 처리하고
있던 것이 이미 그 신호였다 — 예외를 이름마다 덧붙이는 방식은 지표가 늘수록 반드시 어긋난다.
**무차원 여부를 정하는 것은 이름이 아니라 `value_type`이고**, 그것은 이미 애너테이션에 있다.

거짓 양성인 검증은 침묵보다 나쁘다. 다음 세션이 멀쩡한 코드를 고치러 가거나, 몇 번 겪은 뒤로는
검증 자체를 읽지 않게 된다.

부등호를 프론트가 정하지 않는지 — `IndicatorBand`의 `operator`를 화면이 다시 계산하면 안 된다:

```bash
# 결과가 없어야 한다. 주석의 언급까지 잡히던 것을 코드만 보도록 좁혔다(2026-08-08) —
# "이렇게 하지 않는다"고 적어 둔 주석이 매번 걸리면 검증이 읽히지 않게 된다.
rg -n --glob '!*.md' 'band\.(from|to)\b' frontend/src | rg -v '^\s*\S+:\d+:\s*[*/]'
```

구간의 순서·겹침은 `IndicatorCatalogSynchronizer.bandsOf`가 기동 시 던진다. 애플리케이션이 뜨는 것
자체가 검증이며 `IndicatorExtensibilityTest`가 컨텍스트를 올린다.

## 배경

`.claude/rules/frontend.md`가 "지표를 값만 나열하지 않는다"를 MUST NOT으로 두고 있는데 정작
조건 빌더가 그 위반이었다. 사용자는 PER 입력칸에 무슨 숫자를 넣어야 하는지 알 수 없었고,
대상 사용자는 초보~중급 투자자다. 경계 자체는 이미 `interpretation`에 산문으로 있었다 —
"통상 70 이상이면 과매수, 30 이하면 과매도로 본다". 화면이 문장을 파싱할 수 없었을 뿐이다.
그래서 이 결정은 **새로운 투자 판단을 더하지 않는다.** 이미 적혀 있던 경계를 구조로 옮긴다.

## 버린 대안

**정규화 테이블(`indicator_band`).** 구간은 다른 행을 참조하지 않고, 지표 하나에 종속되며,
언제나 그 지표와 함께 통째로 읽힌다 — 구간만 따로 조회할 일이 원리적으로 없다. 테이블로 두면
`IndicatorCatalog`의 SELECT 하나가 일대다 조인이 되어 읽기 경로 세 곳
(`activeIndicators`/`describe`/`find`)의 RowMapper가 전부 그룹핑을 해야 한다. 얻는 것이 없다.
V3가 조건을 jsonb로 두는 안을 기각한 근거는 "indicator FK를 잃는다"였는데, 구간에는 잃을 FK가 없다.

**`interpretation` 문자열을 파싱.** 문구를 고치면 화면이 조용히 구간을 잃는다. 산문이 파서의
암묵적 계약이 되는 것은 `source_indicator_code`를 두고 `_PCTL` 접미사 파싱을 기각한 것(V10)과
같은 이유로 받아들일 수 없다.

**프론트에 구간 표를 둔다.** 지표 코드로 분기하는 것이고, 백엔드에서 없앤 하드코딩이 프론트로
옮겨온 것이다. 지표가 추가될 때마다 화면을 고쳐야 한다.

**모든 지표에 구간을 붙인다.** 주가 스케일 지표에서 구간이 거짓이 된다. `Atr14Calculator`가
"주가 수준에 비례하므로 다른 종목과 직접 비교하지 않는다"고 이미 적어 둔 것과 충돌한다.
없는 것을 지어내는 것보다 없다고 말하고 직접 입력만 내는 편이 낫다.

## 결과

**쉬워지는 것.** 조건 빌더가 숫자를 모르는 사용자에게 선택지를 먼저 제시할 수 있다. 새 지표에
구간을 붙이는 비용이 애너테이션 몇 줄이고 화면은 고치지 않는다. 종목 상세에서 "이 값이 어느
구간인가"를 말하는 것도 같은 데이터로 가능해진다 — **`lib/band.ts`와 `BandMeter.vue`가 그것을
한다**(2026-08-08). 색을 어떻게 쓰는지는 ADR-0021의 "형태로 가른다" 절이 정한다.

**어려워지는 것.** 구간은 관습이라 근거가 약한 것이 섞인다. `interpretation`에 명시된 경계
(RSI 70/30, MACD-V ±150, ROE 15%, 부채비율 100%, 볼린저 0/1)와 그렇지 않은 것이 코드에서
구별되지 않는다 — 후자에는 근거를 주석으로 남겼다.

**되돌리려면.** 애너테이션의 `bands`, `IndicatorBand`, `IndicatorBandCodec`, V11 마이그레이션,
`IndicatorResponse.BandResponse`, 프론트의 `IndicatorBand` 타입과 그것을 쓰는 조건 편집기를
함께 걷어낸다. 저장된 화면은 영향받지 않는다 — 구간은 조건을 **만드는 입력**일 뿐 저장되는
것은 언제나 평범한 조건이다.
