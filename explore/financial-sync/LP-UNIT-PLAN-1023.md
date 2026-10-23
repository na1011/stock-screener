# LP 단위 — 클래스 축이 LP 클래스 축과 LP 가중평균을 못 읽는다 (계획 v1 · 2026-10-23)

progress 3번 줄 「LP 단위(SUN)」 · `docs/reload-gate.md` A2-c 행. 관문 뒤(`stockscreener.gate.us`)만 바꾼다.

## 0. 실측 (★ = 내가 원장 · 코드 · DB에서 직접 쟀다)

재료: `scratchpad/lp/` — companyfacts 476 CIK · SEC frames 42 · 인스턴스 265 · Alpaca 봉 4콜. DB는 SELECT만.

1. ★ **관문 꺼짐: SUN · BSM은 주식수 0행이다**(재무 37행씩). KRP 2/37 · GEL 26/37 · CQP 30/57 · MPLX 36/57 · WLKP 31/37.
   - 무엇에 물었나: `financial_statement`(is_latest) · SEC companyfacts `CIK0001552275` · `CIK0001621434`.
   - 대조군: ET · EPD는 축 없는 `dei:EntityCommonStockSharesOutstanding`가 있어 값을 받는다.
2. ★ **원장은 SUN 보통 단위를 준다 — 축 둘에 나눠서다.**
   - 표지: `dei:EntityCommonStockSharesOutstanding`가 `StatementClassOfStockAxis`의 `sun:CommonUnitsMember` 136,896,651 · Class C 16,410,780(자회사 보유) · Class D 51,517,198(SunocoCorp 보유)에만 실린다(2026-08-06 10-Q).
   - 가중평균: `WeightedAverageLimitedPartnershipUnitsOutstanding`가 **`LimitedPartnersCapitalAccountByClassAxis`**의 같은 구성원 `sun:CommonUnitsMember` 136,895,211에만 실린다. 2023-08 뒤 공시는 축 없는 꼴이 없다.
   - EPS는 축 없는 `NetIncomeLossPerOutstandingLimitedPartnershipUnitBasicNetOfTax`(0.94)다.
3. ★ **우리 판독기는 그 축을 버린다.** `ClassFactReader`는 `StatementClassOfStockAxis` · IFRS `ClassesOfShareCapitalAxis`만 클래스 축으로 받는다.
   - 그래서 관문 켬 클래스 축 스윕(0콜 · `SecShareClassSweepTest`)에서 SUN 앵커 24판 중 17판이 숨김이다. 16판이 `no-wavg`, 범주는 「안줌」이다.
   - **「안줌」은 거짓이다** — 원장이 다른 축으로 준다. 가짜 한계다.
   - 뿌리 한 줄: `ClassFactReader.read`의 `member = dims.getOrDefault(CLASS_AXIS, …IFRS_CLASS_AXIS…)`.
   - 0콜 재현: 위 스윕에 SUN 재료를 먹인다.
4. ★ **reload-gate A2-c가 적은 후보(「LP 단위 태그를 주식수로」)는 틀린 값을 낸다.**
   - SUN 2025 10-K의 축 없는 `LimitedPartnersCapitalAccountUnitsOutstanding` 51,517,198은 **Class D 단위**다. 같은 인스턴스의 Class D 구성원 값과 같다.
   - 보통 단위 136.9M의 0.38배다. 그래서 LP 태그를 `SecEdgarColumn.SHARES_OUTSTANDING` 사다리에 넣지 않는다.
5. ★ **지금 경로(d)의 분모 n — 두 목록이 갈렸다.**
   - `IssuerFacts.WEIGHTED`는 기본 · 기본겸희석 두 태그다.
   - `ClassFactReader.WEIGHTED_TAGS`는 네 태그다(`…SharesIssuedBasic` · LP 단위가 뒤에 더해졌다).
   - 그래서 축 없는 dei가 있는 LP(EPD · ET · MPLX · WES · CQP …)와 `SharesIssuedBasic`만 쓰는 법인(CI · GGG · GTES · SEIC)의 d 판정 n이 비어 있다.
   - ADR-0179 §규칙: 관문 켬 미국에서 주식수 비 지표 셋은 두 끝 다 n이다. 그래서 그 셋이 **값 없음**이 된다(`FinancialMath.endpointShares`는 시점 주식수로 안 내려가고, `fromFinancials`는 앞 판으로 걷다가 끝까지 비면 null).
   - 모집단: `.factcache` 1,217종목. 2024-09 뒤 d 판정의 n이 전부 빈 종목은 20, 그중 현 편입은 18이다.
6. ★ **LP 개념 공시자 모집단.** SEC frames(LP 개념 7개 × 기간 10)에서 186 CIK이 나온다. 그중 우리 `stock`에 64 CIK · 73줄이 있다.
   - 앵커 공시(2020-09 뒤 · 축 없는 dei 없음)가 있는 곳은 GEL 24 · SUN 24 · BSM 24 · KRP 24 · SMC 7 · DLNG 6 · KNOP 2 · WLKP 2와 법인 9곳이다(BGC · ESRT · PECO · NHP · JSPR · SST · DYN · VGAS · ANGX).
   - 한계: 축 없는 LP 개념을 한 번도 안 낸 LP는 frames에 안 잡힌다. 그런 LP는 주식수가 비어 null 모집단(`financial_statement` 2020-09 뒤 null 660줄 · 439 CIK — companyfacts 전부 받음)에 들어오고, 거기서 LP 개념을 가진 곳은 위와 같다.
7. ★ **시총(Alpaca 10-22 종가)**: SUN 136.9M × $72.52 ≈ **$9.9B**(1000위 문턱 약 $5.2B 위 — 관문 켬에서 편입권) · BSM ≈ $3.1B · GEL ≈ $1.7B · KRP ≈ $1.5B.

## 1. 계획

관문 켬 코드만 바꾼다(클래스 축 엔진은 관문 켬에서만 돈다 — ADR-0179 §검증).

- **P1 — LP 클래스 축.** `ClassFactReader`에서 LP 개념(가중평균 LP 단위 셋)에 한해 `LimitedPartnersCapitalAccountByClassAxis`를 클래스 축으로 받는다.
  - 조건: 그 맥락에 `StatementClassOfStockAxis`가 없을 때다. 구성원 열쇠는 구성원 그대로다(표지 구성원과 같은 QName — SUN 실측).
  - LP 개념 밖(법인 태그)에는 안 넓힌다. REIT 운영 파트너십 단위가 그 축으로 실릴 수 있다.
- **P2 — LP+GP 합계 가중평균**(`WeightedAverageNumberOfLimitedPartnershipAndGeneralPartnershipUnitOutstandingBasicAndDiluted` · `WeightedAverageLimitedPartnershipAndGeneralPartnershipUnitsOutstandingBasic`)을 **다른 가중평균이 없을 때만 쓰는 폴백**으로 더한다.
  - 판독기의 `periodValues`는 뒤 태그가 이기므로 목록 앞에, companyfacts `pick`은 앞이 이기므로 뒤에 둔다.
  - 근거(★): 앞에 두면 CAPL 2016 Q1 d 판정 n이 28,475,363에서 4,630,769로 바뀐다(그 태그가 다른 단위를 실었다).
- **P3 — 두 목록을 한 자리에서 짓는다.** `IssuerFacts`가 `ClassFactReader.WEIGHTED_PRIMARY_TAGS` + `WEIGHTED_FALLBACK_TAGS`를 쓴다(중복 목록 제거).
- **P4 — LP EPS 개념은 안 더한다.**
  - ★ 더한 시안: KRP 15판이 보임에서 숨김으로 갔다(`A-FAIL/대용`). LP는 축 없는 분자가 `ProfitLoss`뿐이라 EPS × n ≠ 대용 분자가 되고(NCI · Class B · 우선 배분), 폐합이 실패한다.
  - LP 분자(`NetIncomeLossAllocatedToLimitedPartners`)는 BSM에서 E×W와 맞는다(400.8 · 400.8). SUN에서는 10-K에만 축 없이 있고, KRP에는 없다. 분자 축은 progress 「주당 지표 분자(TPG)」 항목으로 넘긴다.
- **P5 — `READER_VERSION` 4 → 5.** `sec_filing_class_fact`는 운영에 없다(V70은 testmigration). 관문 날 받기가 처음부터 새 판으로 받는다.
- **P6 — 기록.** ADR-0179 갱신 또는 새 ADR(LP 축 · 폴백 순서 · 버린 대안: LP 태그 사다리 · LP EPS) · `reload-gate.md` A2-c 행 · progress.

## 2. 드라이런 (시안을 지금 작업 트리에 넣고 잼 — 커밋 전)

0콜 스윕 두 판: LP 공시자 64 CIK(앵커 240판 + d 판정 · 2,640판정)와 `.factcache` 1,217종목(d 판정만 · 54,671판정). `base` = main.

- **앵커(T) 240판**: 숨김→보임 27 · 보임→숨김 **0** · 보임 유지 147 · 숨김 유지 66.
  - SUN 숨김→보임 16(2024-11 뒤 전부). 최신 N_L = 136,896,651(표지 보통 단위) · n = 136,895,211.
  - SUN 2024-08-08 하나만 `jump 84.4→117.3M`으로 숨김이다. NuStar 합병 단위 발행이고, 기존 점프 규칙이다.
  - GEL 2020-11~2021-05 3 · BSM 3 · WLKP 2 · JSPR 2 · KRP 1.
  - 보임 유지 값 변화: SUN 2021~2023 10-Q 7판이 축 없는 n 값에서 `P-ab/sumw` 값으로 바뀌었다(+0~0.09%). BSM 20판이 `A-unverifiable`에서 `A-ok`로 바뀌었다(값 같음).
- **d 판정 n**: `.factcache`에서 빈 n 636칸이 채워진다(2020-09 뒤 276 · 29종목). 상태 · 값 · 다른 분모 변화는 **0**이다.
  - 2024-09 뒤 n 전부 없음: 현 편입 18 → 9.
  - 남는 9: BKR · CBSH · FDXF · KKR · LLYVK · LYB · PAA · PAGP · SJM. companyfacts에 축 없는 가중평균이 없다 — 별개 축(아래 4).

## 3. 계획 밖 (순위 · 하류 · 회귀)

- **순위**: 관문 켬에서 SUN이 약 $9.9B로 편입권에 든다. 과거 평면(`UsPastRanking`)도 2024-11 뒤 판에서 SUN을 보게 된다. 2024-08 판은 숨김이다 — 그 사이(2024-08~11) SUN이 과거 명부에서 어떻게 다뤄지는지는 재지 않았다(보류 규칙 B는 「앞 편입」에만 선다 · SUN은 앞 편입이 없다).
- **SUN 분자**: 연결 순이익은 Class D(SunocoCorp)와 우선 배분을 포함한다. PER = 시총(보통 단위) ÷ 연결 순이익은 기저가 어긋난다 → 「주당 지표 분자(TPG)」 항목으로 넘긴다(재지 않았다 — 그 항목에서 잰다).
- **SUNC**(SunocoCorp LLC · 별도 상장 · CIK 2089661)는 재무 3행이고 별개 줄이다. 이중 계상은 아니다. SUN 보통 단위와 SUNC 주식은 다른 증권이다.
- **회귀**: 관문 꺼짐은 클래스 축 엔진을 안 부른다. 같은 판독기를 쓰는 관문 밖 소비자는 `readListingCover`(A′ 예탁증권 판정 · 상장 판정)다. 그것은 표지만 읽어 기간 팩트 변화가 닿지 않는다 — 전체 시험으로 확인한다.

## 4. 넘기는 것 (같은 측정에서 나온 별개 축)

- **d 판정 n 커버리지(관문 켬)**: P3 뒤에도 `.factcache` 2020-09 뒤 d 판정 22,970 중 687칸 · 220종목의 n이 비고, 현 편입 9종목은 2024-09 뒤 전부 빈다.
  - 원인 분류(★ 칸 수): 10-K Q4 유도 실패 378 · companyfacts에 가중평균 없음 258 · 목록 밖 태그만(희석만 등) 29 · 10-Q 3개월 창 없음 17 · 10-K 12개월 창 없음 5.
  - 「가중평균 없음」이 원장이 안 준 것인지(인스턴스의 축 · 다른 태그) 아직 안 물었다. 다음 항목으로 잰다.
- **KRP 점프 숨김 8판**: 단위 발행이 실제이지만 대용 분자로 확인을 못 해 숨긴다(기존 규칙 · LP 축과 무관). 최신 판은 보인다.
