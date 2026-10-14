# 관문 순서 0 — 관문 설정을 시장·묶음별로 가른다

> **정본은 맨 아래 「계획 v2」 절이다**(2026-10-14). v1 본문은 반증이 겨눈 판이라 남긴다.

## 왜
- 지금 main의 미국 관문 설정은 `stockscreener.gate.us`(불리언 하나 · `US_GATE`)이고 읽는 곳은 `SecEdgarFinancialAdapter` 하나다(S0 · `faeb2d7`).
- P3a·P2 반증 둘이 같은 것을 잡았다: 다음 묶음(P2·P3a…)이 같은 불리언 뒤에 들어오면 **「S0만 켠 상태」가 main에 존재하지 않는다** — S0 귀속 드라이런과,
  P2·P3의 대조군(= S0만 켠 상태 · md5 38aece06)을 main 코드로 재현할 길이 사라진다. 결정을 미루면 그 자체가 「귀속 불가」를 고르는 것이다.
- `CLAUDE.md` 「재적재·백필」의 규칙(에이전트 결정): **제품 상태는 「전부 끔」과 「전부 켬」 둘뿐**이고 관문 전 사본 실행은 인자로만 켠다.

## 수정
1. `application.yaml`: 주 설정 아래 묶음 설정을 둔다 — `stockscreener.gate.us-s0: ${US_GATE_S0:${stockscreener.gate.us}}`. **기본값은 주 설정**이라 주 설정 하나로 「전부 끔/전부 켬」이 된다.
   다음 묶음은 같은 꼴로 한 줄씩 더한다(`us-p2` …).
2. `UsGate` 레코드(같은 패키지 · 묶음마다 불리언 하나 — 지금은 `s0`) · `UsGate.allOff()`.
   `SecEdgarFacts.build(facts, from, UsGate)` — 지금의 `boolean usGate` 자리를 바꾼다. 2인자 진입점은 `allOff()`. 내부는 `gate.s0()`를 넘긴다(동작 무변화).
3. 어댑터가 주 설정과 묶음 설정을 함께 읽어 `UsGate`를 만든다(읽는 곳은 여전히 여기 하나).
4. **부분 상태 가드**: 기동 시 묶음 설정 중 하나라도 주 설정과 다르면, 연결된 DB 이름이 운영 DB 이름(`stockscreener.gate.product-database` · 기본 `stock_screener`)과 같을 때 기동을 세운다(`InitializingBean` · `Connection#getCatalog()` — `BootstrapDatabaseGuard`와 같은 꼴).
   운영 jar(배포 `.env`)나 저장소 `.env`에 묶음 하나만 켜 두는 실수가 제품 상태 셋째를 만들지 못하게 한다. 사본 DB(`boot()` — `DB_URL`이 사본)에서는 인자로 하나씩 켤 수 있다.
5. 스윕 시험(`SecEdgarFactsSweepTest`): `US_GATE_S0`(없으면 `US_GATE`)를 읽는다 · 출력 줄에 묶음별 상태를 찍는다.
6. 시험: 전부 끔 = 2인자 진입점(기존 `gateOffMatchesLegacyEntryPoint`) · `s0` 켬 = 지금 `usGate=true` 출력(기존 네 시험을 `UsGate`로) · 가드: 부분 상태 + 운영 DB 이름 → 기동 실패 / 부분 상태 + 다른 DB → 기동 / 고른 상태 → 기동.
7. `CLAUDE.md` 「재적재·백필」의 설정 문장에 「묶음별 하위 설정 · 부분 상태 가드」를 더한다(에이전트 결정 표시 유지) · `docs/reload-gate.md` 순서 0을 닫음으로.

## 수용 기준
- 스윕 바이트 동일: `US_GATE=false` → md5 121a06ab(설정 생기기 전 기준선) · `US_GATE=true` → md5 38aece06(S0 켬) · TSV 밖 필드(`REPLAY_SWEEP_EXTRA`)도 같다.
- 전체 빌드 시험 실패 0 · 새 시험이 대조군으로 붉어지는지(가드를 지우면 가드 시험이 붉다).

## 재지 않은 것
- `${US_GATE_S0:${stockscreener.gate.us}}`의 중첩 자리표시자가 Spring Boot 4에서 이 꼴로 풀리는지 — 시험으로 잰다.
- 운영 앱의 DB 이름이 정말 `stock_screener`인지 — `ops/status.sh`·배포 `.env`를 읽어야 하는데 `.env`는 읽지 않는다(시크릿). 기본값이 그 이름이고 `DB_URL` 기본값도 같다.

## 반증 v1 판정 (2026-10-04 · 두 렌즈) — v2로 간다
- **선 것**: 중첩 자리표시자는 Boot 4.1.0에서 의도대로 풀린다(16조합 실측) · yaml 키가 `@Value` 기본값을 이긴다 · 운영 DB 이름은 `stock_screener`(클러스터 DB 넷 · 운영 로그 Flyway 줄) · `getCatalog()`는 `select current_catalog`로 서버에 묻는다.
- **깨진 것**
  1. **「배포 `.env`는 저장소 `.env`와 따로다」가 거짓**(`ops/stage.sh:40`이 배포마다 복사) — 다섯 곳의 문장을 `b0df6ba`로 바로잡았다: 켜는 길은 코드 기본값을 뒤집은 jar 하나.
  2. 수용 기준이 설정 배선(yaml → `@Value` → 어댑터)을 한 번도 안 태운다 — 어댑터는 `@ConditionalOnApiKey`라 시험 컨텍스트에 빈이 없다 · `@Value` 기본값이 키 오타를 삼킨다(`us-so` → 조용히 끔).
  3. 스윕·하네스가 설정 해석을 손으로 복제한다(`"true".equals` 대 Spring의 TRUE·1·yes · 빈 값) · `EarlierVintageReplayHarnessTest`·`SecEdgarFactsReplayTest`는 2인자 진입점이라 늘 끔.
  4. 다음 소비자가 미국 어댑터 밖에 있다(순서 1(c) `FinancialWriter.overwritable()` · 순서 7 `GovernanceMath.annualOf` — 두 시장 공용) → 관문 상태는 **중립 패키지의 단일 바인딩 빈**(시장 → 묶음)이어야 하고 「읽는 곳은 하나」는 「바인딩은 하나」로 바꾼다 · 이름 공간(`gate.us`가 스칼라)도 지금 정한다.
  5. `US_GATE_S0` 같은 묶음 환경변수는 쓰는 곳이 없고 운영 부분 상태의 유일한 길이다 → 묶음은 환경변수 이름 없이 주 설정을 기본값으로(`${stockscreener.gate.us…}`) · 가드는 인자·relaxed 이름(`STOCKSCREENER_GATE_…`)을 막는 백스톱.
  6. 가드: 선례 `BootstrapDatabaseGuard`는 `@Profile("bootstrap")`이라 그대로 따르면 운영이 빠진다 · bootstrap에서 서면 종료코드 0 · 유효 관문 상태를 찍는 곳이 0(기동 로그 · refill note)이라 「어느 상태로 돌았나」를 SELECT로 증명할 길이 없다 · `gateOffMatchesLegacyEntryPoint`는 항진명제.
  7. 스윕 측정 함정: env만 바꾼 재실행은 `:test UP-TO-DATE`로 안 돈다 — `--rerun-tasks` + 새 출력 경로 + 그 실행 XML의 `SWEEP 관문=` 줄로 판정(기준선 EXTRA md5 끔 81bb1782 · 켬 6ddcc3e1).
- **순위(반증 밖 렌즈 · 내 판단으로 받음)**: 지금 main의 묶음은 S0 하나라 `US_GATE=true` = 「S0만」이다 — **설정 분리는 다음 묶음이 main에 들어오기 전까지만 있으면 된다.** 그 전에 날짜가 박힌 축(재무 정정 자동 백필 상한 100 대 10월 말 첫 정기 refill — `BACKFILL-CAP-PLAN.md`)을 먼저 한다.


---

## 계획 v2 (2026-10-14 · v1 반증 일곱 반영 + 그 뒤 main이 바뀐 것을 다시 잼)

### 지금 main (★ 10-14 내가 잼)
- 주 설정 `stockscreener.gate.us: false`(classpath yaml · 환경변수 자리표시자 없음). 출처 허용 목록 가드 `GateSettingGuard`가 있다(ADR-0166).
  - 실효값을 정한 출처가 명령줄 인자나 classpath `application*.yaml`이 아니면 기동을 세운다.
  - 옛 이름 `US_GATE`가 실효값과 다르면 기동을 세운다.
  - 매 기동 `관문 설정 …=… (출처: …)`를 INFO로 찍는다.
  - → v1 반증 1(배포 `.env`)·5(묶음 환경변수가 부분 상태의 길)는 이 가드가 이미 닫았다.
- 읽는 곳: `SecEdgarFinancialAdapter`가 `@Value("${stockscreener.gate.us}")`로 읽고, `SecEdgarFacts.build(facts, from, boolean usGate)`(S0)로 넘긴다.
- 시험 쪽: `SecEdgarFactsSweepTest:91`이 `"true".equals(System.getenv("US_GATE"))`로 손으로 해석한다(v1 반증 3). `SecEdgarControllingQ4GateTest`는 `build(…, true/false)`를 직접 부른다.
- **국내 관문 설정이 없다.** 그런데 국내 관문 대상이 이미 있다 — 001720 결산월 라벨(가짜 한계 · 값을 바꾸는 어댑터 변경 · progress [국내 결산월]).
  - 다음 소비자도 미국 어댑터 밖이다: `FinancialWriter.overwritable()`(주식수 0 · 두 시장) · `GovernanceMath.annualOf`(두 시장)(v1 반증 4).

### 결정
1. **주 설정은 시장마다 스칼라 하나**: `stockscreener.gate.us`(그대로) · `stockscreener.gate.kr: false`(신설). 「제품 상태는 시장마다 전부 끔 · 전부 켬」의 단위다.
2. **묶음 설정은 다른 이름 공간**: `stockscreener.gate-bundle.<market>.<bundle>: ${stockscreener.gate.<market>}`.
   - `gate.us`가 스칼라라 그 아래에 키를 못 단다(v1 반증 4의 「이름 공간을 지금 정한다」).
   - 기본값이 주 설정을 가리키는 자리표시자다. 환경변수 이름이 없으므로 부분 상태로 가는 이름 붙은 길이 없다(v1 반증 5).
   - 지금 묶음은 `us.s0` 하나다. 다음 묶음(P2 · 국내 결산월 …)은 한 줄씩 더한다.
3. **중립 단일 바인딩 빈 `ReloadGates`**(`com.stockscreener.pipeline` · 가드 옆): 시장 → 묶음 → 불리언.
   - 모든 소비자가 이것만 읽는다 — 「읽는 곳은 하나」를 「바인딩은 하나」로 바꾼다(v1 반증 4).
   - `@Value` 기본값을 두지 않는다. 키가 없으면 기동을 세운다(v1 반증 2의 「오타가 조용히 끔」).
   - 묶음 키 목록은 enum(`UsBundle {S0}`)이 정본이다. yaml에 enum 밖 키가 있으면 기동을 세운다(오타 `us.so`).
4. **가드를 넓힌다**(`GateSettingGuard` — 새 가드를 만들지 않는다)
   - 출처 허용 목록을 `stockscreener.gate.*`·`stockscreener.gate-bundle.*` 전 키로 넓힌다.
   - **부분 상태 거절**: 한 시장의 묶음 실효값이 그 시장 주 설정과 다르고, 연결 DB 이름이 운영 DB 이름이면 기동을 세운다.
     - 운영 DB 이름은 `stockscreener.gate.product-database`이고 기본 `stock_screener`다. 연결 DB는 `select current_catalog`로 묻는다.
     - 사본(`boot()`의 `DB_URL`)에서는 인자로 묶음 하나만 켤 수 있다(귀속 대조군).
   - v1 반증 6: 선례 `BootstrapDatabaseGuard`는 `@Profile("bootstrap")`이지만 이 가드는 프로파일 없이 모든 기동에서 돈다(지금도 그렇다). bootstrap에서 서면 종료코드가 0인 문제는 이 가드가 `afterPropertiesSet`에서 던져 컨텍스트가 안 뜨는 길이라 해당 여부를 D3에서 종료코드로 잰다(재지 않음).
   - 기동 로그: 시장마다 주 설정과 묶음 전부를 한 줄로 찍는다.
5. **실행이 어느 상태로 돌았는지를 행에 남긴다**(v1 반증 6 「SELECT로 증명할 길이 없다」): 재무를 쓰는 잡(FR · FSR · initial-load · 소비 · 따라잡기)의 `note` 끝에 `관문=us:off,kr:off`(묶음이 주 설정과 같으면 시장만, 다르면 `us:off(s0:on)`)를 붙인다.
6. **시험과 스윕이 같은 해석을 쓴다**(v1 반증 3·7)
   - `ReloadGates.from(PropertyResolver)` 하나로 해석한다. 스윕은 `StandardEnvironment`에 시스템 속성·환경변수를 실어 같은 메서드로 푼다 — 문자열 비교로 손 해석하지 않는다.
   - `US_GATE` 대신 `-Dstockscreener.gate.us=true`(인자 꼴)를 받는다.
   - 스윕 판정은 `--rerun-tasks` + 새 출력 경로 + 그 실행 XML의 `SWEEP 관문=` 줄로 한다(env만 바꾼 재실행은 `:test UP-TO-DATE`로 안 돈다).
7. **배선 시험**(v1 반증 2): 시험 컨텍스트에서 `ReloadGates`가 yaml을 읽어 `us.s0=false`를 내는지 묻는다(어댑터는 `@ConditionalOnApiKey`라 빈이 없으므로 바인딩 빈 단계에서 묻는다). 어댑터 생성자는 `ReloadGates`를 받는 단위 시험으로 묻는다.

### 수용 기준
- **스윕 바이트 동일**: 같은 `.factcache`·같은 보충 원자료로 고치기 전 main과 고친 뒤를 각각 「끔」·「켬」 두 번씩 돌린다. 두 쌍의 md5가 같아야 한다(10-04 기준선 md5는 그때 캐시라 다시 뜬다).
- 전체 빌드 실패 0.
- 대조군
  - 부분 상태 가드를 지우면 가드 시험이 붉다.
  - 키 하나를 지우면 기동 실패 시험이 붉다.
  - enum 밖 키 시험이 붉다.
- 운영 배포 뒤 기동 로그에 시장·묶음 줄이 있다. FR·소비 note에 `관문=us:off,kr:off`가 있다.

### 순서·배포
- 값 무변화라 무해한 선행 배포다.
- 2부 2차(오늘 저녁)와 편입 따라잡기(10-16)를 먼저 보낸다. 이것은 그 뒤 main에 쌓는다 — 셋 다 `FinancialRefillJob` note를 건드리므로 순서를 정해 충돌을 피한다.

### 재지 않은 것
- `stockscreener.gate-bundle.us.s0: ${stockscreener.gate.us}`를 명령줄 인자 `--stockscreener.gate.us=true`가 덮을 때 묶음이 따라 켜지는지 — v1 반증이 중첩 자리표시자 16조합을 쟀으나 인자 덮기는 그 조합에 있는지 확인 안 함 · D3가 잰다.
