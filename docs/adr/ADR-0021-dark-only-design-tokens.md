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
- MUST NOT: 유불리 방향 표식(`▲`/`▼`)에 색을 주지 않는다. 그것은 **지표의 성질**이지 이 종목 값의 좋고 나쁨이 아니다. 색을 주면 미터가 말하는 것과 뜻이 겹쳐 어긋난다.
- 색조의 값은 `preset.ts`의 `semantic.tone`이 정한다(`--p-tone-*`). 소비처는 그것을 쓰기만 한다.

## 검증

```bash
# 1. 토큰 밖의 색 리터럴이 스타일에 없는가 (폴백 var(--p-…, #…)만 허용)
rg -n '#[0-9a-fA-F]{3,8}\b' frontend/src --glob '*.vue' --glob '*.css' | rg -v 'var\(--p-'
#    → 아무것도 나오지 않아야 한다

# 2. 다크 선택자가 살아 있는가
rg -n 'class="app-dark"' frontend/index.html
rg -n "darkModeSelector: '.app-dark'" frontend/src/main.ts

# 3. Tag warn이 amber인가 (orange가 아닌가)
rg -n 'warn: \{ background:.*amber' frontend/src/theme/preset.ts

# 3-1. 값 색조가 판정 Tag와 한 화면에 섞이지 않았는가 (채널 분리)
rg -ln -- '--p-tone-' frontend/src
#    → components/ui/BandMeter.vue 와 views/StockDetailView.vue 뿐이어야 한다.
#      components/result/ 아래가 나오면 판정 알약과 값 색조가 한 줄에 놓인 것이다.

# 3-2. 값 색조 매핑의 정본이 하나인가 (부르는 곳만 센다 — 주석의 언급은 세지 않는다)
rg -ln 'readBand\(|toneForPosition\(|matchBandIndex\(' frontend/src
#    → lib/band.ts, lib/band.spec.ts, views/StockDetailView.vue 뿐이어야 한다.
#      BandMeter는 읽은 결과를 받기만 한다 — 두 곳이 계산하면 초록 숫자 아래 빨간 막대가 나온다.

# 4. 글자 크기가 토큰 밖에 적혀 있지 않은가
#    (캐럿·마커의 0.625rem 예외 둘은 2026-08-08에 `--app-text-2xs`로 흡수됐다. 예외는 없다)
rg -n 'font-size: [0-9]' frontend/src --glob '*.vue'
#    → 아무것도 나오지 않아야 한다

# 5. PrimeVue 주입 CSS를 이기는 접두어가 살아 있는가
rg -n ':root \.p-button' frontend/src/assets/main.css
```

색이 실제로 읽히는지(대비비)는 자동 검증 대상이 아니다. 브라우저에서 본문 4.5:1, 큰 글자 3:1을 확인한다.

## 배경

Phase 5 착수 시점의 `main.ts`는 `darkModeSelector: '.app-dark'`를 **존재하지 않는 선택자로** 지정해 OS 다크 모드를 봉인하고 있었다. "v1은 라이트 한 벌만 손본다"는 판단이었고 합리적이었다. 그런데 손본 것이 실제로는 없었다 — 전역 CSS는 38줄이고 색의 정본이 Aura 기본 프리셋이라 화면이 "PrimeVue 데모"로 보였다. 이 앱은 종가·지표·백분위를 밀도 높게 훑는 도구이고, 그런 화면은 다크에서 숫자 대비가 더 잘 잡힌다.

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
