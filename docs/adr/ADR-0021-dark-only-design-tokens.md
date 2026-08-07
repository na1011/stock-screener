---
id: ADR-0021
status: 채택됨
date: 2026-08-07
applies_to:
  - "frontend/src/theme/**"
  - "frontend/src/assets/main.css"
  - "frontend/src/main.ts"
  - "frontend/index.html"
---

# ADR-0021. 화면을 다크 한 벌로 하고 디자인 토큰의 정본을 PrimeVue 프리셋에 둔다

## 결정

색·간격·반경의 정본을 `frontend/src/theme/preset.ts`의 `definePreset(Aura, …)`에 둔다. 화면은 **다크 한 벌만** 만들고 `<html class="app-dark">`로 항상 켜 둔다. 라이트 팔레트는 손대지 않는다.

## 규칙

- MUST: 색·반경·폼 필드 치수는 `theme/preset.ts`에서만 정의한다. `.vue`와 `main.css`는 `--p-*`를 **소비만** 한다.
- MUST NOT: `main.css`나 `<style scoped>`에 색 리터럴(hex·rgb·hsl)을 적지 않는다. 예외는 `PriceReferencePanel`의 `var(--p-orange-400, #fb923c)` 같은 **폴백**뿐이다.
- MUST: `index.html`의 `<html class="app-dark">`를 유지한다. 지우면 `darkModeSelector`가 걸리지 않아 **손보지 않은 Aura 라이트 팔레트**로 떨어진다. 컴파일도 되고 테스트도 통과한다.
- MUST NOT: `darkModeSelector`를 기본값 `'system'`으로 되돌리지 않는다. OS 설정만으로 검증하지 않은 라이트 화면이 나온다.
- MUST: `Tag`의 `warn`을 amber로 유지한다. Aura 기본값은 `{orange.*}`이고, 주황은 지지·저항 참고정보 **전용**이다(ADR-0014, `frontend.md`). 같은 색이 되면 "판정하지 못했다"와 "확정된 매매 신호가 아니다"가 화면에서 구별되지 않는다.
- MUST NOT: primary(accent)를 초록·빨강·노랑·주황 계열로 바꾸지 않는다. 그 넷은 판정 3상태와 지지·저항이 이미 쓰고 있다.

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

## 결과

색을 한 곳에서 바꿀 수 있고, PrimeVue 컴포넌트 내부까지 같은 팔레트를 쓴다. 대신 라이트 화면은 존재하지 않으며, 켜려면 `colorScheme.light`를 새로 설계하고 판정 3상태·지지·저항 주황의 대비를 다시 검증해야 한다. 되돌리려면 `theme/preset.ts`를 지우고 `main.ts`의 `preset`을 `Aura`로, `index.html`의 클래스와 `color-scheme`을 되돌린다.
