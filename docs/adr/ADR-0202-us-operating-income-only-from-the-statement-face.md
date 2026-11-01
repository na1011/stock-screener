---
id: ADR-0202
status: 채택됨
date: 2026-11-01
applies_to:
  - "src/main/java/com/stockscreener/pipeline/datasource/us/SecStatementFace.java"
  - "src/main/java/com/stockscreener/pipeline/datasource/us/SecStatementFaceCollector.java"
  - "src/main/java/com/stockscreener/pipeline/datasource/us/SecEdgarFacts.java"
  - "src/main/java/com/stockscreener/pipeline/datasource/us/SecEdgarFinancialAdapter.java"
---

# ADR-0202. 미국 영업이익은 그 공시 손익계산서 본문의 줄만 받는다

## 결정

미국 관문 켬(`stockscreener.gate.us`)에서 영업이익 사실은 **그 공시 손익계산서 본문의 줄일 때만** 받는다
(`SecEdgarFacts.dropOffStatementHeadlines`). 손익계산서에 영업 소계가 없는 회사(보험사 · SHW · PPG · SLB · DE · HON · TJX · LHX · GPC · TGT 2015~2016
EBIT …)의 부문 주석 합계 줄(축 없음)이 소계로 들어왔다 — 그 값은 전사 비용을 안 뺀 다른 뜻이다(SHW FY2023 4,161.7 대 매출총이익 − 판관비 3,692.7).
버린 공시의 그 기간 칸은 **「그 판이 안 실었다」**다 — 판 짓기가 앞 판에서 잇지 않고 부재 판정(`assertedAbsent`)으로 싣는다(이으면 재작성 전 기저의
값이 재작성된 매출과 한 행에 앉는다 · FTI FY2016 · CAG 2015 1분기 · 안 실으면 쓰기기가 소스 행의 옛 값을 안 지운다). 열 전체가 그렇게 비면 결측
사유는 `ABSENT`(그 회사가 본문에 싣지 않는다 · 우리 한계 `FILTERED`가 아니다). 국내는 응답 행이 이미 재무제표 본문(`sj_div`)이라 같은 기준이다.

**판정**: 본문이 그 기간을 싣는 사실만 판정한다(양식의 본문 기간 — 연차 보고서 4분기 길이 · 분기 보고서 1~3분기 · 수시보고서는 안 한다: 10-K의
4분기 3개월 값은 원리적으로 주석이다). 본문 줄 = **값이 같은 공시 · 같은 기간 손익계산서 본문의 한 줄 값과 같다**(다른 개념 · 회사 확장 개념으로
단 소계도 받는다 — SSD 「Income from operations」 · HRL · BR · 0은 판정하지 않는다) — 값 재료가 없는 공시만 개념 이름으로 본다. 데이터셋의 기말
(`ddate`)은 가장 가까운 월말로 반올림한 날이라 사실의 기말을 그 날로 맞춰 찾는다(52/53주 회계연도 — SNA 2018-09-29 · NTAP 2022-01-28).

**재료**(공시마다 본문 · 디스크 캐시 `stockscreener.sec-face.cache-dir`):
- 정본: **SEC Financial Statement Data Sets**(분기 ZIP — `pre.txt`의 `stmt`와 `num.txt`의 본문 값). 본문 = `stmt` ∈ IS · BS · CF · EQ · CI · UN · SI
  (표지 · 분류 없는 줄 빼고) · 손익계산서 본문 값 = IS · CI · UN 줄의 축 없는 값. **데이터셋이 그 공시의 손익계산서를 실었을 때만**(IS의 순이익 · 주당이익
  줄, 합본 CI · UN의 주당이익 줄) 그 재료로 판정한다 — 아니면 캐시를 안 남긴다.
- 그 밖(데이터셋에 없거나 손익계산서를 못 실은 공시): 공시 폴더 — `MetaLinks.json`(보고서 `groupType` statement · document) · 그 앞 판은
  `FilingSummary.xml`의 `MenuCategory = Statements`, 분류가 없으면 역할 정의(`- Statement -` · `- Document -`)와 표시 링크베이스(`.xsd` 포함).
  손익계산서의 마지막 줄(순이익 · 주당이익)이 없는 재료는 「모른다」(`source=none` · 표지만 있는 8-K).
- 수집: 어댑터가 관문 켬에서 **창 안 기간의 영업이익 사실을 낸 공시** 중 캐시에 없는 것만 공시 폴더에서 받는다(`SecStatementFaceCollector` ·
  받기 실패는 그 종목의 실패 — 「모른다」로 접으면 그 실행이 부문 합계를 받아 저장하고 다음 실행이 고쳐 실행마다 값이 갈린다).

## 규칙

- MUST: 부재는 재료가 그 공시의 손익계산서를 실었을 때만 판정이다 — 데이터셋 부분 수록이 실재한다: PEP 2018 3분기 10-Q(줄 전부 분류 없음) ·
  INTU FY2024 10-K(BS · EQ · CI만 — CI는 순이익 줄로 시작한다) · CCL FY2019 10-K(IS 한 줄 · 손상).
- MUST: 데이터셋이 있는 공시는 데이터셋이 정본이다 — 공시 폴더의 `groupType`은 제출자의 역할 정의에서 오고 NRG FY2019 10-K는 손익계산서 역할을
  `Document`로 정의했다(두 재료 대조 1,266 중 갈린 것은 그 2건).
- MUST NOT: 개념 이름만으로 판정하지 않는다(값 재료가 있으면) — 이름은 기간을 안 가린다(GE FY2015 10-K: 연간 `Revenues`가 본문에 있어 주석의
  3개월 `Revenues`가 본문 줄로 셌다).
- MUST NOT: 이름표 정규식으로 소계를 찾지 않는다 — 「Other Operating Income」 오탐이 그 집합 58 중 대부분이었다.
- 꺼진 상태의 출력은 이 결정 전과 바이트까지 같다.

## 버린 대안

| 대안 | 근거(실측) |
|---|---|
| 공시마다 `MetaLinks.json`을 받는다 | 공시당 수 MB — 초당 약 1.3건(38,525건에 약 8시간) · 데이터셋은 분기당 1콜(70콜) |
| 항등식(매출총이익 − 판관비 · 세전 + 이자)으로 소계인지 가른다 | 답 아는 칸에서 성분식 23% · 세전 − 영업외 78%만 맞는다(재고 「영업이익 유도 NULL」) |
| 「영업이익 태그를 어느 해부터 끊은 발행사」만 고친다 | 진짜 소계를 바꾼 회사와 처음부터 부문 합계인 회사를 못 가른다 · SLB는 2019~2025 내내 부문 주석에만 있다 |
| 버린 칸을 비우기만(부재 판정 없이) | 쓰기기는 소스 행의 null로 저장값을 안 지운다 — 운영에 그 값으로 저장된 칸 2,022(반증 밖 렌즈) · `carryForward`가 앞 판 값을 되살린다(반증 안 렌즈) |
| 매출 · 원가에도 「본문 우선」(본문 줄 후보만 최댓값을 다툰다) | MA(본문 16,883 대 리베이트 전 주석 24,980) · MKSI(주석 ×1,000) · IONQ는 바로잡지만 ARES 2020 1분기(데이터셋 본문 값 11줄 · 총매출 줄 없음)에서 구성 줄(13.4M)이 총매출(429M)을 밀어낸다 — 부분 수록을 가르는 일반 규칙이 아직 없다(재고 「본문 밖 매출 후보가 최댓값으로 이김」) |

## 검증

- 단위: `./gradlew test --tests '*SecStatementFaceTest*'`.
- 재료: `FSDS_QUARTERS=<분기들> FSDS_DIR=~/.stock-screener/sec-fsds ./gradlew liveTest --tests '*SecFinancialStatementDataSetLiveTest*'` →
  `FSDS_DIR=… FACE_DIR=~/.stock-screener/sec-face FACE_CIKS=<미국 CIK 목록> ./gradlew test --tests '*SecStatementFaceImportTest*'` →
  창 안 빈 공시: `REPLAY_FACE_MISSING_OUT=<tsv>`를 준 스윕 → `FACE_TARGETS=<tsv> FACE_DIR=… ./gradlew liveTest --tests '*SecStatementFaceCacheLiveTest*'`.
- 0콜 스윕: `REPLAY_FACE_DIR=~/.stock-screener/sec-face REPLAY_ISSUERS=<티커 · 결산월 · SIC TSV> US_GATE=true REPLAY_SWEEP_DIR=.factcache REPLAY_SUPPLEMENT_DIR=off ./gradlew test --tests '*SecEdgarFactsSweepTest*'`.

## 결과

**0콜 스윕(`.factcache` 1,218 · 발행사 속성 · 보충 끔 · 관문 켬 · 재료 없음 대 있음 · 2026-11-01 · 반증 두 렌즈 반영 뒤)**: 바뀐 열은 영업이익
하나 — 1,488칸 · 44종목(값 → 빈칸 1,487 · FY ≥ 2018 945칸) · 판 −81(영업이익 비교열로만 생기던 판 · 다른 열 값을 잃는 판 0) · +31 · 관문 꺼짐
= 이 결정 전과 바이트 같음(`121a06ab…`). 대조군: 손익계산서에 영업이익이 있는 MSFT · NRG · INTU · ITT · CCL은 안 바뀐다 · 값이 같아 다른 개념의
본문 줄을 받는 SSD는 남는다. **기말 반올림 고침(11-01 · 반증 안 렌즈가 찾음)**: 그 전 판은 52/53주 회계연도의 기간을 판정하지 못했다 — 고친 뒤 HEAD
대비 영업이익 549칸 · 14종목(값 → 빈칸 548 · FY ≥ 2018 380 · TJX · DE · CAG · MOG · LEA · LHX · DIS · SWK · NYT · HRL · HSY · JNJ · TGT) · 판 −44 · +6 ·
다른 열 0 · 관문 꺼짐 바이트 같음 — NYT 2022 1분기 10-Q 본문 「Operating profit」 6.284M(`GrossProfit` 태그) 대 버린 60.9M(인수 비용 전) · LHX FY2022 본문
1,127 대 부문 합계 1,826. 재료: 데이터셋 2009q2~2026q3 70분기 · 캐시 공시 약 18.6만(손익계산서 못 실음 거둠 89) · 창 안 영업이익 공시 중 재료
없음 6(공시 폴더에서 받음).

**지표 계층**(반증 밖 렌즈 · 0콜 스윕 · 11-01 · 기말 반올림 고침 전 판 — 고친 뒤 비는 FY ≥ 2018 380칸도 같은 회사 꼴): 비운 칸(FY ≥ 2018 945) 중 유도(ADR-0104)가 다른 값으로 다시 짓는 것 344(보험형 · 매출 − 총비용)
· 4분기 162(FY − 분기 셋이라 FY를 따른다) · 그대로 비는 것 439 — 손익계산서에 영업 소계도 영업비용 합계 줄도 없는 회사(PH · SHW · MOG · CAG · PPG ·
AOS · IP · EXP · TJX · LEA · SLB …). **그 칸은 진짜 한계로 미표시다** — 회사가 그 소계를 안 싣는다(US GAAP는 영업 소계를 요구하지 않는다). 성분식
(매출총이익 또는 매출 − 원가 · − 판관비 − 연구개발비)은 본문에 영업이익이 있는 답 아는 칸 17,038 중 5,570(32.7%)만 1% 안에 맞고 전 칸이 맞는 종목은
521 중 49다(IP 2022 1분기: 1,398 − 341 = 1,057 대 본문 348 — 감가상각 · 유통비가 따로 있다) · 세전 + 이자비용과 둘 다 맞을 때만 받는 두 증인 규칙은
답 아는 칸에서 맞았을 때 120/170만 영업이익과 같고 비운 칸을 0칸 연다. 재지 않았다: 보충을 켠 스윕 · 공동 등록인 합본 공시.
