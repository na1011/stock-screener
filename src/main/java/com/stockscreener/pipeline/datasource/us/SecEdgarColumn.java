package com.stockscreener.pipeline.datasource.us;

import java.util.List;

/**
 * {@link com.stockscreener.pipeline.datasource.RawFinancialReport}의 금액 컬럼 하나가
 * EDGAR의 어느 태그·어느 단위에서 오는가.
 *
 * <p><b>후보를 여럿 두는 이유는 회사마다, 그리고 같은 회사도 연도마다 태그가 다르기 때문</b>이다
 * (개발가이드 §2.5가 「이 프로젝트 최대의 미지수」로 적은 자리). 표준 태그 하나만 보면 결측이
 * 대량으로 난다. 순서가 우선순위이고, <b>앞의 것이 이긴다.</b>
 *
 * <p><b>후보는 표본 19종목으로 채점했다</b>({@code sec-edgar-financial.md}의 「컬럼별 커버리지」).
 * 커버리지가 19/19가 아닌 컬럼이 있는데 <b>그것이 결손이라는 뜻은 아니다</b> — 은행·REIT는
 * 재무상태표를 유동/비유동으로 가르지 않아 {@code AssetsCurrent}가 원리적으로 없고, 재고가
 * 없는 업종도 마찬가지다(국내 금융업과 같은 성질).
 *
 * <p><b>MUST: 후보를 더하거나 순서를 바꾸는 것 자체가 정정을 만든다.</b> 예전엔 null이던 뒤
 * 보고서의 값이 값으로 바뀌면 {@code SecEdgarFacts}의 접기가 「달라졌다」로 읽고, <b>소스는
 * 그대로인데 우리 코드 변경으로</b> {@code FINANCIAL_REVISED}가 발화해 그 종목의 지표가
 * 보존 창 전체에서 지워진다. 바꿀 때는 그것이 의도인지 먼저 답한다.
 *
 * <p><b>단위를 컬럼이 고르는 이유</b>는 {@code units} 키가 통화가 아니기 때문이다 — 실측에서
 * 3자를 넘는 키가 44종이었다({@code pure}·{@code shares}·{@code segment}…). 금액 컬럼이
 * {@code shares}를 받아들이면 주식 수가 매출로 저장되고 값이 그럴듯하다.
 */
enum SecEdgarColumn {

    /**
     * 매출. <b>이 컬럼만 후보 중 첫값이 아니라 가장 큰 값을 고른다.</b>
     *
     * <p><b>왜</b>: {@code RevenueFromContractWithCustomer…}는 ASC 606 계약 매출이라
     * <b>총매출의 부분집합</b>이다. 리스(ASC 842)·상품파생·보험료가 큰 회사에서는 그 태그가
     * 매출의 일부만 담는데, 첫값 규칙이 그것을 총매출 자리에 앉혔다. 실측(US_TOP1000 최신 FY
     * 970종목 전수): <b>92종목이 어긋났고 92 전부가 과소계상</b>이며 과대계상은 0이었다 —
     * MET 2,436M→77,084M · CHTR 889M→54,774M · ADM 24,956M→80,269M. 보험사와 은행이
     * 가장 크게 걸린다.
     *
     * <p><b>MUST NOT: {@code …IncludingAssessedTax}를 최댓값 경쟁에 넣지 않는다.</b> 그것은
     * 정의상 {@code …Excluding…}보다 크므로 둘 다 내는 회사에서 <b>언제나 이겨</b> 주세·판매세가
     * 매출로 들어온다. 실측에서 브라운포먼(BF.A)이 순매출 3,928M 대신 총매출 5,082M을 받았다.
     * 그래서 <b>마지막 수단</b>으로만 둔다 — 빼버리면 안 되는 이유는 그 태그가 <b>유일한</b>
     * 매출 출처인 종목이 42개(AKAM·BALL·CPRT·CRWD…)이기 때문이다.
     */
    REVENUE(Kind.DURATION, Unit.MONETARY, Selection.LARGEST,
            List.of("Revenues",
                    "RevenueFromContractWithCustomerExcludingAssessedTax",
                    "SalesRevenueNet", "SalesRevenueGoodsNet"),
            List.of("RevenueFromContractWithCustomerIncludingAssessedTax")),
    OPERATING_INCOME(Kind.DURATION, Unit.MONETARY, List.of("OperatingIncomeLoss")),
    /**
     * 순이익. <b>후보 셋의 뜻이 서로 다르다</b> — {@code NetIncomeLoss}는 지배주주 귀속,
     * {@code ProfitLoss}는 비지배 포함, {@code …AvailableToCommonStockholdersBasic}은
     * 우선주 배당까지 뺀 값이다. 그래서 이 컬럼은 <b>마지막 수단</b>이고, 실제로 저장되는
     * 값은 {@code SecEdgarFacts.controllingIncome}이 고른다.
     *
     * <p><b>MUST NOT: 후보를 줄여 해결하지 않는다.</b> 줄이면 그 태그만 내는 종목의 순이익이
     * 통째로 빈다 — ADR-0126 §결정이 <b>입력 값을 버리는 것을 금지</b>한다. 갈라 담고
     * 유도하는 것이 요점이다.
     */
    NET_INCOME(Kind.DURATION, Unit.MONETARY, List.of(
            "NetIncomeLoss", "ProfitLoss", "NetIncomeLossAvailableToCommonStockholdersBasic")),

    /**
     * <b>지배주주 귀속 순이익.</b> {@link #NET_INCOME}의 첫 후보만 따로 든다 — 그 태그가
     * 이겼는지를 값만 보고는 알 수 없기 때문이다.
     */
    NET_INCOME_CONTROLLING(Kind.DURATION, Unit.MONETARY, List.of("NetIncomeLoss")),

    /**
     * <b>비지배 포함 순이익.</b> {@link #NET_INCOME_CONTROLLING}이 없을 때 유도의 피감수다.
     *
     * <p>이것을 따로 드는 이유는 <b>셋째 후보에서 빼면 안 되기 때문</b>이다 —
     * {@code …AvailableToCommonStockholdersBasic}은 이미 지배주주 기준이라, 거기서
     * 비지배지분을 또 빼면 두 번 빠진다.
     */
    NET_INCOME_TOTAL(Kind.DURATION, Unit.MONETARY, List.of("ProfitLoss")),
    /**
     * <b>보통주 귀속 순이익</b>({@code 지배주주 − 우선주배당}). <b>증인으로만 쓴다</b> —
     * 이 값이 {@code NET_INCOME_TOTAL − 비지배}와 <b>정확히 같으면</b> 우선주배당이 0이라는 뜻이고,
     * 그때 {@code NET_INCOME_CONTROLLING}이 그 값과 다르면 <b>그 태그가 틀린 것</b>이다.
     * {@code ESI} 2019 Q2가 그 자리다 — 원장이 1.4M − (−0.1M) = 1.5M을 두 경로로 확증하는데
     * 직접 태그가 <b>14.7M</b>이다.
     */
    NET_INCOME_AVAILABLE_TO_COMMON(Kind.DURATION, Unit.MONETARY,
            List.of("NetIncomeLossAvailableToCommonStockholdersBasic")),

    /**
     * <b>비지배 귀속 순이익.</b> {@link #NET_INCOME_CONTROLLING}을 <b>유도하는 재료</b>이지
     * 저장되는 컬럼이 아니다 — 지배주주 순이익 = 비지배 포함 순이익 − 비지배 귀속분.
     *
     * <p><b>왜 필요한가</b>: ADR-0153이 {@code ROE}의 분모를 지배주주 자본으로 옮겼는데
     * 분자를 그대로 두면 <b>분자만 비지배 포함</b>이 되어 전보다 더 어긋난다. 실측
     * (2026-09-15 · 표본 47): {@code NetIncomeLoss}가 없어 {@code ProfitLoss}로 떨어지는
     * 종목이 <b>13</b>이고 그중 <b>8</b>이 한 기간이라도 유도된다 — {@code CAT} 11/11 ·
     * {@code WEC} 9/9 · {@code F} 6/6 · {@code EL} 4/6 · {@code TEL} 3/3 · {@code AVAV} 2/7 ·
     * {@code AVGO} 2/4 · {@code MPLX} 2/2 기간.
     *
     * <p><b>레시피가 옳은지는 다른 표본으로 잰다</b> — 유도가 발화하는 칸은 답을 모르므로
     * ({@code NetIncomeLoss}가 없어서 유도한다) <b>답을 아는 379칸</b>에 같은 산수를 태웠다:
     * 유도가 <b>314칸에서 더 가깝고 19칸에서 멀어지며</b> 절대오차 총합이 <b>90.8% 준다</b>
     * (100,352M → 9,234M). 항등식 자체는 <b>342/379(90.2%)</b>가 0.1% 이내이고, 이탈은
     * {@code KKR}·{@code MPLX}·{@code FSV}·{@code GM}·{@code LNG} 같은 복합 지배구조에 몰린다.
     */
    NET_INCOME_NONCONTROLLING(Kind.DURATION, Unit.MONETARY, List.of(
            "NetIncomeLossAttributableToNoncontrollingInterest",
            "ProfitLossAttributableToNoncontrollingInterest")),

    /**
     * <b>상환가능 비지배 귀속 순이익.</b> {@link #NET_INCOME_NONCONTROLLING}의 <b>구성요소</b>라
     * 그 태그가 없을 때만, 그리고 {@link #NET_INCOME_NONCONTROLLING_NONREDEEMABLE}과 <b>합해서</b>
     * 쓴다. 합계 태그와 함께 더하면 두 번 센다.
     *
     * <p><b>표본 47에서 이 갈래가 답이 되는 칸은 0이다.</b> {@code FSV}를 보고 넣었는데
     * 재보니 그 종목은 <b>두 태그가 같은 기간에 함께 오지 않아</b> 이 갈래로도 안 닫힌다.
     * 그래도 남기는 이유는 둘뿐이다 — <b>정의상 옳은 분해</b>이고(검증 가능한 4칸에서 4/4),
     * <b>표본이 1,000 중 47이라 나머지에서 안 뜬다고 못 말한다.</b> 전수로는 안 쟀다.
     */
    NET_INCOME_NONCONTROLLING_REDEEMABLE(Kind.DURATION, Unit.MONETARY, List.of(
            "NetIncomeLossAttributableToRedeemableNoncontrollingInterest")),

    /** {@link #NET_INCOME_NONCONTROLLING_REDEEMABLE}의 짝. 둘은 언제나 함께 쓴다. */
    NET_INCOME_NONCONTROLLING_NONREDEEMABLE(Kind.DURATION, Unit.MONETARY, List.of(
            "NetIncomeLossAttributableToNonredeemableNoncontrollingInterest")),
    /**
     * 매출원가. <b>{@link #REVENUE}와 같은 이유로 첫값이 아니라 가장 큰 값을 고른다.</b>
     *
     * <p><b>왜</b>: 같은 회사가 후보 둘을 함께 내면서 <b>앞의 것에 훨씬 작은 뜻</b>을 담는
     * 일이 있다. {@code CAT}은 {@code CostOfGoodsAndServicesSold}를 <b>8팩트</b>만 내고
     * (최신 49M) 진짜 총원가는 {@code CostOfRevenue} <b>128팩트</b>(24.1B)인데, 첫값 규칙이
     * 앞의 것을 집어 매출원가가 FY2023 <b>42,767M → FY2024 33M</b>이 됐다(세 해 다
     * {@code revision_no=0} — 정정이 아니라 <b>연도별로 이긴 태그가 갈린 것</b>이다).
     *
     * <p><b>실측(2026-09-14 · {@code US_TOP1000} 최신 FY).</b> 소스가 매출·매출원가·매출총이익을
     * 셋 다 주는 3,909행에서 항등식 {@code 매출 − 매출원가 = 매출총이익}이 <b>3,675(94.0%)에서
     * 1% 이내</b>로 성립하고 <b>155행·69종목</b>이 10%를 넘게 어긋난다. 방향을 가르면
     * <b>원가 과소 133행 · 과대 22행(16종목)</b>이다.
     *
     * <p><b>{@code LARGEST}가 악화시키는 자리를 전수로 물었다.</b> 과대 16종목 중 후보가 둘 이상
     * 오는 것은 <b>6</b>이고, 그중 다섯({@code ATI}·{@code MELI}·{@code MSFT}·{@code NTNX}·
     * {@code APTV})은 <b>지금 고르는 것이 이미 최댓값이거나 기간이 겹치지 않아</b> 선택이 안
     * 바뀐다. 실제로 커지는 것은 {@code ENS} 하나인데 그 종목의 어긋남은 <b>매출 쪽</b>이다
     * (FY2019 매출 187M에 원가 2,105M — {@code REVENUE}가 이미 {@code LARGEST}인데도 그렇다).
     *
     * <p><b>MUST NOT: 이것을 「값을 버리는 게이트」로 읽지 않는다.</b> ADR-0126 §결정이
     * <b>입력 값을 자동으로 버리는 게이트를 금지</b>하고 ADR-0125가 그런 축 다섯을 실측으로
     * 기각했다. 이것은 <b>이미 존재하는 후보들 중 하나를 고르는 규칙</b>이고, ADR-0089 §규칙이
     * <i>"컬럼마다 후보 선택 전략을 정한다"</i>를 이미 요구한다.
     *
     * <p><b>후보가 하나뿐인 자리는 이것으로 안 고쳐진다.</b> {@code CNC}는 후보 넷 중
     * {@code CostOfGoodsAndServicesSold} 하나만 오고 그 값이 매출의 <b>1.4%</b>(194,777M 대
     * 2,670M)다. 그런 자리는 손으로 고치지 않는다(2026-10-25 사용자 기준 · ADR-0193) — 원장이 틀린 칸의 처방은 일반 규칙으로 고르거나
     * 판정이 숨기는 것이고, 원가 단일 후보의 그 판정은 아직 없다.
     */
    COST_OF_SALES(Kind.DURATION, Unit.MONETARY, Selection.LARGEST, List.of(
            "CostOfGoodsAndServicesSold", "CostOfRevenue", "CostOfGoodsSold", "CostOfSales"),
            List.of()),

    /**
     * 영업활동현금흐름. <b>연간만 담는다.</b>
     *
     * <p>분기 팩트가 있기는 하지만 커버리지가 손익보다 훨씬 낮다(실측 208 대 28). 담으면
     * <b>한 행에 3개월 매출과 9개월 현금흐름</b>이 앉을 수 있고, 그 행을 쓰는 쪽은 기간이
     * 다르다는 것을 모른다. {@code DartFullStatementAdapter}가 같은 이유로 같은 형태다.
     */
    OPERATING_CASH_FLOW(Kind.DURATION, Unit.MONETARY, true, List.of(
            "NetCashProvidedByUsedInOperatingActivities",
            "NetCashProvidedByUsedInOperatingActivitiesContinuingOperations")),

    /**
     * <b>총 영업활동현금흐름.</b> {@link #OPERATING_CASH_FLOW}의 첫 후보만 따로 든다 — 그
     * 태그가 이겼는지를 값만 보고는 알 수 없기 때문이다({@code NET_INCOME_TOTAL}과 같은 형태).
     */
    OPERATING_CASH_FLOW_TOTAL(Kind.DURATION, Unit.MONETARY, true, List.of(
            "NetCashProvidedByUsedInOperatingActivities")),

    /** 계속영업분. 유도의 피가산수다. */
    OPERATING_CASH_FLOW_CONTINUING(Kind.DURATION, Unit.MONETARY, true, List.of(
            "NetCashProvidedByUsedInOperatingActivitiesContinuingOperations")),

    /**
     * <b>중단영업분.</b> {@link #OPERATING_CASH_FLOW}를 <b>유도하는 재료</b>다 —
     * 총액 = 계속영업 + 중단영업.
     *
     * <p><b>실측(2026-09-15 · 표본 47)</b>: 답을 아는 82칸에서 항등식이 <b>81(98.8%)</b>
     * 성립하고, 폴백과 견주면 <b>67칸이 가까워지고 1칸이 멀어지며 절대오차 총합이 94.2%
     * 준다</b>(50,530M → 2,928M).
     *
     * <p><b>MUST: 「…ContinuingOperations」가 실제로 제외하는지는 회사마다 다르다.</b>
     * 두 태그 값이 <b>이미 같은 칸이 18.3%</b>이고 거기에 더하면 없던 오차가 생긴다
     * ({@code AIG} 2012가 그 자리다). 그래도 총오차는 위와 같이 크게 준다.
     */
    OPERATING_CASH_FLOW_DISCONTINUED(Kind.DURATION, Unit.MONETARY, true, List.of(
            "CashProvidedByUsedInOperatingActivitiesDiscontinuedOperations")),

    /**
     * 자본적지출. <b>연간만 담는다 — 현금흐름과 같은 창이어야 한다.</b>
     *
     * <p>잉여현금흐름이 둘의 차이인데 한쪽만 분기에 값이 있으면 <b>기간이 다른 두 수를 뺀다.</b>
     * 유출을 양수 크기로 담는 것이 포트의 계약이고(ADR-0067), EDGAR의 {@code PaymentsTo…}는
     * <b>실측에서 2,276건 전부 양수</b>였다(표본 19종목) — 부호를 뒤집어 담으면 잉여현금흐름이
     * 두 배로 부풀고 값이 그럴듯해 드러나지 않는다.
     */
    CAPEX(Kind.DURATION, Unit.MONETARY, true, List.of(
            "PaymentsToAcquirePropertyPlantAndEquipment", "PaymentsToAcquireProductiveAssets")),

    TOTAL_ASSETS(Kind.INSTANT, Unit.MONETARY, List.of("Assets")),
    TOTAL_LIABILITIES(Kind.INSTANT, Unit.MONETARY, List.of("Liabilities")),

    /**
     * <b>중간지분(메자닌).</b> {@link #TOTAL_LIABILITIES}를 <b>유도하는 재료</b>이지 저장되는
     * 컬럼이 아니다 — 재무상태표는 {@code 자산 = 부채 + 중간지분 + 자본}이고, 상환가능
     * 비지배지분·임시자본이 부채와 영구자본 <b>사이</b>에 앉는다.
     *
     * <p><b>빼지 않으면 부채가 그만큼 부풀어 항등식이 안 선다.</b> 실측(2026-09-15 · 표본 47 ·
     * 셋이 다 오는 1,845칸): {@code 자산 − 총자본}만 쓰면 <b>87.0%</b>가 0.1% 이내인데
     * 중간지분을 빼면 <b>93.7%</b>가 된다.
     *
     * <p><b>없으면 0으로 본다</b> — 위 수가 이미 그 가정 위에서 잰 것이다.
     *
     * <p><b>후보 순서는 표본에서 결과를 안 바꾼다</b>(넓은 {@code TemporaryEquity…}를 먼저
     * 둬도 93.7%로 같다) — 네 태그가 <b>같은 기간에 함께 오지 않기 때문</b>이다. 그래도
     * 좁은 것을 먼저 두는 이유는 그것이 <b>더 자주 오는 쪽</b>이라서다.
     */
    MEZZANINE_EQUITY(Kind.INSTANT, Unit.MONETARY, List.of(
            "RedeemableNoncontrollingInterestEquityCarryingAmount",
            "RedeemableNoncontrollingInterestEquityFairValue",
            "TemporaryEquityCarryingAmountAttributableToParent",
            "TemporaryEquityCarryingAmountIncludingPortionAttributableToNoncontrollingInterest")),
    /**
     * 자본총계. <b>비지배지분을 포함하는 쪽을 먼저 본다.</b>
     *
     * <p>국내가 {@code 자본총계}(K-IFRS 연결에서 비지배지분 포함)를 쓰므로 여기서도 같은 것을
     * 골라야 <b>시장 간 계산 방식이 갈리지 않는다</b>(spec §7). 순서를 뒤집으면 비지배지분이
     * 큰 회사에서 <b>자산 = 부채 + 자본이 깨진다</b> — 실측에서 BRK·F의 151행 중 50행이
     * 어긋났고, 차이가 정확히 비지배지분이었다. 값이 그럴듯해 눈으로는 안 드러난다.
     */
    TOTAL_EQUITY(Kind.INSTANT, Unit.MONETARY, List.of(
            "StockholdersEquityIncludingPortionAttributableToNoncontrollingInterest",
            "StockholdersEquity")),

    /**
     * <b>지배주주 자본.</b> {@link #TOTAL_EQUITY}는 첫 후보가
     * {@code …IncludingPortionAttributableToNoncontrollingInterest}라 <b>비지배지분을 포함</b>하는데,
     * {@link #NET_INCOME}의 첫 후보 {@code NetIncomeLoss}는 <b>지배주주 귀속</b>이다. 그래서
     * {@code ROE}가 <b>분자와 분모의 기저가 다른 채로</b> 나간다 — 그것이 이 컬럼이 생긴 이유다.
     *
     * <p>실측(2026-09-15 · 표본 8): 대부분은 오차가 0이지만 지배구조가 있는 회사에서 거대하다 —
     * <b>{@code LNG} 비지배 46.1%로 ROE 59.13% → 109.74%(+50.61%p)</b>,
     * <b>{@code KKR} 비지배 59.9%로 3.06% → 7.63%(+4.57%p)</b>, {@code KMI} +0.37%p,
     * {@code GM} +0.12%p, {@code AIG}·{@code JNJ}·{@code F}·{@code BRK-B}는 0.
     * <b>{@code ROE}는 {@code 버핏 우량주}의 필수 게이트다.</b>
     *
     * <p><b>MUST NOT: {@code TOTAL_EQUITY}의 후보 순서를 뒤집어 해결하지 않는다.</b> 그러면
     * {@code PBR}·{@code DEBT_RATIO}·{@code ALTMAN_Z}의 분모가 함께 바뀐다 — 그쪽은
     * <b>총자본이 옳다</b>(총자산·총부채와 같은 기저라야 항등식이 선다). 갈라 담는 것이 요점이다.
     */
    TOTAL_EQUITY_CONTROLLING(Kind.INSTANT, Unit.MONETARY, List.of("StockholdersEquity")),

    /**
     * <b>비지배 포함 자본.</b> {@link #TOTAL_EQUITY}의 첫 후보만 따로 든다 — 그 태그가
     * 이겼는지를 값만 보고는 알 수 없기 때문이다({@link #NET_INCOME_TOTAL}과 같은 형태).
     *
     * <p><b>왜 필요한가</b>: 소스가 그 태그를 안 내면 {@code TOTAL_EQUITY}가
     * {@code StockholdersEquity}(지배주주분)로 떨어지는데, 그 값은 {@code TOTAL_ASSETS}·
     * {@code TOTAL_LIABILITIES}와 <b>기저가 달라 {@code 자산 = 부채 + 자본}이 깨진다.</b>
     * <p><b>두 수를 갈라 읽는다.</b> <i>얼마나 자주 발화하는가</i>와 <i>발화했을 때 얼마나
     * 옳은가</i>는 다른 질문이고, 표본이 다르다(실측 2026-09-15 · 표본 47).
     * <ul>
     *   <li><b>발화</b>: 2순위로 떨어지는 종목이 <b>17</b>인데 그중 비지배지분 태그까지 있어
     *       실제로 유도되는 것은 <b>2</b>다. 나머지 15는 <b>유도할 재료가 없어 그대로</b>다.</li>
     *   <li><b>정확도</b>: 셋이 다 오는 1,042칸에서 항등식이 <b>997(95.7%)</b> 성립하고,
     *       폴백과 견주면 <b>990칸이 가까워지고 19칸이 멀어지며 절대오차 총합이 96.4% 준다.</b>
     *       <b>그 1,042칸은 유도가 발화하는 칸이 아니다</b> — 레시피가 옳은지를 재는 대조군이다.</li>
     * </ul>
     *
     * <p><b>MUST NOT: 상환가능 비지배지분을 더하지 않는다.</b> 더하면 항등식이
     * <b>85.5%로 나빠진다</b> — {@code …IncludingPortionAttributableToNoncontrollingInterest}는
     * 정의상 영구자본 안의 비지배지분만 포함한다.
     */
    TOTAL_EQUITY_INCLUDING_NCI(Kind.INSTANT, Unit.MONETARY, List.of(
            "StockholdersEquityIncludingPortionAttributableToNoncontrollingInterest")),

    /**
     * <b>비지배지분.</b> {@link #TOTAL_EQUITY_CONTROLLING}을 <b>유도하는 재료</b>이지 저장되는
     * 컬럼이 아니다 — 지배주주 자본 = 총자본 − 비지배지분.
     *
     * <p>이것이 필요한 이유는 <b>비지배지분이 0에 가까운 회사가 두 자본 태그 중 하나만 내기
     * 때문</b>이다. 실측(2026-09-15): {@code CAT}·{@code ADM}은 {@code StockholdersEquity}를
     * 아예 안 내고 {@code MinorityInterest}만 내며, {@code AVGO}(2019-11-03 이후)·
     * {@code CPRT}(2023-07-31 이후)·{@code CAG}(2025-05-25 이후)는 <b>어느 해부터 그만 냈다.</b>
     * <b>그래서 그 62종목은 원장의 한계가 아니라 우리가 유도를 안 한 자리였다.</b>
     */
    MINORITY_INTEREST(Kind.INSTANT, Unit.MONETARY, List.of("MinorityInterest")),

    /**
     * <b>LP · LLC 자본(관문 뒤 재료 · ADR-0188).</b> 법인 {@code StockholdersEquity…} 대신 파트너십 · LLC 태그로 자본을 싣는 발행사가
     * 있다(EPD · ET · PAA · CQP · MPLX · WES). 사다리 후보로 넣지 않고 재료로 따로 받는 이유는 둘이다 — 후보는 판 증거
     * ({@code reportEvidence})라 자본변동표만 실은 얕은 보고서가 판을 새로 내고(첫 시안: 새 판 77 · 사라짐 47), {@code PartnersCapital}의
     * 뜻이 발행사마다 갈린다(비지배를 포함해 실은 CG 2016~2019). 어느 자리에 실을지는 {@code SecEdgarFacts.gatedEquity}가
     * 같은 공시의 합(지배 + 비지배 = 총)으로 확인해 정한다.
     */
    PARTNERS_EQUITY_INCLUDING_NCI(Kind.INSTANT, Unit.MONETARY, List.of(
            "PartnersCapitalIncludingPortionAttributableToNoncontrollingInterest",
            "MembersEquityIncludingPortionAttributableToNoncontrollingInterest",
            "LimitedLiabilityCompanyLlcMembersEquityIncludingPortionAttributableToNoncontrollingInterest")),
    PARTNERS_EQUITY(Kind.INSTANT, Unit.MONETARY, List.of("PartnersCapital", "MembersEquity")),
    /**
     * <b>포괄손익의 비지배 몫(관문 뒤 재료 · ADR-0188).</b> 「이 기간에 비지배가 있는가」의 손익 쪽 증거에 더한다 — TKO는 2026년
     * 비지배(49억 달러)를 손익 태그 없이 이 태그로만 낸다.
     */
    COMPREHENSIVE_INCOME_NONCONTROLLING(Kind.DURATION, Unit.MONETARY, List.of(
            "ComprehensiveIncomeNetOfTaxAttributableToNoncontrollingInterest")),
    PARTNERS_EQUITY_NONCONTROLLING(Kind.INSTANT, Unit.MONETARY, List.of(
            "PartnersCapitalAttributableToNoncontrollingInterest",
            "MembersEquityAttributableToNoncontrollingInterest")),

    CURRENT_ASSETS(Kind.INSTANT, Unit.MONETARY, List.of("AssetsCurrent")),
    CURRENT_LIABILITIES(Kind.INSTANT, Unit.MONETARY, List.of("LiabilitiesCurrent")),
    RETAINED_EARNINGS(Kind.INSTANT, Unit.MONETARY, List.of("RetainedEarningsAccumulatedDeficit")),
    CASH_AND_EQUIVALENTS(Kind.INSTANT, Unit.MONETARY, List.of(
            "CashAndCashEquivalentsAtCarryingValue",
            "CashCashEquivalentsRestrictedCashAndRestrictedCashEquivalents")),

    /** 재고자산. <b>저량이라 분기 행에도 담는다</b> — 유량인 {@code CAPEX}와 다른 자리다. */
    INVENTORIES(Kind.INSTANT, Unit.MONETARY, List.of("InventoryNet")),

    /**
     * 상장주식수. <b>종료일을 묻지 않고 같은 보고서에서 받는다.</b>
     *
     * <p>{@code dei:EntityCommonStockSharesOutstanding}은 표지 항목이라 종료일이 기말이 아니라
     * <b>제출 직전 날짜</b>다. 다른 잔액처럼 종료일로 거르면 전부 탈락하고, 그러면
     * {@code FinancialMath.sharesOf}의 재무 폴백이 미국에서 영영 비어 <b>PER·PBR이 하나도
     * 안 나온다</b> — 미국은 시세 쪽 {@code listed_shares}도 비어 있기 때문이다(ADR-0013).
     */
    SHARES_OUTSTANDING(Kind.ANCHORED_INSTANT, Unit.SHARES, List.of(
            "dei:EntityCommonStockSharesOutstanding",
            "CommonStockSharesOutstanding",
            "WeightedAverageNumberOfDilutedSharesOutstanding",
            "WeightedAverageNumberOfSharesOutstandingBasic")),

    /**
     * 영업이익 유도의 재료 여덟. <b>그 자체가 지표는 아니다</b> — 유도는 지표 계층
     * ({@code FinancialMath.operatingIncomeOf})이 한다.
     *
     * <p><b>왜 있는가</b>: {@code OperatingIncomeLoss}가 미국에서 대량으로 비는데 그것이
     * 「없는 것」이 아니라 <b>US-GAAP의 표시 관행</b>이다. K-IFRS는 은행도 영업이익을 표시해
     * 국내 금융업 50종목이 FY 영업이익을 <b>100%</b> 갖는 반면, 미국은 금융업 170 중
     * <b>119(70%)</b>가 판정불가였다 — <b>같은 이름의 조건이 시장마다 다른 것을 쟀다.</b>
     * 막힌 119종목 전량에 물으니 <b>83(69.7%)</b>이 아래 재료를 갖는다.
     *
     * <p><b>MUST NOT: 비었다고 결손으로 읽지 않는다.</b> 업종마다 내는 것이 다르고,
     * 셋 다 비는 것이 정상인 종목은 {@code OperatingIncomeLoss}를 직접 내는 종목이다.
     */
    INTEREST_AND_DIVIDEND_INCOME(Kind.DURATION, Unit.MONETARY, List.of(
            "InterestAndDividendIncomeOperating")),
    NONINTEREST_INCOME(Kind.DURATION, Unit.MONETARY, List.of("NoninterestIncome")),
    /**
     * 이자비용. <b>{@code InterestExpense}는 2024년 US-GAAP 택소노미에서 사실상 퇴역했다</b> —
     * 그 자리를 {@code InterestExpenseOperating}이 받았다.
     *
     * <p><b>후보가 하나뿐이라 조용히 무너져 있었다.</b> 실측(2026-09-15 · {@code US_TOP1000}
     * 최신 판): 은행형 행의 이자비용 보유가 <b>FY2023 60/66 → FY2024 8/65 → FY2025 8/69</b>로
     * 떨어졌고, 그래서 <b>영업이익 유도 재료 셋을 다 갖는 행이 44 → 5</b>가 됐다
     * (ADR-0104의 은행형 갈래가 그만큼 죽어 있었다).
     *
     * <p><b>둘의 값은 같다</b> — 둘 다 오는 110칸에서 <b>109(99.1%)</b>가 1% 이내다.
     * 후보만 더하면 재료 셋을 다 갖는 종목이 <b>5 → 46</b>이 된다.
     */
    INTEREST_EXPENSE(Kind.DURATION, Unit.MONETARY, List.of(
            "InterestExpense", "InterestExpenseOperating")),

    /**
     * <b>순이자손익.</b> 은행형 매출을 <b>유도하는 재료</b>이지 저장되는 컬럼이 아니다 —
     * 은행의 총수익 = 순이자손익 + 비이자수익.
     *
     * <p><b>왜 필요한가</b>: 은행은 {@code Revenues}를 안 내거나 <b>좁은 하위 항목에 쓴다.</b>
     * 실측(2026-09-15 · {@code US_TOP1000} 최신 FY): 은행형 69종목 중 <b>매출 NULL 27 ·
     * 비이자수익보다도 작은 명백한 과소 11</b>({@code WAL} 136M인데 실제 3,543M — <b>26.1배</b> ·
     * {@code AX} 18.2배 · {@code COLB} 13.0배 · {@code ZION} 5.1배).
     *
     * <p><b>이 공식은 우리가 정한 정의가 아니다</b> — 소스가 이미 그렇게 쓴다. 제대로 신고하는
     * 은행 15 중 <b>14가 저장/공식 = 1.00으로 정확히 일치</b>하고({@code BAC}·{@code C}·
     * {@code JPM}·{@code USB}·{@code PNC}·{@code COF}…), 회사가 스스로 붙인
     * {@code RevenuesNetOfInterestExpense} 태그와도 <b>95.8%가 일치</b>한다.
     */
    NET_INTEREST_INCOME(Kind.DURATION, Unit.MONETARY, List.of("InterestIncomeExpenseNet")),
    NONINTEREST_EXPENSE(Kind.DURATION, Unit.MONETARY, List.of("NoninterestExpense")),
    BENEFITS_LOSSES_AND_EXPENSES(Kind.DURATION, Unit.MONETARY, List.of(
            "BenefitsLossesAndExpenses")),
    GROSS_PROFIT(Kind.DURATION, Unit.MONETARY, List.of("GrossProfit")),
    OPERATING_EXPENSES(Kind.DURATION, Unit.MONETARY, List.of("OperatingExpenses")),
    COSTS_AND_EXPENSES(Kind.DURATION, Unit.MONETARY, List.of("CostsAndExpenses")),

    /** 주당순이익. <b>대조용이다</b> — 지표는 언제나 자체 계산이다(spec §7). */
    EPS(Kind.DURATION, Unit.PER_SHARE, List.of("EarningsPerShareBasic", "EarningsPerShareDiluted")),

    /**
     * 법인세비용. 세후 투하자본이익률의 유효세율에서 <b>분자</b>다(ADR-0144).
     *
     * <p><b>연간만 담는다</b> — 세후 지표가 직전 회계연도 세율을 쓰므로 분기 값이 필요 없고,
     * 국내와 기저를 같게 두려면 창이 같아야 한다(ADR-0143).
     *
     * <p><b>MUST: {@link #reportEvidence}에서 빠진다.</b> 이 컬럼이 reporter 자격을 주면
     * <b>세금만 든 얕은 보고서가 {@code filed} 앞에 서서</b> baseline을 세우고, 뒤따르는 진짜
     * 10-K가 {@code conflicts} 거짓으로 버려진다 — 그 기간 행에 <b>세금만 남고 매출·영업이익·
     * 자산이 전부 NULL</b>이 된다. 값은 {@code valuesOf}가 따로 뽑으므로 <b>제외해도 안 잃는다.</b>
     */
    INCOME_TAX_EXPENSE(Kind.DURATION, Unit.MONETARY, true, List.of("IncomeTaxExpenseBenefit")),

    /**
     * 세전이익. 유효세율의 <b>분모</b>이고 <b>계속영업 + 비지배 포함</b>이다.
     *
     * <p>국내({@code ifrs-full_ProfitLossBeforeTax} — 계속영업)와 <b>기저가 완전히 같지 않다</b> —
     * 미국 태그는 비지배지분을 포함한다. 그 차이를 {@code description}이 말하게 둔다.
     *
     * <p>후보 둘은 <b>배타적이 아니다</b> — 표본 10종목에서 첫째가 10/10, 둘째가 5/10이고
     * 둘 다 가진 회사가 있다. 첫값 규칙이므로 순서가 뜻을 정한다.
     *
     * <p>{@link #INCOME_TAX_EXPENSE}와 같은 이유로 {@link #reportEvidence}에서 빠진다.
     */
    PROFIT_BEFORE_TAX(Kind.DURATION, Unit.MONETARY, true, List.of(
            "IncomeLossFromContinuingOperationsBeforeIncomeTaxesExtraordinaryItemsNoncontrollingInterest",
            "IncomeLossFromContinuingOperationsBeforeIncomeTaxesMinorityInterestAndIncomeLossFromEquityMethodInvestments")),

    /** 지분법을 포함한 세전이익. {@link #PROFIT_BEFORE_TAX}의 첫 후보만 따로 든다. */
    PROFIT_BEFORE_TAX_INCLUDING_EQUITY(Kind.DURATION, Unit.MONETARY, true, List.of(
            "IncomeLossFromContinuingOperationsBeforeIncomeTaxesExtraordinaryItemsNoncontrollingInterest")),

    /** 지분법을 제외한 세전이익. 유도의 피가산수다. */
    PROFIT_BEFORE_TAX_EXCLUDING_EQUITY(Kind.DURATION, Unit.MONETARY, true, List.of(
            "IncomeLossFromContinuingOperationsBeforeIncomeTaxesMinorityInterestAndIncomeLossFromEquityMethodInvestments")),

    /**
     * <b>지분법손익.</b> {@link #PROFIT_BEFORE_TAX}를 <b>유도하는 재료</b>다 —
     * 포함 = 제외 + 지분법손익.
     *
     * <p><b>실측(2026-09-15 · 표본 47)</b>: 답을 아는 75칸에서 항등식이 <b>62(82.7%)</b>
     * 성립하고, 폴백과 견주면 <b>60칸이 가까워지고 13칸이 멀어지며 절대오차 총합이 78.0%
     * 준다</b>(48,981M → 10,773M).
     *
     * <p><b>악화 13 중 11이 「피감수 = 정답」인 칸</b>이다 — 그 회사들은 <i>"지분법 제외"</i>
     * 태그에 <b>이미 지분법을 포함</b>해 낸다({@code F}·{@code GM}·{@code CACI}·{@code PNR}·
     * {@code WEC} · 검증 칸의 <b>14.7%</b>). <b>이름이 계약이 아니다.</b>
     */
    EQUITY_METHOD_INCOME(Kind.DURATION, Unit.MONETARY, true, List.of(
            "IncomeLossFromEquityMethodInvestments")),

    /**
     * <b>계속영업손익(비지배 포함).</b> 세전이익 유도의 피가산수 — 세전이익 = 계속영업손익 + 법인세.
     *
     * <p>실측(2026-09-15 · 표본 407): 셋이 다 오는 151칸에서 항등식이 <b>147(97.4%)</b> 성립한다.
     */
    INCOME_FROM_CONTINUING_OPERATIONS(Kind.DURATION, Unit.MONETARY, true, List.of(
            "IncomeLossFromContinuingOperationsIncludingPortionAttributableToNoncontrollingInterest")),

    /**
     * <b>중단영업손익.</b> 값을 쓰려는 것이 아니라 <b>그 회사에 중단영업이 있는지</b>를 묻는
     * 재료다 — {@code SecEdgarFacts.profitBeforeTax}의 둘째 갈래가 그것으로 갈린다.
     *
     * <p><b>왜 가드가 필요한가</b>: 세전이익 = 총액순이익 + 법인세는 <b>중단영업이 없을 때만</b>
     * 선다. 실측(표본 407 · 답을 아는 2,320칸)으로 <b>중단영업이 없는 회사에서 95.9%가 정확하고
     * 97.6%가 1% 이내(최대 137%)</b>인데, 있는 회사에서는 <b>55.9%로 떨어지고 최대 7,974%</b>다.
     *
     * <p><b>MUST: 네 태그를 다 본다.</b> 처음에 {@code IncomeLossFromDiscontinuedOperationsNetOfTax}
     * 하나만 보고 <b>87%로 재서 유도를 기각할 뻔했다.</b> 계열을 다 넣자 95.9%가 됐다 —
     * <b>이름이 계약이 아니듯, 태그 하나가 개념 전체도 아니다.</b>
     */
    DISCONTINUED_OPERATIONS(Kind.DURATION, Unit.MONETARY, true, List.of(
            "IncomeLossFromDiscontinuedOperationsNetOfTax",
            "IncomeLossFromDiscontinuedOperationsNetOfTaxAttributableToReportingEntity",
            "DiscontinuedOperationIncomeLossFromDiscontinuedOperationNetOfTax",
            "IncomeLossFromDiscontinuedOperationsNetOfTaxAttributableToNoncontrollingInterest")),

    // ── 중단영업 · 계속영업 재료(F2b · ADR-0184 · 관문 켬만 · 분기도 읽는다) ──────────────────────────
    // 저장 컬럼 둘(discontinued_income · _controlling)을 짓는 재료다. 위 DISCONTINUED_OPERATIONS ·
    // INCOME_FROM_CONTINUING_OPERATIONS(연간만 · profitBeforeTax의 회사 단위 게이트와 세전 유도)는 건드리지 않는다 — 그 둘을 분기로
    // 열면 관문 꺼짐의 판 집합이 바뀐다(conflicts가 원시 컬럼 전체를 본다).
    // MUST: 이름이 계약이 아니다 — 「계속영업」 태그는 중단영업 줄이 있는 행에서만 쓴다. 2019 뒤 중단 태그 없는 798칸 중 88은
    // 그 태그가 지분법 전 이익이었다(MPLX · THG · 탐색 F2B-PLAN-1023.md 1b).

    /** 중단영업 손익 연결(비지배 몫 포함). 후보 둘은 같은 뜻의 옛 · 새 이름이다. */
    DISCONTINUED_INCOME_TOTAL(Kind.DURATION, Unit.MONETARY, List.of(
            "IncomeLossFromDiscontinuedOperationsNetOfTax",
            "DiscontinuedOperationIncomeLossFromDiscontinuedOperationNetOfTax")),

    /** 중단영업 손익의 지배주주 몫. */
    DISCONTINUED_INCOME_PARENT(Kind.DURATION, Unit.MONETARY, List.of(
            "IncomeLossFromDiscontinuedOperationsNetOfTaxAttributableToReportingEntity")),

    /** 중단영업 손익의 비지배 몫. */
    DISCONTINUED_INCOME_NONCONTROLLING(Kind.DURATION, Unit.MONETARY, List.of(
            "IncomeLossFromDiscontinuedOperationsNetOfTaxAttributableToNoncontrollingInterest")),

    /**
     * 중단영업 처분 손익(세후) — 구성요소. 연결 후보가 없는 행에서만 쓰고, 같은 공시의 {@code 연결 순이익 − 연결 계속영업}과
     * <b>같을 때만</b> 연결 몫으로 받는다(구성요소가 하나뿐인지 원장이 확인해 준 것). 지금 창 16칸 · 6종목(GTES · KEY · LAUR ·
     * AES · ESE · TEX).
     */
    DISCONTINUED_DISPOSAL_GAIN(Kind.DURATION, Unit.MONETARY, List.of(
            "DiscontinuedOperationGainLossOnDisposalOfDiscontinuedOperationNetOfTax")),

    /** 중단영업 처분 전 기간 손익(세후) — 구성요소. {@link #DISCONTINUED_DISPOSAL_GAIN}과 같은 규칙이다. */
    DISCONTINUED_PHASE_OUT(Kind.DURATION, Unit.MONETARY, List.of(
            "DiscontinuedOperationIncomeLossFromDiscontinuedOperationDuringPhaseOutPeriodNetOfTax")),

    /** 계속영업 손익의 지배주주 몫. 중단영업 줄이 있는 행에서 {@code 지배주주 순이익 − 이것 = 지배 중단}의 재료다. */
    CONTINUING_INCOME_PARENT(Kind.DURATION, Unit.MONETARY, List.of(
            "IncomeLossFromContinuingOperations")),

    /** 계속영업 손익 연결 — {@link #INCOME_FROM_CONTINUING_OPERATIONS}와 같은 태그를 분기에서도 읽는 관문 몫이다. */
    CONTINUING_INCOME_TOTAL(Kind.DURATION, Unit.MONETARY, List.of(
            "IncomeLossFromContinuingOperationsIncludingPortionAttributableToNoncontrollingInterest")),

    /** 계속영업 손익의 비지배 몫. */
    CONTINUING_INCOME_NONCONTROLLING(Kind.DURATION, Unit.MONETARY, List.of(
            "IncomeLossFromContinuingOperationsAttributableToNoncontrollingEntity")),

    /**
     * 보통주 귀속 계속영업 손익(지배주주 − 우선주 배분). 같은 행 {@link #NET_INCOME_AVAILABLE_TO_COMMON}이 지배주주 순이익과
     * 같을 때만(우선주 배분 0의 증인) 지배 계속영업으로 쓴다.
     */
    CONTINUING_INCOME_AVAILABLE_TO_COMMON(Kind.DURATION, Unit.MONETARY, List.of(
            "NetIncomeLossFromContinuingOperationsAvailableToCommonShareholdersBasic"));

    private enum Kind { DURATION, INSTANT, ANCHORED_INSTANT }

    private enum Unit { MONETARY, SHARES, PER_SHARE }

    /**
     * 그 기간을 덮는 태그가 여럿일 때 <b>무엇을 고르는가.</b>
     *
     * <p>기본은 {@link #FIRST}다 — 후보 순서가 곧 우선순위이고, 대부분의 계정은 태그마다
     * 뜻이 같아 순서로 충분하다.
     */
    private enum Selection {
        /** 후보 순서대로 처음 덮는 것. */
        FIRST,
        /**
         * 값이 가장 큰 것. <b>후보끼리 뜻이 달라 부분값 태그가 총계보다 앞설 수 있는 컬럼</b>에만
         * 쓴다. {@link #fallbackCandidates}는 이 경쟁에 참여하지 않는다.
         */
        LARGEST
    }

    private final Kind kind;
    private final Unit unit;
    private final boolean annualOnly;
    private final Selection selection;
    private final List<String> candidates;
    private final List<String> fallbackCandidates;

    SecEdgarColumn(Kind kind, Unit unit, List<String> candidates) {
        this(kind, unit, false, candidates);
    }

    SecEdgarColumn(Kind kind, Unit unit, boolean annualOnly, List<String> candidates) {
        this(kind, unit, annualOnly, Selection.FIRST, candidates, List.of());
    }

    SecEdgarColumn(Kind kind, Unit unit, Selection selection,
                   List<String> candidates, List<String> fallbackCandidates) {
        this(kind, unit, false, selection, candidates, fallbackCandidates);
    }

    SecEdgarColumn(Kind kind, Unit unit, boolean annualOnly, Selection selection,
                   List<String> candidates, List<String> fallbackCandidates) {
        this.kind = kind;
        this.unit = unit;
        this.annualOnly = annualOnly;
        this.selection = selection;
        this.candidates = candidates;
        this.fallbackCandidates = fallbackCandidates;
    }

    /**
     * 이 컬럼이 볼 태그 전부. <b>경쟁 후보가 앞, 마지막 수단이 뒤</b>이고 이 순서가
     * {@code SecEdgarFacts}의 태그 맵 순서가 된다.
     */
    List<String> candidates() {
        if (fallbackCandidates.isEmpty()) {
            return candidates;
        }
        List<String> all = new java.util.ArrayList<>(candidates);
        all.addAll(fallbackCandidates);
        return List.copyOf(all);
    }

    /**
     * 관문 설정에 따른 후보 순서. 관문 켬의 주식수만 다르다 — 가중평균 폴백에서 <b>기본이 희석보다 앞</b>이다(탐색
     * {@code SHARES-TIE-PLAN-1023.md} v1.1): 축 없는 dei 표지가 있는 정기 공시 49,941에서 같은 공시의 가장 짧은 창 가중평균을
     * 표지에 대면 기본이 가까운 것 35,000 · 희석 7,625 · 같음 7,316이고 상대차 중앙 0.48% 대 1.28%다(2026-10-23 · `.factcache`).
     * 희석은 반희석 판정이 기간마다 바뀌어 Up-C에서 교환 단위를 넣고 뺀다(TKO 2026 Q2 3개월 75.9M · 누계 193.3M · 기본 74.8M).
     */
    List<String> candidates(boolean usGate) {
        if (usGate && this == SHARES_OUTSTANDING) {
            return GATED_SHARES_ORDER;
        }
        return candidates();
    }

    private static final List<String> GATED_SHARES_ORDER = List.of(
            "dei:EntityCommonStockSharesOutstanding",
            "CommonStockSharesOutstanding",
            "WeightedAverageNumberOfSharesOutstandingBasic",
            "WeightedAverageNumberOfDilutedSharesOutstanding");

    /** 가장 큰 값을 골라야 하는 컬럼인가. */
    boolean picksLargest() {
        return selection == Selection.LARGEST;
    }

    /**
     * 이 태그가 <b>최댓값 경쟁에 참여하는가.</b> 마지막 수단 태그는 참여하지 않는다 —
     * 덮는 경쟁 후보가 하나도 없을 때만 쓰인다.
     */
    boolean competesForLargest(String tag) {
        return !fallbackCandidates.contains(tag);
    }

    /**
     * {@code financial_statement}의 컬럼 이름. <b>상수 이름을 그대로 내린다</b> —
     * 이 enum의 이름이 컬럼 이름과 일부러 같게 지어져 있고, 사본을 따로 두면 갈려도
     * 컴파일이 통과한다. {@code FinancialColumnAbsenceWriter}가 이 값을 키로 쓴다.
     */
    /**
     * <b>그 컬럼의 부분집합인 태그들.</b> 후보가 아니다 — 이 태그가 값을 내도 컬럼을 채우지 않는다.
     *
     * <p>후보 목록은 지금까지 <b>「태그가 그 컬럼에 속하는가」만</b> 담았고 <b>그 태그의 뜻이
     * 컬럼의 전체인지 부분인지는 어디에도 없었다.</b> 그래서 「우리 후보가 없다」와 「소스가
     * 부분 개념만 낸다」가 한 상태로 뭉쳤고, 화면은 부분 개념을 내는 회사에도
     * <i>"이 회사는 매출원가·매출총이익을 공시에 내지 않습니다"</i>라고 말했다.
     * <b>실측(EDGAR 전수 탐침, 2026-09-14)으로 그 거짓 문장을 받는 종목이 20이다.</b>
     *
     * <p><b>MUST NOT: 낱말({@code Excluding})로 자동 분류하지 않는다.</b> 문자열 분류는 가짜
     * 구성원을 만든다 — 목록은 탐침이 낸 태그를 하나씩 정의로 판정해 손으로 적는다.
     *
     * <p><b>MUST NOT: 이 태그들을 후보로 승격하지 않는다.</b> 실측이 그 위험을 이름 지었다 —
     * {@code CostOfServices}는 39종목이 갖는데 <b>38이 상품원가 태그를 함께 가진다</b>(용역 쪽
     * 절반이라는 뜻이다). {@code Selection.LARGEST}에서 후보로 넣으면 어느 해에는 전체 대신
     * 조각이 이긴다.
     *
     * <p><b>D&amp;A 조각과 더해서 전체를 만들지 않는다 — 재보지 않았다.</b>
     * {@code DepreciationDepletionAndAmortization}은 <b>판관비에 들어간 몫까지 포함한 총액</b>이라
     * 그냥 더하면 원가가 과대해진다. 그 오차를 실측하기 전에는 만들지 않는다.
     */
    /**
     * <b>한 기간에 음수일 수 없는 유량인가.</b> 유도 결과가 음수로 나오면 그것은 「그 분기가
     * 음수다」가 아니라 <b>우리 뺄셈이 뜻이 다른 두 수를 뺐다</b>는 뜻이다(ADR-0151).
     *
     * <p><b>MUST NOT: 영업이익·순이익·매출총이익에 넣지 않는다.</b> 그 셋은 정당하게 음수다.
     * <b>그리고 소스가 준 값에는 이 판정을 걸지 않는다</b> — {@code KKR}의 10-K가 2018 Q4
     * 매출을 <b>−178.1M으로 직접 낸다</b>(자산운용사라 투자손익이 매출에 섞인다).
     */
    boolean nonNegativeFlow() {
        return this == REVENUE || this == COST_OF_SALES
                // 비용 유량은 한 기간에 음수일 수 없다. 2026-09-16까지 이 목록에 없어서
                // **가드가 매출·매출원가 둘에만 걸려 있었고**, 4분기 유도를 열자 그 구멍으로
                // 불가능한 값이 들어왔다 — 실측 `is_latest` Q4 **44칸 · 41라벨 · 30종목**
                // (`GE` 2022 총비용 **−14,222,000,000** · `ARW` 2016 **−9,348,304,000**(매출의
                // 1.45배) · `CNX` 2016 −505,601,000(**8.74배**)). 뿌리는 `ARW`처럼 회사가
                // **태그의 뜻을 갈았는데**(FY2016 총비용 22,966,722,000 → 2,285,660,000)
                // 분기는 옛 기저인 자리이고, `representedOtherYears`는 그 공시가 분기를 하나도
                // 안 태그하면 못 잡는다.
                //
                // **파급이 큰 이유**: 이 다섯이 `FinancialMath.operatingIncomeOf`의 폴백
                // 사슬이다. 실측으로 `GE` 2022 TTM 영업이익률이 **51.1%**(그 해 FY 행은 2.4%),
                // `CNX` 2015·2016은 **부호가 뒤집혀** +20.0% / +17.1%(FY는 −77.6% / −77.0%)로
                // 나갔다. 전에는 `null`이었다 — **참인 침묵이 그럴듯한 오답으로 바뀌는 자리다.**
                || this == OPERATING_EXPENSES
                || this == COSTS_AND_EXPENSES
                || this == INTEREST_EXPENSE
                || this == NONINTEREST_EXPENSE
                || this == BENEFITS_LOSSES_AND_EXPENSES
                // 소스가 한 번도 음수를 안 낸다(실측 0 / 8,750).
                || this == INTEREST_AND_DIVIDEND_INCOME;
        // MUST NOT: NONINTEREST_INCOME을 넣지 않는다 — 트레이딩 손실로 **정당하게 음수**다
        // (실측으로 소스가 0.24%에서 음수를 낸다).
    }

    List<String> partialCandidates() {
        return this == COST_OF_SALES ? COST_OF_SALES_PARTIALS : List.of();
    }

    /**
     * 매출원가의 부분 개념. 앞 넷은 <b>정의상 감가상각을 뺀 원가</b>이고(태그 이름이 그렇게
     * 말한다) 뒤 둘은 이름 그대로 구성요소다 — {@code CostOfServices}는 용역 쪽,
     * {@code CostOfReimbursableExpense}는 청구대행 비용이다.
     */
    private static final List<String> COST_OF_SALES_PARTIALS = List.of(
            "CostOfGoodsAndServiceExcludingDepreciationDepletionAndAmortization",
            "CostOfServicesExcludingDepreciationDepletionAndAmortization",
            "CostOfGoodsSoldExcludingDepreciationDepletionAndAmortization",
            "CostOfRevenueExcludingDepreciationDepletionAndAmortization",
            "CostOfServices",
            "CostOfReimbursableExpense");

    String columnName() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    boolean instant() {
        return kind != Kind.DURATION;
    }

    /**
     * 이 컬럼이 <b>「그 보고서가 그 기간을 보고했다」의 증거</b>가 되는가.
     *
     * <p>거짓이면 {@code hasAnyValue}가 그 컬럼을 세지 않아 <b>reporter 집합이 안 커진다.</b>
     * 주식수가 그런 이유는 기간을 안 묻는 팩트라 모든 보고서가 모든 기간의 보고자가 되기
     * 때문이고, 세율 재료 둘이 그런 이유는 <b>그 컬럼만 든 얕은 보고서가 baseline을 세우면
     * 뒤따르는 진짜 10-K가 버려지기</b> 때문이다(ADR-0143 §버린 대안).
     *
     * <p><b>값을 잃지 않는다</b> — {@code valuesOf}가 컬럼을 독립적으로 순회한다.
     */
    boolean reportEvidence() {
        return !anchoredInstant()
                && this != INCOME_TAX_EXPENSE
                && this != PROFIT_BEFORE_TAX
                && !derivationMaterial();
    }

    /**
     * <b>유도의 재료일 뿐 저장되지 않는 컬럼인가.</b>
     *
     * <p>{@link #reportEvidence}에서 빼는 이유는 세율 재료 둘과 같다 — <b>그 컬럼만 든 얕은
     * 보고서가 baseline을 세우면 뒤따르는 진짜 10-K가 버려진다</b>(ADR-0143 §버린 대안).
     * 비지배지분은 재무상태표·손익계산서의 말단 항목이라 그런 보고서가 실제로 있다.
     *
     * <p><b>{@code NET_INCOME_CONTROLLING}·{@code NET_INCOME_TOTAL}은 여기 없다</b> —
     * 그 둘의 후보가 {@link #NET_INCOME}의 부분집합이라 증거 자격을 새로 만들지 않는다.
     */
    private boolean derivationMaterial() {
        return gated()
                || this == NET_INCOME_AVAILABLE_TO_COMMON
                || this == MINORITY_INTEREST
                || this == MEZZANINE_EQUITY
                || this == OPERATING_CASH_FLOW_DISCONTINUED
                || this == EQUITY_METHOD_INCOME
                // 부모 PROFIT_BEFORE_TAX가 이미 빠져 있다 — 갈래만 남겨 두면 세금만 든 얕은
                // 보고서가 reporter가 되어 ADR-0143이 닫은 자리가 다시 열린다.
                || this == PROFIT_BEFORE_TAX_INCLUDING_EQUITY
                || this == PROFIT_BEFORE_TAX_EXCLUDING_EQUITY
                || this == INCOME_FROM_CONTINUING_OPERATIONS
                || this == DISCONTINUED_OPERATIONS
                || this == NET_INTEREST_INCOME
                || this == NET_INCOME_NONCONTROLLING
                || this == NET_INCOME_NONCONTROLLING_REDEEMABLE
                || this == NET_INCOME_NONCONTROLLING_NONREDEEMABLE;
    }

    /**
     * <b>미국 관문 뒤 재료인가</b>(F2b). 관문이 꺼졌으면 수집하지 않는다({@code SecEdgarFacts.collect}) — 그러면 판 판정 ·
     * 태그 선택 · 통화 · 부재 목록이 이 컬럼들이 생기기 전과 같다.
     */
    boolean gated() {
        return this == DISCONTINUED_INCOME_TOTAL
                || this == DISCONTINUED_INCOME_PARENT
                || this == DISCONTINUED_INCOME_NONCONTROLLING
                || this == DISCONTINUED_DISPOSAL_GAIN
                || this == DISCONTINUED_PHASE_OUT
                || this == CONTINUING_INCOME_PARENT
                || this == CONTINUING_INCOME_TOTAL
                || this == CONTINUING_INCOME_NONCONTROLLING
                || this == CONTINUING_INCOME_AVAILABLE_TO_COMMON
                || this == PARTNERS_EQUITY_INCLUDING_NCI
                || this == PARTNERS_EQUITY
                || this == PARTNERS_EQUITY_NONCONTROLLING
                || this == COMPREHENSIVE_INCOME_NONCONTROLLING;
    }

    /**
     * LP · LLC 자본 잔액 재료(ADR-0188). F2b의 「순이익 없는 공시 → 관문 재료 null」에서 빠진다 — 뒤 해 10-Q의 비교 재무상태표는
     * 순이익 없이 자산 · 부채 · LP 자본을 함께 싣는데, 지우면 자산 · 부채만 새 판이고 LP 자본은 앞 판이 이어진다(WES 2018 FY).
     */
    boolean partnersEquity() {
        return this == PARTNERS_EQUITY_INCLUDING_NCI || this == PARTNERS_EQUITY || this == PARTNERS_EQUITY_NONCONTROLLING;
    }

    boolean anchoredInstant() {
        return kind == Kind.ANCHORED_INSTANT;
    }

    boolean annualOnly() {
        return annualOnly;
    }

    /** 통화를 뽑을 수 있는 컬럼인가. 주식 수·주당 값은 통화의 근거가 아니다. */
    boolean monetary() {
        return unit == Unit.MONETARY;
    }

    /**
     * 이 단위 키의 팩트를 받는가.
     *
     * <p>금액은 <b>3자 ISO 키만</b> 받는다 — {@code character(3)} 컬럼에 담기 때문이고,
     * 그 필터가 없으면 {@code segment}·{@code store} 같은 키의 값이 금액으로 들어온다.
     */
    boolean acceptsUnit(String key) {
        return switch (unit) {
            case MONETARY -> key.length() == 3 && key.chars().allMatch(Character::isLetter);
            case SHARES -> key.equals("shares");
            case PER_SHARE -> key.startsWith("USD/shares");
        };
    }
}
