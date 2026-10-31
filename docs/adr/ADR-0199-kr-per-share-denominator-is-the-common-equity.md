---
id: ADR-0199
status: 채택됨
date: 2026-11-01
applies_to:
  - "src/main/java/com/stockscreener/pipeline/datasource/kr/xbrl/KrCommonEquity.java"
  - "src/main/java/com/stockscreener/pipeline/datasource/kr/xbrl/KrCommonIncomeRows.java"
  - "src/main/java/com/stockscreener/pipeline/datasource/kr/xbrl/KrCommonIncomeDeriver.java"
  - "src/main/java/com/stockscreener/indicator/CommonIncomeRead.java"
  - "src/main/java/com/stockscreener/indicator/calculator/FinancialMath.java"
  - "src/main/java/com/stockscreener/indicator/calculator/NcavToMarketCalculator.java"
  - "src/test/resources/db/testmigration/V70_8__kr_common_equity.sql"
---

# ADR-0199. 국내 PBR · NCAV 분모는 보통주 자본이다 — 신종자본증권은 별도재무제표로, 우선주는 참가 지분일 때 주식수 비로

## 결정

국내 관문 켬(`stockscreener.gate.kr`)에서 `PBR` · `PBR_MEDIAN_5Y`(의존 `PBR_DISCOUNT_5Y` · `PBR_PCTL`) · `NCAV_TO_MARKET`의 분모를 **보통주 자본**으로 바꾼다.
분자(보통주 시가총액 = 보통주 상장주식수 × 보통주 종가)와 분자 쪽 주당이익(ADR-0192 — 보통주 몫)과 기저를 맞춘다.

- 보통주 자본 = (지배 자본 − H) × c · NCAV 보통주 몫 = (유동자산 − 총부채 − H) × c.
- H = 지배기업이 낸 신종자본증권(보통주보다 앞서는 고정 청구권) · c = 보통주와 나란한 잔여 청구권 중 보통주 몫(보통주 유통 ÷ 전 종류 유통).
- 판정은 보통주 몫 엔진(`KrCommonIncome` 판 3)의 같은 행에 `KrCommonEquity`가 붙인다 — 손익 앵커와 따로, 행의 대차대조표 날짜 · 그 행에 묶인 판 인스턴스로.
- 저장은 관문 시험 마이그레이션 `V70_8`(`financial_common_income.common_fraction` · CHECK 넓힘) · `non_common` = `ncav_non_common` = H · `equity_branch` `kr` · `kr-s` · `kr-nokey` · `kr-open-h` · `kr-open-c`.

## 원칙

1. **지배기업 자신의 청구권은 지배기업 자신의 재무제표(별도)가 말한다.** 연결의 신종자본증권 · 상환전환우선주 부채는 자회사 것을 섞는다.
2. **보통주 몫으로 나누는 것은 원장이 그 종류를 참가 지분으로 실을 때(2계층 EPS)뿐이다.** 부채로 실은 종류는 자본 밖이고, 둘 다 아니면 원장이 그 종류의 청구권을 안 준다(못 정함). 다만 보통주 밖 비중이 2% 안이면 청구권이 무엇이든 보통주 자본이 [(지배 − H) × c, 지배 − H] 안이라 c로 정한다(미국 `immat`과 같은 값 · 같은 근거).

## 규칙

- MUST: H는 그 날짜의 **별도** 맥락 신종자본증권(자본변동표 성분 `HybridBondsMember` 먼저 · 표준 `dart:HybridBonds` 축 없음 · `ReportedAmountMember`). 별도만 낸 판은 별도 · 맥락 없음.
- MUST: 별도 값이 없고 연결 값 ≠ 0이면 — 그 연결 값이 다른 판 · 다른 날짜에서 별도 확인값과 짝을 이룬 연결 값과 같으면 그 별도 값(HL만도 2026 분기) · 아니면 그 날짜 별도 재무제표가 판에 있으면 0(자회사 것 — 현대차 · 한화에어로 · CJ제일제당) · 그것도 없으면 못 정함.
- MUST: 잔액이 어느 기저에도 없으면 **잔액 = 기초 + 발행 − 상환**. 기초는 사업연도 직전 별도 확인값, 그날까지 신종자본증권 증거가 없으면 0. 기초 ≠ 0이면 두 움직임(`IssueOfHybridBond` · `RepaymentOfHybridBond` 축 없음 · 사업연도 시작 ~ 그 날짜)이 다 실린 판에서만 쓴다(SK텔레콤 FY2023 — 상환을 안 실어 797B로 틀렸다). 움직임이 없으면 앞의 별도 확인값을 잇고, 확인값도 없는데 지배기업 자신의 흐름(이자 · 배당)이 있으면 못 정함.
- MUST NOT: 연결 신종자본증권을 지배 몫으로 읽지 않는다(한화에어로 2,332.8B는 한화오션 것 · 롯데지주 연결 772.1B 대 별도 623.1B).
- MUST NOT: 회사 확장 구성원(`…HybridBondsMemberOf…`)을 이름으로 읽지 않는다(2026-10-25 사용자 기준 — 종목 특정).
- MUST: c는 명부(그 날짜 = 기준일이면 그 해 · 아니면 전년 · 없으면 그 해)에서 — 한 종류이거나 보통주 밖이 전부 자기주식이면 1 · 그 날짜 지배기업 자신의 상환전환우선주 부채(DART 개념 이름에 `ConvertibleRedeemablePreferredStockLiabilities`)가 있고 보통주 밖 종류가 하나면 1 · 비중 ≤ 2%이거나, 종류 구성(종류 수)이 같은 해의 판에 2계층 EPS(DART 우선주 EPS · IFRS 참가지분상품 EPS ≠ 0)가 있으면 보통주 유통 ÷ 전 종류 유통 · 그 밖 못 정함. 명부가 없으면 우선 증거가 없을 때 1.
- MUST: 2계층 EPS 증거는 종류 구성이 같은 해의 것만 받는다 — 기간 경계 없이 받으면 뒤에 새로 낸 종류까지 덮고, 그 해 증거만 받으면 2024 앞 판(우선주 EPS를 확장 개념으로 실은 발행사가 많다 · ADR-0192 no-eps)이 같은 종류인데 못 정함이 된다(드라이런: 못 정함 530행 · 45종목 → 244행 · 20종목).
- MUST: `kr-s`(총자본이 곧 지배 자본 — 별도재무제표만 낸 판이거나 그 날짜 연결 지배 성분 = 축 없는 자본총계)는 지배 자본 칸이 비어도 총자본에서 뺀다. 창 판정(`controllingEquityThroughout`)도 그 행을 지배로 센다.
- MUST: 국내 행에는 메자닌 간극(자산 − 부채 − 자본)을 NCAV 청구권에 더하지 않는다 — 미국 개념이고 국내 양수 간극은 데이터 간극이다.
- MUST: Q4 행은 같은 기말의 FY 판(Q4 공시일 이하 마지막)의 판정을 쓴다. 판 재료가 없는 행은 못 정함(분자 `nokey`와 같다).
- MUST NOT: `total_equity_controlling`의 뜻을 바꾸지 않는다 — ROE · 부채비율 · Altman이 그 뜻을 읽는다. H · c를 따로 싣고 읽을 때 쓴다.

**사정거리.** 원칙 2의 「참가 지분」은 이익 참가를 말하고 잔여재산 조항은 원장이 구조화로 주지 않는다(재보지 않았다) — 2계층 EPS 비(우선 ÷ 보통 · 최신 사업보고서)가 0.99~1.033인 종목이 34 중 29이고, 밖 다섯 중 키움 0.236(보통주 밖 1.9%) · CJ제일제당 0.563(8.3%)은 c 오차 추정 약 1.4% · 3.6%다.

## 버린 대안

| 대안 | 근거(실측) |
|---|---|
| 연결 자본변동표 성분 · 축 없는 연결 `HybridBonds`로 H | 반증 안 렌즈: 지배 신종자본증권을 0으로 읽는 판 현 편입 69 · 12종목(CJ대한통운 9.5% · 롯데지주 9.7% · 아시아나 창 안 90.4%) · 자회사 것을 지배로 잡는 판(CJ제일제당 약 16%) |
| 다중 종류 전부 주식수 비 | 부채로 실은 상환전환우선주를 두 번 뺀다(파마리서치 10.2% · 브이엠 · 차바이오텍 · 알테오젠) · 자본으로 실은 전환형 종류(피엔티 · 에코프로머티 · 올릭스)는 원장이 전환 비율을 안 준다 |
| 다중 종류 전부 못 정함(미국 `conv` 그대로) | 원장이 2계층 EPS로 참가를 밝힌 38종목(삼성전자 · 현대차 · LG화학)의 PBR을 버린다 · 비중 0.004%(현대모비스)도 버린다 |
| 잔액 태그가 없으면 0 | 에코프로비엠 2025 분기 334.6B(지배 약 20%) · 롯데에너지머티리얼즈 2026 9.1%를 0으로 읽는다 |
| 지배 기저가 아니면 미표시(미국 NRG · AVGO 근거 그대로) | 별도만 낸 회사(케이뱅크 · 두산퓨얼셀 2021~2023)는 비지배가 없어 그 근거가 안 선다 — 값 → 빈칸(반증 밖 렌즈 4종목) |

## 검증

- 판정: `./gradlew test --tests '*KrCommonEquity*' --tests '*CommonEquityBasis*'`.
- 0콜 드라이런: `DartCommonIncomeSweepTest`(`KR_FILINGS`로 발행사 판 전부 — 출력 열 `equity` · `hybrid` · `fraction`).

## 결과

**드라이런(0콜 · 2026-11-01)**: 의심 종목 132(신종자본증권 증거 발행사 · 현 편입 다중 종류 53 · 대조군 10) · 2020~ 행 · 운영 SELECT 재료(XBRL 캐시 5,287판 · 명부 · 기업행위) · DB 생성 없음.
- 최신 판(132종목): 정함 118(`kr` 95 · `kr-s` 23) · 못 정함 14(H 6 — GS건설 · HMM · 아시아나 · 덕산하이메탈 · 에코프로 · 디앤디파마텍 · c 8 — 더존비즈온 · 와이지-원 · 켐트로닉스 · 브이엠 · 피엔티 · 앱클론 · 올릭스 · 에코프로머티).
- 최신 PBR 배수(지배 자본 ÷ 보통주 자본)가 바뀌는 종목 80 · 1% 넘게 67 · 10% 넘게 35 · 최대 대신증권 2.09(보통주 밖 유통이 보통주와 비슷하다 — 명부 보통 38.5M · 우선 30M · 기타 7.8M) · 한화오션 1.45 · 코리안리 1.25(`kr-s`) · 삼성전자 1.14 · 은행지주 1.07~1.16.
- 반증 두 렌즈 두 판(v1 안 「다시 짠다」 · 밖 「고쳐서 간다」 → v2 둘 다 「고쳐서 간다」): v1이 틀린 69판 · 12종목이 v2에서 전부 닫힘(안 렌즈 모의) · 연결만 있는 판 0 처리가 자회사 몫을 지배로 잡는 판 0.
- 관문 꺼짐: 읽는 쪽 · 유도가 `gate.kr` 뒤 · 미국 행 `common_fraction` NULL이라 미국 식 그대로.
- 재지 않았다: 롯데쇼핑 연결만 148.7~178.7B(5판 · 0으로 읽음 · 자회사 몫인지) · 잔여재산 조항 · 관문 날 받기 뒤 2024 앞 판 전 범위.
