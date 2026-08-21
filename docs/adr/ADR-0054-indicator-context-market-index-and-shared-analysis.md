---
id: ADR-0054
status: 채택됨
date: 2026-08-13
applies_to:
  - "src/main/java/com/stockscreener/indicator/IndicatorContext.java"
  - "src/main/java/com/stockscreener/indicator/IndicatorRunner.java"
  - "src/main/java/com/stockscreener/indicator/IndicatorDataReader.java"
  - "src/main/java/com/stockscreener/indicator/calculator/ichimoku/IchimokuAnalyses.java"
  - "src/main/java/com/stockscreener/indicator/calculator/ichimoku/IchimokuIndicator.java"
---

# ADR-0054. 지표 계층이 종목 밖 시계열을 받고, 계산기들이 분석 하나를 공유한다

## 결정

`IndicatorContext`에 **그 종목이 속한 시장의 지수 종가 시계열**을 담는다. 러너가
`asOf` 단위로 한 번 읽어 받은 것을 종목의 `market_code`로 골라 넣는다.

그리고 `ICHI_*` 18개가 **`IchimokuAnalyses` 메모이저 빈 하나**를 통해 같은 분석 결과를
나눠 쓴다. 이것이 "계산기끼리 중간 구조체를 공유하지 않는다"는 암묵 규약을 깨는 첫 자리다.

## 규칙

- **MUST: 지수를 잡이 종목 루프 <b>밖에서</b> 읽어 러너에 넘긴다**
  (`IndicatorRunner.marketIndexesFor(asOf)` → `run(…, marketIndexes)`).
  `readRawData`는 종목마다 불리므로 거기서 읽으면 350번 같은 쿼리를 던진다.
  ADR-0045가 `retentionCutoff`에 쓴 것과 같은 형태다.
- **MUST NOT: 지수 인자 없는 `run()` 경로를 남기지 않는다.** 남기면 새 호출부가 그쪽을 골라도
  아무 증상이 없고, `ICHI_*`가 통째로 비는 것만이 결과다 — ADR-0045가 2인자 `run()`을 지운
  이유와 같다.
- **MUST: 읽는 폭을 시세 창과 같게 한다**(`registry.readWindowBars()` — 시세와 **같은 메서드**를
  부른다, ADR-0079). 대상일 근처만
  읽으면 창 안의 오래된 산출 일자가 전부 "지수 결손"으로 접혀 사라지는데, 백필은 몇 일자만
  쓰고 **SUCCESS로 끝난다.**
- **MUST: 어느 지수를 쓸지는 러너가 정한다.** 계산기에 지수 여럿을 넘기면 "코스닥 종목의 지수는
  코스닥"이라는 **시장의 사실**을 지표마다 다시 알아야 하고, 한 곳만 안 고쳐도 그 지표만 조용히
  다른 벤치마크를 쓴다.
- **MUST NOT: `dependsOn`으로 일목 값을 나르려 하지 않는다.** 그 채널이 나르는 것은 부모가 자기
  코드로 낸 **스칼라 시계열 하나**다(`IndicatorRunner`의 `computed.put(code, series)`).
  `ICHI_MOD_A`를 의존으로 받으면 그 값 하나만 오고 거기서 나머지 수식자를 복원할 방법이 없으며,
  `code()`도 단수이고 레지스트리가 중복 코드를 기동 시 던진다.
- **MUST NOT: 메모이저의 값을 "그 일자 하나"로 두지 않는다.** 창 안 **모든 산출 일자**의 분석을
  담아야 한다. 단수로 두면 실제 산출이 1일자가 되고, `batch-load` skill이 예고한 상태가 온다 —
  "최신 거래일 말고는 프리셋이 전부 0건".
- **MUST NOT: 메모이저가 저장소를 조회하지 않는다.** 계산기 계약(컨텍스트에 담긴 것만 쓴다)은
  그대로여야 테스트가 순수하게 남는다.
- **MUST: 캐시 키에 창의 양 끝과 봉 수를 함께 넣는다.** 같은 종목이라도 창이 다르면 결과가
  다르다 — `stockId`만으로 키를 잡으면 백필이 날짜를 옮길 때 **이전 날짜의 분석을 그대로 쓴다.**

## 검증

```bash
./gradlew test --tests "*IndicatorRunnerTest*" --tests "*IchimokuAnalyzerTest*" \
               --tests "*IndicatorExtensibilityTest*"
```

`IndicatorExtensibilityTest`가 **"지표 추가 비용이 1파일"이 유지되는지**를 지킨다 — 이 결정이
계약을 열었어도 지수를 쓰지 않는 새 지표는 여전히 파일 하나로 끝나야 한다. 아니면 ADR-0039가
예고한 비용이 실현된 것이다.

```premise 공유 분석 — ICHI_* 18개의 산출 일자가 서로 같은가
psql -d stock_screener -At -c "SELECT CASE
  WHEN (SELECT count(*) FROM indicator_value WHERE indicator_code LIKE 'ICHI\_%') = 0
    THEN '잴 것이 없었다 — ICHI_* 가 한 행도 없다'
  WHEN (SELECT count(DISTINCT n) FROM (SELECT indicator_code, count(*) n FROM indicator_value
        WHERE indicator_code LIKE 'ICHI\_%' GROUP BY 1) t) = 1
    THEN 'OK 18개가 같은 일자·종목 집합에 값을 낸다 (' ||
         (SELECT max(n)::text FROM (SELECT count(*) n FROM indicator_value
          WHERE indicator_code LIKE 'ICHI\_%' GROUP BY indicator_code) u) || '행씩)'
  ELSE '행 수가 코드마다 다르다 — 분석 하나를 나눠 쓰는 전제가 깨졌거나 일부 코드만 무효화됐다: ' ||
       (SELECT string_agg(indicator_code || '=' || n, ' ' ORDER BY n) FROM
        (SELECT indicator_code, count(*) n FROM indicator_value
         WHERE indicator_code LIKE 'ICHI\_%' GROUP BY 1) v)
  END"
```

**행 수가 같아야 하는 근거**는 18개가 같은 `NavigableMap`을 순회해 포인트를 만든다는 것이다.
갈리면 공유가 깨졌거나 일부 코드만 지워진 것이고, 둘 다 **화면에서는 "그 종목이 조건에 안
맞았다"와 같아 보인다.** `EXCESS_RETURN_26W`는 워밍업이 달라 여기 넣지 않는다.

## 배경

지표 계층은 원자료(시세·재무)만 보는 계약이었고, 그 뒤 두 번 열렸다 — 종목 속성(ADR-0044,
업종)과 다른 계층의 원장(ADR-0051, 기업 행위). 이번이 세 번째이고 성격이 다르다:
**종목과 아무 관계가 없는 시계열**이다.

공유 분석 쪽은 성능이 이유다. 일목 분석 하나가 지표 18개의 값을 함께 내는데 계산기 계약은
지표 하나당 하나다. 각자 분석하면 종목·일자마다 18번 재계산이고, 실행당 산출 일자
수만큼 그 비용이 곱해진다(실측 종목당 177.4일자, 2026-08-20). `phase-criteria.md` §P12가 *"한 종목의 일목
분석이 지표 개수만큼 반복되지 않는다"*를 완료 조건으로 적어 둔 이유다.

## 버린 대안

- **`dependsOn`으로 일목 지표 하나를 부모 삼는다** — 스칼라 시계열 하나만 오므로 나머지 수식자를
  복원할 수 없다. 위 규칙에 적었다.
- **계산기가 저장소를 직접 조회한다** — 계약이 무너지고 테스트가 DB를 요구하게 된다. 그리고
  종목마다 조회가 늘어 `readRawData`가 피하려던 N+1이 다른 문에서 들어온다.
- **`IndicatorContext`에 시장 코드를 담고 계산기가 지수를 고른다** — 시장의 사실을 지표마다
  알아야 하고, 미국 어댑터(v1.1)를 붙일 때 30곳을 고치게 된다.
- **`ThreadLocal`로 분석을 나른다** — 캐시 키가 없어져 "지금 어느 창인가"를 아무도 확인하지
  못한다. 배치가 순차라 동작은 하겠지만, 틀렸을 때 증상이 "값이 하루 낡았다"뿐이다.

## 결과

- **쉬워지는 것**: T-08(주간 베타)이 계산기 하나만 남는다 — 지수를 받는 길이 이미 열려 있다.
  상대 지표를 더할 때 같은 통로를 쓴다.
- **어려워지는 것**
  - **계산기 계약에 "공유해도 되는 경우"가 생겼다.** 다음 세션이 이것을 선례로 삼아 임의의
    계산기끼리 구조체를 나누기 시작하면 실행 순서가 숨은 결합이 된다. 그래서 메모이저는
    **컨텍스트만 입력으로 받고 저장소를 모른다** — 그 제약이 선례의 범위를 정한다.
  - **`IndicatorContext`가 한 필드 더 커졌다.** 생성자를 다시 부르는 자리에서 조용히 빠질 수
    있고, 빠진 필드는 예외가 아니라 **값이 없는 지표**로 나타난다. `withDependencies`·
    `withMarketIndex`가 사본을 만드는 이유가 그것이다.
- **되돌리려면**: `IndicatorContext.marketIndex()`, `IndicatorRunner`의 4번째 인자와
  `marketIndexesFor`, 두 잡의 호출부, 그리고 `IchimokuAnalyses`. 되돌리면 `EXCESS_RETURN_26W`가
  값을 못 내므로 **무증상은 아니다.** (`ICHI_*` 18개는 지수를 쓰지 않는다 —
  ADR-0046 §승인된 이탈 2가 그 게이트의 철회를 든다.)
