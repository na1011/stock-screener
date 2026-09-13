---
id: ADR-0142
status: 채택됨
date: 2026-09-14
applies_to:
  - "src/main/java/**/screening/query/ScreeningRepository.java"
  - "src/main/java/**/screening/web/ScreeningResponse.java"
  - "src/main/java/**/screening/web/ScreeningResponseAssembler.java"
  - "src/main/java/**/screening/web/ScreeningService.java"
  - "frontend/src/components/result/**"
---

# ADR-0142. 0건의 사유를 행 밖에 싣는다

## 결정

스크리닝 응답의 **머리말**에 ① 판정 대상 모집단의 크기와 ② 조건별 귀속을 싣는다. 행이 0개여도
사는 자리이므로 **0건에서도 화면이 이유를 말한다**(P27 축④).

- `populationCount` — 그 기준일에 판정 대상이던 종목 수.
- `conditionTallies` — 조건마다 `notMatched`·`notEvaluable`·`wouldPassWithout`.
- `evaluatedCount`는 **지운다.** 세는 것이 「조건에 맞은 행 수」인데 이름과 화면 문구가
  「평가한 종목 수」라고 말하고 있었다.

**ADR-0106 §버린 대안의 첫 항목을 이 결정이 뒤집는다.** 거기서 기각한 것은 *"판정 상태를 3 → 4로
늘려 「이 시장 임계 미정」을 **조건 단위**로 낸다"*였고 기각 근거가 *"`outcomes`가 `Row` 안에만
있어 … 행이 0개가 되고 화면이 아무 이유도 말하지 않는다"*였다. **그 근거는 자리를 행 밖에 만들면
성립하지 않는다** — 같은 응답에 `adsSuspectCount`·`relativePopulations`가 이미 그 형태로 살고
있었다. **ADR-0106 §규칙의 「임계가 없으면 요청 단위로 거절한다」는 그대로 둔다.**

## 규칙

- **MUST: 집계를 조건 **인덱스**로 센다. 지표 코드로 세지 않는다.** 한 화면 안에 같은 지표의
  상·하한 쌍이 설 수 있고(ADR-0106의 이식표가 `MARKET_CAP >=`와 `<=`를 함께 싣는다), 코드로
  세면 둘이 **조용히** 합쳐진다. 인덱스는 `Row.outcomes`의 `conditionIndex`와 같은 것이다.
- **MUST: 조립자가 인덱스 정합을 단언한다.** `ConditionGroup.flattenConditions()`와
  `ConditionEvaluator.evaluateGroup`가 같은 전위 순회라는 것이 **어디에도 단언돼 있지 않다** —
  두 구현이 우연히 발맞추고 있다. 길이와 인덱스가 어긋나면 던진다.
- **MUST: `wouldPassWithout`은 「실패한 관문이 정확히 하나인 종목」만 귀속한다.** 여럿에 걸린
  종목은 어디에도 안 센다. ADR-0070 §규칙이 요구한 **한계 기여**가 그 정의이고, 누적 교집합은
  얹는 순서에 따라 값이 달라져 재현되지 않는다.
- **MUST: AND 트리가 아니면 `wouldPassWithout`을 내지 않는다.** 「정확히 하나」는 논리곱에 대한
  정리다. `ScreenDefinitionValidator`가 중첩·비-AND를 거절하지만 **`screen(...)`에는 그 검증을
  안 거치는 호출부가 일곱 있으므로**(백테스트 탐침·스윕) repository가 스스로 모양을 확인한다.
- **MUST: SCORING의 배점 조건은 `wouldPassWithout`이 null이다.** 0이나 통과 수를 실으면 둘 다
  「빼도 안 바뀐다」로 읽히는데, ADR-0070 §검증이 *"`lo = 0`이면 성립하지 않는다"*와 *"상수다"*를
  일부러 갈라 놓았다.
- **MUST NOT: 이 수를 펜스 축으로 쓰지 않는다.** ADR-0070 §검증의 2026-09-13 사용자 결정이
  *"펜스 축을 「대상별 절단 수」로 갈아끼우지 않는다"*를 반례(`LYNCH_STALWART.TURNOVER_60`
  44→44)와 함께 못박았다. **여기 실리는 것은 사용자에게 보이는 진단이고 층이 다르다.**
- **MUST: `GateDiagnosticsTest`와 세는 규칙이 다르다는 것을 안다.** 그 탐침은 **값이 하나도 없는
  종목을 모집단에서 빼고** `required`만 관문으로 센다. 이 응답은 **그 종목을 넣고**(그것이
  `notEvaluable`이고 축④ 둘째가 요구하는 답이다) FILTER에서는 **전 조건**을 관문으로 센다.
  같은 낱말의 수가 두 층에서 다른 것을 세므로 **둘을 견주지 않는다.**
- **MUST NOT: 응답에 「거절」 상태를 새로 만들지 않는다.** 임계 결손의 요청 단위 거절은
  ADR-0106이 이미 4xx로 내보낸다. 여기서 다시 만들면 그 완화가 받아들일 만해 보인다.

## 검증

```bash
./gradlew test --tests "*ScreeningRepositoryTest*" --tests "*ScreeningApiContractTest*" \
               --tests "*ConditionEvaluatorTest*" --tests "*WireContractTest*"
cd frontend && npm run test:unit
```

**대조군으로 확인한다.** ① FILTER 2차 판정을 되돌리면 FILTER 0건의 귀속과 0행 계약이 붉고
모집단·인덱스 케이스는 초록이다. ② 모집단을 다시 `results.size()`로 되돌리면 FILTER 비0건의
`populationCount > resultCount`만 붉는다. ③ 인덱스 대신 지표 코드로 집계하면 같은 지표
상·하한 쌍 케이스만 붉는다.

**이 결정은 premise를 두지 않는다.** 재는 것이 저장된 값이 아니라 **응답의 모양**이고 질의로
답할 수 없다(ADR-0111이 같은 사유로 뺐다). 대신 계약 시험이 필드 이름을 본다 —
**`wire-contract.json`은 응답 필드명을 원리적으로 안 덮으므로**(`enums`·`problemTypes`·
`problemProperties`·`constants`뿐) ADR-0020의 트립와이어가 이 자리에 없다.

## 배경

P27 축④가 넷을 요구하는데 셋째(*"결손 사유 문구가 그 시장에 대해 참인가"*)만 ADR-0111이 닫아
두고 나머지는 비어 있었다. 실측(2026-09-14): 탈락분은 `ScreeningRepository`의 루프에서
`outcomes`까지 통째로 버려지고, 0건 화면은 `'조건에 맞는 종목이 없습니다.'` 한 줄인데 **그
문구는 사용자의 표 검색어가 전부 걸러낸 경우에도 같이 쓰인다.** 그리고 넷째는 깨져 있었다 —
`evaluatedCount`가 세는 것은 「조건에 맞은 행 수」인데 화면이 `결과 N / 평가 M종목`으로 그려,
FILTER에서 절단이 없으면 **같은 수가 두 번** 나왔다.

**0건은 지금 실재한다** — `STAGE2_BREAKOUT`이 최신 지표일에 `KOSDAQ_TOP150`·`KOSPI_TOP200`
양쪽에서 0건이다(2026-09-14 실측).

## 버린 대안

- **이름을 두고 타입 주석과 화면 라벨만 고친다** — 축④ 넷째의 *"이름이 세는 것과 맞는다"*는
  닫히지만 **모집단이 여전히 응답에 없어** 앞절이 안 닫힌다. 그리고 어긋남을 잡을 자동
  트립와이어가 없다. 필드를 **지우면** `vue-tsc`가 `ResultSummary.vue`에서 빌드를 세운다.
- **`UniverseInfo.memberCount`로 대신한다** — **다른 것을 센다.** 그것은 「현재 편입」이고
  `universeCode == null`이면 `UniverseInfo` 자체가 null이다. 과거 기준일 조회에서는 판정
  모집단과 갈린다(ADR-0135).
- **0건 사유를 서버가 enum으로 분류해 보낸다** — 서버는 수만 내고 문구는 화면이 정한다
  (`adsSuspectCount`·`relativePopulations`가 이미 그 형태다). enum을 만들면 `wire-contract.json`·
  `WireContractTest`·`frontend/src/api/contract.spec.ts` 셋을 함께 고쳐야 한다.
- **FILTER에서도 술어를 SQL로 안 내리고 전부 인메모리로 판정한다** — `ConditionTranslator`의
  존재 이유를 지우고 `.claude/rules/backend.md`의 첫 MUST NOT을 되돌린다. 0건이 아닌 요청까지
  전부 SCORING 비용을 낸다.
- **진단을 `ResultTable`의 `#empty` 슬롯에 그린다** — 그 슬롯은 **검색어가 전부 걸러낸 경우에도**
  뜨므로, 거기 두면 화면이 지금 보이는 것과 **다른 질문에 답한다.**

## 결과

**쉬워지는 것** — 0건에서 화면이 「어느 조건이 몇 종목을 잘랐는가」와 「그중 데이터가 없어서
못 판정한 것은 몇인가」를 말한다. 모집단이 응답에 있으므로 「전체」의 종목 수를 화면이
카탈로그 요청으로 다시 만들던 자리도 함께 닫힌다(`.claude/rules/frontend.md`가 막은 형태다).

**어려워지는 것**

- **`ADR-0003 §규칙의 「쿼리는 항상 2개다」를 이 결정이 다시 연다.** FILTER가 0건일 때만
  모집단 SELECT와 지표 SELECT **둘이 더** 나간다. 그 불변식이 막으려던 것은 **조건 개수에
  비례해 쿼리가 느는 것**이고(원문이 *"조건 개수만큼 SELECT 절에 CASE 컬럼을 추가하지 않는다"*),
  이 둘은 **조건 수와 무관한 상수**다. 그래도 코드 넷이 그 문장을 하중으로 인용하므로
  (`ScreeningRepository`·`UniverseEntity`·`UniverseMembershipEntity`) 여기 적어 둔다 — **적지
  않으면 다음 세션이 그 주석들을 근거로 2차 패스를 걷어낸다.**
- **0건 경로가 가장 싼 요청에서 가장 비싼 요청이 된다.** 지금은 `stockIds.isEmpty()` 단축 때문에
  질의 하나로 끝나는데, 앞으로는 전량을 Java로 판정한다. 상한의 잣대는
  `ScreenDefinitionValidator`의 실측(**21조건 프리셋이 350종목에서 475 ms**)이다.
- **`PointInTimeScreener`가 「`screen`과 같은 모양」을 계약으로 들고 있었다** — 2차 패스가 그
  모양을 바꾸므로 그 javadoc을 함께 고쳤다. 같은 파일 `Outcome`의 `evaluated`가 이 결정의
  `populationCount`와 **같은 개념**이다.
- **SQL 경로와 Java 경로가 사유를 다르게 말한다.** `ConditionTranslator`는 참조 지표 결손을
  「비교 실패」와 같은 거짓으로 접고 `IndicatorCondition.evaluate`는 `NOT_EVALUABLE`로 가른다.
  **포함/제외는 같고 사유만 갈리며, 그 가름이 축④ 둘째가 요구하는 것이다.**

**되돌리려면** — `ScreeningResponse`에서 세 필드를 빼고 `evaluatedCount`를 되살리며,
`ScreeningRepository`의 집계와 2차 패스를 걷는다. 그러면 0건 화면이 다시 한 줄만 말한다.
