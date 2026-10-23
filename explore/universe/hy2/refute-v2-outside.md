# 반증 — 누계 기저 계획 v2 · 렌즈 「계획 밖」 (2026-10-23)

판정: **다시 짠다**
뼈대인 「누계 라벨은 6-K에서만 만든다 · 10-Q 누계 행 0을 단언」(§1a · §3-1(d))의 근거가 실측으로 무너졌다(1번). 같은 뿌리(`labelOf`가 6·9개월을 버림)에서 더 크게 틀어진 축이 10-Q 쪽에 있고, 관문 재고의 Q4 정책 설계(P3)와 라벨 개념이 겹친다(2번). 나머지(3~9번)는 고쳐서 갈 수 있는 지적이다.

작업 조건: DB 생성 없음 · 운영 DB는 SELECT만 · gradle 안 돌림 · 외부 호출 0. 스크립트는 `refute-hy2-out/out/py/`에 있다(전부 `python3 -I`).

---

## 1. 「10-Q 누계는 얻을 것이 없다」가 거짓이다 — 더 크게 틀어진 축이 10-Q 쪽에 있다 · 심각도 높음
- **계획이 한 말**: §1a는 「10-Q 제출자는 자기 기말 3개월이 없는 10-Q가 사실상 없다 → 그래서 누계 라벨은 6-K에서만 만든다」라고 했다.
  - 이것은 3개월 팩트가 있느냐를 잰 것이다. TTM이 서느냐를 잰 것이 아니다.
- **근거 1 — 운영 SELECT**: 현 편입 미국 1,000 중 최신 4개 비FY 행이 연속 12개월을 덮지 못하는 종목은 40이다. 그중 27은 FY보다 새 중간 행을 이미 저장하고 있다.
  - 10-Q 제출자가 그 다수다: AMRZ CAI CHYM CRCL FIG HNGE NIQ RAL(4행인데 2025 Q4가 비어 있음) · BLLN FIGR LLYVK NAVN NTSK Q SOLS VNOM(3행).
  - 이유: 상장 첫해라 Q1이 없어 `deriveFourthQuarters`가 Q4를 못 짓는다. 그래서 지금 FY 기저로 떨어져 있다.
- **근거 2 — `.factcache` 09-15**: 16종목 모두 자기 10-Q에 원 공시 누계가 있다.
  - 2025 9개월: 전원.
  - 2025 6개월: 앞 8종목.
  - 2026 6개월: Q·SOLS 밖 전원.
  - 순이익 TTM을 누계 식(`H1'26 + FY25 − H1'25`)으로 다시 지으면 이렇다.

  | 종목 | 지금 FY25 | 누계 식 |
  |---|---|---|
  | CAI | −68.1 | +105.1 |
  | CRCL | −69.5 | +451.3 |
  | HNGE | −528.3 | +109.1 |
  | CHYM | −1,009.9 | −18.2 |

  - 앞 셋은 **부호가 뒤집힌다**.
  - 뒤 7종목은 `Q4 = FY − 9개월`(F2b가 `nineMonthSlot`으로 이미 쓰는 재료)로 분기 기저가 선다.
- **크기 비교**: 계획이 지금 값을 바꾸는 현 편입은 AER(−9.5%) · DSGX(+15%) · BNT 셋이다. 10-Q 쪽은 16종목이고 부호 반전이 있다.
  - CLAUDE.md 「더 크게 틀어진 다른 축」이 바로 이 경우다.
- **오류 형태**: 1(묶어서 판정 — 「10-Q 제출자」 전원에 근거 하나) · 3(TTM이 서는가가 아니라 3개월이 있는가를 봄).
- **재지 않음**: 칸×일 수 · 16종목 밖 과거 편입 · 후보.

## 2. 순서 — 관문 재고의 Q4 정책(P3)과 개념이 겹치는데 계획이 P3를 언급하지 않는다 · 높음
- `docs/reload-gate.md:123` 순서 3은 「설계: Q4 정책(P3)」이 어댑터 입력·조립·유도층보다 앞이라고 정한다.
- P3 계획(브랜치 `explore/s-vintage-coherence` · `explore/s-vintage/P3-PLAN.md:39 · 78 · 127`)은 이미 **누계 라벨 C2·C3**와 「P3b 누계 차분」 · 「P3c 9개월 누계 술어」를 쓴다.
- v2는 같은 개념을 6-K 전용 `H1` · `M9`로 `ck_fs_period`에 박는다(되돌리기 어려운 스키마 값). 또 `deriveFourthQuarters` · `quarterYears`를 고친다(P3a가 고칠 자리).
- 라벨 계열이 둘이 되든지, P3가 뒤에 이 스키마를 다시 연다.
- **재지 않음**: P3 판에서 C2·C3가 저장 라벨인지 진실표 전용인지.

## 3. F2b(ADR-0184)의 「재분류된 해」 가드가 꼬리 계산에서 빠진다 · 높음 · 오류 형태 4
- **F2b의 Q4 규칙**: 9개월 공시에 중단 줄이 없는데 FY 중단이 0이 아니면 `null`이다(`SecEdgarFacts.q4Discontinued` :2470-2504). MLM 94 대 참값 46이 그 근거였다.
- **계획의 꼬리**: 행 함수 `continuingIncome`을 FY와 누계에 각각 쓴다(§2a-5 · §2c-4).
  - 비Q4 행은 줄이 없으면 0이다(`withDiscontinued` · `toReport` :1374 — 「그 라벨을 실은 어느 판에도 중단 줄이 없다면 0」).
  - 그래서 가드 없이 섞인 값이 나온다. 행 단위에서 정당한 「줄 없음 = 0」을 잔차로 옮긴 것이다.
- **실측(`disc.py` · 원 공시 누계에 중단 줄이 없고 그 FY 중단 ≠ 0)**:
  - GLNG 9M'22(FY 중단 −76.45M)
  - MT H1'23(FY23 1,469M)
  - VALE H1'21(−2,291M)
  - VEON H1'21(69M)
  - SWVL H1'24 · TRIB H1'18
  - IHS H1·9M'25(분기 기저가 이기므로 값은 무관)
- **MT H1'24 점의 꼬리**: cont(FY23) = 1,022 − 1,469 = −447, cont(H1'23 원 공시) = 3,047(중단 줄 없음) → −3,494.
  - 같은 꼴의 분기 제출자 Q4는 미표시다. 누계 제출자만 값이 난다.
- **같은 축의 짝짓기 구멍(VEON 꼴)**:
  - FY22(20-F 2023-07-24)는 이미 새 기준으로 나왔다(계속영업 733).
  - H1'22 원 공시(2022-08-04)의 계속영업은 −35다. 같은 기간 재작성 비교열(283)은 2023-08-29에야 나온다.
  - 규칙(FY 판 공시일 이하의 가장 늦은 판)은 −35와 짝짓는다 → 꼬리 768(재작성 기준이면 450).
  - `pairing.py`는 순이익 한 태그만 본다(VEON 순이익은 87 = 87). 그래서 이 꼴을 「섬」으로 셌다 — 오류 형태 1.
- **재지 않음**: 전체 건수 · 매출 칸.

## 4. 드라이런 1의 보충 재생 경로가 지금 코드·저장분으로는 안 선다 · 중
- **운영 `sec_filing_supplement`**: 판독 판 7이 147행, 8이 4,121행이다. main은 `XbrlInstanceReader.READER_VERSION = 10`(:72).
  - `ifrs-full` 팩트를 실은 저장 행은 **0**이다(판 9 · 10에서 IFRS를 읽기 시작했다).
  - 그래서 IFRS 6-K 중 companyfacts가 빠뜨린 것(ADR-0182: 2023 뒤 97/375)의 누계는 저장분으로 못 본다.
  - §1a의 IFRS 열과 §1c의 IFRS 수는 하한이다.
- **`SupplementReplay`(시험 · :26-80)는 녹화된 SEC 경로를 내주는 서버다.**
  - 공시 목록 파일이 없으면 `filings()`가 404 → `fromStoreOnly`(회수 정지 · 판이 다르면 `staleReused`)로 간다.
  - 그리고 `missing()`이 0을 넘는다. `SecEdgarFactsSweepTest:214`가 `missing() == 0`을 단언하므로 그 스윕은 **실패한다.**
  - 목록이 있으면 판 8 ≠ 10이라 다시 받으러 가고, 인스턴스가 없어 역시 404가 난다.
- **「시험 안의 파일 원장」은 없다.** 있는 것은 `MemorySupplementLedger`와 JDBC 저장소뿐이다.
- **H1 · M9를 켜는 것 자체는 보충 대상이나 다시 받기를 바꾸지 않는다.**
  - 대상 = 보충 폼 ∧ `isXBRL` ∧ companyfacts 미수록(`targets` :399) — 라벨과 무관하다.
  - `mergeInto`(:254)는 기간 길이를 안 거르므로 6·9개월 팩트가 이미 트리에 들어온다.
  - `SecEdgarFacts`는 판 고정 범위 밖이다.

## 5. 반기만 내는 발행사는 그 6-K에서 처음 행이 생긴다 — 주식수·순위·예탁 k를 단언 (c)가 덮지 않는다 · 중(ADS는 조건부 높음)
- **(c)가 공허한 쪽**: §2a-4 · (c)는 「같은 (공시, 기말)의 H1 행과 Q2 행」만 본다.
  - CAMT · NVMI · CLBT · MNDY · GLNG · CHKP(M9)와 IFRS 반기 전용 약 28은 Q2 행이 없다. 이들에게 그 단언은 공허하다.
  - 새 행이 최신 기말이 되어 `JdbcStoredShareCounts`(DISTINCT ON period_end DESC) · `PriceRefutation` · `PriceIntegrityAlerter` · `ShareBasisDeriver.filingsOf` · 가드 훅 `guard-rebalance-on-dirty-rank.sh:99`가 그 행을 읽는다.
- **선례**: `progress.md:472` — 6-K Q2 행을 들인 refill이 과거 격자 174칸의 주식수 선택을 바꿨다(BNT 1.61배).
- **실측(`shares6k.py` · `us13`)**: 6-K 행의 주식수 출처가 발행사마다 다르다(오류 형태 1).
  - CAMT · NVMI · CLBT · MNDY · CHKP: `CommonStockSharesOutstanding`(NVMI 29,652,464 — 같은 공시의 가중평균은 29,315로 ×1000이 어긋나 있다).
  - GLNG: 표지도 잔액 태그도 없어 희석 가중평균 105,517,000이다(20-F 표지는 104,534,703).
- **IFRS**: H1 · M9 원 공시 131건 중 표지 주식수를 실은 것이 **0**이다. IFRS 별칭에는 주식수가 없다(`TaxonomyFrame` 별칭 목록) → 행 주식수가 NULL이다.
- **ADS 경로**: 관문 켬 클래스 축에서 6-K 행은 `standingOn` 판정 주식수를 받는다(`ShareClassLine.read` :97-106).
  - 그런데 예탁 k는 그 접수번호의 보통주 수가 NULL이라 이어받기를 못 한다(`DepositaryRatioJudge.near`).
  - 6-K 표지는 애초에 받지 않는다(`DepositaryCoverCollector.PERIODIC` :43에 6-K 없음) → `UNRESOLVED`.
  - `classAxisCounts`(:259-262)는 그 판에서 멈춘다 → **그 종목이 다음 20-F까지 순위에서 빠진다.**
  - 조건: ADS ∧ 지금 경로가 아닌 클래스 판정 ∧ 반기 전용 6-K(HSBC · RIO · WDS가 후보).
- **재지 않음**: 클래스 · 예탁 표가 운영에 없어서다(V70 미적용 — `pg_tables`에 `sec_%`가 보충 하나뿐).

## 6. 「누계가 FY보다 새롭지 않으면 무변화 · 미정은 FY에 머문다」는 유량에만 선다 · 중 · 오류 형태 2
- §2c-6은 `pointRows`에 누계 행을 기저와 무관하게 더한다.
- 그래서 ANNUAL로 남는 반기 전용 · 미정 종목(NVMI · CHKP · TSEM 꼴)도 `latestPoint()` · `sharesOf`가 H1 행으로 옮겨간다. 읽는 곳은 다음과 같다.
  - `NcavToMarket:104` · `DebtRatio:75` · `AltmanZ:167`(잔액은 H1 기말 · EBIT · 매출은 FY)
  - `RoicPretax:107` · `RoicAfterTax:121`
  - `MARKET_CAP` 계열
  - `endpointShares`의 `weightedShares`(:664 — 6개월 분모)
- 드라이런 1(b)는 행 단위이고 드라이런 2의 꼴 시험 목록에 이 칸들이 없다.
- **재지 않음**: 칸 수.

## 7. 소비자 전수 — 「ADR 전제 SQL 여섯」이 아니다 · 중 · 오류 형태 2
- **v2가 빠뜨린 펜스**: `ADR-0164:94`(기간 기저 펜스 · `fiscal_period <> 'FY'` 최신 4행). v1은 이것을 셌다.
  - 안 고치면 Q2 + H1 + M9가 최신 4행을 채워 분기 제출자(ICLR · AS · AZ 등)가 「폴백」으로 잘못 분류된다. 펜스는 OK를 내지만 수가 거짓이 된다.
- **`financial_statement`를 읽는 premise 펜스는 34개 ADR 파일에 있다**(`awk` 추출).
  - `period_end DESC`로 최신 행을 고르는 것: 0056 · 0104 · 0125 · 0153 · 0162 · 0164 · 0171. Q2와 H1이 기말 · 공시일 · 판까지 같으면 임의로 고른다.
- **문서 서술(기록 정정 목록에서 빠짐)**:
  - `docs/phase-criteria.md:1810`(「11종목 원장 0건 — 진짜 한계」)
  - `docs/api-spec/sec-edgar-financial.md:324`
  - `RawFinancialReport:23 · 32` javadoc(「`FY|Q1|Q2|Q3|Q4` … `ck_fs_period`와 같은 집합」)
  - `SecEdgarFacts:2017` javadoc
  - `docs/reload-gate.md:140`(「분기 TTM 원장 0건 11종목 · GLNG」을 진짜 한계로 적은 줄)
- **안전 확인(라벨 문자열 · FY 전용)**: `GovernanceMath:221` · `GovernanceFactDeriver:241 · 292` · `StockFold:282` · ops `a2.sql` · `00-precheck.sh` · probe 시험 셋(fiscal_period 참조 0).

## 8. TSEM H1'25 — 원장에 없는가, 안 읽었는가 · 낮음
- **운영 `sec_filing_supplement`(CIK 928876)**: 2017-08-03 NO_XBRL과 2026-08-17 OK 두 행뿐이다.
  - 보충 대상 규칙(`isXBRL` ∧ companyfacts 미수록 ∧ 창 안)상 2025 반기 6-K가 XBRL이었다면 OK · NO_XBRL · FAILED 중 하나로 남았어야 한다.
  - 행을 지우는 길도 없다(`settle` javadoc).
  - 그래서 「목록이 XBRL 없음을 말했다」 쪽이 강하다. 다만 이것은 간접 증거다.
- **`filing_consumption`은 2026-10-13 뒤만 있다**(TSEM 6-K 3건 · 전부 `isXBRL=0`) — 2025를 못 답한다.
- **재지 않음**: submissions 한 콜(금지).

## 9. 「비교열만 = 그 발행사가 XBRL 6-K를 낸 첫해」는 짐작이다 · 중 · 「우리 컬럼이 비었다 ≠ 소스가 안 준다」
- `cmponly.py`(`ifrs130`)로 직전 해 같은 길이의 원 공시가 companyfacts에 없는 누계 점 51을 셌다.
- 그중 **16은 그보다 앞서 XBRL 6-K를 이미 낸 발행사**다: VALE · STNG · EQNR · GFS · LEGN ×2 · TRMD ×2 · CMBT ×2 · BHP · BWAY · AKAN · AZ · MLEC · TRIB.
  - 이들의 「원 공시 없음」은 첫해가 아니다. companyfacts 누락(ADR-0182 · 26%)일 수 있고, 판 10 보충이 받으면 섬으로 바뀔 후보다.
- 나머지 35도 「첫해」와 「누락」을 가르지 않았다.
- §6의 「47점 미정」은 원인 판정이 아니다.

## 국내 — 「대상이 아니다」는 현 편입에서 반증 못 함
- 운영 SELECT: KOSPI 2 · KOSDAQ 3만 연속 분기가 안 선다. 원인은 이렇다.
  - 490470 · 491000: 2025 Q3 행 없음
  - 950260: Q2'26 하나
  - 001720: A29 결산월
  - 088980: 분기 행 없음
- 6-K형 누계 라벨로 닫히는 꼴이 아니다.
- **재지 않음**: DART 누계(`thstrm_add_amount`)로 490470 · 491000의 Q3가 지어지는지.

## 재지 않은 것(종합)
- 1번의 칸×일 수와 과거 편입 · 후보 범위.
- 3번의 전체 건수.
- 5번 ADS 숨김의 실제 발생(운영에 표가 없다).
- 6번 칸 수.
- 8번 TSEM 공시 목록.
- 9번의 「첫해 대 누락」 판정.
