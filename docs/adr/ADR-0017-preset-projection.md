---
id: ADR-0017
status: 채택됨
date: 2026-08-06
applies_to:
  - "src/main/java/com/stockscreener/screening/preset/**"
  - "src/main/java/com/stockscreener/screening/store/**"
---

# ADR-0017. 프리셋은 코드 선언을 DB로 투영하고 폐기는 비활성화로만 한다

## 결정

사전 정의 전략(프리셋)을 `PresetCatalog`에 코드로 선언하고, 기동 시 `PresetSynchronizer`가 `screen`·`condition_group`·`screen_condition`으로 투영한다. `screen.code`가 키다. 선언에서 사라진 프리셋은 **`is_active = false`로 내릴 뿐 삭제하지 않는다.** 시더는 커스텀 화면과 **같은** 저장 경로(`ScreenStore`)와 **같은** 검증(`ScreenDefinitionValidator`)을 쓴다.

## 규칙

- MUST: 프리셋 정의의 정본은 코드다. DB 행은 사본이며 재기동 때 덮어써진다.
- MUST: 동기화가 멱등하다. 두 번 기동해도 프리셋 수와 조건 수가 늘지 않는다.
- MUST NOT: 프리셋 행을 DELETE하지 않는다. `watchlist_item.screen_id`가 FK로 붙어 있어 삭제가 실패하거나 사용자 데이터를 끊는다.
- MUST: 비활성화 UPDATE에 `screen_type = 'PRESET'` 조건을 반드시 붙인다. 빠지면 **사용자의 커스텀 화면이 전부 비활성화된다.**
- MUST: `PresetCatalogInitializer`가 `IndicatorCatalogInitializer`보다 **나중에** 돈다(`@Order(10)` vs `@Order(0)`).
- MUST NOT: 시더 전용 저장 경로를 만들지 않는다. 검증을 우회하면 UI가 열지 못하는 화면이 생긴다.
- MUST: 지표 코드를 문자열 리터럴이 아니라 계산기의 `CODE` 상수로 참조한다.

## 검증

```bash
./gradlew test --tests "*PresetSynchronizerTest*"
```
멱등성, 사라진 프리셋의 비활성화(행 유지), **커스텀 화면 불간섭**, 조건 교체를 확인한다.

```bash
./gradlew test --tests "*PresetCatalogTest*"
```
IMPORTANT — 백분위 조건의 부등호가 원본 지표의 유불리와 일치하는지 확인한다. `PER_PCTL <= 0.3`(낮을수록 좋음)과 `ROE_PCTL >= 0.7`(높을수록 좋음)이 뒤집히면 프리셋이 **정반대 종목**을 최상위로 올리는데, 점수도 결과도 그럴듯해서 화면으로는 드러나지 않는다. 자동으로 잡을 수 있는 유일한 지점이다.

```bash
grep -rn '@Order' src/main/java/com/stockscreener/indicator/IndicatorCatalogInitializer.java \
                  src/main/java/com/stockscreener/screening/preset/PresetCatalogInitializer.java
```
0과 10이 있어야 한다. 없으면 깨끗한 DB에서 기동이 FK 위반으로 실패할 수 있다.

## 배경

spec §3이 "사전 정의된 투자 전략을 프리셋 형태로 제공"을 요구하고 §9의 수용 기준이 "최소 1개 이상의 프리셋으로 스크리닝을 실행"을 요구한다. V3는 프리셋과 커스텀을 **같은 테이블**에 두기로 이미 정했다(`screen_type`으로 구분).

프리셋을 코드에 두는 이유는 그것이 **제품의 일부**이지 사용자 데이터가 아니기 때문이다. 조건 변경이 diff에 남고 리뷰를 거치며 테스트가 검증한다. 그럼에도 DB로 투영하는 이유는 **실행 경로를 하나로 두기 위해서**다 — 프리셋을 메모리에서만 다루면 "프리셋 실행"과 "저장된 화면 실행"이 다른 코드가 되고, 지표를 하나 추가할 때 고칠 곳이 둘이 된다(`backend.md`가 금지).

삭제하지 않는 규칙은 지표 카탈로그의 `deactivateMissing`과 같은 형태이되 근거가 더 강하다. 지표는 저장된 화면이 참조할 뿐이지만, 프리셋은 `watchlist_item`이 FK로 직접 참조한다.

## 버린 대안

- **프리셋을 마이그레이션 SQL로 시딩** — 한 번 넣고 끝나 단순하다. 그러나 조건을 고칠 때마다 새 마이그레이션이 필요하고, 그 SQL이 도메인 불변식(BETWEEN의 상한, 백분위 방향)을 검증하지 못한다. 테스트로 잡을 수도 없다.
- **프리셋을 코드에만 두고 DB에 넣지 않음** — 저장량이 0이다. 그러나 실행 경로가 갈라지고, `watchlist_item.screen_id`가 프리셋을 가리킬 수 없어 Phase 6의 워치리스트 알림이 프리셋을 조건으로 쓰지 못한다.
- **`ON CONFLICT (code) DO UPDATE`로 직접 upsert** — 시더가 저장소를 우회하게 되고, `uq_screen_code`가 **부분 유니크 인덱스**(`WHERE code IS NOT NULL`)라 술어를 반복하지 않으면 런타임에 `no unique or exclusion constraint matching`이 난다. `findIdByCode` 후 create/update로 나누면 그 함정 자체가 없어지고 검증도 공유된다.
- **사라진 프리셋 삭제** — 위 규칙 참조.
- **`@Component` 하나당 프리셋 하나** — 계산기와 대칭이라 일관돼 보이지만, 프리셋은 행위가 아니라 데이터이고 가중치 균형은 나란히 놓고 봐야 잡을 수 있다. `PercentileConfig`와 같은 형태를 택했다.

## 결과

- 쉬워지는 것: 프리셋 추가·수정이 `PresetCatalog`의 한 블록이고 배포로 반영된다. 실행 경로가 저장된 화면과 완전히 같아 API도 화면도 프리셋을 특별 취급하지 않는다.
- 어려워지는 것: 운영 중 프리셋을 고치려면 배포가 필요하다. 관리자 화면에서 프리셋을 편집하게 하려면 이 결정을 뒤집어야 한다 — 그때는 "코드가 정본"이 성립하지 않는다.
- 되돌리려면: `screening.preset` 패키지와 기동 러너를 함께 고쳐야 한다. 이미 투영된 행은 남아 있으므로 데이터 손실은 없다.
