---
id: ADR-0186
status: 채택됨
date: 2026-10-23
applies_to:
  - "src/main/java/com/stockscreener/pipeline/datasource/us/SecEdgarFacts.java"
  - "src/main/java/com/stockscreener/pipeline/datasource/us/SecEdgarColumn.java"
  - "src/main/java/com/stockscreener/pipeline/collector/FinancialWriter.java"
  - "src/main/java/com/stockscreener/pipeline/datasource/RawFinancialReport.java"
  - "ops/apply-window/03-refill.sh"
---

# ADR-0186. 미국 주식수 폴백은 기본 가중평균의 가장 짧은 창이고, Writer는 우리 옛 추출 값만 덮는다(관문 뒤)

## 결정

축 없는 표지(dei)와 대차대조표 주식수가 없는 공시의 주식수 폴백을 관문 켬(`stockscreener.gate.us`)에서 두 군데 고친다.

- **사다리**: 기본 가중평균이 희석보다 앞이다.
- **동점**: 같은 공시 · 같은 기말의 후보(3개월과 누계)는 늦은 시작, 곧 가장 짧은 창으로 깬다. 지금은 `HashMap` 순서다.

저장된 양수 주식수는 지금껏 `overwritable()`이 거짓이라 재적재로도 안 바뀌었다. 이제 **그 저장값이 그 행이 대신한 옛 추출 값일 때만** 덮는다.

- 옛 추출 값: 옛 사다리와 옛 동점 규칙이 같은 라벨 앵커 공시에서 낼 수 있던 값(`RawFinancialReport.sharesSuperseded`).
- 손 수리(ADR-0126)는 그 집합 밖이라 지켜진다.
- 손 수리가 옛 값의 축척만 고친 행은 덮지 않는다. Writer가 경고로 남기고, 관문 날 근거 둘로 사람이 다시 쓴다.

설계 · 실측 · 반증의 정본은 탐색 브랜치 `explore/s-vintage-coherence`의 `explore/financial-sync/SHARES-TIE-PLAN-1023.md`다. v1을 두 렌즈로 반증받았고 둘 다 「고쳐서 간다」였다. 고친 판이 v1.1이다.

## 왜

- **같은 원장 사실이 경로마다 다른 값을 냈다.** 동점을 `HashMap` 순서로 고르면 companyfacts 경로와 공시 보충 경로가 다른 값을 낸다. 이것은 가짜 한계다.
  - 관문 켬 스윕(2026-10-23 · `.factcache` 1,218종목) 기준 1,335칸이 그렇다.
- **사다리 순서가 원장과 어긋났다.** 축 없는 dei가 있는 정기 공시 49,941에서, 같은 공시의 가장 짧은 창 가중평균을 표지와 견줬다.
  - 표지에 더 가까운 것: 기본 35,000 · 희석 7,625 · 같음 7,316.
  - 상대차 중앙값: 기본 0.48% · 희석 1.28%.
  - 희석은 반희석 판정이 기간마다 바뀐다. 그래서 Up-C에서 교환 단위가 들고 난다(TKO 2026 Q2: 3개월 희석 75.9M · 누계 희석 193.3M · 기본 74.8M).
- **관문 켬 화면은 클래스 축(ADR-0179)이 대부분 덮는다.** 2024-09 뒤 동점 공시 377 중 인스턴스 표지에 클래스 구성원이 없는 것은 2뿐이다(SEC 실호출). 그래도 두 곳은 저장값을 관문과 무관하게 읽는다.
  - `PriceRefutation.shareLens`: 분할 판정을 APPLY에서 HOLD로 바꾼다.
  - `PriceIntegrityAlerter.offsetJumps`
  - 「판정 없음」 이력(2020-09 전)도 저장값을 읽는다.
  - 새 규칙으로 분기 열의 1.5배 넘는 점프는 201에서 160으로 준다(UWMC 8 → 0 · RRR 9 → 1).

## 규칙

- MUST: 관문 꺼짐 출력은 이 결정 전과 바이트까지 같다. `SecEdgarColumn.candidates(false)`와 `factFor(…, false)`가 옛 순서 · 옛 동점 그대로다.
- MUST: 동점은 「창 일치」가 아니라 「가장 짧은 창」으로 깬다. 주식수는 시점 값이라 같은 10-K의 FY와 Q4가 같은 값이어야 한다.
  - 창 일치는 FY 60행을 12개월 평균으로 옮긴다. 그러면 (공시일, 기말)이 같은 FY · Q4 쌍에서 주식수가 갈린다.
  - 그 쌍을 하나로 고르는 소비자 셋(`ShareBasisDeriver` `DISTINCT ON` · `shareLens` · `offsetJumps`)이 비결정적이 된다(반증 안 1).
- MUST: 대신한 값은 `SecEdgarFacts.build` 끝 한 자리에서 라벨 앵커로 단다.
  - 값은 `carryForward` · Q4 유도 · 중단영업 판정을 지나며 행을 새로 짓는다.
  - 주식수는 어느 판이든 라벨 앵커에서 온다.
- MUST: Writer는 `stored.compareTo`로 집합을 본다. 저장 컬럼은 `numeric(_,2)`라 `193323374.00`처럼 소수가 붙는다.
- MUST NOT: `overwritable()`을 관문 켬에서 통째로 열지 않는다. 드라이런으로 잰 결과다.
  - 축척 손 수리 40행(DDS · PKG · RPAY …)이 ×1000 오태깅으로 되돌아간다.
  - BKH 3행이 반올림 차로 매 재적재마다 「교정」된다.
- MUST NOT: 손 수리된 행에 옛 수리 배수를 새 값에 곱해 자동 교정하지 않는다. 같은 공시 · 같은 태그 안에서도 축척이 갈린다.
  - TBLA 2025 Q2: 누계 327,578,134,000(×1000)과 3개월 313,572,282(옳음).
- MUST: 관문 날 03 뒤 · 04b 앞에 `shares-hand-<db>.txt`를 손 수리한다. 원시 SQL이므로 사용자 승인을 받는다.

## 버린 대안

| 대안 | 기각 근거 |
|---|---|
| 동점을 창 일치로(계획 v1) | FY ≠ Q4 78묶음 · 14종목이 새로 생긴다(반증 안 1). 희석을 그대로 두면 Up-C 점프가 는다(185 → 198 · 반증 두 렌즈). |
| 희석을 둔 채 동점만 고친다 | 위와 같다. 희석이 반희석으로 흔들리는 것이 뿌리다. |
| 클래스 축에 맡기고 고치지 않는다 | 관문과 무관한 소비자 둘과 2020-09 전 이력이 저장값을 읽는다. 경로마다 값이 갈리는 비결정성도 남는다. |
| Writer에 값 축 문턱(배수가 10^k면 안 덮는다) | TBLA처럼 ×1000 오류에서 옳은 값으로 가야 하는 자리를 막는다. ADR-0126이 값 축 판정을 실측으로 기각했다. |

## 결과

**드라이런 1(0콜 스윕 · 2026-10-23 · companyfacts만 · 보충 원자료 없음 → 하한)**
- 꺼짐: TSV와 extra가 main과 md5 같다.
- 켬: `sharesOutstanding` 열만 6,298행 · 199종목이 바뀐다. 행 집합과 다른 열은 같다.

**Writer 모사(운영 SELECT)**
- 교정 6,253 · 새 행 24 · 지킴 21(ECHO · RPAY · TBLA · TEM · VCTR 손 수리).
- 교정 상대차 중앙값 1.25% · 5% 초과 1,200 · 50% 초과 129(Up-C 희석 → 기본).

**시험**: `SecEdgarSharesFallbackTest` 4 · `FinancialWriterTest` 새 3. 대조군으로 원래 결함을 되살리면 붉다.
- 동점 규칙 제거 → TKO가 붉다.
- 무조건 덮기 → 손 수리 셋이 붉다.

**재지 않았다**
- 후보 모집단(캐시 밖 3,939종목 · 124,310행)의 교정 수와 순위 효과. 관문 날 03 로그의 `주식수교정=` 합계로 잰다.
- `shareLens` 재판정(22건 · 이미 APPLIED).
- 펜스 출력 변화: ADR-0113 · 0125 · 0099 · 0135 전제가 바뀌면 07 ④에 이 묶음으로 귀속한다.

## 검증

- `./gradlew test --tests '*SecEdgarSharesFallbackTest' --tests '*FinancialWriterTest'`
- 꺼짐 동일성: `REPLAY_SWEEP_DIR=.factcache REPLAY_SUPPLEMENT_DIR=off US_GATE=false` 스윕의 TSV · extra md5가 이 결정 전 main과 같다.
- 이 결정은 운영 자동 검증 대상이 아니다. 관문 날 03이 교정 수와 손 수리 목록을 낸다.
