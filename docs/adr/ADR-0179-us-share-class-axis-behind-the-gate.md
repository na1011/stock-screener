---
id: ADR-0179
status: 채택됨
date: 2026-10-15
applies_to:
  - "src/main/java/com/stockscreener/pipeline/datasource/us/shareclass/**"
  - "src/main/java/com/stockscreener/pipeline/datasource/us/ShareClassCollector.java"
  - "src/main/java/com/stockscreener/pipeline/collector/ShareClassDeriver.java"
  - "src/test/resources/db/testmigration/V70__share_class_axis.sql"
---

# ADR-0179. 미국 주식수 클래스 축을 판정 표로 들고, 관문 뒤에서만 읽는다

## 결정

**클래스 축 주식수(N_L)와 EPS 분모(n)를 `financial_statement`에 컬럼으로 두지 않고, 상장 줄 × 정기 공시의 판정 표
`financial_share_class_filing`에 둔다.** 판정은 저장한 재료 둘(줄마다 가지친 companyfacts `sec_share_class_input` ·
앵커 후보 공시의 인스턴스 팩트 `sec_filing_class_fact`)과 분할 원장에서 **외부 호출 없이** 다시 만든다(`ShareClassDeriver`).
판독기 셋(순위 · 지표 · 기저 판정)과 배당 총액 유도는 **같은 규칙 한 자리**(`ShareClassLine`)로 재무 행에 판정을 입힌다.
**전부 `stockscreener.gate.us` 뒤다** — 꺼진 jar는 세 표를 이름으로도 안 부르고, 마이그레이션(V70)은 관문 직전까지
`db/testmigration`에만 둔다.

설계와 수의 정본은 탐색 브랜치 `explore/s-vintage-coherence`의 `explore/financial-sync/SHARES-CLASS-PLAN-V5-1015.md` §10이다
(v5.1 · 두 렌즈 반증 「고쳐서 간다」를 재서 닫음 · 드라이런 1 스윕 §10d · 드라이런 2 명부 재생 §10e).

## 규칙

- **MUST: 판정 표 키에 상장 줄을 넣는다 — (종목, 접수번호).** 계획 v5.1은 (CIK, 접수번호)였다. 판정 엔진이 줄에 매여 있어서다
  (클래스 매핑 · N_L 단위). 실측(2026-10-15 · 0콜 스윕 · 형제 22쌍): 상태가 줄마다 갈린 것은 `UHAL`/`UHAL.B` 4판뿐
  (`UHAL` 의결권 줄이 구성원에 못 붙어 숨김 · `UHAL.B`는 값) · 값이 갈린 것은 `BRK.A`/`BRK.B` 23판 · `GEF`/`GEF.B` 21판
  (단위가 다르다 — 정상) · `GOOGL`/`GOOG` 1판(0.35%). CIK 단위로 숨기면 `UHAL.B`가 남의 줄 매핑 실패로 빠진다.
  형제 줄 이중 편입은 CIK 대표(ADR-0169)가 막는다.
- **MUST: 숨김은 null이 아니라 상태다.** 판정 표 `status = 'X'`, 판독기의 `ShareClassLine.State.HIDDEN`. `FinancialMath`의 주식수
  걷기는 「주식수가 없으면 앞 판으로」보다 **먼저** 숨김을 보고 멈춘다(§10b-3).
- **MUST: 판정이 없는 정기 공시는 그 줄이 앵커에 든 뒤(2020-09-01 뒤)면 미판정 = 숨김이다.** 유도가 아직 안 돈 새 공시가 옛
  N_L로 조용히 돌아가지 않게. 정기 공시가 아닌 행(정정)은 그날 서 있던 판정을 따르고 N_L은 그 사이 분할만큼 옮긴다.
- **MUST: 유도 순서는 클래스 축 → 기저 판정이다**(`IndicatorReconciler.deriveShareBases` 한 자리). 기저 판정이 판독기가 읽는
  계열(N_L)로 배수를 잰다(§0-13).
- **MUST: 편입 보류는 앞 편입이 마지막으로 보인 판정의 N_L로 순위를 다투는 것이다(규칙 B · §10e).** 자리를 순위 없이 떼지 않는다.
  보류 함수의 상태(`MembershipHold.stateAt`)는 판정 이력의 순수 함수다 — 리밸런싱과 복원이 같은 함수를 탄다.
- **MUST: 주식수 비 지표 셋(`SHARES_GROWTH_3Y` · `EPS_GROWTH_TTM_YOY` · `EPS_CAGR_5Y`)은 관문이 켜진 미국에서 두 끝 다 그 공시의
  분모 n이다**(미국 전 발행사 · §10b-6). n이 없는 끝은 시점 주식수로 안 내려간다.
- **MUST NOT: 공시 보충의 판 고정 범위(`ReaderVersionPinTest` 일곱 파일)를 고치지 않는다.** 받기는 그 범위의 받기·인스턴스 고르기를
  부르기만 한다(`ShareClassCollector`).
- **MUST: 마이그레이션 V70을 관문 직전에 `db/migration`으로 한 글자도 안 바꾸고 옮긴다.** 번호가 운영의 다음 번호와 겹치면 올린다.
- **MUST: 펜스는 뷰 `us_share_class_effective`로 잰다** — 판독기의 `ShareClassLine`과 같은 규칙이고 `ShareClassAxisReadTest`가 행마다
  맞댄다. 뷰가 없으면(관문 전) 펜스는 이 축이 없던 때와 같다.

## 버린 대안

| 대안 | 기각 근거 |
|---|---|
| `financial_statement`에 N_L·n 컬럼 | 국내 해시(`kr-fs-hash`)와 `FinancialWriter.managedColumns`가 컬럼 목록에 묶여 있다(계획 v2 §1e) · 판정은 공시 단위인데 재무 행은 기간 단위라 정정·비교열마다 같은 판정을 복사한다 |
| 판정 표 키 (CIK, 접수번호) | 위 §규칙 첫 줄의 실측 — 줄마다 판정이 갈린다 |
| 받을 때(어댑터 안에서) 판정까지 끝냄 | 분할 원장이 늦게 오거나 판정 규칙이 바뀌면 다시 받아야 한다 — 유도는 외부 0이어야 한다 |
| 발행사 제외를 표로 | 판정 이력의 순수 함수로 매번 계산된다(`MembershipHold`) — 표는 그 계산과 갈릴 자리만 만든다 |
| 새 설정 키 `us-share-class-basis` | 관문 가드(`GateSettingGuard`) 밖이다(§10b-4) — 미국 상태는 「전부 끔/전부 켬」 둘뿐 |

## 검증

- 꺼진 상태: `ShareClassStore`의 질의는 `gate.us`가 참인 갈래에서만 나간다 — `rg "shareClasses\.|shareClass\.collect|shareClassDeriver\.derive" src/main/java`의 모든 자리가 `usGate` 분기 안이다.
- 두 정의의 일치: `./gradlew test --tests '*ShareClassAxisReadTest'`.
- 엔진 동일성: 저장 재료 스윕(`SecShareClassSweepTest`)의 상태·값이 드라이런 2의 판정(`replay-verdicts.tsv`)과 같다 — 2026-10-15 4,576판 전부 같고 지금 경로 판 1,176에 분모가 채워진 것만 다르다.

이 결정의 전제(관문 뒤 펜스)는 ADR-0113 · 0125 · 0135 · 0162의 `premise`와 `guard-rebalance-on-dirty-rank.sh`에 있다.

## 결과

**드라이런 3(2026-10-15 · 작은 DB · 27줄 · SEC 실호출 FR 4판)** — 수와 귀속은 탐색 계획 §10g.
- 꺼짐 = 이전 main: 기저 판정 · 거버넌스 사실 · 지표 536,401행 md5가 같다(`ded0289c` 대 새 커밋).
- 결함 둘을 잡아 고쳤다: 받기가 응답의 `facts` 노드를 루트로 알고 빈 트리로 가지침(`fae91245`) · 공시 보충으로 실린 정기 공시가
  판정 목록 밖이라 최신 판이 미판정 숨김(`3d3c0d8e`).
- 받기 27줄: 인스턴스 348 · 실패 0 · 판정 622(숨김 83) · 끔/켬 보충 경로 같음. 유도: 스윕에 있는 591판 상태·값 같음.
- 보류 8날짜 93쌍이 드라이런 2와 같음 · 펜스 다섯 OK(거버넌스를 켬으로 다시 유도한 뒤).
- **재지 않음**: 판독 변화(지표 539,610 → 516,459행)가 줄마다 맞는지 · 1년 창 편입 효과 · 보충 공시가 §5 수를 얼마나 바꾸는지.

관문 전에 남은 것: V70을 `db/migration`으로 옮길 번호 · 관문 0단계 인스턴스 받기 규모(후보 쪽 포함) · 관문 순서 「재적재 → 클래스 축·기저 판정 →
거버넌스 유도 → 백필 → 복원」을 `docs/reload-gate.md`에 싣기.
