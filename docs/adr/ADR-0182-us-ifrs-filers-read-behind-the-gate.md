---
id: ADR-0182
status: 채택됨
date: 2026-10-22
applies_to:
  - "src/main/java/com/stockscreener/pipeline/datasource/us/TaxonomyFrame.java"
  - "src/main/java/com/stockscreener/pipeline/datasource/us/SecEdgarFacts.java"
  - "src/main/java/com/stockscreener/pipeline/datasource/us/XbrlInstanceReader.java"
  - "src/main/java/com/stockscreener/pipeline/datasource/us/SecFilingSupplement.java"
---

# ADR-0182. 미국 IFRS 발행사의 재무를 관문 뒤에서 같은 뜻의 us-gaap 열쇠로 읽는다

## 결정

미국 재무 어댑터가 `companyfacts`의 `ifrs-full`도 읽는다 — **`stockscreener.gate.us` 뒤에서만.** 읽기 전에 `facts`를 공시(접수번호) 단위로
다시 짓는다(`TaxonomyFrame`): 공시마다 택소노미 하나 · 표시 통화 하나 · 회계기준 전환의 다리 · IFRS 개념을 같은 뜻의 us-gaap 후보 열쇠에 별칭.
가장 늦은 연차 공시가 비USD면 종목 단위로 담지 않는다. 공시 보충(ADR-0173)의 인스턴스 판독기는 켬·꺼짐 둘 다 `ifrs-full`을 읽는다(판 9).

설계와 실측의 정본은 탐색 브랜치 `explore/s-vintage-coherence`의 `explore/universe/ifrs/IFRS-PLAN-1022.md` v2다(두 렌즈 반증 「고쳐서 간다」 · 절 「반증 v1 판정 표」).

## 왜

- **가짜 한계다.** 어댑터가 `facts.path(dei ? "dei" : "us-gaap")`만 읽어 IFRS로만 보고하는 발행사가 재무 0행 → 주식수 없음 → 순위 후보에서
  `excluded.noShares`였다. 2026-10-22: 재무 0행 · 공시 장부 있는 미국 활성 종목 CIK 386 중 `ifrs-full`만 329(장부 밖까지 넓히면 더 — NVS · UBS ·
  SAP · SPOT · CNQ · MFC). 원장은 같은 응답에 값을 정직하게 준다. 기록 `ADR-0125`의 *"TSM은 어떤 태그를 더해도 안 열린다"*는 us-gaap 태그에만 섰다.
- **공시 단위로 짓는 이유**: 공시 하나는 한 기준 · 한 표시 통화로 작성되는데 companyfacts는 개념마다 펼쳐 그 묶음이 안 보인다. IFRS 공시 안의
  us-gaap 태그가 후보 우선순위로 IFRS 값을 이기고(182 중 6 · `LTM` 2018 자산 2.70B 대 20.08B), 편의환산 USD가 표시 통화를 덮는다(182 중 125가
  다통화 공시 · 다수결 동률 0).
- **열쇠에 싣는 이유**: `chooseTags`가 라벨 앵커 공시의 태그에 고정하므로 컬럼 후보로 따로 두면 전환기 뒤 us-gaap 재작성 판을 못 읽는다(`CLS`
  FY2022 지배순이익 IFRS 145.5M → 10-K 재작성 180.1M). 유도 함수(지배 순이익 · 세전 · 지배 자본 · Q4)도 us-gaap 이름으로 짜여 있어 유도 재료마다
  별칭을 둔다 — 세전 재료를 빠뜨리면 「총순이익 + 세」 유도가 원장 세전을 이겼다(78칸 · 25발행사).

## 규칙

- MUST: IFRS 개념의 별칭은 **뜻이 같은 것만** 둔다(`TaxonomyFrame.ALIASES` · 대조표는 계획의 「별칭 표」 절). 부재의 뜻은 소스마다 다시 묻는다 —
  비지배 팩트가 없는 공시만 `ProfitLoss`·`Equity`를 지배 귀속으로도 싣는다(IAS 1이 비지배를 본문 한 줄로 요구한다 · 2021 뒤 IFRS 공시 616 중 279).
- MUST NOT: 계약 매출(`RevenueFromContractsWithCustomers`)을 최댓값 경쟁에 넣지 않는다 — 광산사는 잠정가격 조정이 음수라 IAS 1 합계보다 크다
  (21칸 · 6발행사 · BHP · RIO …). `Revenue`가 없는 공시에서만 매출이다.
- MUST NOT: IFRS 주식수 개념(가중평균 · 주석 주식수)을 주식수 폴백으로 받지 않는다 — 같은 연차 공시 안 표지와 10배 넘게 갈리는 쌍 22/636
  (천 주 단위 · VALE · EMBJ · KEN …). IFRS 주식수는 표지(`dei`)와 클래스 축(인스턴스 차원 표지)만 준다.
- MUST NOT: `FinanceCosts`를 이자비용으로 받지 않는다 — 은행형 영업이익 재료이고 넷이 다 있어야 켜지는데 IFRS에 비이자 수익·비용 표준 개념이 없다.
- MUST: 회계기준을 바꾼 발행사에서 새 기준의 가장 이른 자산 기말이 옛 기준의 마지막 자산 기말보다 뒤면 옛 기준 공시를 버린다 — 다리가 끊긴 같은 CIK는
  다른 실체일 수 있다(`FLUT` 2015~2019 40-F는 옛 등록인 Stars Group · Amaya의 재무 · submissions `formerNames`로 확인). 전환기 7종목(CRH · CLS ·
  TEAM · SSRM · IREN · BNT · SMMT)은 재작성 비교값으로 겹쳐 안 걸린다.
- MUST: 가장 늦은 연차 공시의 표시 통화가 비USD면 그 발행사의 USD 판도 담지 않고 **회수에서 지키지 않는다** — 지키면 옛 USD 판이 남아 통화 게이트를
  「USD 보고사」로 통과한다(ADR-0094의 종목 단위 · `HMY` · 편입 0).
- MUST: 라벨 앵커(먼저 낸 6-K)에 표지가 없으면 그 라벨을 **자기 기간으로 낸** 연차 공시의 표지로 새 판을 낸다 — 앞 판으로 되돌려 채우지 않는다
  (ADR-0128). 비교열로 다시 실은 뒤 해 공시의 표지는 그 기간의 주식수가 아니다(`AEM` FY2024 · FY2025).
- MUST: 공시 보충 판독기는 관문과 무관하게 `ifrs-full`을 읽는다 — 관문 켬에서만 읽게 하면 꺼진 정기 FR이 찍은 판 9 행을 관문 실행이 지금 판으로 보고
  다시 안 받는다. 「companyfacts가 덮은 공시」만 관문 켬에서 `ifrs-full`을 센다.

## 버린 대안

- **컬럼마다 IFRS 후보 목록**: 전환기 재작성 판을 못 읽고(앵커 태그 고정) 유도 함수를 택소노미마다 두 벌 짓게 된다.
- **IFRS 가중평균 주식수를 폴백으로**: 위 22/636. 표지가 없는 발행사는 순위에서 미표시가 옳다 — `NTR` · `BN`은 인스턴스에도 표지 주식수가 없다.
- **IFRS 전용 발행사만 관문 밖에서 켬**: 「전부 끔 · 전부 켬」 밖 셋째 상태가 생기고, 전환기 7종목은 값이 바뀌므로 어차피 관문 뒤다.

## 결과

- 0콜 스윕(10-22 · `.factcache` 1,218 · IFRS 182 companyfacts): 꺼짐 TSV · EXTRA가 `main`과 md5 같음. 켬은 `main` 켬 대비 바뀐 행 0 · 새 행 86
  (전환기 7종목의 옛 IFRS 연도) · EXTRA 차이는 그 종목들뿐(새 보류 판은 `main`이 저장한 적 없는 CRH EUR 시절 — P1 회수 보호와 교집합 0).
  IFRS 182: 꺼짐 1행 → 켬 150발행사 1,786행(전부 USD · TSM은 TWD 표시라 0 · HMY는 종목 단위 통화로 0). 대조군 `main` 켬도 1행.
- 항등식 불성립: IFRS 대차 17/1,760(1.0%) · 총이익 76/769(9.9%) — us-gaap 편입 7.2% · 7.4%와 같은 부류([S] · [Z] 축)다.
- 2년 창 안 표지 주식수가 없는 IFRS 발행사 29 — 표지를 클래스(차원)로만 싣는 TRI · VALE · BIP · AZN(`ifrs-full:ClassesOfShareCapitalAxis`) · NU,
  표지 주식수가 없는 NTR · BN. 클래스 축이 IFRS 공시를 앵커로 세도록 재료(`IssuerFacts`)를 틀로 넘겼다. **클래스 판독기의 IFRS 축과 20-F · 40-F 클래스
  앵커를 숨기는 규칙은 B v2의 몫이다**(`docs/reload-gate.md` 「ADS 판 비율(B)」).
- 관문 수용 기대(D2 · 표지 주식수 × 10-21 종가 · ADS 비율 미반영): $5B 이상 51 — 보통주(AEM · WPM · MT · FNV · AU · KGC · GFS · AS · PAAS …)가 들고,
  ADS(이름이 평범한 HSBC · TTE · RIO · DEO 포함)는 관측 평면에서 A′ 표지 판정이 가린다. 과거 평면(`UsPastRanking`)에는 표지 판정이 없어 **B v2 없이 IFRS를 켠
  관문은 비율배로 부푼 ADS를 과거 명부에 들인다.**

## 검증

- `./gradlew test --tests '*TaxonomyFrameTest*' --tests '*IfrsSupplementTest*' --tests '*ReaderVersionPinTest*'`
- 꺼진 출력이 그대로인지: `REPLAY_SWEEP_DIR=.factcache REPLAY_SUPPLEMENT_DIR=off US_GATE=false ./gradlew test --tests '*SecEdgarFactsSweepTest*' --rerun`의
  TSV · EXTRA를 이 결정 전 커밋(`f1fdcd58`)의 같은 실행과 대조한다.
