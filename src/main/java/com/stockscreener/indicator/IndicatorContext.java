package com.stockscreener.indicator;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * 지표 계산기에 전달되는 한 종목의 원자료.
 *
 * <p>러너가 {@link DataRequirement}의 선언을 보고 채워서 넘긴다. 계산기는 여기 담긴 것만 쓰고
 * 직접 저장소를 조회하지 않는다 — 그래야 계산 로직이 순수해져 테스트가 쉽고,
 * 러너가 종목당 한 번만 읽어 N+1을 피할 수 있다.
 */
public final class IndicatorContext {

    private final long stockId;
    private final String industryDivision;
    private final List<PriceBar> priceBars;
    private final List<FinancialFigures> annualFigures;
    private final List<FinancialFigures> quarterlyFigures;
    private final List<ShareAdjustment> shareAdjustments;
    private final Map<String, NavigableMap<LocalDate, BigDecimal>> dependencies;

    public IndicatorContext(
            long stockId,
            String industryDivision,
            List<PriceBar> priceBars,
            List<FinancialFigures> annualFigures,
            List<FinancialFigures> quarterlyFigures,
            List<ShareAdjustment> shareAdjustments,
            Map<String, NavigableMap<LocalDate, BigDecimal>> dependencies) {
        this.stockId = stockId;
        this.industryDivision = industryDivision;
        this.priceBars = priceBars == null ? List.of() : List.copyOf(priceBars);
        this.annualFigures = annualFigures == null ? List.of() : List.copyOf(annualFigures);
        this.quarterlyFigures = quarterlyFigures == null ? List.of() : List.copyOf(quarterlyFigures);
        this.shareAdjustments =
                shareAdjustments == null ? List.of() : List.copyOf(shareAdjustments);
        this.dependencies = dependencies == null ? Map.of() : Map.copyOf(dependencies);
    }

    /** 업종을 모르는 컨텍스트. 업종을 쓰지 않는 계산기의 테스트가 쓴다. */
    public IndicatorContext(
            long stockId,
            List<PriceBar> priceBars,
            List<FinancialFigures> annualFigures,
            List<FinancialFigures> quarterlyFigures,
            Map<String, NavigableMap<LocalDate, BigDecimal>> dependencies) {
        this(stockId, null, priceBars, annualFigures, quarterlyFigures, List.of(), dependencies);
    }

    /**
     * 의존 지표만 갈아끼운 사본.
     *
     * <p>IMPORTANT — 러너가 의존 지표를 채울 때 <b>생성자를 다시 부르지 않고 이것을 쓴다.</b>
     * 생성자를 부르면 새 필드가 늘 때마다 그 자리에서 조용히 빠질 수 있고, 빠진 필드는
     * 예외가 아니라 <b>값이 없는 지표</b>로 나타난다 — 필수 조건이면 전 종목 판정불가가
     * 되는데 에러도 경고도 없다.
     */
    public IndicatorContext withDependencies(
            Map<String, NavigableMap<LocalDate, BigDecimal>> newDependencies) {
        return new IndicatorContext(stockId, industryDivision,
                priceBars, annualFigures, quarterlyFigures, shareAdjustments, newDependencies);
    }

    public long stockId() {
        return stockId;
    }

    /**
     * 종목의 KSIC 중분류(업종코드 앞 2자리). 업종을 모르면 null이다.
     *
     * <p>지표가 <b>정의역</b>을 판정하는 데 쓴다 — Altman Z가 금융업에 성립하지 않는 것이
     * 그 예다(ADR-0044). 업종 <b>비교</b>는 여기서 할 수 없다. 그것은 같은 날 여러 종목을
     * 봐야 하므로 {@link SectorRelativeCalculator}의 몫이다.
     */
    public String industryDivision() {
        return industryDivision;
    }

    /** 날짜 오름차순 시세. 워밍업 구간을 포함해 {@link DataRequirement}가 요구한 만큼 담긴다. */
    public List<PriceBar> priceBars() {
        return priceBars;
    }

    /** {@code periodEnd} 내림차순(최신 우선) 연간 재무. */
    public List<FinancialFigures> annualFigures() {
        return annualFigures;
    }

    /** {@code periodEnd} 내림차순(최신 우선) 분기 재무. TTM 합산에 쓴다. */
    public List<FinancialFigures> quarterlyFigures() {
        return quarterlyFigures;
    }

    /** 이 종목에 반영할 기업 행위. 원장의 승인된 행에서 온다(ADR-0051). */
    public List<ShareAdjustment> shareAdjustments() {
        return shareAdjustments;
    }

    /**
     * 그 거래일의 <b>종가와 짝이 맞는</b> 상장주식수.
     *
     * <p>IMPORTANT — {@code close}에 곱하거나 나누는 자리는 {@link PriceBar#listedShares()}를
     * 직접 쓰지 않고 <b>반드시 이것을 거친다.</b> 무상증자는 권리락일에 가격을, 신주상장일에
     * 주식수를 바꾸므로 그 사이 14~16거래일은 두 값의 축척이 다르다 — 시가총액·PER·PBR이
     * 배수만큼 틀리는데 <b>어느 값도 결측이 아니라 화면에 그대로 나간다.</b>
     *
     * <p>원자료를 고치는 것이 아니다. {@code price_daily.listed_shares}는 그날의 사실이고
     * 틀린 것은 그것을 이미 희석된 가격과 짝지은 쪽이므로, 고칠 자리도 그쪽이다(ADR-0049).
     *
     * <p>사건이 걸리지 않으면 <b>인자를 그대로 돌려준다</b> — 곱셈으로 스케일이 흔들리면
     * 저장된 값이 이유 없이 다시 쓰인다.
     *
     * @return 보정된 주식수. 인자가 null이면 null이다(판단은 호출자 몫이다)
     */
    public BigDecimal sharesOn(LocalDate tradeDate, BigDecimal listedShares) {
        if (listedShares == null || shareAdjustments.isEmpty()) {
            return listedShares;
        }
        BigDecimal scaled = listedShares;
        for (ShareAdjustment adjustment : shareAdjustments) {
            if (adjustment.coversPriceButNotShares(tradeDate)) {
                scaled = scaled.multiply(adjustment.ratio());
            }
        }
        return scaled;
    }

    /**
     * {@code from}(제외)부터 {@code to}(포함)까지 <b>기업 행위가 만든</b> 주식수 배수.
     *
     * <p>{@link #sharesOn}이 낸 두 시점의 주식수를 견줄 때 <b>나눠서</b> 쓴다 — 액면분할과
     * 무상증자는 주식수를 늘리지만 <b>희석이 아니다.</b> 나누지 않으면 5:1 분할 종목이
     * "3년간 400% 희석"으로 읽히고, 그것이 필수 게이트를 그대로 뒤집는다.
     *
     * <p>MUST: 기준일은 {@code effectiveDate}다. {@code listingDate}가 아닌 이유는
     * {@link #sharesOn}이 <b>권리락일에 계단을 올리기</b> 때문이다 — 두 메서드가 서로 다른
     * 날짜를 보면 권리락~신주상장 구간에서 배수가 한 번 더 붙거나 빠진다. 경계의 등호도
     * 그래서 이 방향이다: {@code from} 당일 사건은 {@code sharesOn(from)}이 이미 곱했으므로
     * 빼고, {@code to} 당일 사건은 {@code sharesOn(to)}가 곱했으므로 넣는다.
     */
    public BigDecimal corporateActionMultiple(LocalDate from, LocalDate to) {
        BigDecimal multiple = BigDecimal.ONE;
        for (ShareAdjustment adjustment : shareAdjustments) {
            if (adjustment.tookEffectBy(to) && !adjustment.tookEffectBy(from)) {
                multiple = multiple.multiply(adjustment.ratio());
            }
        }
        return multiple;
    }

    /**
     * 의존 지표의 이미 계산된 시계열. {@link DataRequirement#dependsOn()}에 선언한 코드로 찾는다.
     *
     * <p>파생 지표가 부모의 계산을 다시 구현하지 않게 하는 통로다.
     */
    public NavigableMap<LocalDate, BigDecimal> dependency(String indicatorCode) {
        NavigableMap<LocalDate, BigDecimal> series = dependencies.get(indicatorCode);
        if (series == null) {
            throw new IllegalStateException(
                    "선언하지 않은 의존 지표를 요청했다: " + indicatorCode
                            + " — DataRequirement.dependsOn에 추가하라.");
        }
        return Collections.unmodifiableNavigableMap(series);
    }

    /** 계산 대상 날짜들(시세 기준, 오름차순). */
    public List<LocalDate> tradeDates() {
        return priceBars.stream().map(PriceBar::tradeDate).toList();
    }

    /** 빈 컨텍스트. 테스트와 원자료가 없는 종목에 쓴다. */
    public static IndicatorContext empty(long stockId) {
        return new IndicatorContext(stockId, List.of(), List.of(), List.of(), new TreeMap<>());
    }
}
