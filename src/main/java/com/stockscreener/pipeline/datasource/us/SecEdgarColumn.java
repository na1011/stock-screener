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

    REVENUE(Kind.DURATION, Unit.MONETARY, List.of(
            "RevenueFromContractWithCustomerExcludingAssessedTax",
            "RevenueFromContractWithCustomerIncludingAssessedTax",
            "Revenues", "SalesRevenueNet", "SalesRevenueGoodsNet")),
    OPERATING_INCOME(Kind.DURATION, Unit.MONETARY, List.of("OperatingIncomeLoss")),
    NET_INCOME(Kind.DURATION, Unit.MONETARY, List.of(
            "NetIncomeLoss", "ProfitLoss", "NetIncomeLossAvailableToCommonStockholdersBasic")),
    COST_OF_SALES(Kind.DURATION, Unit.MONETARY, List.of(
            "CostOfGoodsAndServicesSold", "CostOfRevenue", "CostOfGoodsSold", "CostOfSales")),

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
    TOTAL_EQUITY(Kind.INSTANT, Unit.MONETARY, List.of(
            "StockholdersEquity",
            "StockholdersEquityIncludingPortionAttributableToNoncontrollingInterest")),
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

    /** 주당순이익. <b>대조용이다</b> — 지표는 언제나 자체 계산이다(spec §7). */
    EPS(Kind.DURATION, Unit.PER_SHARE, List.of("EarningsPerShareBasic", "EarningsPerShareDiluted"));

    private enum Kind { DURATION, INSTANT, ANCHORED_INSTANT }

    private enum Unit { MONETARY, SHARES, PER_SHARE }

    private final Kind kind;
    private final Unit unit;
    private final boolean annualOnly;
    private final List<String> candidates;

    SecEdgarColumn(Kind kind, Unit unit, List<String> candidates) {
        this(kind, unit, false, candidates);
    }

    SecEdgarColumn(Kind kind, Unit unit, boolean annualOnly, List<String> candidates) {
        this.kind = kind;
        this.unit = unit;
        this.annualOnly = annualOnly;
        this.candidates = candidates;
    }

    List<String> candidates() {
        return candidates;
    }

    boolean instant() {
        return kind != Kind.DURATION;
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
