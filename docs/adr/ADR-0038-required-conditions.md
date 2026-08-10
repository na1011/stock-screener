---
id: ADR-0038
status: 채택됨
date: 2026-08-10
applies_to:
  - "src/main/java/com/stockscreener/screening/**"
  - "src/main/resources/db/migration/V15__condition_required.sql"
  - "frontend/src/components/condition/ConditionEditor.vue"
  - "frontend/src/components/result/ConditionOutcomes.vue"
---

# ADR-0038. 필수 여부는 조건-소속 관계의 속성이고, SCORING의 접기에서만 좁힌다

## 결정

조건에 `required`를 더한다. SCORING에서 필수 조건을 충족하지 못한 종목을 **결과에서 뺀다.**
필수 조건은 **점수와 만점에 참여하지 않는다.** SQL은 그대로 2개이며, 좁히는 일은 쿼리가 아니라
`ConditionEvaluator`의 접기 단계에서 일어난다.

## 규칙

- MUST: 좁히는 판단을 `ConditionEvaluator.satisfiesRequired` 한 곳에만 둔다. `ConditionTranslator`는
  `required`를 모른다 — SCORING이 좁히는 WHERE를 갖지 않는다는 ADR-0003의 성질을 유지한다.
- MUST: 판정불가(`NOT_EVALUABLE`)를 필수 조건의 **미충족**으로 본다. `passesFilter()`가 이미 그 뜻이며
  다시 정의하지 않는다. 통과로 접으면 **지표가 없는 종목일수록 게이트를 잘 통과한다.**
- MUST: 점수·만점 합산에서 필수 조건을 뺀다. 통과한 종목은 정의상 전부 충족하므로 같은 몫을 받고,
  섞으면 달성률이 언제나 높게 나와 변별이 사라진다.
- MUST NOT: `required`와 `weight`를 함께 두지 않는다. `IndicatorCondition`의 생성자와
  `ck_cond_required_weight`가 양쪽에서 막는다. 화면도 체크박스가 아니라 **둘 중 하나를 고르는 토글**로
  둔다 — 나란히 두면 둘 다 채워 놓고 저장 때 400을 맞는 조합이 만들어진다.
- MUST NOT: FILTER에서 `required=true`를 저장 시점에 거절하지 않는다. 무시할 뿐이다. 거절하면 사용자가
  판정 방식을 오갈 때마다 저장된 정의가 파괴된다.
- MUST NOT: 화면이 `weight === null`로 필수 여부를 유도하지 않는다. FILTER 전략은 둘 다 null이라 구분이
  서지 않는다(`frontend.md`의 "서버가 준 값을 다른 필드로 되짚지 않는다").
- MUST: 필수 조건도 `outcomes`에는 남긴다. 결과에 남은 종목이 **왜** 남았는지를 화면이 말해야 한다.

## 검증

```bash
./gradlew test --tests "*ConditionEvaluatorTest*" --tests "*ScreeningApiContractTest*"
```

`필수 조건` 중첩 클래스가 여섯을 고정한다 — 게이트가 실제로 빼는지, 판정불가가 충족이 아닌지,
점수·만점에서 빠지는지, FILTER의 결과가 도입 전과 같은지, 필수가 없으면 종전대로인지,
가중치와 함께 쓸 수 없는지.

번역기가 `required`를 모르는지 — 결과가 없어야 한다:

```bash
rg -n 'required' src/main/java/com/stockscreener/screening/query/
```

저장된 것과 판정이 어긋나지 않는지. **필수 조건을 가진 SCORING 전략의 결과에는 그 조건을 충족하지
못한 종목이 없다**는 것이 이 결정의 전부이며, DB만으로는 확인할 수 없다(판정이 Java에 있다).
그래서 전제로는 **저장 구조가 불변식을 지키는지**를 묻는다:

```premise 필수 조건이 가중치를 갖지 않는다
psql -d stock_screener -At -c "SELECT CASE WHEN count(*) FILTER (WHERE is_required) = 0 THEN '필수 조건이 한 건도 없다 — 잴 것이 없었다' WHEN count(*) FILTER (WHERE is_required AND weight IS NOT NULL) = 0 THEN 'OK 필수 ' || count(*) FILTER (WHERE is_required) || '건 전부 가중치 없음' ELSE count(*) FILTER (WHERE is_required AND weight IS NOT NULL) || '건이 필수인데 가중치를 갖는다 — 달성률이 부풀려진다' END FROM screen_condition"
```

**`count(*) = 0`이 아니라 `필수 조건이 0건`을 따로 답하는 것이 요점이다.** `screen_condition`은 프리셋만으로도
비지 않으므로, 전체 행 수를 세면 필수 조건이 하나도 투영되지 않은 상태가 `OK`로 읽힌다.

## 배경

첨부된 중장기 스윙 조건셋 명세(미국시장 기준)를 프리셋으로 옮기려는데, 그 구조가 **Hard 게이트로 거른 뒤
남은 종목에 Soft 점수를 매기는 2단계**였다. 우리 `screen`은 `evaluation_mode`가 FILTER 또는 SCORING 하나다.

- FILTER로 만들면 게이트는 걸리지만 Soft 점수·정렬·조건별 획득 내역이 통째로 사라진다.
- SCORING으로 만들면 점수는 나오지만 아무도 걸러지지 않는다. 그 명세에서 가장 중요한 조건(이격도 상한)의
  목적이 "이미 과열된 종목을 화면에 아예 띄우지 않는 것"이라, 낮은 점수로 결과에 남는 순간 실패다.

가중치로 게이트를 흉내내는 길이 처음 떠오르지만, 미충족 종목이 **결과에 남는다**는 사실 자체가 문제라
가중치를 아무리 크게 줘도 풀리지 않는다.

핵심 관찰은 ADR-0003이 이미 답을 정해 두었다는 것이다 — **가중치가 조건-소속 관계의 속성**이듯 필수 여부도
같은 자리다. 조건은 여전히 자신이 어디에 쓰이는지 모르고, 판정 방식도 여전히 실행의 속성이다.
덤으로 기존 `QVG_KR`의 "가드" 조건(부채비율 ≤ 200)이 지금 가중치 1짜리라 부채비율 300%인 종목도 상위에
올라올 수 있는데, 그것을 고칠 수단이 이것으로 생긴다.

## 버린 대안

- **FILTER 전용 프리셋으로 축소** — 코드 변경이 0에 가깝다. 그러나 명세 §6의 Soft 점수와 "5개 중 4개 충족"이
  사라지고, `frontend.md`가 요구하는 "프리셋은 조건별 획득 점수를 보인다"를 그 프리셋만 못 지키게 된다.
- **게이트용 FILTER + 점수용 SCORING 두 프리셋** — 기존 구조를 전혀 건드리지 않는다. 그러나 사용자가 두 번
  실행하고 교집합을 눈으로 맞춰야 하며, 그 대조는 종목이 20개만 넘어도 실질적으로 불가능하다.
- **가중치 0을 "제외하지 않는 조건"으로 쓰기**(플래그 표현) — 방향이 반대인 요구였고, `ck_cond_weight`가
  0을 막고 있어 어차피 성립하지 않는다.
- **`EvaluationMode`에 세 번째 값(`GATED_SCORING`)을 더하기** — 판정 방식이 실행의 속성이라는 축은 지켜진다.
  그러나 **어느 조건이 게이트인지**를 모드가 말해 주지 못해, 결국 조건 쪽에 표시가 또 필요해진다.
  모드가 늘면 `wire-contract.json`·`lib/evaluationMode`·워치리스트 판정(ADR-0026)이 함께 늘어난다.
- **필수 조건을 WHERE로 내려 SQL에서 좁히기** — 쿼리 하나로 끝나 메모리 접기가 준다. 그러나 SCORING의
  두 쿼리 모양이 조건 구성에 따라 달라지고, **판정 의미가 SQL과 Java 두 곳에 생긴다** — ADR-0003이 조건의
  판정 의미를 `ComparisonOperator.test()` 한 곳에 둔 이유가 그것이다.

## 결과

- **쉬워지는 것**: 게이트 + 점수 전략을 프리셋 하나로 표현할 수 있다. 기존 전략은 `is_required`가 전부
  `false`라 동작이 그대로다. 커스텀 전략도 같은 수단을 얻는다.
- **어려워지는 것**: SCORING에서 빠진 종목의 판정 내역이 응답에 남지 않는다. "어느 게이트가 몇 종목을
  떨어뜨렸나"를 알려면 같은 조건셋을 FILTER로 하나씩 빼며 돌려야 한다. 깔때기를 상시로 보고 싶어지면
  그때가 응답에 단계별 통과 수를 싣는 것을 검토할 시점이다.
- **되돌리려면**: V15 마이그레이션, `IndicatorCondition.required`, `ConditionEvaluator.satisfiesRequired`와
  점수 합산의 필터, `ScreenStore`의 컬럼, 페이로드 왕복, 프론트의 역할 토글과 결과 구획을 함께 걷어낸다.
  저장된 전략은 컬럼이 사라져도 조건을 잃지 않는다.
