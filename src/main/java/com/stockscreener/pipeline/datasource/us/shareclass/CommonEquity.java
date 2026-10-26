package com.stockscreener.pipeline.datasource.us.shareclass;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * (공시, 대차대조표 날짜)마다 <b>보통주가 아닌 청구권</b> N을 정한다 — PBR 분모를 지배 자본 − N으로 짓는 재료(관문 켬 · 탐색
 * {@code PERSHARE-DEN-PLAN-1026.md} v1.1). 미국 GAAP의 {@code StockholdersEquity}는 정의상 우선주를 품고, LP의 {@code PartnersCapital}은
 * GP 자본 · 우선 단위를 품는다 — 분자인 시가총액은 보통주 몫이다.
 *
 * <p>사다리(첫 번째로 서는 것 · 계획 2절)는 인스턴스 자본변동표 성분이 첫 칸이다. companyfacts 축 없는 태그만으로는 우선주를 축 맥락에만 싣는
 * 발행사(MS 9,750M)와 액면만 싣는 발행사(AXP · PNC 0)를 못 가른다. 못 정한 칸은 지어내지 않고 {@code null}(미표시)이다.
 */
public final class CommonEquity {

    /** 보통주 자본 오차 상한 — 계획 5 ①의 수용 기준과 같은 값(정한 뒤 안 바꾼다). */
    static final double TOLERANCE = 0.02;
    /** 연 배당률이 이것을 넘는 우선주는 없다 — 넘으면 그 값은 장부가가 아니라 액면이다. */
    private static final double MAX_COUPON = 0.25;

    private static final List<String> EQUITY_TOTAL = List.of("StockholdersEquity", "PartnersCapital", "MembersEquity");
    private static final List<String> EQUITY_INCLUDING = List.of("StockholdersEquityIncludingPortionAttributableToNoncontrollingInterest",
            "PartnersCapitalIncludingPortionAttributableToNoncontrollingInterest");
    private static final List<String> NONCONTROLLING = List.of("MinorityInterest", "PartnersCapitalAttributableToNoncontrollingInterest");
    private static final Set<String> COMPONENT_AXES = Set.of("StatementEquityComponentsAxis", "PartnerCapitalComponentsAxis",
            "PartnerTypeOfPartnersCapitalAccountAxis");
    private static final Set<String> PREFERRED_MEMBERS = Set.of("us-gaap:PreferredStockMember",
            "us-gaap:PreferredStockIncludingAdditionalPaidInCapitalMember");
    private static final String GENERAL_PARTNER_MEMBER = "us-gaap:GeneralPartnerMember";
    private static final String PREFERRED_PARTNER_MEMBER = "us-gaap:PreferredPartnerMember";
    private static final Set<String> SERIES_AXES = Set.of("StatementClassOfStockAxis", "ClassOfStockAxis");
    private static final String LP_CLASS_AXIS = "LimitedPartnersCapitalAccountByClassAxis";
    private static final List<String> CARRY = List.of("PreferredStockIncludingAdditionalPaidInCapital",
            "PreferredStockIncludingAdditionalPaidInCapitalNetOfDiscount", "PreferredStockValue", "PreferredStockValueOutstanding");
    private static final String LIQUIDATION_VALUE = "PreferredStockLiquidationPreferenceValue";
    private static final String LIQUIDATION_PER_SHARE = "PreferredStockLiquidationPreference";
    private static final String PAR = "PreferredStockParOrStatedValuePerShare";
    private static final String SHARES = "PreferredStockSharesOutstanding";
    private static final List<String> NONCONTROLLING_PREFERRED = List.of("RedeemableNoncontrollingInterestEquityPreferredCarryingAmount",
            "MinorityInterestAmountRepresentedByPreferredStock", "TemporaryEquityCarryingAmountAttributableToParent",
            "TemporaryEquityCarryingAmountIncludingPortionAttributableToNoncontrollingInterests",
            "RedeemableNoncontrollingInterestEquityPreferredRedemptionValue");
    private static final String MEZZANINE_PARENT = "TemporaryEquityCarryingAmountAttributableToParent";
    private static final List<String> MEZZANINE = List.of(MEZZANINE_PARENT,
            "TemporaryEquityCarryingAmountIncludingPortionAttributableToNoncontrollingInterests");
    /** 경제 조건 — 이 중 하나라도 실리면 그 우선주는 액면 이상의 청구권을 가질 수 있다. */
    private static final List<String> TERMS = List.of(LIQUIDATION_PER_SHARE, "PreferredStockRedemptionPricePerShare",
            "PreferredStockConvertibleConversionRatio", "PreferredStockConversionPrice", "PreferredStockConvertibleConversionPrice",
            "PreferredStockDividendRatePercentage", "PreferredStockDividendRatePerDollarAmount", "PreferredStockRedemptionAmount",
            LIQUIDATION_VALUE);
    /** 전환 조건 — 보통주로 바뀌는 증권. */
    private static final List<String> CONVERSION = List.of("PreferredStockConvertibleConversionRatio", "PreferredStockConversionPrice",
            "PreferredStockConvertibleConversionPrice", "PreferredStockConvertibleSharesIssuable",
            "ConvertiblePreferredStockSharesIssuedUponConversion", "IncrementalCommonSharesAttributableToConversionOfPreferredStock");
    /** 선순위 조건 — 보통주보다 앞서는 청구권(청산가 · 배당률 · 상환가). */
    private static final List<String> SENIOR = List.of(LIQUIDATION_VALUE, "PreferredStockDividendRatePercentage",
            "PreferredStockDividendRatePerDollarAmount", "PreferredStockRedemptionPricePerShare");
    private static final List<String> RETIREMENT = List.of("PaymentsForRepurchaseOfPreferredStockAndPreferenceStock",
            "PaymentsForRedemptionOfPreferredStock", "StockRedeemedOrCalledDuringPeriodValue", "ConversionOfStockAmountConverted1");

    /**
     * 우선주 · LP 비보통 청구권의 신호 — 이 중 하나라도 {@code IssuerTimeline.DENOMINATOR_FROM} 뒤 공시에 0 아닌 값이 있으면 그 발행사의
     * 행 폼 공시 인스턴스를 받아 판정한다(계획 v1.1 3절). 낱말로 고르지 않고 원장에서 센 표준 개념을 적는다(현 편입 캐시 990 · 2026-10-26 ·
     * 권한 · 액면 · 주당 · 비율 · 가격 태그는 뺐다).
     */
    public static final List<String> SIGNAL = List.of("PreferredStockDividendsIncomeStatementImpact",
            "PaymentsOfDividendsPreferredStockAndPreferenceStock", "PreferredStockSharesIssued", "ProceedsFromIssuanceOfPreferredStockAndPreferenceStock",
            "PreferredStockSharesOutstanding", "DividendsPreferredStock", "PreferredStockValue", "DividendsPreferredStockCash",
            "PaymentsForRepurchaseOfPreferredStockAndPreferenceStock", "ProceedsFromIssuanceOfConvertiblePreferredStock",
            "PreferredStockDividendsAndOtherAdjustments", "IncrementalCommonSharesAttributableToConversionOfPreferredStock",
            "PreferredStockLiquidationPreferenceValue", "PreferredStockLiquidationPreference", "PaymentsForRepurchaseOfRedeemablePreferredStock",
            "PreferredStockRedemptionPremium", "ProceedsFromIssuanceOfRedeemableConvertiblePreferredStock",
            "AdjustmentsToAdditionalPaidInCapitalIncreaseInCarryingAmountOfRedeemablePreferredStock",
            "StockAndWarrantsIssuedDuringPeriodValuePreferredStockAndWarrants", "PreferredStockValueOutstanding",
            "RedeemablePreferredStockDividends", "ConvertiblePreferredStockSharesIssuedUponConversion", "DividendsPreferredStockStock",
            "ConvertiblePreferredDividendsNetOfTax", "OtherPreferredStockDividendsAndAdjustments",
            "PaymentsForRepurchaseOfRedeemableConvertiblePreferredStock", "PreferredStockAccretionOfRedemptionDiscount",
            "ProceedsFromIssuanceOfRedeemablePreferredStock", "ConvertiblePreferredStockConvertedToOtherSecurities", "PreferredStockNoParValue",
            "PreferredStockRedemptionAmount", "PaymentsForRepurchaseOfConvertiblePreferredStock", "ProceedsFromRepurchaseOfRedeemablePreferredStock",
            "PreferredStockIncludingAdditionalPaidInCapital", "DividendsPreferredStockPaidinkind", "PreferredStockDividendsShares",
            "IncreaseInCarryingAmountOfRedeemablePreferredStock", "PreferredStockRedemptionDiscount", "PreferredStockConvertibleSharesIssuable",
            "PreferredStockIncludingAdditionalPaidInCapitalNetOfDiscount", "PreferredStockAmountOfPreferredDividendsInArrears",
            "ConvertiblePreferredStockSharesReservedForFutureIssuance", "GeneralPartnersCapitalAccount",
            "MinorityInterestAmountRepresentedByPreferredStock", "ProceedsFromIssuanceOfPreferredLimitedPartnersUnits",
            "ConvertiblePreferredStockNonredeemableOrRedeemableIssuerOptionValue", "MandatorilyRedeemablePreferredStockFairValueDisclosure",
            "PreferredStockConversionsInducements", "PreferredUnitsIssued", "PreferredUnitsPreferredPartnersCapitalAccounts",
            "ProceedsFromIssuanceOfPreferredStockPreferenceStockAndWarrants", "AmountOfDilutiveSecuritiesESOPConvertiblePreferredStock",
            "PreferredStockDiscountOnShares", "PreferredStockRedemptionAmountFutureRedeemableSecurities", "PreferredUnitsOutstanding",
            "PreconfirmationPreferredStock", "GeneralPartnersOfferingCosts", "PreferredStockCapitalSharesReservedForFutureIssuance",
            "GeneralPartnersCapitalAccountUnitsIssued", "GeneralPartnersCapitalAccountUnitsOutstanding", "AdditionalPaidInCapitalPreferredStock",
            // LP 자본 — 보통 아닌 LP 클래스(PAA 우선 단위)는 LP 클래스 축에만 실린다(companyfacts에 안 보인다).
            "PartnersCapital", "PartnersCapitalIncludingPortionAttributableToNoncontrollingInterest", "LimitedPartnersCapitalAccount");
    /** IFRS 우선주 신호 — 이 사다리는 us-gaap 개념만 읽으므로 그 발행사의 행은 못 정함이 된다(계획 v1.1 3절 · 캐시 1,218 중 1발행사). */
    public static final List<String> SIGNAL_IFRS = List.of("ProceedsFromIssueOfPreferenceShares", "CumulativePreferenceDividendsNotRecognised",
            "AdjustmentToProfitLossForPreferenceShareDividends");

    /**
     * @param nonCommon     보통주가 아닌 청구권(지배 자본에서 뺀다) — {@code null}이면 못 정함
     * @param ncavNonCommon NCAV가 유동자산에서 더 빼는 영구자본 청구권(우선주 · 우선 단위 · 보통 아닌 LP 클래스 — GP 자본은 보통주보다 앞서지
     *                      않아 뺀다). 메자닌은 재무 행의 「자산 − 부채 − 자본」 간극으로 계산기가 따로 뺀다.
     */
    public record Value(Double nonCommon, Double ncavNonCommon, String branch) {
        public boolean decided() {
            return nonCommon != null;
        }
    }

    public record Key(String accession, LocalDate date) {
    }

    /** 공시 하나 · 날짜 하나의 재료(시안 {@code rule6.py}의 {@code extract}). */
    record Material(String accession, LocalDate date, double equity, Double component, double componentHalf, Double carry,
                    double carryHalf, Double seriesCarry, Double liquidation, Double seriesLiquidation, Double seriesLiquidationProduct,
                    Double liquidationPerShare, Double par, Double shares, Map<String, Double> seriesShares, Double generalPartner,
                    Double preferredUnits, double lpNonCommon, List<Double> noncontrollingPreferred, Double mezzanine,
                    Double mezzanineParent, Double liabilities, Double liabilitiesAndEquity, Double equityIncluding, boolean valueZero, boolean terms,
                    boolean conversion, boolean senior, double preferredDividends, double preferredDividendsHalf, double preferredDividendsAnnual,
                    double noncontrollingIncome, boolean retired) {

        Double sharesTotal() {
            if (shares != null) {
                return shares;
            }
            return seriesShares.isEmpty() ? null : seriesShares.values().stream().mapToDouble(Double::doubleValue).sum();
        }
    }

    private CommonEquity() {
    }

    /**
     * 발행사 하나의 공시 인스턴스 재료 전부를 판정한다 — 발행사 단위 조건(경제 · 전환 · 선순위 조건의 공시 이력)과 이어받기가 공시를 건너
     * 서므로 한 번에 받는다.
     */
    public static Map<Key, Value> judge(Map<String, ClassFacts> facts) {
        List<Material> materials = new ArrayList<>();
        facts.forEach((accession, f) -> {
            if (f != null) {
                for (LocalDate date : balanceDates(f)) {
                    Material m = material(accession, date, f);
                    if (m != null) {
                        materials.add(m);
                    }
                }
            }
        });
        boolean terms = materials.stream().anyMatch(m -> m.terms() || m.preferredDividends() > 0);
        boolean conversion = materials.stream().anyMatch(Material::conversion);
        boolean senior = materials.stream().anyMatch(m -> m.senior() || m.preferredDividends() > 0);
        Map<Key, Value> out = new LinkedHashMap<>();
        Map<Key, Material> byKey = new LinkedHashMap<>();
        for (Material m : materials) {
            Key key = new Key(m.accession(), m.date());
            byKey.put(key, m);
            out.put(key, decide(m, terms, conversion, senior));
        }
        carry(out, byKey);
        sameDateShares(out, byKey);
        immaterial(out, byKey);
        return out;
    }

    /**
     * 같은 날짜에 다른 공시가 우선주 주식수 &gt; 0을 실었으면 그 날짜의 「뺄 것 0」은 받지 않는다 — 우선주를 APIC에 싣는 발행사(FLR)는 성분이 늘
     * 0이라, 주식수가 없는 뒤 공시 비교열에서 「명시 0」이 나온다. 그 날짜에 주식수를 실은 공시가 정한 값을 옮기고, 없으면 못 정함이다.
     */
    static void sameDateShares(Map<Key, Value> out, Map<Key, Material> byKey) {
        Map<LocalDate, Value> held = new HashMap<>();
        Set<LocalDate> heldUndecided = new LinkedHashSet<>();
        byKey.forEach((k, m) -> {
            if (positive(m.sharesTotal())) {
                Value v = out.get(k);
                if (v.decided()) {
                    held.putIfAbsent(k.date(), v);
                } else {
                    heldUndecided.add(k.date());
                }
            }
        });
        Set<String> nothing = Set.of("zero", "none", "mezz", "subsid", "retired");
        for (Map.Entry<Key, Value> e : new ArrayList<>(out.entrySet())) {
            LocalDate date = e.getKey().date();
            if (!nothing.contains(e.getValue().branch()) || positive(byKey.get(e.getKey()).sharesTotal())) {
                continue;
            }
            Value source = held.get(date);
            if (source != null) {
                out.put(e.getKey(), decided(source.nonCommon(), source.ncavNonCommon(), "carry"));
            } else if (heldUndecided.contains(date)) {
                out.put(e.getKey(), open("open"));
            }
        }
    }

    /** 그 공시가 축 없는 지배 자본(없으면 비지배 포함 자본)을 실은 날짜 전부 — 10-K는 앞 해 대차대조표도 싣는다. */
    static Set<LocalDate> balanceDates(ClassFacts f) {
        Set<LocalDate> dates = new LinkedHashSet<>();
        for (String concept : concat(EQUITY_TOTAL, EQUITY_INCLUDING)) {
            f.equityOf(concept).keySet().forEach(s -> {
                if (s.start() == null && ClassFacts.AXISLESS.equals(s.key())) {
                    dates.add(s.end());
                }
            });
        }
        return dates;
    }

    static Material material(String accession, LocalDate date, ClassFacts f) {
        Double equity = first(f, EQUITY_TOTAL, date);
        Double including = first(f, EQUITY_INCLUDING, date);
        if (equity == null) {
            if (including == null) {
                return null;
            }
            Double nci = first(f, NONCONTROLLING, date);
            equity = including - (nci == null ? 0 : nci);
        }
        Double component = null;
        double componentHalf = 0;
        Double generalPartner = axisless(f, "GeneralPartnersCapitalAccount", date);
        Double preferredUnits = axisless(f, "PreferredUnitsPreferredPartnersCapitalAccounts", date);
        for (String concept : concat(EQUITY_TOTAL, EQUITY_INCLUDING)) {
            for (Map.Entry<ClassFacts.Slot, ClassFacts.Measured> e : f.equityOf(concept).entrySet()) {
                ClassFacts.Slot s = e.getKey();
                if (s.start() != null || !date.equals(s.end()) || ClassFacts.AXISLESS.equals(s.key())) {
                    continue;
                }
                String axis = axis(s.key());
                String member = member(s.key());
                if (!COMPONENT_AXES.contains(axis)) {
                    continue;
                }
                double v = e.getValue().value();
                if (PREFERRED_MEMBERS.contains(member) && (component == null || v > component)) {
                    component = v;
                    componentHalf = e.getValue().halfWidth();
                } else if (GENERAL_PARTNER_MEMBER.equals(member) && generalPartner == null) {
                    generalPartner = v;
                } else if (PREFERRED_PARTNER_MEMBER.equals(member) && preferredUnits == null) {
                    preferredUnits = v;
                }
            }
        }
        Double carry = null;
        double carryHalf = 0;
        for (String concept : CARRY) {
            ClassFacts.Measured m = measured(f, concept, ClassFacts.AXISLESS, date);
            if (m != null && m.value() != 0 && (carry == null || m.value() > carry)) {
                carry = m.value();
                carryHalf = m.halfWidth();
            }
        }
        Double seriesCarry = null;
        for (String concept : CARRY) {
            double sum = seriesSum(f, concept, date);
            if (sum != 0 && (seriesCarry == null || sum > seriesCarry)) {
                seriesCarry = sum;
            }
        }
        Map<String, Double> seriesShares = series(f, SHARES, date);
        Map<String, Double> seriesPerShare = seriesAnyDate(f, LIQUIDATION_PER_SHARE);
        double product = 0;
        for (Map.Entry<String, Double> e : seriesShares.entrySet()) {
            Double per = seriesPerShare.get(e.getKey());
            if (per != null && e.getValue() != 0) {
                product += per * e.getValue();
            }
        }
        double seriesLiquidation = seriesSum(f, LIQUIDATION_VALUE, date);
        Map<String, Double> lpClasses = new HashMap<>();
        Set<String> lpWeighted = new LinkedHashSet<>();
        f.equityOf("LimitedPartnersCapitalAccount").forEach((s, m) -> {
            if (s.start() == null && date.equals(s.end()) && LP_CLASS_AXIS.equals(axis(s.key()))) {
                lpClasses.putIfAbsent(member(s.key()), m.value());
            }
        });
        f.equityOf("WeightedAverageLimitedPartnershipUnitsOutstanding").keySet().forEach(s -> {
            if (s.start() != null && LP_CLASS_AXIS.equals(axis(s.key()))) {
                lpWeighted.add(member(s.key()));
            }
        });
        double lpNonCommon = 0;
        if (!lpClasses.isEmpty() && lpClasses.keySet().stream().anyMatch(lpWeighted::contains)) {
            for (Map.Entry<String, Double> e : lpClasses.entrySet()) {
                if (!lpWeighted.contains(e.getKey())) {
                    lpNonCommon += e.getValue();
                }
            }
        }
        List<Double> ncp = new ArrayList<>();
        for (String concept : NONCONTROLLING_PREFERRED) {
            Double v = axisless(f, concept, date);
            if (v != null && v != 0) {
                ncp.add(v);
            }
        }
        Double par = axisless(f, PAR, date);
        Double perShare = axisless(f, LIQUIDATION_PER_SHARE, date);
        boolean terms = false;
        boolean conversion = false;
        boolean senior = false;
        for (Map.Entry<String, LinkedHashMap<ClassFacts.Slot, ClassFacts.Measured>> e : f.equity().entrySet()) {
            String concept = e.getKey();
            for (Map.Entry<ClassFacts.Slot, ClassFacts.Measured> x : e.getValue().entrySet()) {
                String key = x.getKey().key();
                boolean seriesOrAxisless = ClassFacts.AXISLESS.equals(key) || SERIES_AXES.contains(axis(key));
                double v = x.getValue().value();
                if (TERMS.contains(concept) && seriesOrAxisless) {
                    terms = true;
                }
                if (CONVERSION.contains(concept) && v != 0) {
                    conversion = true;
                }
                if (SENIOR.contains(concept) && v != 0) {
                    senior = true;
                }
                if (LIQUIDATION_PER_SHARE.equals(concept) && v != 0 && (par == null || v > par * 1.01)) {
                    senior = true;
                }
            }
        }
        double[] dividends = periodEnding(f, ClassFactReader.PREFERRED_DIVIDENDS, date);
        double[] nci = periodEnding(f, List.of(ClassFactReader.NONCONTROLLING), date);
        boolean retired = false;
        for (String concept : RETIREMENT) {
            for (Map.Entry<ClassFacts.Slot, ClassFacts.Measured> x : f.equityOf(concept).entrySet()) {
                ClassFacts.Slot s = x.getKey();
                if (s.start() != null && date.equals(s.end()) && x.getValue().value() != 0
                        && (ClassFacts.AXISLESS.equals(s.key()) || PREFERRED_MEMBERS.contains(member(s.key())))) {
                    retired = true;
                }
            }
        }
        return new Material(accession, date, equity, component, componentHalf, carry, carryHalf, seriesCarry,
                axisless(f, LIQUIDATION_VALUE, date), seriesLiquidation == 0 ? null : seriesLiquidation, product == 0 ? null : product,
                perShare, par, axisless(f, SHARES, date), seriesShares, generalPartner, preferredUnits, lpNonCommon, ncp,
                first(f, MEZZANINE, date), axisless(f, MEZZANINE_PARENT, date), axisless(f, "Liabilities", date),
                axisless(f, "LiabilitiesAndStockholdersEquity", date), including,
                Objects.equals(axisless(f, "PreferredStockValue", date), 0.0), terms, conversion, senior, dividends[0],
                dividends[1], dividends[2], nci[0], retired);
    }

    /** 시안 {@code rule6.py}의 {@code decide} — 공시 하나 · 날짜 하나. */
    static Value decide(Material m, boolean issuerTerms, boolean issuerConversion, boolean issuerSenior) {
        Double shares = m.sharesTotal();
        boolean seriesPositive = m.seriesShares().values().stream().anyMatch(v -> v > 0);
        if (nonZero(m.generalPartner()) || nonZero(m.preferredUnits()) || m.lpNonCommon() != 0) {
            double gp = m.generalPartner() == null ? 0 : m.generalPartner();
            double pu = m.preferredUnits() == null ? 0 : m.preferredUnits();
            return new Value(gp + pu + m.lpNonCommon(), pu + m.lpNonCommon(), "lp");
        }
        Double parTimesShares = m.par() != null && shares != null && shares != 0 ? m.par() * shares : null;
        Function<double[], Boolean> parLike = vh -> {
            double v = vh[0];
            double half = vh[1];
            if (parTimesShares != null && close(v, parTimesShares, half)) {
                return true;
            }
            return m.preferredDividends() > 0 && m.preferredDividends() - m.preferredDividendsHalf() > MAX_COUPON * (v + half);
        };
        double seriesCarry = m.seriesCarry() == null ? 0 : m.seriesCarry();
        double carry = m.carry() == null ? 0 : m.carry();
        double component = m.component() == null ? 0 : m.component();
        if (issuerConversion && !issuerSenior && m.preferredDividends() == 0
                && (positive(shares) || component != 0 || carry != 0 || seriesCarry != 0)) {
            return open("conv");
        }
        // 액면 값 명시 0 · 모회사 주식수 없음 · 성분 없음 · 그 기간 배당 없음 — 남의 청산가(NI 1,763M)는 버린다. 배당 조건이 없으면 PNC 10-Q처럼
        // 액면 0만 남은 공시가 0이 된다(우선주는 APIC에 있다).
        // 자본변동표 우선주 성분 명시 0도 같은 증인이다 — NI 2025는 성분 0인데 옛 시리즈 청산가(1,763M)가 그 날짜로 남아 실렸다.
        boolean explicitZero = (m.valueZero() || Objects.equals(m.component(), 0.0)) && !positive(shares) && component == 0
                && m.preferredDividends() == 0;
        if (explicitZero && carry == 0 && seriesCarry == 0) {
            return decided(0, 0, "zero");
        }
        // 성분은 같은 공시의 다른 우선주 증거(주식수 · 값 · 청산가 · 배당)가 받친다 — ZETA는 우선주가 없는데 APIC 열을
        // PreferredStockIncludingAdditionalPaidInCapitalMember로 실었다(1,997M · 자본 926M보다 크다).
        boolean corroborated = positive(shares) || carry != 0 || seriesCarry != 0 || nonZero(m.liquidation())
                || nonZero(m.seriesLiquidation()) || m.preferredDividends() > 0;
        if (component != 0 && corroborated && !parLike.apply(new double[]{component, m.componentHalf()})) {
            return decided(component, component, "stmt");
        }
        if (carry != 0 && !noncontrolling(carry, m) && !parLike.apply(new double[]{carry, m.carryHalf()})) {
            return decided(carry, carry, "tag");
        }
        List<Double> liquidation = new ArrayList<>();
        // 명시 합계(축 없음 · 시리즈)만 시리즈 장부가를 거부할 수 있다 — 주당 청산가 × 주식수는 상환된 시리즈의 남은 주식수까지 곱한다
        // (STT 2024-06: 곱 3,500M 대 시리즈 장부가 = 성분 2,468M).
        List<Double> statedTotals = new ArrayList<>();
        if (nonZero(m.liquidation())) {
            liquidation.add(m.liquidation());
            statedTotals.add(m.liquidation());
        } else if (nonZero(m.seriesLiquidation())) {
            liquidation.add(m.seriesLiquidation());
            statedTotals.add(m.seriesLiquidation());
        } else if (nonZero(m.seriesLiquidationProduct())) {
            liquidation.add(m.seriesLiquidationProduct());
        } else if (m.liquidationPerShare() != null && positive(shares) && m.seriesShares().isEmpty()) {
            liquidation.add(m.liquidationPerShare() * shares);
        }
        if (seriesCarry != 0 && !noncontrolling(seriesCarry, m) && !parLike.apply(new double[]{seriesCarry, 0})
                && (statedTotals.isEmpty() || statedTotals.stream().anyMatch(x -> close(seriesCarry, x, 0)
                || seriesCarry >= 0.9 * x && seriesCarry <= x))) {
            return decided(seriesCarry, seriesCarry, "sertag");
        }
        // 청산가의 유효성 — 그 기간 배당보다 작거나 액면 × 주식수보다 작은 「총액」은 주당 금액을 총액 태그에 실은 꼴이다.
        // 청구권은 부채 · 자본 합계를 못 넘는다(MS 2024-09 곱 9.8 × 10^15). 연 배당이 그 값의 25%를 넘으면 총액이 아니다(FLR 2022-06 시리즈
        // 청산가 96M 대 반기 배당 20M — 60만 주 × 1,000달러 우선주).
        liquidation.removeIf(x -> m.preferredDividendsAnnual() > MAX_COUPON * x
                || m.preferredDividends() > 0 && x < m.preferredDividends()
                || parTimesShares != null && x < parTimesShares
                || m.liabilitiesAndEquity() != null && x > m.liabilitiesAndEquity());
        Double gap = m.liabilitiesAndEquity() != null && m.liabilities() != null
                ? m.liabilitiesAndEquity() - m.liabilities() - (m.equityIncluding() != null ? m.equityIncluding() : m.equity())
                : null;
        List<Double> permanent = liquidation.stream().filter(x -> !mezzanine(x, gap, m)).toList();
        if (!permanent.isEmpty() && (positive(shares) || component != 0 || carry != 0)) {
            double max = permanent.stream().mapToDouble(Double::doubleValue).max().orElseThrow();
            double min = permanent.stream().mapToDouble(Double::doubleValue).min().orElseThrow();
            if (permanent.size() == 1 || max <= 1.02 * min) {
                return decided(max, max, "liq");
            }
            return open("liqconf");
        }
        if (!liquidation.isEmpty() && liquidation.stream().allMatch(x -> mezzanine(x, gap, m))) {
            return decided(0, 0, "mezz");
        }
        if ((m.shares() == null || m.shares() == 0) && !seriesPositive && component == 0 && carry == 0 && seriesCarry == 0
                && (Objects.equals(m.shares(), 0.0) || !m.seriesShares().isEmpty())) {
            return decided(0, 0, "zero");
        }
        if (positive(shares)) {
            if (!issuerTerms && m.preferredDividends() == 0) {
                double stated = Math.max(Math.max(component, carry), Math.max(seriesCarry, parTimesShares == null ? 0 : parTimesShares));
                return decided(stated, stated, "nominal");
            }
            return open("open");
        }
        if (m.preferredDividends() > 0 && m.noncontrollingIncome() != 0
                && close(m.preferredDividends(), m.noncontrollingIncome(), Math.max(m.preferredDividendsHalf(), 500_000))) {
            return decided(0, 0, "subsid");
        }
        if (m.preferredDividends() > 0 && m.retired()) {
            return decided(0, 0, "retired");
        }
        if (m.preferredDividends() > 0) {
            return open("divonly");
        }
        if (Objects.equals(m.shares(), 0.0)) {
            return decided(0, 0, "zero");
        }
        return decided(0, 0, "none");
    }

    /** 이어받기 — 못 정한 칸의 우선주 주식수가 같은 발행사의 다른 날짜(정한 값)와 같으면 장부가가 그대로다(발행 · 상환 · 전환으로만 움직인다). */
    static void carry(Map<Key, Value> out, Map<Key, Material> byKey) {
        Set<String> sources = Set.of("lp", "stmt", "tag", "sertag", "liq", "mezz", "subsid", "retired");
        Map<Key, Value> snapshot = new LinkedHashMap<>(out);
        for (Map.Entry<Key, Value> e : snapshot.entrySet()) {
            String branch = e.getValue().branch();
            Material m = byKey.get(e.getKey());
            Double shares = m.sharesTotal();
            if (!(branch.equals("open") || branch.equals("divonly")) || !positive(shares)) {
                continue;
            }
            snapshot.entrySet().stream()
                    .filter(x -> !x.getKey().equals(e.getKey()) && x.getValue().decided() && sources.contains(x.getValue().branch())
                            && shares.equals(byKey.get(x.getKey()).sharesTotal()))
                    .min(Comparator.comparingLong(x -> Math.abs(x.getKey().date().toEpochDay() - e.getKey().date().toEpochDay())))
                    .ifPresent(x -> {
                        out.put(e.getKey(), decided(x.getValue().nonCommon(), x.getValue().ncavNonCommon(), "carry"));
                    });
        }
    }

    /** 미미한 이웃 — 같은 발행사의 정한 N이 모두 그 칸 보통주 자본의 허용 오차 안이고 주식수가 안 늘었다. */
    static void immaterial(Map<Key, Value> out, Map<Key, Material> byKey) {
        List<Map.Entry<Key, Value>> decided = out.entrySet().stream()
                .filter(e -> e.getValue().decided() && !e.getValue().branch().equals("none")).toList();
        if (decided.isEmpty() || decided.stream().noneMatch(e -> e.getValue().nonCommon() > 0)) {
            return;
        }
        double max = decided.stream().mapToDouble(e -> e.getValue().nonCommon()).max().orElseThrow();
        double sharesMax = decided.stream().mapToDouble(e -> {
            Double s = byKey.get(e.getKey()).sharesTotal();
            return s == null ? 0 : s;
        }).max().orElseThrow();
        for (Map.Entry<Key, Value> e : new ArrayList<>(out.entrySet())) {
            String branch = e.getValue().branch();
            if (!(branch.equals("open") || branch.equals("divonly"))) {
                continue;
            }
            Material m = byKey.get(e.getKey());
            Double shares = m.sharesTotal();
            double common = m.equity() - max;
            if (common > 0 && max / common <= TOLERANCE && (shares == null || shares <= sharesMax)) {
                out.put(e.getKey(), decided(max, max, "immat"));
            }
        }
    }

    private static boolean noncontrolling(double v, Material m) {
        return m.noncontrollingPreferred().stream().anyMatch(n -> close(v, n, 0));
    }

    private static boolean mezzanine(double x, Double gap, Material m) {
        return gap != null && gap >= 0.5 * x || m.mezzanine() != null && close(x, m.mezzanine(), 0) || noncontrolling(x, m);
    }

    private static Value decided(double nonCommon, double ncav, String branch) {
        return new Value(nonCommon, ncav, branch);
    }

    private static Value open(String branch) {
        return new Value(null, null, branch);
    }

    static boolean close(double a, double b, double half) {
        return Math.abs(a - b) <= Math.max(half, 0.01 * Math.max(Math.abs(a), Math.abs(b)));
    }

    private static boolean nonZero(Double v) {
        return v != null && v != 0;
    }

    private static boolean positive(Double v) {
        return v != null && v > 0;
    }

    private static String axis(String key) {
        int i = key.indexOf('=');
        return i < 0 ? key : key.substring(0, i);
    }

    private static String member(String key) {
        int i = key.indexOf('=');
        return i < 0 ? key : key.substring(i + 1);
    }

    private static ClassFacts.Measured measured(ClassFacts f, String concept, String key, LocalDate date) {
        return f.equityOf(concept).get(new ClassFacts.Slot(key, null, date));
    }

    private static Double axisless(ClassFacts f, String concept, LocalDate date) {
        ClassFacts.Measured m = measured(f, concept, ClassFacts.AXISLESS, date);
        return m == null ? null : m.value();
    }

    private static Double first(ClassFacts f, List<String> concepts, LocalDate date) {
        for (String c : concepts) {
            Double v = axisless(f, c, date);
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    private static Map<String, Double> series(ClassFacts f, String concept, LocalDate date) {
        Map<String, Double> out = new LinkedHashMap<>();
        f.equityOf(concept).forEach((s, m) -> {
            if (s.start() == null && date.equals(s.end()) && SERIES_AXES.contains(axis(s.key()))) {
                out.putIfAbsent(member(s.key()), m.value());
            }
        });
        return out;
    }

    private static Map<String, Double> seriesAnyDate(ClassFacts f, String concept) {
        Map<String, Double> out = new LinkedHashMap<>();
        f.equityOf(concept).forEach((s, m) -> {
            if (SERIES_AXES.contains(axis(s.key()))) {
                out.put(member(s.key()), m.value());
            }
        });
        return out;
    }

    private static double seriesSum(ClassFacts f, String concept, LocalDate date) {
        return series(f, concept, date).values().stream().mapToDouble(Double::doubleValue).sum();
    }

    /** 축 없는 기간 팩트 중 그 날짜에 끝나는 것의 최대 절댓값 · 그 반폭 · 그 값의 연환산(하한 — 반폭을 뺀 값). */
    private static double[] periodEnding(ClassFacts f, List<String> concepts, LocalDate date) {
        double max = 0;
        double half = 0;
        double annual = 0;
        for (String c : concepts) {
            for (Map.Entry<ClassFacts.MemberPeriod, ClassFacts.Measured> e : f.of(c).entrySet()) {
                if (ClassFacts.AXISLESS.equals(e.getKey().member()) && date.equals(e.getKey().period().end())
                        && Math.abs(e.getValue().value()) > max) {
                    max = Math.abs(e.getValue().value());
                    half = e.getValue().halfWidth();
                    long days = Math.max(1, e.getKey().period().days());
                    annual = Math.max(0, max - half) * 365.0 / days;
                }
            }
        }
        return new double[]{max, half, annual};
    }

    @SafeVarargs
    private static List<String> concat(List<String>... lists) {
        List<String> out = new ArrayList<>();
        for (List<String> l : lists) {
            out.addAll(l);
        }
        return out;
    }
}
