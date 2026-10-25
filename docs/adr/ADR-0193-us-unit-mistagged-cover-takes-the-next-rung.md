---
id: ADR-0193
status: 채택됨
date: 2026-10-26
applies_to:
  - "src/main/java/com/stockscreener/pipeline/datasource/us/shareclass/CoverUnitJudge.java"
  - "src/main/java/com/stockscreener/pipeline/datasource/us/ShareClassCollector.java"
  - "src/main/java/com/stockscreener/pipeline/datasource/us/SecEdgarFinancialAdapter.java"
  - "src/main/java/com/stockscreener/pipeline/datasource/us/SecEdgarFacts.java"
  - "src/main/java/com/stockscreener/pipeline/datasource/us/shareclass/IssuerFacts.java"
  - "src/main/java/com/stockscreener/pipeline/datasource/us/shareclass/IssuerTimeline.java"
  - "src/main/java/com/stockscreener/pipeline/collector/FinancialWriter.java"
  - "ops/apply-window/03-refill.sh"
---

# ADR-0193. 단위가 틀린 표지는 「없는 표지」로 보고 사다리 다음 칸을 낸다 · 손 수리를 걷는다(관문 뒤)

## 결정

관문 켬(`stockscreener.gate.us`)에서 dei 표지 주식수(`EntityCommonStockSharesOutstanding`)가 증인에서 단위(10^±3 · ±6)만큼 갈리면, 그 표지를 **없는 표지**로 본다. 그러면 표지가 없는 공시와 같은 사다리(ADR-0186 — 같은 공시의 `CommonStockSharesOutstanding` → 기본 가중평균 → 희석 가중평균)의 다음 칸을 낸다. 사다리 칸 중 어느 것도 증인과 맞지 않으면 그 공시의 판정은 숨김이다.

관문 날 손 수리(ADR-0126 · ADR-0186 §결정의 MUST)를 걷는다.

사용자 기준(2026-10-25 원문)은 다음과 같다: 「원장 자체가 뒤틀려서 특정 종목만을 위한 로직이 들어가야 한다면 정합성 훼손으로 판단해. 원장에서 주는 정보로 조합이 되면 개발을 해.」

내 해석(사용자 문장 아님)은 다음과 같다.
- 손 수리는 종목 특정 수리다.
- 같은 공시의 다음 표준 후보는 원장이 정직하게 준 값이다. 그것을 고르는 것은 배율을 곱해 고치는 것(ADR-0189가 금함)과 다르다.

판정(`CoverUnitJudge`)은 아래와 같다.

1. **증인은 둘이다.**
   - ① 그 공시 당기 기본 가중평균. EPS × n이 분자와 닫힐 때만 쓴다(`IssuerTimeline.closes` · 운영 대용 τ).
   - ② 앞서 보인 표지 넷의 중앙값. 분할을 반영하고, 바뀐 표지는 바뀐 값으로 넣는다.
2. 아래 셋이 모두 서면 **틀린 표지**다. 사다리 칸 중 모든 증인과 자릿수가 맞는 첫 값으로 바꾸고, 그런 값이 없으면 「없음」이다.
   - 어느 증인에서 단위만큼(띠 0.1) 갈린다.
   - 어느 증인과도 자릿수(10배 안)가 안 맞는다.
   - 확인 안 된 가중평균과도 τ_c 안에서 안 맞는다.
3. 표지가 앞 표지 증인과 자릿수가 맞는 공시에서, n이 표지와 단위만큼 갈리면 **n이 틀린 쪽**이다. 폐합이 참이어도 그렇다. 엔진의 단위 확인이 다음 태그 대체를 먼저 묻고, 없으면 0으로 둔다.
4. **한 번만 계산한다.** 관문 켬 어댑터가 공시 보충 **뒤** 트리로 부른다.
   - 증인 재료는 클래스 축 틀이고, 사다리 재료는 재무 열 틀이다(IFRS 가중평균 별칭 없음 · ADR-0182).
   - 결정은 재무 추출(`SecEdgarFacts` — 판정이 버린 공시의 주식수 사실을 결정으로 갈음)과 클래스 축 입력(`sec_share_class_input`의 `facts._coverUnit`)에 함께 싣는다.
   - 엔진은 다시 계산하지 않는다.
5. **Writer**
   - 판정이 버린 표지의 행은, 그 표지와 그 손 수리 값(단위 계단만큼 옮긴 값)을 `sharesSuperseded`에 단다. 이미 있는 덮기 갈래가 사다리 값으로 덮는다.
   - 그 밖의 손 수리 행은 그대로 두고 정보 로그로 센다(`주식수손수리남김=`).

## 규칙

- MUST NOT: 단위가 틀린 표지를 배율로 고쳐 쓰지 않는다. 다음 칸은 원장이 준 값이다(ADR-0189 · 2026-10-25 사용자 기준).
- MUST: 다음 칸을 증인에 대 본다. CB 2010의 다음 칸 CSO가 표지와 같은 ×10^6이었다.
- MUST: 판정은 어댑터에서 한 번 낸 결정을 추출과 엔진이 함께 쓴다. 두 쪽이 저마다 부르면 입력(보충 공시 표지 · IFRS 별칭)이 달라 결정이 갈린다.
- MUST NOT: 관문 날 손 수리(원시 SQL)를 처방하지 않는다. 남긴 손 수리 행은 지우지도 원장 값으로 덮지도 않는다.
  - 그 행들은 관문 켬 제품이 안 읽는다(판정 `X` · 판정 없는 2019 행).
  - 판정을 안 거치는 판독기(`PriceRefutation` · `PriceIntegrityAlerter`)는 그 값이 정직한 수라 오히려 맞는다. 들어오는 원장 값 자체가 오태깅인 경우가 있다(ECHO 271,519 · DDS 15,655).
- MUST NOT: 「표지가 증인 둘과 다 안 맞는다」만으로 단위 계단 밖의 표지를 버리지 않는다. QXO 2024 재편(표지 409M · 앞 표지와 n 664k)이 그 꼴이고 표지가 옳다. CIGI 2025 284,583(257배)은 별도 항목이다.
- MUST NOT: 꺼진 순위 가드(`guard-rebalance-on-dirty-rank.sh`)를 양방향으로 넓히지 않는다. 그 축(순이익 ÷ EPS 대 주식수)은 어느 칸이 틀렸는지 못 가른다 — 새로 걸릴 현 편입 WSE는 EPS가 센트 단위인 것이다.

## 버린 대안

| 대안 | 근거(실측) |
|---|---|
| 판정을 통째로 숨김(상태 `X`) | 정직한 n까지 버리고 보통주 몫의 확인이 무너진다(v1 반증) |
| 주식수만 숨기고 n 유지(새 상태 · 새 열 · 뷰) | 숨긴 14판 전부에서 같은 공시가 EPS로 확인되는 표준 재료를 준다 — 「조합되면 개발」. 숨김은 단일 클래스 발행사에 편입 보류 · 제외를 새로 태운다(CONSECUTIVE 19종목 · EXCLUDED 9 · v2 반증) |
| 띠 0.052(`COVER_WITNESS_LOG`) | HBAN 2011(증인 둘 다 0.08)을 놓친다 |
| 앞 표지 증인에 분할 미반영 | 역분할이 겹친 1:800(−2.903)이 띠 안에 든다(DKI · RPGL · YYAI · ZNB) |
| 다음 칸 없는 표지를 지움 | 사건 종류 · 앵커 후보 · 인스턴스 받기가 바뀐다 — 기존 숨김(`X` · `cover-unit`)을 쓴다 |
| `Issued − Treasury` 칸을 사다리에 더함 | 표지 없는 공시 전부의 사다리가 바뀐다 — 이 결정의 범위(같은 규칙을 탄다) 밖 |

## 결과

0콜 스윕으로 쟀다(2026-10-26). 재료는 `.factcache` 1,218과 캐시 밖 13 companyfacts, 대상 32종목 인스턴스 265다. 탐색 `explore/financial-sync/HAND-REPAIR-PLAN-1025.md` v3.1.

| 지표 | 수 |
|---|---|
| 표지를 다음 칸으로 바꾼 공시 | 81. 2020-09 뒤 14(HL 3 · PKG 3 · HXL 2 · SKY 2 · CCL · CMG · RMD · HROW) |
| 다음 칸 없음 → 숨김 | 2(CB 2010-05 · MAR 2010) |
| 판정 표 변화 | 18, 전부 2020-09 앞(숨김 2 · n 없음 10 · Q4 n 새로 섬 6) |
| 보통주 몫 바뀐 공시 | 13. 2020년은 EIX 2020-02(갈래만) · COHR 2020-05(9개월 −118M → none — n과 EPS가 함께 틀린 곱) |
| 관문 켬 재무 스윕 주식수가 바뀐 행 | 50(2020-09 뒤 22). 바뀐 칸은 주식수뿐 · 판정이 버린 원래 값을 실은 행 0 |
| 관문 꺼짐 TSV | main과 md5 같음(121a06ab…) |

- 손 수리 51행(관문 켬 실제 경로)
  - 판정 `X`: 43
  - 지금 경로 7(CMG · PKG · RMD): 이 결정이 사다리 값으로 덮는다.
  - 판정 없음 1(VCTR 2019 Q2 · 노출 0)
- 남는 것
  - 증인 2가 없는 첫 공시에서 n과 EPS가 함께 반대로 틀린 꼴
  - 인스턴스 `cur` 표지
  - 단위 계단 밖 쓰레기 값(CIGI · CSX 2011 · PCG 2010)
  - 공시 보충 표지의 엔진 쪽 효과(결정은 보충 뒤 트리로 내므로 추출에는 서고, 이 재료에는 보충 인스턴스가 없어 재지 않음)

## 검증

`./gradlew test --tests '*CoverUnitJudgeTest' --tests '*SecEdgarSharesFallbackTest' --tests '*FinancialWriterTest'`

규칙마다 대조군이 있고, 규칙을 끄면 붉다. 띠 0.052 · 다음 칸 검증 없음 · 분할 미반영 · 자릿수 일치 무시 · n 반전 없음 · 없음 숨김 없음, 여섯을 실제로 바꿔 붉음을 확인했다.

관문 날 수용은 다음과 같다.
- 켬 재적재 뒤 결정 목록이 캐시 모집단에서 이 81 + 2 접수번호와 같은지 본다(`sec_share_class_input`의 `company_facts->'facts'->'_coverUnit'`).
- 후보 모집단에서 늘어난 것은 목록으로 내고 하나씩 연다.
- 03이 `주식수교정=` · `주식수손수리남김=`을 낸다.
