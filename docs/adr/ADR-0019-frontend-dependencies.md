---
id: ADR-0019
status: 채택됨
date: 2026-08-07
applies_to:
  - "frontend/package.json"
  - "frontend/src/**"
---

# ADR-0019. 프론트엔드 의존성을 고정하고 PrimeVue는 MIT인 4.x에 묶는다

## 결정

Vue3·PrimeVue는 spec §6이 이미 정했으므로 여기서 다시 고르지 않는다. 새로 고른 것만 기록한다 — Vite, TypeScript, vue-router, Pinia, Vitest. HTTP 클라이언트는 별도 라이브러리 없이 `fetch` 래퍼로 만든다.

IMPORTANT — **PrimeVue는 4.5.5(MIT)에 고정한다.** 최신인 5.0.0은 라이선스가 바뀌었다.

## 규칙

- MUST: `primevue`는 `4.x`, `@primeuix/themes`는 `2.x`를 쓴다. 둘 다 MIT이고 `@primeuix/styled@^0.7`을 공유한다.
- MUST NOT: `primevue@5` 이상으로 올리지 않는다. 라이선스 키가 필요해지고, 키가 없거나 만료되면 **화면에 라이선스 고지가 뜬다.**
- MUST NOT: axios 같은 HTTP 라이브러리를 추가하지 않는다. 필요한 것은 CSRF 헤더·재시도·`problem+json` 파싱뿐이고 전부 `fetch` 래퍼 한 곳에 있다.
- MUST: 의존성 버전을 `package.json`에 정확한 값으로 적는다(캐럿 금지, `@types/*` 제외). 잠금 파일과 함께 커밋한다.
- MUST: TypeScript `strict`를 켠 채로 둔다. 판정 상태(`MATCHED`/`NOT_MATCHED`/`NOT_EVALUABLE`)와 오류 타입을 exhaustive switch로 다루는 것이 이 선택의 목적이다.

## 검증

```bash
# 라이선스 매니저가 딸려오지 않아야 한다 (= 상용 라인이 아니다)
cd frontend && npm ls @primeui/license-manager   # "(empty)" 또는 not found

# 선언한 버전이 그대로 설치되었는가
cd frontend && npm ls primevue @primeuix/themes

# 타입 검사가 빌드의 일부다
cd frontend && npm run build
```

## 배경

착수 시점(2026-08-07)에 `npm view primevue version`이 **5.0.0**을 준다. 그런데 5.0.0의 `LICENSE.md`는 MIT가 아니라 PrimeTek의 상용 라이선스 계열이다 — 연매출 100만 달러 미만·개발자 5인 미만 등의 조건을 만족하면 무료인 "Community License"를 쓸 수 있으나 **라이선스 키가 필요하고 매년 갱신해야 하며, 키가 없거나 만료되면 라이선스 고지가 표시될 수 있다.** 의존성에 `@primeui/license-manager`("Offline license verifier")가 들어 있는 것이 그 실체다.

spec §6이 PrimeVue를 고른 근거는 "정렬·필터·가상스크롤을 **무료로** 지원"이었다. 이 프로젝트는 개인용이라 Community License 자격 자체는 충족하지만, 그 전제가 **무조건**에서 **매년 갱신하는 조건부**로 바뀐다. 4.5.5는 MIT이고 `v4-stable` 태그로 유지되고 있어 같은 목적을 조건 없이 만족한다.

## 버린 대안

- **PrimeVue 5 + Community License 키 발급** — 자격은 되지만 갱신을 잊으면 사용자가 보는 화면에 고지가 뜬다. 지인이 쓰는 서비스에서 그 실패 모드는 설명하기 곤란하고, 실패 시점이 1년 뒤라 아무도 기억하지 못한다.
- **PrimeVue 대신 다른 표 라이브러리** — spec §6의 결정을 뒤집는 일이고, 4.x가 요구를 그대로 만족하므로 뒤집을 이유가 없다.
- **jsdom + 컴포넌트 렌더링 테스트** — 검증하려는 것이 재시도 판별·서식·상태 매핑 같은 순수 로직이라 DOM 런타임을 얹어도 잡히는 결함이 늘지 않는다. `document.cookie`를 읽는 한 곳은 테스트가 전역을 직접 대체한다.
- **axios** — 인터셉터가 편하지만 `fetch`로 30줄이면 끝나는 일에 의존성을 늘린다. `problem+json` 파싱과 상태 코드 기반 재시도 판별(ADR-0015)은 어차피 직접 써야 한다.

## 결과

라이선스 조건을 신경 쓰지 않아도 되고 화면에 고지가 뜰 위험이 없다. 대신 PrimeVue 5의 새 컴포넌트·API를 쓸 수 없고, 4.x가 유지보수를 멈추면 그때 다시 판단해야 한다. 되돌리려면 `frontend/package.json`의 두 버전과 그에 따라 바뀌는 테마 임포트 경로를 고치고, 라이선스 키 주입 경로를 만들어야 한다.
