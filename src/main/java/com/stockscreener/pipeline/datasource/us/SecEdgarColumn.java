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
    NET_INCOME(Kind.DURATION, Unit.MONETARY, List.of(
            "NetIncomeLoss", "ProfitLoss", "NetIncomeLossAvailableToCommonStockholdersBasic")),
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
     * 2,670M)다. 그런 자리의 처방은 ADR-0126 §규칙의 <b>손으로 고치되 근거를 둘 이상</b>이다.
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
    INTEREST_EXPENSE(Kind.DURATION, Unit.MONETARY, List.of("InterestExpense")),
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
            "IncomeLossFromContinuingOperationsBeforeIncomeTaxesMinorityInterestAndIncomeLossFromEquityMethodInvestments"));

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
        return this == REVENUE || this == COST_OF_SALES;
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
                && this != PROFIT_BEFORE_TAX;
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
