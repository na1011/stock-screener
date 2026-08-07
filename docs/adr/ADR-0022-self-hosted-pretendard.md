---
id: ADR-0022
status: 채택됨
date: 2026-08-07
applies_to:
  - "frontend/public/fonts/**"
  - "frontend/src/assets/main.css"
  - "frontend/index.html"
  - "src/main/java/com/stockscreener/web/WebMvcConfig.java"
---

# ADR-0022. Pretendard를 전체 variable 한 파일로 자체 호스팅한다

## 결정

Pretendard Variable(v1.3.9, OFL-1.1)의 **전체** woff2 한 파일(2.0MB)을 `frontend/public/fonts/PretendardVariable-1.3.9.woff2`에 두고 `@font-face`로 직접 건다. CDN을 쓰지 않고, npm 패키지로 받지도 않으며, 서브셋도 쓰지 않는다.

## 규칙

- MUST: 파일명에 버전을 유지한다(`PretendardVariable-<version>.woff2`). `WebMvcConfig`가 `/fonts/**`에 `immutable` 캐시를 거는 근거가 이 이름이다 — 버전을 지우면 서체를 바꿔도 1년 동안 옛 파일이 나간다.
- MUST: 버전을 올릴 때 파일명·`main.css`의 `src`·`index.html`의 `preload` **셋을 함께** 바꾼다.
- MUST: `font-display: swap`을 유지한다. 2MB가 첫 페인트를 막지 않게 하는 유일한 장치다.
- MUST NOT: CDN(`cdn.jsdelivr.net` 등)에서 불러오지 않는다. ADR-0018이 정한 단일 오리진을 깨고, 터널 뒤에서 도는 개인용 앱이 외부 가용성에 묶인다.
- MUST NOT: `pretendard` npm 패키지를 의존성에 추가하지 않는다. 필요한 것이 바이너리 한 개라 ADR-0019의 기준을 넘지 못한다.
- MUST: `public/fonts/LICENSE.txt`(OFL 전문)를 함께 둔다. OFL이 요구한다.

## 검증

```bash
# 1. 서체 파일과 라이선스가 있는가
ls -l frontend/public/fonts/

# 2. 세 곳의 버전이 같은가 — 하나만 어긋나면 서체가 조용히 안 나온다
rg -o 'PretendardVariable-[0-9.]+\.woff2' frontend/src/assets/main.css frontend/index.html
ls frontend/public/fonts/ | rg 'PretendardVariable-[0-9.]+\.woff2'

# 3. 번들을 거쳐 실제로 서빙되는가 (SpaFallbackTest가 단언한다)
./gradlew test --tests "*SpaFallbackTest*"
```

## 배경

`main.css`는 처음부터 `font-family`에 `'Pretendard'`를 적어 두었으나 **어디서도 로드하지 않았다.** 선언만 있고 파일이 없으니 조용히 시스템 서체로 떨어졌고, 그래서 "왜 화면이 밋밋한가"의 원인 중 하나가 코드를 읽어도 보이지 않았다. 이 앱은 종가·지표·백분위를 표로 훑는 도구라 자간·숫자 정렬이 인상을 크게 좌우한다.

전체 파일을 고른 이유는 **종목명 때문**이다. 흔히 쓰는 KS X 1001 서브셋(가중치당 약 267KB)은 한글 2,350자만 담아, 그 밖의 음절이 든 종목명은 그 글자만 시스템 서체로 떨어진다. 표 한 줄 안에서 서체가 섞이는 것은 서체를 안 쓰는 것보다 나쁘다. 동적 서브셋(92분할)은 전송량은 줄지만 한국어 UI는 음절이 유니코드 구간 전반에 흩어져 있어 실제로 수십 개를 받게 되고, **저장소에 93개 바이너리가 들어간다** — iCloud 복제로 이미 한 번 컴파일이 멈춘 저장소에서 그 위험은 전송량 절감보다 크다.

## 버린 대안

- **정적 서브셋 3종(400/600/700, 합 807KB)** — 전송량은 절반 이하지만 KS X 1001 밖 음절이 든 종목명에서 서체가 섞인다. 이 앱에서 가장 자주 보는 문자열이 종목명이라 정확히 최악의 자리에서 드러난다.
- **동적 서브셋 92분할** — 전송량은 가장 좋다. 저장소에 93개 바이너리가 들어가는 것과, `checkDuplicateFiles`가 `frontend/public`을 훑지 않아 복제본이 생겨도 잡히지 않는 것이 이유다.
- **Google Fonts / jsDelivr CDN** — 한 줄로 끝나지만 ADR-0018의 단일 오리진을 깬다. 외부 호스트가 죽으면 화면이 시스템 서체로 떨어지고, 그것을 알아차릴 방법이 없다.
- **시스템 서체만 쓰기** — 비용 0. macOS는 Apple SD Gothic Neo로 괜찮지만 Windows 기본(맑은 고딕)은 숫자 정렬과 굵기 대비가 눈에 띄게 떨어진다. 지인이 쓰는 서비스라 클라이언트 OS를 고를 수 없다.

## 결과

첫 방문에 2.0MB를 받는다. `swap`이라 렌더는 막히지 않고, `immutable` 캐시라 이후 방문에는 요청조차 나가지 않는다. 그 대가로 한글·한자 커버리지가 완전하고 가변 축을 그대로 쓴다. 전송량이 문제가 되면 되돌리는 경로는 정적 서브셋 3종이며, 그때 `@font-face`가 셋으로 늘고 종목명 일부에서 서체가 섞이는 것을 받아들이게 된다.
