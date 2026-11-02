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
    private final String industryDivisionKey;
    private final List<PriceBar> priceBars;
    private final List<FinancialFigures> annualRevisions;
    private final List<FinancialFigures> quarterlyRevisions;
    private final List<FinancialFigures> annualFigures;
    private final List<FinancialFigures> quarterlyFigures;
    private final List<ShareAdjustment> shareAdjustments;
    private final List<LocalDate> unadjustedActions;
    private final NavigableMap<LocalDate, BigDecimal> marketIndex;
    private final List<GovernanceFigures> governanceFacts;
    private final Map<String, NavigableMap<LocalDate, BigDecimal>> dependencies;
    /** 실적 발표가 공개된 날(오름차순 · ADR-0195) — 재무 기준보다 새 발표가 공개된 봉의 재무 지표를 막는 재료다. */
    private final List<LocalDate> earningsReleaseDates;
    /**
     * 이 종목이 모체인 분사의 효력일(오름차순) — 원장의 승인된 분사 중 가격 배수가 1이 아닌 것만. 그날부터 가격이 분사 뒤 회사를 뜻하므로
     * 분사 앞 기준 재무를 가격과 짝짓지 않는 재료다({@link #withoutPreSpinOffBasis}).
     */
    private final List<LocalDate> spinOffDates;

    public IndicatorContext(
            long stockId,
            String industryDivisionKey,
            List<PriceBar> priceBars,
            List<FinancialFigures> annualFigures,
            List<FinancialFigures> quarterlyFigures,
            List<ShareAdjustment> shareAdjustments,
            List<LocalDate> unadjustedActions,
            NavigableMap<LocalDate, BigDecimal> marketIndex,
            List<GovernanceFigures> governanceFacts,
            Map<String, NavigableMap<LocalDate, BigDecimal>> dependencies) {
        this(stockId, industryDivisionKey, priceBars, annualFigures, quarterlyFigures, shareAdjustments,
                unadjustedActions, marketIndex, governanceFacts, dependencies, List.of());
    }

    /**
     * @param earningsReleaseDates 실적 발표가 공개된 날({@code earnings_release.effective_on}) — 러너가 싣는다. 빠뜨리면 컴파일되고
     *                             새 실적이 공개된 봉도 옛 분기로 값이 나온다(ADR-0195). {@code IndicatorRunnerTest}가 이 자리를 지킨다
     */
    public IndicatorContext(
            long stockId,
            String industryDivisionKey,
            List<PriceBar> priceBars,
            List<FinancialFigures> annualFigures,
            List<FinancialFigures> quarterlyFigures,
            List<ShareAdjustment> shareAdjustments,
            List<LocalDate> unadjustedActions,
            NavigableMap<LocalDate, BigDecimal> marketIndex,
            List<GovernanceFigures> governanceFacts,
            Map<String, NavigableMap<LocalDate, BigDecimal>> dependencies,
            List<LocalDate> earningsReleaseDates) {
        this(stockId, industryDivisionKey, priceBars, annualFigures, quarterlyFigures, shareAdjustments,
                unadjustedActions, marketIndex, governanceFacts, dependencies, earningsReleaseDates, List.of());
    }

    private IndicatorContext(
            long stockId,
            String industryDivisionKey,
            List<PriceBar> priceBars,
            List<FinancialFigures> annualFigures,
            List<FinancialFigures> quarterlyFigures,
            List<ShareAdjustment> shareAdjustments,
            List<LocalDate> unadjustedActions,
            NavigableMap<LocalDate, BigDecimal> marketIndex,
            List<GovernanceFigures> governanceFacts,
            Map<String, NavigableMap<LocalDate, BigDecimal>> dependencies,
            List<LocalDate> earningsReleaseDates,
            List<LocalDate> spinOffDates) {
        this.spinOffDates = spinOffDates == null ? List.of() : spinOffDates.stream().distinct().sorted().toList();
        this.earningsReleaseDates = earningsReleaseDates == null ? List.of()
                : earningsReleaseDates.stream().distinct().sorted().toList();
        this.stockId = stockId;
        this.industryDivisionKey = industryDivisionKey;
        this.priceBars = priceBars == null ? List.of() : List.copyOf(priceBars);
        this.annualRevisions = annualFigures == null ? List.of() : List.copyOf(annualFigures);
        this.quarterlyRevisions =
                quarterlyFigures == null ? List.of() : List.copyOf(quarterlyFigures);
        this.annualFigures = latestPerPeriod(this.annualRevisions);
        this.quarterlyFigures = latestPerPeriod(this.quarterlyRevisions);
        this.shareAdjustments =
                shareAdjustments == null ? List.of() : List.copyOf(shareAdjustments);
        this.unadjustedActions =
                unadjustedActions == null ? List.of() : List.copyOf(unadjustedActions);
        this.marketIndex = marketIndex == null ? new TreeMap<>() : new TreeMap<>(marketIndex);
        this.governanceFacts =
                governanceFacts == null ? List.of() : List.copyOf(governanceFacts);
        this.dependencies = dependencies == null ? Map.of() : Map.copyOf(dependencies);
    }

    /** 지수를 쓰지 않던 시절의 생성자. 기존 테스트가 그대로 컴파일되게 남긴다. */
    public IndicatorContext(
            long stockId,
            String industryDivisionKey,
            List<PriceBar> priceBars,
            List<FinancialFigures> annualFigures,
            List<FinancialFigures> quarterlyFigures,
            List<ShareAdjustment> shareAdjustments,
            Map<String, NavigableMap<LocalDate, BigDecimal>> dependencies) {
        this(stockId, industryDivisionKey, priceBars, annualFigures, quarterlyFigures,
                shareAdjustments, List.of(), null, List.of(), dependencies);
    }

    /** 업종을 모르는 컨텍스트. 업종을 쓰지 않는 계산기의 테스트가 쓴다. */
    public IndicatorContext(
            long stockId,
            List<PriceBar> priceBars,
            List<FinancialFigures> annualFigures,
            List<FinancialFigures> quarterlyFigures,
            Map<String, NavigableMap<LocalDate, BigDecimal>> dependencies) {
        this(stockId, null, priceBars, annualFigures, quarterlyFigures, List.of(), List.of(), null,
                List.of(), dependencies);
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
        return new IndicatorContext(stockId, industryDivisionKey, priceBars, annualRevisions,
                quarterlyRevisions, shareAdjustments, unadjustedActions, marketIndex,
                governanceFacts, newDependencies, earningsReleaseDates, spinOffDates);
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
    public String industryDivisionKey() {
        return industryDivisionKey;
    }

    /** 날짜 오름차순 시세. 워밍업 구간을 포함해 {@link DataRequirement}가 요구한 만큼 담긴다. */
    public List<PriceBar> priceBars() {
        return priceBars;
    }

    /**
     * 그 <b>봉 시점에 공시돼 있던</b> 재무만 담은 사본(ADR-0128).
     *
     * <p>재무 지표의 시간축이 여기서 나온다. 리더가 {@code KNOWN_ON}으로 읽어 오는 것은
     * <b>{@code asOf}까지의 판 이력 전부</b>이고, 그중 어느 판을 볼지는 <b>봉이 정한다</b> —
     * {@code GovernanceMath}가 거버넌스에 대해 하는 것과 같은 형태다
     * ({@code readGovernanceFacts} javadoc).
     *
     * <p>IMPORTANT — <b>거르기만 하고 접지 않는다.</b> 접는 것은 생성자가 하며
     * ({@link #latestPerPeriod}) 그래서 이 사본의 {@link #annualFigures()}는 <b>그 봉 시점의
     * 최신 판</b>이 된다. 여기서 접어 넘기면 판 이력이 사라져 다음 사본이 다시 못 고른다.
     *
     * <p>시세·기업행위·지수·거버넌스는 <b>안 거른다.</b> 시세는 계산기가 창으로 다루고,
     * 기업행위 원장과 수정계수는 <b>지금 아는 것이 맞다</b>(과거 일자의 수정주가는 언제나
     * 최신 원장 기준이다), 거버넌스는 {@code GovernanceMath}가 자기 축으로 이미 고른다.
     */
    public IndicatorContext visibleAt(LocalDate tradeDate) {
        List<FinancialFigures> annual = publishedOnOrBefore(annualRevisions, tradeDate);
        List<FinancialFigures> quarterly = publishedOnOrBefore(quarterlyRevisions, tradeDate);
        // 4분기 행에 그 봉이 보는 그 해 FY 행(가장 늦은 판)을 단다: 4분기로 끝나는 TTM 창의 네 분기 합은 그 해 FY다(재고 「A6 남은 것」).
        List<FinancialFigures> quarterlyWithAnnual = quarterly.stream().map(q -> q.fiscalPeriod() != FinancialFigures.FiscalPeriod.Q4 ? q
                : annual.stream().filter(a -> a.fiscalYear() == q.fiscalYear()).findFirst().map(q::withAnnualOfYear).orElse(q))
                .toList();
        return new IndicatorContext(stockId, industryDivisionKey, priceBars,
                annual,
                quarterlyWithAnnual,
                shareAdjustments, unadjustedActions, marketIndex, governanceFacts, dependencies,
                earningsReleaseDates.stream().filter(d -> !d.isAfter(tradeDate)).toList(), spinOffDates);
    }

    /**
     * 재무를 비운 사본 — 그 봉의 재무 지표를 내지 않을 때 계산기에 넘긴다(ADR-0195 · {@code FinancialMath.financialsAt}). 재무가 없는
     * 컨텍스트에서 계산기는 값을 안 내므로 그 봉에 행이 안 생긴다. 시세 · 거버넌스는 그대로다.
     */
    public IndicatorContext withoutFinancials() {
        return new IndicatorContext(stockId, industryDivisionKey, priceBars, List.of(), List.of(),
                shareAdjustments, unadjustedActions, marketIndex, governanceFacts, dependencies, earningsReleaseDates, spinOffDates);
    }

    /** 실적 발표가 공개된 날(오름차순). */
    public List<LocalDate> earningsReleaseDates() {
        return earningsReleaseDates;
    }

    /** 실적 발표 공개일만 갈아끼운 사본. 시험이 시드할 때 쓴다. */
    public IndicatorContext withEarningsReleaseDates(List<LocalDate> dates) {
        return new IndicatorContext(stockId, industryDivisionKey, priceBars, annualRevisions,
                quarterlyRevisions, shareAdjustments, unadjustedActions, marketIndex,
                governanceFacts, dependencies, dates, spinOffDates);
    }

    /** 이 종목이 모체인 분사의 효력일(오름차순 · {@link #spinOffDates} 필드). */
    public List<LocalDate> spinOffDates() {
        return spinOffDates;
    }

    /** 분사 효력일만 갈아끼운 사본. 러너가 원장으로 채우고, 시험이 시드할 때 쓴다. */
    public IndicatorContext withSpinOffDates(List<LocalDate> dates) {
        return new IndicatorContext(stockId, industryDivisionKey, priceBars, annualRevisions,
                quarterlyRevisions, shareAdjustments, unadjustedActions, marketIndex,
                governanceFacts, dependencies, earningsReleaseDates, dates);
    }

    /**
     * 분사 뒤 회사의 판만 남긴 사본 — 분사 뒤 봉의 재무 지표가 볼 재무다(ADR-0168 F1 · {@code FinancialMath.financialsAt}).
     *
     * <p>규칙 하나다: 그 봉 이하 중 가장 늦은 분사에 대해, <b>입력 기간이 분사 앞에 시작한 판</b>을 뺀다. 분사일 뒤 그 종목은 자식이
     * 빠진 회사인데, 분사 앞에 시작한 기간은 자식을 품은 회사의 기간이다. 재게재 판으로 가르지 않는다 — 원장의 재게재가 같은 값
     * 이어받기(HON FY2025 2026-10-22 10-Q 판 = 10-K 판 · 잔액만 다시 실음) · 국내 분할기일과 재상장일의 어긋남(005440 2023 1분기)으로
     * 꼴마다 갈려, 판으로 가르는 규칙은 꼴마다 예외가 붙는다(F1 반증 · 2026-11-01).
     * <ul>
     *   <li><b>4분기의 입력 기간은 그 FY다</b> — 4분기는 FY − 1~3분기로 짓는 값이라, 분사 반영 FY에서 분사 앞 분기를 빼면 기저가 섞인다
     *       (BWA 2023 4분기 매출 1,876 = 14,198 − 분사 앞 세 분기 · 실제 약 3,522).</li>
     *   <li><b>「분사 앞」의 경계는 효력일 직전 거래일이다</b> — 효력일은 분사 뒤 첫 거래일이라, 그 사이 주말에 시작한 기간(DHR 4분기
     *       2023-09-30 · 효력일 10-02)은 분사 뒤 회사의 것이다.</li>
     * </ul>
     *
     * <p>그래서 TTM은 분사 뒤에 시작한 네 분기가 찰 때까지, 잔액만 읽는 지표는 분사 뒤에 시작한 첫 기간이 공시될 때까지, 여러 해 지표는
     * 그 햇수가 분사 뒤로 찰 때까지 값이 안 난다(진짜 한계로 미표시 — 원장이 분사 뒤 기준임을 판으로 확인해 주지 않는다). 재게재하는
     * 회사도 그만큼 기다리고, 분사를 품은 기간의 기말 잔액(분사 뒤 잔액)도 그 기간과 함께 빠진다 — 행을 유량과 잔액으로 쪼개지 않는
     * 단순한 규칙의 대가다(수는 ADR-0168 §결과).
     *
     * <p>분사가 없거나 걷을 판이 없으면 그대로 돌려준다.
     */
    public IndicatorContext withoutPreSpinOffBasis(LocalDate barDate) {
        LocalDate boundary = spinOffBoundary(barDate);
        if (boundary == null) {
            return this;
        }
        List<FinancialFigures> annual = annualRevisions.stream().filter(f -> startsAfter(f, boundary)).toList();
        List<FinancialFigures> quarterly = quarterlyRevisions.stream().filter(f -> startsAfter(f, boundary)).toList();
        if (annual.size() == annualRevisions.size() && quarterly.size() == quarterlyRevisions.size()) {
            return this;
        }
        return new IndicatorContext(stockId, industryDivisionKey, priceBars, annual, quarterly, shareAdjustments,
                unadjustedActions, marketIndex, governanceFacts, dependencies, earningsReleaseDates, spinOffDates);
    }

    /**
     * 그 봉이 분사 뒤인데 분사 뒤 회사의 TTM이 아직 안 섰는가 — 분사 뒤에 시작한 분기가 넷 미만이고 그런 FY도 없다(ADR-0168 F1 ·
     * {@link #withoutPreSpinOffBasis}와 같은 술어). 재무 기준 표시가 읽는다. {@code visibleAt} 사본에 묻는다.
     */
    public boolean awaitingPostSpinOffFinancials(LocalDate barDate) {
        LocalDate boundary = spinOffBoundary(barDate);
        if (boundary == null) {
            return false;
        }
        return quarterlyFigures.stream().filter(f -> startsAfter(f, boundary)).count() < 4
                && annualFigures.stream().noneMatch(f -> startsAfter(f, boundary));
    }

    /** 그 봉 이하 중 가장 늦은 분사 효력일의 직전 거래일(봉이 없으면 효력일 하루 전). 분사 없음이면 null. */
    private LocalDate spinOffBoundary(LocalDate barDate) {
        if (spinOffDates.isEmpty()) {
            return null;
        }
        LocalDate latest = spinOffDates.stream().filter(d -> !d.isAfter(barDate)).reduce((x, y) -> y).orElse(null);
        if (latest == null) {
            return null;
        }
        LocalDate previous = latest.minusDays(1);
        for (PriceBar bar : priceBars) {
            if (bar.tradeDate().isBefore(latest)) {
                previous = bar.tradeDate();
            } else {
                break;
            }
        }
        return previous;
    }

    /** 판의 입력 기간이 경계 뒤에 시작했는가 — 4분기는 그 FY의 시작(같은 기말 FY 판 · 없으면 기말에서 한 해 앞)을 본다. */
    private boolean startsAfter(FinancialFigures f, LocalDate boundary) {
        LocalDate start = f.periodStart() != null ? f.periodStart() : f.periodEnd();
        if (f.fiscalPeriod() == FinancialFigures.FiscalPeriod.Q4 && f.periodEnd() != null) {
            start = annualRevisions.stream().filter(a -> f.periodEnd().equals(a.periodEnd()) && a.periodStart() != null)
                    .map(FinancialFigures::periodStart).findFirst().orElse(f.periodEnd().minusYears(1).plusDays(1));
        }
        return start == null || start.isAfter(boundary);
    }

    /**
     * 재무 판이 바뀌는 날짜들 — <b>오름차순 · 중복 없음</b>(ADR-0128).
     *
     * <p>재무에서 나온 값은 <b>공시가 있을 때만</b> 바뀐다. 그래서 봉마다 다시 계산하지 않고
     * 이 날짜들로 구간을 나눠 구간당 한 번만 계산한다({@code FinancialMath.byPublication}).
     * 실측으로 보존 창 안 공시일은 종목당 한 자릿수라, 봉 1,516개에 대해 계산이
     * 그만큼만 늘어난다.
     */
    public List<LocalDate> financialPublicationDates() {
        // 실적 발표 공개일도 경계다(ADR-0195) — 그 날부터 재무 지표가 막히고, 다음 공시일에 다시 열린다. 빼면 구간이 안 갈려 막힌 날
        // 앞의 값이 그 봉까지 펴진다.
        return java.util.stream.Stream.concat(
                        java.util.stream.Stream.concat(annualRevisions.stream(), quarterlyRevisions.stream())
                                // 보이기 시작하는 날이 경계다 — 공시일로 자르면 장 마감 뒤 공시 판이 다음 경계까지 안 들어온다.
                                .map(FinancialFigures::visibleOn),
                        java.util.stream.Stream.concat(earningsReleaseDates.stream(),
                                // 분사 효력일도 경계다(F1) — 그날부터 가격과 짝짓는 재무가 바뀐다(withoutPreSpinOffBasis).
                                spinOffDates.stream()))
                .filter(java.util.Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
    }

    /** 그 날짜까지 공시된 판만. 순서는 그대로 둔다 — {@link #latestPerPeriod}가 그것에 기댄다. */
    private static List<FinancialFigures> publishedOnOrBefore(List<FinancialFigures> figures,
                                                              LocalDate tradeDate) {
        return figures.stream()
                .filter(f -> f.visibleOn() == null || !f.visibleOn().isAfter(tradeDate))
                // 기저가 대체된 손익 칸은 그날부터 모른다(국내 A6 — 비면 그대로).
                .map(f -> f.asSeenOn(tradeDate))
                .toList();
    }

    /**
     * 기(期)마다 판 하나만 남긴다. <b>이것이 없으면 TTM이 같은 분기를 두 번 더한다.</b>
     *
     * <p>리더가 판 이력을 통째로 주므로({@code SELECT_FINANCIALS_KNOWN_ON}) 접는 자리가
     * 필요하다. 예전에는 SQL의 {@code row_number()}가 {@code asOf} 하나에 대해 접었는데,
     * 그러면 <b>봉마다 다른 판을 고를 수 없다.</b>
     *
     * <p>IMPORTANT — <b>고르는 축이 {@code published_at}인 것은 실측에 근거한다.</b>
     * {@code revision_no}가 {@link FinancialFigures}에 없어 공시일로 고르는데, 저장소 전체
     * 다판(多版) 행 48,488개에서 <b>공시일이 판 번호와 역전되거나 같은 경우가 0</b>이다(2026-09-17 ·
     * 미국·국내). 그 성질을 지키는 것은 {@code FinancialWriter}다 — 정정 갈래는 더 늦은 공시일에만
     * 번호를 올리고, 저장본보다 이른 판을 넣는 갈래는 넣은 즉시 그 라벨을, 호출 끝에는 어긋난 라벨
     * 전부를 공시일 순으로 다시 매긴다(ADR-0161).
     * 그 성질이 깨지면 이 선택이 조용히 옛 판을 고른다.
     *
     * <p>입력이 {@code period_end DESC, published_at DESC} 순이라 <b>먼저 만난 것이 그 기의
     * 최신 판</b>이다. 순서를 바꾸면 이 메서드가 조용히 옛 판을 남긴다.
     *
     * <p><b>MUST: 기(期)를 {@code periodEnd}로 가른다 — 회계연도 라벨로 가르지 않는다.</b>
     * {@link FinancialFigures} javadoc이 *"{@code periodEnd}가 기간의 근거다 … {@code fiscalYear}는
     * 라벨일 뿐"*으로 그 축을 정했고, 미국 라벨은 실제로 틀린다(ADR-0124가 회계연도 배정 결함
     * 셋을 고쳤다). 라벨로 가르면 <b>라벨이 겹친 분기들이 하나로 접혀 TTM이 네 분기를 못
     * 채우고 값이 통째로 사라진다</b> — 에러가 아니라 「판정불가」로 나온다.
     *
     * <p>연간과 분기가 <b>다른 목록</b>이므로 같은 {@code periodEnd}를 공유하는 FY와 유도 Q4가
     * 여기서 만나지 않는다(ADR-0124 §검증이 분기 행끼리의 기준선을 0으로 잰다).
     */
    private static List<FinancialFigures> latestPerPeriod(List<FinancialFigures> figures) {
        if (figures.size() <= 1) {
            return figures;
        }
        List<FinancialFigures> kept = new java.util.ArrayList<>(figures.size());
        java.util.Set<LocalDate> seen = new java.util.HashSet<>();
        for (FinancialFigures f : figures) {
            if (seen.add(f.periodEnd())) {
                kept.add(f);
            }
        }
        return List.copyOf(kept);
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
     * <b>계수에 반영되지 않은</b> 기업 행위의 효력일. 오름차순이다(ADR-0055).
     *
     * <p>{@link #shareAdjustments()}의 짝이다 — 그쪽이 "보정한 것", 이쪽이 "보정하지 못한 것"이고
     * 둘의 합집합이 원장 전체다. 여기 날짜가 있으면 그 종목의 시세에 <b>축척이 다른 구간이
     * 이어붙어 있다.</b> 007340은 2024-10-08에 종가가 102,100에서 19,200으로 떨어지는데
     * {@code adj_factor}가 1이다 — 그 계단을 넘는 창으로 낸 값은 무엇이든 틀리다.
     *
     * <p>MUST NOT: 계산기가 이것을 보고 스스로 값을 낼지 정하지 않는다. 판정은 러너가
     * {@link IndicatorRegistry#effectiveLookback(String)}과 함께 한 곳에서 끝낸다 —
     * 계산기마다 다시 판단하면 지표 하나를 더할 때 그 판단이 빠지고, 빠진 것은
     * <b>값이 나오는 것</b>으로 나타나 아무도 못 본다.
     */
    public List<LocalDate> unadjustedActions() {
        return unadjustedActions;
    }

    /**
     * <b>이 종목이 속한 시장</b>의 지수 종가 시계열(ADR-0053·ADR-0054).
     *
     * <p>지표 계층이 <b>종목 밖 시계열</b>을 받는 첫 자리다. 업종(ADR-0044)은 종목 속성이었고
     * 기업 행위(ADR-0051)는 다른 계층의 원장이었는데, 이것은 종목과 아무 관계가 없는 시계열이다.
     *
     * <p>MUST: 코스피 종목은 코스피, 코스닥 종목은 코스닥이다 — 그 판정은 <b>러너가 끝내서</b>
     * 넘긴다. 계산기가 시장 코드를 보고 고르게 하면 "코스닥 종목의 지수는 코스닥"이라는 시장의
     * 사실을 지표마다 다시 알아야 하고, 한 곳만 안 고쳐도 그 지표만 조용히 다른 벤치마크를 쓴다.
     * 원본 Pine이 통화(`syminfo.currency`)로만 갈렸던 것은 판단이 아니라 한계이며, 우리가 그
     * 폴백을 재현하지 않는 근거는 ADR-0046 §승인된 이탈 4에 있다.
     *
     * <p>MUST NOT: 비어 있는 것을 0으로 접지 않는다. 지수가 없으면 초과 수익률이 <b>절대
     * 수익률로 뜻이 바뀌는데 에러도 화면 흔적도 없다</b>. 그날 값을 내지 않는 것이 옳다.
     *
     * @return 거래일 오름차순 종가. 시장을 모르거나 그 지수가 적재되지 않았으면 비어 있다
     */
    public NavigableMap<LocalDate, BigDecimal> marketIndex() {
        return Collections.unmodifiableNavigableMap(marketIndex);
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
     * 거버넌스 사실. {@code fiscalYear} <b>내림차순</b>(최신 우선)이다(ADR-0081).
     *
     * <p>지표 계층이 <b>재무제표 밖의 회사 정보</b>를 받는 첫 자리다. 업종(ADR-0044)은 종목
     * 속성이었고 지수(ADR-0053)는 종목 밖 시계열이었는데, 이것은 <b>주기가 다른 원자료</b>다 —
     * 연 1회 스냅샷이고 회계연도가 끝난 이듬해 3월에 공시된다.
     *
     * <p>MUST: 봉마다 값을 고를 때 {@code GovernanceMath.on}을 쓴다. 최신 것을 창 전체에 펴면
     * <b>공시 전 구간이 미래를 알고 계산한 값</b>이 된다 — 실측 공시일 중앙값이 2026-03-18이고
     * 쓰기 창 255거래일 중 137이 그 앞이다(2026-09-08 실측).
     *
     * <p>MUST NOT: 비어 있는 것을 0으로 접지 않는다. 그 회사에 그 표가 없는 것과 아직 안 받은
     * 것이 같아지고, 둘 다 「지배구조가 좋다」로 읽힌다.
     */
    public List<GovernanceFigures> governanceFacts() {
        return governanceFacts;
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

    /**
     * 거버넌스 사실만 갈아끼운 사본. 테스트가 시드할 때 쓴다.
     *
     * <p>{@link #withMarketIndex}와 같은 이유로 있다 — 수집 어댑터가
     * {@code @ConditionalOnApiKey}라 키 없는 환경에 아예 없는데 계산기가 읽는 것은 컨텍스트다.
     */
    public IndicatorContext withGovernanceFacts(List<GovernanceFigures> newGovernanceFacts) {
        return new IndicatorContext(stockId, industryDivisionKey, priceBars, annualRevisions,
                quarterlyRevisions, shareAdjustments, unadjustedActions, marketIndex,
                newGovernanceFacts, dependencies, earningsReleaseDates, spinOffDates);
    }

    /** 계산 대상 날짜들(시세 기준, 오름차순). */
    public List<LocalDate> tradeDates() {
        return priceBars.stream().map(PriceBar::tradeDate).toList();
    }

    /** 빈 컨텍스트. 테스트와 원자료가 없는 종목에 쓴다. */
    public static IndicatorContext empty(long stockId) {
        return new IndicatorContext(stockId, List.of(), List.of(), List.of(), new TreeMap<>());
    }

    /**
     * 시장 지수만 갈아끼운 사본. 테스트가 지수를 시드할 때 쓴다.
     *
     * <p>MUST: 테스트는 이것이나 {@code market_index_daily} 직접 시드로 지수를 넣는다.
     * 수집 어댑터는 {@code @ConditionalOnApiKey}라 키 없는 환경에 아예 없는데 계산기가 읽는 것은
     * 컨텍스트이므로, 시드가 없으면 {@code EXCESS_RETURN_26W}가 비고 <b>에러는 안 난다.</b>
     */
    public IndicatorContext withMarketIndex(NavigableMap<LocalDate, BigDecimal> newMarketIndex) {
        return new IndicatorContext(stockId, industryDivisionKey, priceBars, annualRevisions,
                quarterlyRevisions, shareAdjustments, unadjustedActions, newMarketIndex,
                governanceFacts, dependencies, earningsReleaseDates, spinOffDates);
    }
}
