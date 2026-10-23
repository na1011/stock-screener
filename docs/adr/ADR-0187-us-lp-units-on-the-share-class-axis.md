---
id: ADR-0187
status: 채택됨
date: 2026-10-23
applies_to:
  - "src/main/java/com/stockscreener/pipeline/datasource/us/shareclass/ClassFactReader.java"
  - "src/main/java/com/stockscreener/pipeline/datasource/us/shareclass/IssuerFacts.java"
---

# ADR-0187. 미국 LP 단위를 클래스 축으로 읽는다(관문 뒤)

## 결정

클래스 축 판정(ADR-0179)이 LP 발행사의 단위를 읽게 한다. 두 경로를 고친다.

- **앵커 경로(인스턴스)**: `ClassFactReader`가 LP 단위 가중평균(`WeightedAverageLimitedPartnershipUnitsOutstanding`)을 LP 클래스 축 `LimitedPartnersCapitalAccountByClassAxis`에서도 받는다. 조건은 그 구성원이 같은 공시 표지(`dei:EntityCommonStockSharesOutstanding`)의 클래스 구성원인 것이다.
- **LP+GP 합계 단위**(`WeightedAverageNumberOfLimitedPartnershipAndGeneralPartnershipUnitOutstandingBasicAndDiluted` · `WeightedAverageLimitedPartnershipAndGeneralPartnershipUnitsOutstandingBasic`)는 같은 (구성원, 기간)에 다른 가중평균이 없을 때만 싣는다.
- **지금 경로(companyfacts · d 판정의 분모 n)**: 기본 · 기본겸희석 두 태그 밖의 판독기 목록 태그(`…SharesIssuedBasic` · LP 단위 · LP+GP)를 그 공시의 축 없는 표지 주식수로 확인될 때만 받는다(|log10(n ÷ 표지)| ≤ `IssuerTimeline.TAU_COVER`).
- 판독기 판 `READER_VERSION` 4 → 5. 관문 뒤 클래스 축 엔진만 바뀐다. 관문 꺼짐은 이 경로를 안 부른다.

설계 · 실측 · 반증의 정본은 탐색 브랜치 `explore/s-vintage-coherence`의 `explore/financial-sync/LP-UNIT-PLAN-1023.md`다. v1을 두 렌즈로 반증받았고 둘 다 「고쳐서 간다」였다. 고친 판이 v1.1이다.

## 왜

- **SUN은 관문 켬에서도 숨었다 — 가짜 한계다.**
  - 원장은 보통 단위를 표지 클래스 축 `sun:CommonUnitsMember`(136,896,651 · 2026-08-06 10-Q)에 싣는다.
  - 가중평균은 LP 클래스 축의 같은 구성원(136,895,211)에 싣는다. 2023-08 뒤로는 축 없는 꼴이 없다.
  - 판독기가 그 축을 버려서 앵커 24판 중 17판이 `no-wavg`(범주 「안줌」) 숨김이었다.
  - SUN 시총은 10-22 종가 기준 약 $9.9B로, 1000위 문턱(약 $5.2B) 위다.
- **지금 경로의 분모 목록이 판독기 목록과 갈렸다.** `IssuerFacts`는 두 태그, `ClassFactReader.WEIGHTED_TAGS`는 네 태그였다.
  - 그래서 축 없는 dei가 있는 LP(EPD · ET · MPLX · WES · CQP)와 `SharesIssuedBasic` 법인(CI · GGG · GTES · SEIC)의 n이 비었다.
  - ADR-0179 §규칙(주식수 비 지표 셋은 두 끝 다 n)에 따라 관문 켬에서 그 셋이 값 없음이었다.
  - 측정: `.factcache` 1,217종목. 2024-09 뒤 n 전부 없음인 현 편입이 18이었다.

## 규칙

- MUST NOT: LP 단위 태그를 `SecEdgarColumn.SHARES_OUTSTANDING` 사다리에 넣지 않는다. SUN 2025 10-K의 축 없는 `LimitedPartnersCapitalAccountUnitsOutstanding` 51,517,198은 Class D 단위(SunocoCorp 보유)이고, 보통 단위의 0.38배다.
- MUST: LP 클래스 축은 LP 단위 개념에만 클래스 축이다. 법인 태그(`WeightedAverageNumberOfSharesOutstandingBasic` 등)는 안 넓힌다. REIT 운영 파트너십 단위가 그 축에 실린다.
- MUST: LP 축 구성원은 그 공시 표지의 클래스 구성원일 때만 받는다. 이 축에는 거래 구성원(KNOP `PartnersCapitalAccountSaleOfUnitsMember` 252,000)과 0인 종속 단위(BSM `SubordinatedUnitsMember`)도 실린다.
- MUST: LP+GP 합계 단위는 순서가 아니라 배타로 막는다. 판정기에는 앞 태그가 이기는 자리(`firstPerMember` · `hw`)와 뒤 태그가 이기는 자리(`periodValues`)가 함께 있다. 둘이 함께 실리면 두 자리가 다른 값을 고른다.
  - JSPR 2022 9개월: 기본 36,425,000 대 합계 3,028,277.
  - CAPL 2016 Q1: LP 28,475,363 대 합계 4,630,769.
- MUST: companyfacts의 넓힌 태그는 표지로 확인될 때만 n이 된다. companyfacts에는 축이 없어 그 태그가 무엇을 셌는지 모른다.
  - REG의 축 없는 LP 단위는 운영 파트너십 외부 조합원 단위다(표지의 0.5%).
  - NRP · VANI는 천 단위로 잘못 실었다.
  - 확인이 안 되면 값 없음으로 둔다 — 틀린 n보다 낫다.
- MUST: 상장 판정 표지 판독(`readListingCover`)은 LP 재료를 안 읽는다. 예탁증권 표지 저장(`DepositaryCoverStore` 판 1)의 바이트가 그대로여야 한다.

## 버린 대안

| 대안 | 기각 근거 |
|---|---|
| LP 단위 태그를 주식수 사다리에(reload-gate A2-c 행의 후보) | 축 없는 LP 단위가 Class D를 실었다(SUN 51.5M 대 보통 136.9M) |
| LP EPS 개념(`NetIncomeLossPerOutstandingLimitedPartnershipUnit…`)을 EPS 목록에 | 드라이런 시안: KRP 15판이 보임 → 숨김(`A-FAIL/대용`). LP는 축 없는 분자가 `ProfitLoss`뿐이라(NCI · Class B · 우선 배분 포함) EPS × n과 안 닫힌다 |
| LP 클래스 축을 모든 개념에 | REIT 운영 파트너십 단위가 그 축에 실린다 — 법인 태그로 들어오면 분모가 섞인다 |
| 합계 단위를 목록 앞(뒤가 이기는 자리에서 최저)에 | 앞이 이기는 자리에서는 최고가 된다 — JSPR 2023-03 10-K 값이 36,654,160 → 36,653,203(반증 안 1) |
| companyfacts 넓힌 태그를 확인 없이 | 2020-09 뒤 29칸 · 7종목이 표지와 0.6~1.67배를 벗어났다(REG 17 · NRP · VANI · SST · KNOP — 반증 밖 1) |

## 결과

**드라이런(0콜 스윕 · 2026-10-23 · 원장 분할 계수 반영 · `SecShareClassSweepTest`)**

LP 공시자 64 CIK(SEC frames의 LP 개념 공시자 186 CIK 중 우리 `stock`에 있는 것): 앵커 240판 · 판정 2,640.
- 숨김 → 보임 29: SUN 16(2024-11 뒤 전부) · BSM 4 · GEL 3 · JSPR 2 · KNOP 2 · WLKP 2.
- 보임 → 숨김 **0**.
- 보임 유지 값 변화는 SUN 7판뿐이다. 2021~2023 10-Q가 `P-ab/sumw`로 가서 +0~0.09%.
- SUN 최신 N_L = 136,896,651(표지 보통 단위) · n = 136,895,211. 2024-08-08 하나만 `jump 84.4→117.3M`(NuStar 합병 단위 발행 · 기존 점프 규칙)으로 숨김이다.

`.factcache` 1,217종목: d 판정 54,671.
- 빈 n이 606칸 채워졌다(2020-09 뒤 273 · 29종목). 상태 · 값 · 기존 n 변화는 **0**이다.
- 채운 값이 표지와 10^0.1 넘게 갈린 것은 2칸(ET 2014 · GGG 2018 10-K)이다. 둘 다 분할을 낀 Q4 유도이고 2020-09 앞이라 지표 창 밖이다.
- 2024-09 뒤 n 전부 없음인 현 편입: 18 → 9.

**시험**: `LpUnitClassFactsTest` 4건. 대조군 다섯(규칙 하나씩 되돌림)이 모두 붉다. 전체 `clean build` 336클래스 · 2,439건 · 실패 0.

**재지 않았다**
- 관문 켬 명부에서 SUN의 실제 순위. 관문 날 05 · 07의 순위로 잰다.
- 과거 평면이 SUN 2024-08~11(숨김 판 사이)을 어떻게 다루는가.

**넘긴 것**(`docs/reload-gate.md` 행)
- SUN 분자: PER이 원장 LP EPS 대비 1.87배 틀린다 → 「주당 지표 분자」 행.
- LP 자본 미매핑(관문 꺼짐 · 현 편입 LP 7종목 PBR · ROE 없음) → 새 행.
- d 판정 n 커버리지(남는 현 편입 9종목) → 새 행.

## 검증

- `./gradlew test --tests '*LpUnitClassFactsTest' --tests '*ClassFactReaderTest' --tests '*ShareClassStorageFormTest'`
- 저장 재료 스윕: `SHARE_CLASS_DIR=<재료> ./gradlew test --tests '*SecShareClassSweepTest' --rerun-tasks`. 위 §결과의 수와 맞대 본다.
- 이 결정은 운영 자동 검증 대상이 아니다. 관문 뒤 경로다.
