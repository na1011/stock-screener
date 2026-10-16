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
- **MUST: 인스턴스를 못 받은 공시는 표면에 닿게 한다.** 받기 실패는 `FAILED` 행 · 한도에 걸린 뒤의 공시도 콜 없이 `FAILED · RATE_LIMITED` 행 —
  「미해결 = 지금 읽기 판의 `FAILED` 중 `UNREADABLE` 아닌 것」이 표 하나로 선다. 그 종류는 잡의 시장 판정 「클래스 축」 층(보충과 같은 장치)에 실리고
  종목 표지(ADR-0177)를 막으며, 미국 공시 소비는 그 종목의 열린 행을 닫지 않는다 — 로그만 남기면 정기 FR이 없는 순위 후보는 다음 공시(약 한 분기)까지
  그 판이 숨는다(후보 손 FR 계획 v2 반증 · 10-16).
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

**후보 순위 효과 스윕(2026-10-16 · `UsCandidateRankSweepLiveTest` · 드라이런 2의 저장 재료 214발행사로 0콜 재생 · 순위만 Alpaca·SEC 매핑)** —
수용 기준 7(순위 차이 귀속)을 처음 쟀다(드라이런 2는 보이는 판의 순위 값을 꺼짐과 같다고 두고 안 쟀다).
- 대조군: 재료 없는 켬 판독기 = 꺼짐(주식수 차이 0) · 엔진: 드라이런 2 판정 4,576이 상태·값까지 같다(분모 1,176만 — 위 검증과 같은 차이).
- 1,000위 켬−꺼짐 들어옴 9 · 나감 9, 전부 귀속: 비편입 후보의 새 주식수(`V` 15위 · `BRK.B` 10위 · `STZ` 418위) · 보류 끝·첫 판 숨김
  (`BNT`·`VNOM`·`WSE`) · 앞 편입 아닌 줄의 숨김(`BROS` — 보류는 앞 편입만) · **N_L 값 — Up-C 다섯**(`SYM` 391→1,003 · `GLXY` 824→1,211 ·
  `LB` 883→1,451 · `CWEN` 897→1,195 · `BLLN` 956→1,024) · 문턱 밀림 여섯(`BB`·`CLMT`·`COGT`·`OBDC`·`WTM`·`XMTR`).
- Up-C 다섯은 꺼짐 주식수가 교환 가능한 단위(LLC 지분과 짝인 무배당 클래스)까지 센 값이었다(켬/끔 0.21~0.88). 켬 값은 같은 행의
  「순이익 ÷ EPS」와 1.00~1.01배다(`SYM`·`LB`·`BLLN`) — 순위 시총이 지배주주 순이익과 같은 기저가 된다. 계획 §1a의 정의(N_L = EPS에
  참여하는 클래스)대로이고 결함이 아니다.
- 이 판의 보류 넷(`BE`·`F`·`KNSA`·`VICR`)은 옛 재료에 그 공시 인스턴스가 없어 숨김으로 읽힌 것이다(재료의 나이 — 관문 받기는 받는다).
- **재지 않음**: 후보 전원 — 이 판의 모집단은 드라이런 2 재료 214발행사뿐이다.

**후보 전원 스윕(2026-10-16 15:50~19:00 · 같은 도구 · 활성 미국 8,504 전원 · SEC 실호출 · 재료 `~/stock-screener-class-sweep`)** — 위 판의 「재지 않음」을 닫는다.
- 받기: 대상 8,504 · SEC 매핑 없음 1,871 · companyfacts 404 144 · 「200인데 `facts` 없음」 7(ETB·HEQ·HPF·HPI·HPS·HTD·JHS) — 실패 151은 전부
  재무 행 0인 종목이다(운영 SELECT) · 인스턴스 12,529(OK 12,527 · NO_XBRL 2). 유도: 6,482줄 · 판정 185,370 · 숨김 5,433 · 실패 0.
- 대조군: 재료 없는 켬 판독기 = 꺼짐(주식수 차이 0). 순위 가드 축(켬 1,000위 · 순이익/EPS ÷ 주식수 ∈ [100,10000)) 0 · 판정 못 함 69.
- 1,000위 켬−꺼짐 들어옴 14 · 나감 14, 전부 귀속:
  - 들어옴 — 비편입 후보의 새 주식수 13: 재무 행 있음 `V`(15위) · `BRK.B`(10위) · `STZ`(421위) · `COKE` · `WMG`, **재무 행도 시세도 0인 「재료만」 8**
    `XOM`(16위) · `HONA` · `ARXS` · `INIO` · `PS` · `RBC` · `MBGL` · `OCTV` — 이 여덟은 관문 후보 재적재가 재무 행을 넣기 전에는 저장 경로의
    순위에 안 실린다 · 문턱 밀림 1(`XMTR` 1,027→977).
  - 나감 — **N_L 값 Up-C 여섯**: 앞 판의 다섯(`SYM` 391→1,011 · `GLXY` 824→1,225 · `LB` 883→1,471 · `CWEN` 897→1,208 · `BLLN` 956→1,034)과
    **`HGTY` 983→1,703**(후보 · 켬 1억 207만 = 표지 클래스 A · 같은 공시 EPS 분모 1억 180만 · 꺼짐 3억 4,680만은 무배당 클래스 V까지 센 값) ·
    숨김 넷(`BNT`·`VNOM` 연속 숨김 · `WSE` 첫 판 숨김 · `BROS` 보류 — 앞 편입이 아니다) · 문턱 밀림 넷(`AROC` 999→1,002 · `CNX` · `GHC` ·
    `KVYO` 963→1,001). `KVYO`는 켬(표지 A+B 2억 8,473만)이 EPS 분모(2억 9,685만)보다 4% 작다 — 표지 날짜 값이 분기 가중평균보다 늦은
    시점이고, 직전 표지 2억 9,928만에서 줄어든 것은 원장의 매입·소각과 방향·규모가 맞다(companyfacts
    `StockRepurchasedAndRetiredDuringPeriodValue` 2026 Q1 1억 81만 달러 · Q2 2억 4,011만 달러 — 주식 수로 맞춰 보지는 않았다).
- 주식수가 꺼짐 없음 → 켬 있음이 되는 후보 399(SIC 없음 4 · 켬 1,000위 안 13).
- **재지 않음**: 관측 평면 여덟 기준일(08-31~10-09)의 꺼짐/켬 순위 차이 — `docs/reload-gate.md` 「관측 평면이 옛 순위 기저로 남음」의 크기 ·
  실패 151의 구성원별 판정(companyfacts 404인데 XBRL 정기 공시가 있는 꼴이 `V`·`BRK` 계열에 있었다 — 404를 「재무가 없다」로 묶어 판정하지 않는다).

관문 전에 남은 것: V70을 `db/migration`으로 옮길 번호 · 관문 0단계 인스턴스 받기 규모(후보 쪽 포함) · 관문 순서 「재적재 → 클래스 축·기저 판정 →
거버넌스 유도 → 백필 → 복원」을 `docs/reload-gate.md`에 싣기.
