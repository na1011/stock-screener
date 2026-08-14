---
id: ADR-0021
status: 채택됨
date: 2026-08-07
applies_to:
  - "frontend/src/theme/**"
  - "frontend/src/assets/main.css"
  - "frontend/src/main.ts"
  - "frontend/index.html"
  - "frontend/src/lib/band.ts"
  - "frontend/src/lib/outcome.ts"
  - "frontend/src/components/ui/BandMeter.vue"
  - "frontend/src/components/result/**"
---

# ADR-0021. 화면을 다크 한 벌로 하고 디자인 토큰의 정본을 PrimeVue 프리셋에 둔다

## 결정

색·간격·반경의 정본을 `frontend/src/theme/preset.ts`의 `definePreset(Aura, …)`에 둔다. 화면은 **다크 한 벌만** 만들고 `<html class="app-dark">`로 항상 켜 둔다. 라이트 팔레트는 손대지 않는다.

## 규칙

- MUST: 색·반경·폼 필드 치수는 `theme/preset.ts`에서만 정의한다. `.vue`와 `main.css`는 `--p-*`를 **소비만** 한다.
- MUST NOT: `main.css`나 `<style scoped>`에 색 리터럴(hex·rgb·hsl)을 적지 않는다. 예외는 `PriceReferencePanel`의 `var(--p-orange-400, #fb923c)` 같은 **폴백**뿐이다.
- MUST: **글자 크기는 `main.css`의 `--app-text-*`에서만 정의한다.** PrimeVue 프리셋에 글자 크기 축이 없기 때문이다 — `formField.fontSize`는 폼에만 닿고 `button.root.fontSize`는 아예 토큰이 아니다(타입이 거부한다). 색이 아니라 레이아웃 상수이므로 `--app-*`와 같은 자리에 둔다.
- MUST NOT: `.vue`의 `<style scoped>`에 rem 리터럴로 글자 크기를 적지 않는다. 손으로 적힌 값 40여 개가 흩어져 있었고, 그래서 본문·버튼·제목이 전부 15~16px 한 옥타브에 몰려 위계가 사라졌다.
- MUST: `main.css`의 `:root .p-inputtext`·`:root .p-button` 계열 규칙에서 **`:root` 접두어를 떼지 않는다.** PrimeVue는 자기 CSS를 런타임에 `<head>`로 주입해 `main.css`보다 항상 뒤에 오므로, 같은 특정도면 그쪽이 이긴다. 접두어 없이 적혀 있던 이전 규칙은 **한 번도 적용된 적이 없었고** 그 사실이 화면 어디에도 드러나지 않았다.
- MUST: `index.html`의 `<html class="app-dark">`를 유지한다. 지우면 `darkModeSelector`가 걸리지 않아 **손보지 않은 Aura 라이트 팔레트**로 떨어진다. 컴파일도 되고 테스트도 통과한다.
- MUST NOT: `darkModeSelector`를 기본값 `'system'`으로 되돌리지 않는다. OS 설정만으로 검증하지 않은 라이트 화면이 나온다.
- MUST: `Tag`의 `warn`을 amber로 유지한다. Aura 기본값은 `{orange.*}`이고, 주황은 지지·저항 참고정보 **전용**이다(ADR-0014, `frontend.md`). 같은 색이 되면 "판정하지 못했다"와 "확정된 매매 신호가 아니다"가 화면에서 구별되지 않는다.
- MUST NOT: primary(accent)를 초록·빨강·노랑·주황 계열로 바꾸지 않는다. 그 넷은 판정 3상태와 지지·저항이 이미 쓰고 있다.

### 초록·빨강을 두 가지 뜻에 쓰는 법 — 색이 아니라 형태로 가른다

초록·빨강이 말해야 하는 것이 **둘**이다. **조건 판정**("이 조건에 맞았는가")과 **값 구간**("이 값이 일반적으로 좋은 쪽인가")이다. 상대적 판단과 절대적 판단이라 같아 보이면 안 되는데, 색을 하나 더 만들면 화면의 색 계열이 일곱으로 늘고 "초록=좋음"이라는 직관을 사용자가 못 쓰게 된다. 그래서 색을 나누지 않고 **형태와 자리**를 나눈다.

- MUST: 판정은 **`Tag` 알약**(배경 있는 필, 아이콘 포함)으로만 그린다. 매핑의 정본은 `lib/outcome.ts`다.
- MUST: 값 구간은 **미터 막대 + 숫자 색조**로만 그린다. 매핑의 정본은 `lib/band.ts`이고 그리는 것은 `components/ui/BandMeter.vue`다. 알약을 쓰지 않는다.
- MUST NOT: **결과 화면(`ResultTable`·`ResultCards`·`ConditionOutcomes`)의 지표 값에 색조를 넣지 않는다.** 거기에는 판정 `Tag`가 함께 있어 두 채널이 한 줄에 섞인다. 값 색조는 판정 `Tag`가 없는 **종목 상세**에만 둔다 — 그 화면에서는 초록·빨강이 뜻하는 것이 하나뿐이다.
  - 등급의 **낱말**("양호", "상위 16%")은 결과 화면에도 둔다. 색이 아니라 글자이므로 채널이 겹치지 않고, 오히려 `frontend.md`가 요구하는 "값이 좋은 쪽인지 말한다"를 판정과 함께 만족시킨다. `ConditionOutcomes`가 `readBand`를 부르되 `label`만 쓰고 `tone`을 버리는 것이 그 구현이다.
- MUST NOT: 유불리 방향 표식(`▲`/`▼`)에 색을 주지 않는다. 그것은 **지표의 성질**이지 이 종목 값의 좋고 나쁨이 아니다. 색을 주면 미터가 말하는 것과 뜻이 겹쳐 어긋난다.
- 색조의 값은 `preset.ts`의 `semantic.tone`이 정한다(`--p-tone-*`). 소비처는 그것을 쓰기만 한다.

### 점수 레인 — 트랙 길이가 배점이고 채운 만큼이 획득이다

선정 이유(`ConditionOutcomes`)의 막대는 **크기를 두 축으로** 말한다. 트랙의 길이가 그 조건의 배점이고, 채워진 만큼이 이 종목이 받은 점수다. 배점 3점 조건의 트랙은 1점 조건의 3배이고, 못 받았으면 그 긴 트랙이 빈 채로 남아 "큰 것을 놓쳤다"가 길이로 읽힌다. 그리는 것은 `components/ui/RatioBar.vue`이고 판단의 정본은 `lib/scoreLane.ts`다.

- MUST: 레인은 **accent 단색**이다. 유불리 색조를 넣지 않는다 — 위 §검증 3-1이 지키는 것이 이 자리다. 못 받은 트랙을 빨갛게 칠하고 싶어지지만, 그 판단은 옆의 판정 `Tag`가 이미 하고 있다.
- MUST: 정규화 기준은 **최대 배점**이고 총점이 아니다. 총점 대비로 재면 조건이 늘수록 모든 레인이 짧아져 배점 차이가 다시 안 보인다.
- MUST: **배점이 전부 같으면 레인을 그리지 않는다.** 그때 트랙은 전부 같은 길이가 되고 채움은 0 아니면 1이라, 막대가 옆의 판정 `Tag`와 똑같은 말만 한다. 뜻이 없는 그래픽 대신 그 폭을 글자가 쓴다.
- MUST NOT: 채움을 **획득/총점**으로 되돌리지 않는다. 그것이 버린 인코딩이다 — 만점을 받은 3점 조건을 30%만 채워 그렸고 바로 옆 숫자는 `3.00 / 3.00`(만점)이었다. 한 줄에 붙은 둘이 분모가 달라 **서로를 부정했다.**
- MUST: `RatioBar`의 `floor`(값 0에 남기는 4% 흔적)를 레인에서는 **끈다.** 채움이 이진값이라 흔적이 남으면 못 받은 조건이 "조금은 받았다"로 읽힌다. `ResultTable`의 크기 막대는 켠다 — 두 자리의 판단이 갈린 것이지 한쪽이 틀린 것이 아니다.

실측이 이 규칙들을 요구했다. 점수 조건 배점이 `VALUE_KR` 3·2·2·2·1, `QVG_KR` 3·2·3·3·2·1로 갈리는 반면 `SWING_MIDTERM_KR`은 1·1·1·1(그리고 필수 17개)이고 `MOMENTUM_KR`·`ICHIMOKU_SP_KR`은 배점 자체가 없다. **앞의 둘에서는 레인이 뜻을 갖고 나머지에서는 갖지 않는다 — 그 대비가 이 설계의 근거다.** 스윙 하나만 보고 설계했으면 "쓸모없는 막대"로, 밸류 하나만 보고 설계했으면 "스윙에서 판정 Tag를 복사하는 막대"로 끝났을 것이다. 값이 변할 수 있으므로 아래 §검증의 전제가 이것을 다시 잰다.

### 조건 내역의 정렬은 subgrid가 맡는다

`ConditionOutcomes`의 목록은 `<ul>` **하나**가 그리드이고 각 행(`<li>`)이 `grid-template-columns: subgrid`로 그 트랙을 공유한다.

- MUST: 행이 **자기 그리드를 갖지 않게** 한다. 그러면 `max-content` 트랙이 행마다 따로 계산되어 실제값·점수의 좌측 경계가 행마다 어긋난다. 그것을 막으려 트랙을 전부 고정폭으로 두었던 것이 **조건문 트랙이 남는 폭을 전부 먹는** 원인이었다.
- MUST: 필수·점수 두 구획이 **한 `<ul>`을 공유한다.** 나누면 두 구획의 컬럼이 서로 어긋난다. 구획 제목은 `grid-column: 1 / -1`인 항목으로 목록 안에 든다.
- MUST NOT: `<table>` + `display: grid`로 만들지 않는다. 브라우저가 접근성 트리에서 table/row/cell 역할을 떼므로 **의미론을 얻으려는 변경이 의미론을 잃는다.** `colspan`도 표 레이아웃 알고리즘의 입력이라 그리드가 보지 않는다 — 폭을 덮으려면 `grid-column`이다.
- MUST: 글자 칸(지표·기준)은 `minmax(0, …)`에 `min-width: 0`을 함께 준다. 그리드 아이템의 자동 최소 크기가 min-content라, 없으면 트랙이 줄어도 아이템이 버티며 옆 칸을 침범하고 말줄임도 걸리지 않는다.
- MUST: 접힘은 **`@container`로 묻는다**(`@media`가 아니다). 이 패널은 데스크톱 표의 확장 행과 모바일 카드 양쪽에 들어가므로 접힘 경계가 표↔카드 교체 경계와 같지 않고, 확장 영역의 폭은 좌측 레일 때문에 뷰포트에 단조증가하지 않는다.
- **subgrid는 이 저장소에서 여기가 처음이다.** `vite.config.ts`에 `build.target`이 없고 esbuild는 subgrid를 낮춰 주지 않으므로 **빌드는 통과하고 구형 브라우저에서만 조용히 깨진다.** 개인용 도구이고 최신 Chrome/Safari로 본다는 것이 전제다. 그 전제가 바뀌면 고정 트랙으로 되돌려야 하고, 그때 위의 공백 문제가 함께 돌아온다.

### 버튼 강조는 3단이다

**채움과 "아무것도 없음" 둘만으로는 부차 액션이 화면에서 사라진다** — 윤곽·텍스트 버튼의 배경이 전부 `transparent`라 페이지 배경 그대로가 되기 때문이다. 가운데 단(M3의 `filled tonal`)을 두어 셋으로 가른다.

- MUST: 강조는 **채움 > 토널 > 고스트** 3단이고 **화면당 채움은 하나**다. 채움은 `{primary.600}` + `{surface.0}` 글자, 토널은 코발트 12% 면 + `{primary.500}` 테두리, 고스트는 쉬는 면 없이 hover만 갖는다.
- MUST NOT: **다크에서 면 없는 버튼을 부차 액션 이상에 쓰지 않는다.** 배경과 같은 색이 되어 글자만 남고, 그 글자는 링크(`a { color: var(--p-primary-color) }`)와 구별되지 않는다.
- MUST NOT: **`colorScheme.dark.root.primary.color`를 지우지 않는다.** 지우면 Aura 기본값 `contrastColor: {surface.900}`이 되살아나는데 이 앱에서 그것은 `#121722`, 곧 **카드·레일·바의 배경색과 같은 hex**다. 라이트 스킴은 `#ffffff`를 하드코딩하고 다크만 참조로 두기 때문에 `surface`를 자체 램프로 덮은 이 앱에서는 **다크만 조용히 깨진다.** 컴파일도 다른 테스트도 통과한다.
- MUST NOT: hover·active 배경에 **불투명 표면 색을 쓰지 않는다.** 팝오버(`{surface.850}`)와 들린 카드가 이미 `{surface.800}`에 가까워 그 면들에서 상태가 사라진다(대비 1.04). 반투명 오버레이만 배경과 무관하게 일정하다.
- MUST NOT: 윤곽 버튼의 테두리를 **반투명으로 밝히려 하지 않는다.** `{primary.700}`(2.44:1)을 `{primary.400}` 45% 혼합으로 바꿨다가 배경 쪽으로 섞이며 **2.19:1로 내려간** 전례가 있다. 불투명한 단계(`{primary.500}`)를 쓴다.
- MUST: `Button`의 `warn`도 `Tag`와 같이 amber다. 주황은 지지·저항 전용이라는 위 규칙이 `Tag`에만 적용돼 있어, 결과가 낡으면 실행 버튼이 지지·저항과 같은 주황으로 떴다.
- 토널의 **쉬는 면**만 `main.css`의 `:root .p-button-outlined`가 맡는다. Aura의 `outlined` 토큰에 `background`가 없어(투명이 설계 전제라 타입이 거부한다) preset에서 줄 수 없기 때문이다. `--app-surface-raised`와 같은 `--p-*` 합성값이라 위의 "색은 preset에서만"과 어긋나지 않는다.

## 검증

```bash
# 1. 토큰 밖의 색 리터럴이 스타일에 없는가 (폴백 var(--p-…, #…)만 허용)
#    마지막 필터가 CSS 블록 주석을 거른다 — 주석은 왜 그 값을 안 쓰는지를 적으려고
#    hex를 부르고, 그것까지 잡으면 이 검사가 늘 빨개져 아무도 안 보게 된다.
rg -n '#[0-9a-fA-F]{3,8}\b' frontend/src --glob '*.vue' --glob '*.css' | rg -v 'var\(--p-' | rg -v ':\s*\*'
#    → 아무것도 나오지 않아야 한다

# 2. 다크 선택자가 살아 있는가
rg -n 'class="app-dark"' frontend/index.html
rg -n "darkModeSelector: '.app-dark'" frontend/src/main.ts

# 3. Tag warn이 amber인가 (orange가 아닌가)
rg -n 'warn: \{ background:.*amber' frontend/src/theme/preset.ts

# 4. 컨트롤 높이의 짝이 살아 있는가
#    preset의 formField 패딩과 main.css의 line-height는 **함께** 목표 높이를 만든다.
#    한쪽만 바꾸면 계산이 어긋나고, 그 어긋남은 Select와 InputNumber가 세로로 붙는
#    조건 편집기에서만 눈에 띈다.
rg -n 'line-height: 1.4' frontend/src/assets/main.css
rg -n "paddingY: '0.5rem'" frontend/src/theme/preset.ts
#    → 기준선(1440×900, Chrome): 기본 컨트롤 36px, size="small" 29px,
#      아이콘 전용 버튼 36×36 / 29×29

# 3-1. 값 색조가 판정 Tag와 한 화면에 섞이지 않았는가 (채널 분리)
#    화면 쪽만 뒤지는 이유는 3-2와 같다 — **쓰는 곳만 센다.** 토큰을 정의하는 `theme/preset.ts`와
#    그것을 재는 spec, 그리고 "왜 이 토큰을 안 쓰는지"를 적은 주석까지 세면 검사가 자기 자신을
#    물고 늘 빨개진다. 실제로 ADR-0065를 쓰면서 그렇게 됐다.
rg -l -- '--p-tone-' frontend/src/components frontend/src/views
#    → components/stock/IndicatorCard.vue 와 components/ui/BandMeter.vue 뿐이어야 한다.
#      components/result/ 아래가 나오면 판정 알약과 값 색조가 한 줄에 놓인 것이다.
#      시세 등락은 이 검사에 걸리지 않는다 — 토큰 이름이 `--p-quote-*`로 갈려 있다(ADR-0065).

# 3-2. 값 색조 매핑의 정본이 하나인가 (부르는 곳만 센다 — 주석의 언급은 세지 않는다)
rg -ln 'readBand\(|toneForPosition\(|matchBandIndex\(' frontend/src
#    → lib/band.ts, lib/band.spec.ts, views/StockDetailView.vue,
#      components/result/ConditionOutcomes.vue 뿐이어야 한다.
#      BandMeter는 읽은 결과를 받기만 한다 — 두 곳이 계산하면 초록 숫자 아래 빨간 막대가 나온다.
#      ConditionOutcomes는 같은 함수를 부르되 **라벨만** 쓰고 tone은 버린다. 그래서 위 3-1에
#      나타나지 않는 것이 이 결정이 지켜졌다는 증거다.

# 4. 글자 크기가 토큰 밖에 적혀 있지 않은가 (예외는 없다 — 가장 작은 값도 `--app-text-2xs`가 받는다)
rg -n 'font-size: [0-9]' frontend/src --glob '*.vue'
#    → 아무것도 나오지 않아야 한다

# 5. PrimeVue 주입 CSS를 이기는 접두어가 살아 있는가
rg -n ':root \.p-button' frontend/src/assets/main.css

# 6. RatioBar의 새 prop이 **기본값을 실제로 갖는가**
#    타입 전용 `defineProps<…>()`는 기본값을 줄 수단이 없다. 이것이 빠지면 기존 소비처 셋에
#    `undefined`가 흘러 `width: undefined%`가 나가고 **관리자 진행률 막대가 오류 없이 사라진다.**
rg -n 'withDefaults' frontend/src/components/ui/RatioBar.vue
#    → 잡혀야 한다

# 7. 조건 내역이 행마다 자기 그리드를 갖지 않는가 (컬럼이 행마다 어긋나는 회귀)
rg -n 'grid-template-columns: subgrid' frontend/src/components/result/ConditionOutcomes.vue
#    → 잡혀야 한다. 사라졌다면 고정 트랙으로 되돌아간 것이고, 그때 오른쪽 공백이 함께 돌아온다

# 8. 레인 규칙이 테스트로 덮여 있는가 (`.vue` 안으로 되돌아가면 어떤 테스트도 닿지 않는다)
cd frontend && npx vitest run src/lib/scoreLane.spec.ts
```

```premise 레인이 뜻을 갖는 전략이 실제로 있다
psql -d stock_screener -At -c "WITH t AS (SELECT s.code, count(DISTINCT sc.weight) FILTER (WHERE NOT sc.is_required) AS spreads, coalesce(string_agg(DISTINCT trim(trailing '.' from trim(trailing '0' from sc.weight::text)), ',') FILTER (WHERE NOT sc.is_required), '없음') AS lane FROM screen s JOIN condition_group g ON g.screen_id = s.id JOIN screen_condition sc ON sc.group_id = g.id GROUP BY s.code) SELECT CASE WHEN count(*) = 0 THEN '잴 것이 없었다 — 조건을 가진 전략이 하나도 없다' WHEN count(*) FILTER (WHERE spreads >= 2) > 0 THEN 'OK 레인이 뜻을 갖는 전략 ' || count(*) FILTER (WHERE spreads >= 2) || '개 — ' || string_agg(code || ' 배점[' || lane || ']', ' / ') FILTER (WHERE spreads >= 2) ELSE '배점이 갈리는 전략이 하나도 없다 — 레인이 어디에서도 안 그려지고 이 결정의 절반이 죽은 코드다: ' || string_agg(code || ' 배점[' || lane || ']', ' / ') END FROM t"
```

전제가 묻는 것은 **배점이 갈리는 전략이 남아 있는가**다. 전부 배점이 같아지면 레인은 어디에서도 그려지지 않고, 그때 이 결정의 절반은 죽은 코드가 된다.

**MUST: 이 펜스가 참일 때 `OK`로 시작하게 유지한다.** 원래는 관측값만 뽑아 훅이 **참·거짓을 가리지 못했고**, 그래서 전제가 성립하는 동안에도 세션마다 발견으로 떴다. 그 상태는 잡음이 아니라 **탐지 실패**다 — 참이든 거짓이든 같은 화면이 나오므로 정작 배점이 평평해졌을 때 아무도 못 알아챈다. 계약은 `0000-template.md`에 있다.

```bash
# 버튼 강조 3단의 대비비
cd frontend && npx vitest run src/theme/contrast.spec.ts
```

`theme/contrast.spec.ts`가 `preset.ts`에서 램프를 import해 WCAG 상대 광도로 계산한다 — 채운 버튼 글자 ≥4.5:1, 버튼 면과 토널 테두리가 네 면 각각에서 ≥3:1, 고스트 hover가 네 면 모두에서 ≥1.2:1, 그리고 **채운 버튼의 글자색이 어느 면 색과도 같지 않을 것**을 단정한다.

마지막 항목이 대비비와 별개인 이유: `#121722` 라벨도 코발트 위에서는 5.92:1로 기준을 넘는다. **대비 검사만으로는 "면으로 쓰는 색을 글자로 썼다"를 잡지 못한다.** 실제로 그 상태가 오래 살아 있었다.

색이 실제로 읽히는지는 여기서 **수치까지만** 보장된다. 밀도·인접색과의 관계는 브라우저에서 확인한다 — 특히 팝오버와 들린 카드 위의 hover는 계산이 통과해도 눈으로 봐야 한다.

## 배경

이 앱은 종가·지표·백분위를 밀도 높게 훑는 도구이고, 그런 화면은 다크에서 숫자 대비가 더 잘 잡힌다. 그 전까지는 색의 정본이 Aura 기본 프리셋이라 화면이 "PrimeVue 데모"로 보였다.

라이트를 함께 만들지 않는 이유는 **쓰지 않는 팔레트는 검증되지 않은 채로 낡기** 때문이다. 판정 3상태와 지지·저항 주황이 두 벌 모두에서 구별되는지는 눈으로만 확인할 수 있고, 한 벌만 있으면 그 확인이 한 번으로 끝난다.

## 버린 대안

- **라이트·다크 두 벌 + 헤더 토글** — 색 예산이 이미 빡빡하다(success·danger·warn·caution 넷이 고정). 두 벌이면 대비 검증이 두 배가 되는데, 이 앱은 장 마감 후 종가를 보는 개인용 도구라 두 벌을 유지할 근거가 약하다.
- **`colorScheme.light`에 다크 값을 넣고 선택자 없이 쓰기** — `darkModeSelector` 없이 다크가 나오므로 클래스를 잊을 위험이 없다. 버린 이유는 **이름이 거짓이 되어서**다. 다음에 라이트를 켜려는 사람이 `light` 키에서 다크 값을 발견하게 되고, 그 시점에 팔레트 전체를 다시 읽어야 한다.
- **Tailwind나 별도 토큰 CSS 파일** — ADR-0019가 새 의존성의 기준을 세워 두었고, `--p-*`가 이미 PrimeVue 컴포넌트 전체에 꽂혀 있다. 토큰을 밖에 두면 컴포넌트 내부 색과 앱 색이 **두 체계**가 되어 어긋난다.
- **Aura 대신 Nora/Material 프리셋** — 바탕을 바꿔도 결국 semantic 토큰을 덮어야 한다. Aura가 이미 붙어 있으므로 바꿀 이득이 없다.
- **값 구간에 새 색쌍(teal ↔ rose 등)을 도입** — 판정과 원천적으로 겹치지 않는다. 버린 이유는 화면의 색 계열이 일곱(cobalt·emerald·red·amber·orange·teal·rose)으로 늘고, 그중 넷이 "좋다/나쁘다"를 뜻하게 되어 **사용자가 무엇을 좋다고 읽어야 하는지 배워야** 하기 때문이다. 초록=좋음은 배우지 않아도 되는 몇 안 되는 관습이고, 그것을 두 번 쓰되 형태로 가르는 편이 새 관습을 하나 더 만드는 것보다 싸다.
- **값 구간을 코발트 하나로만 표현(유리한 쪽일수록 진하게)** — 색 예산을 전혀 건드리지 않는다. 버린 이유는 **불리한 값이 "값이 없다"와 같아 보이기** 때문이다. 흐린 코발트와 회색 트랙은 다크에서 구별되지 않고, `—`(MISSING)로 그리는 자리와도 헷갈린다.

## 결과

색을 한 곳에서 바꿀 수 있고, PrimeVue 컴포넌트 내부까지 같은 팔레트를 쓴다. 대신 라이트 화면은 존재하지 않으며, 켜려면 `colorScheme.light`를 새로 설계하고 판정 3상태·지지·저항 주황의 대비를 다시 검증해야 한다. 되돌리려면 `theme/preset.ts`를 지우고 `main.ts`의 `preset`을 `Aura`로, `index.html`의 클래스와 `color-scheme`을 되돌린다.

값 색조를 더하면서 생긴 대가는 **"어느 화면에 무엇을 그리는가"가 규칙이 되었다는 것**이다. 형태로 채널을 갈랐으므로 결과 화면에 값 색조를 넣는 순간 두 뜻이 한 줄에 섞인다. 그 위반은 컴파일도 테스트도 잡지 못하고 눈으로만 보이므로, 위 §검증 3-1이 그 자리를 지킨다. 되돌리려면 `semantic.tone`과 `lib/band.ts`·`BandMeter.vue`를 걷어낸다 — 지표 카드는 값만 남고 ADR-0023 이전 상태로 돌아간다.
