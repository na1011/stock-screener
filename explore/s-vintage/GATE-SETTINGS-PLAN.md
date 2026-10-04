# 관문 순서 0 — 미국 관문 설정을 묶음별로 가른다 (계획 v1 · 2026-10-04 · 반증 전)

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
