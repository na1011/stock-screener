package com.stockscreener.pipeline.datasource.us;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.time.LocalDate;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * <b>미국 관문 켬의 companyfacts 틀</b> — {@link SecEdgarFacts}가 읽기 전에 {@code facts} 노드를 한 번 고쳐 짓는다(ADR-0182).
 *
 * <p>네 가지를 한다. 전부 <b>공시(접수번호) 단위</b>다 — 공시 하나는 한 회계기준 · 한 표시 통화로 작성되는데 companyfacts가
 * 개념마다 펼쳐 놓아서 그 묶음이 안 보인다.
 * <ol>
 *   <li><b>택소노미 하나</b>: 공시마다 {@code us-gaap}과 {@code ifrs-full} 중 팩트가 많은 쪽을 그 공시의 기준으로 정하고 다른 쪽
 *       팩트를 버린다. IFRS 공시 안에 섞인 {@code us-gaap} 태그가 후보 우선순위로 IFRS 값을 이기던 자리다(실측: 182 IFRS 발행사 중
 *       6곳 · {@code LTM} 2018 자산 2.70B 대 IFRS 20.08B).</li>
 *   <li><b>표시 통화 하나</b>: 공시마다 3자 금액 단위의 팩트 수가 가장 많은 단위를 표시 통화로 정하고 다른 금액 단위(편의환산 ·
 *       주석의 외화)를 버린다. 주당 값은 {@code <표시 통화>/shares}만 남긴다. 지금 {@code Slot}이 단위를 안 가려 마지막 단위가
 *       덮던 자리다(실측: IFRS 182 중 125가 다통화 공시를 낸다 · 동률 0 · 편입 1,218에서 바뀌는 슬롯 3).</li>
 *   <li><b>전환의 다리</b>: 발행사가 회계기준을 바꿨는데 새 기준의 가장 이른 자산 기말이 옛 기준의 마지막 자산 기말보다 뒤면
 *       (새 기준 첫 공시의 비교 재무상태표가 옛 기준 마지막 해에 안 닿는다) 옛 기준 공시를 통째로 버린다. 같은 CIK가 다른 실체의
 *       공시를 이어받은 자리다(실측: {@code FLUT} — 2015~2019 40-F는 옛 등록인 Stars Group · Amaya의 재무 · 2020~2023 공백).
 *       전환기 7종목(CRH · CLS · TEAM · SSRM · IREN · BNT · SMMT)은 재작성 비교값으로 겹쳐 안 걸린다.</li>
 *   <li><b>IFRS 별칭</b>: 남은 {@code ifrs-full} 팩트를 <b>같은 뜻의 {@code us-gaap} 후보 이름 아래</b> 싣는다
 *       ({@link #ALIASES}). 컬럼 후보가 아니라 열쇠에 싣는 이유: {@code chooseTags}가 라벨 앵커 공시의 태그에 고정하므로 컬럼에
 *       따로 두면 전환기 뒤 us-gaap 재작성 판을 못 읽고, 유도 함수(지배 순이익 · 세전 · 지배 자본 · Q4)가 us-gaap 이름으로 짜여 있다.</li>
 * </ol>
 *
 * <p><b>MUST NOT: IFRS 주식수 개념(가중평균 · 주석 주식수)을 별칭으로 싣지 않는다.</b> 같은 연차 공시 안에서 표지와 10배 넘게
 * 갈리는 쌍이 가중평균 22/636 · 주석 주식수는 2배 넘게 38/279다 — 표지와 같은 뜻의 재료가 아니다. IFRS 발행사의 주식수는 표지
 * ({@code dei})와 클래스 축(인스턴스의 차원 표지)만 준다.
 *
 * <p><b>MUST NOT: {@code FinanceCosts}를 이자비용으로 싣지 않는다.</b> 은행형 영업이익 재료이고 리스 이자 · 충당부채 할인 상각까지
 * 담는다. 은행 재료 넷이 IFRS에 표준 개념으로 없어 그 갈래는 어차피 안 켜진다.
 */
final class TaxonomyFrame {

    static final String US_GAAP = "us-gaap";
    static final String IFRS = "ifrs-full";
    private static final String DEI = "dei";

    private static final Set<String> ANNUAL_FORMS = Set.of("10-K", "10-K/A", "20-F", "20-F/A", "40-F", "40-F/A");

    /** 비지배가 그 공시에 있다는 표지 — 하나라도 있으면 지배 귀속을 총에서 추론하지 않는다(IAS 1은 비지배를 본문 한 줄로 요구한다). */
    private static final Set<String> NONCONTROLLING = Set.of("NoncontrollingInterests",
            "ProfitLossAttributableToNoncontrollingInterests", "ComprehensiveIncomeAttributableToNoncontrollingInterests");

    /** 별칭 하나. {@code unlessPresent}가 그 공시에 있으면 싣지 않는다 · {@code onlyWithoutNoncontrolling}이면 비지배 없는 공시에만. */
    record Alias(String ifrs, String usGaap, String unlessPresent, boolean onlyWithoutNoncontrolling) {
        static Alias of(String ifrs, String usGaap) {
            return new Alias(ifrs, usGaap, null, false);
        }
    }

    /**
     * IFRS 개념 → 같은 뜻의 us-gaap 후보 이름. 뜻 대조와 근거는 탐색 {@code explore/universe/ifrs/IFRS-PLAN-1022.md}의 「별칭 표」 절이다.
     *
     * <p>조건부 셋: 계약 매출은 {@code Revenue}가 없는 공시에서만 매출이 된다 — 광산사는 잠정가격 조정이 음수라 계약 매출이 IAS 1
     * 합계보다 크고(21칸 · 6발행사) 최댓값 경쟁에 넣으면 합계가 아닌 값이 이긴다. 비지배가 없는 공시의 {@code ProfitLoss}·{@code Equity}는
     * 곧 지배 귀속이다(2021 뒤 IFRS 공시 616 중 지배 태그 없이 총만 낸 것 279).
     */
    static final List<Alias> ALIASES = List.of(
            Alias.of("Revenue", "Revenues"),
            new Alias("RevenueFromContractsWithCustomers", "RevenueFromContractWithCustomerExcludingAssessedTax",
                    "Revenue", false),
            Alias.of("CostOfSales", "CostOfSales"),
            Alias.of("GrossProfit", "GrossProfit"),
            Alias.of("ProfitLossFromOperatingActivities", "OperatingIncomeLoss"),
            Alias.of("ProfitLoss", "ProfitLoss"),
            Alias.of("ProfitLossAttributableToOwnersOfParent", "NetIncomeLoss"),
            new Alias("ProfitLoss", "NetIncomeLoss", "ProfitLossAttributableToOwnersOfParent", true),
            Alias.of("ProfitLossAttributableToOrdinaryEquityHoldersOfParentEntity",
                    "NetIncomeLossAvailableToCommonStockholdersBasic"),
            Alias.of("ProfitLossAttributableToNoncontrollingInterests", "NetIncomeLossAttributableToNoncontrollingInterest"),
            Alias.of("ProfitLossBeforeTax",
                    "IncomeLossFromContinuingOperationsBeforeIncomeTaxesExtraordinaryItemsNoncontrollingInterest"),
            Alias.of("ShareOfProfitLossOfAssociatesAndJointVenturesAccountedForUsingEquityMethod",
                    "IncomeLossFromEquityMethodInvestments"),
            Alias.of("ProfitLossFromContinuingOperations",
                    "IncomeLossFromContinuingOperationsIncludingPortionAttributableToNoncontrollingInterest"),
            Alias.of("ProfitLossFromDiscontinuedOperations", "IncomeLossFromDiscontinuedOperationsNetOfTax"),
            Alias.of("IncomeTaxExpenseContinuingOperations", "IncomeTaxExpenseBenefit"),
            Alias.of("CashFlowsFromUsedInOperatingActivities", "NetCashProvidedByUsedInOperatingActivities"),
            Alias.of("CashFlowsFromUsedInOperatingActivitiesContinuingOperations",
                    "NetCashProvidedByUsedInOperatingActivitiesContinuingOperations"),
            Alias.of("CashFlowsFromUsedInOperatingActivitiesDiscontinuedOperations",
                    "CashProvidedByUsedInOperatingActivitiesDiscontinuedOperations"),
            Alias.of("PurchaseOfPropertyPlantAndEquipmentClassifiedAsInvestingActivities",
                    "PaymentsToAcquirePropertyPlantAndEquipment"),
            Alias.of("Assets", "Assets"),
            Alias.of("Liabilities", "Liabilities"),
            Alias.of("Equity", "StockholdersEquityIncludingPortionAttributableToNoncontrollingInterest"),
            Alias.of("EquityAttributableToOwnersOfParent", "StockholdersEquity"),
            new Alias("Equity", "StockholdersEquity", "EquityAttributableToOwnersOfParent", true),
            Alias.of("NoncontrollingInterests", "MinorityInterest"),
            Alias.of("CurrentAssets", "AssetsCurrent"),
            Alias.of("CurrentLiabilities", "LiabilitiesCurrent"),
            Alias.of("RetainedEarnings", "RetainedEarningsAccumulatedDeficit"),
            Alias.of("CashAndCashEquivalents", "CashAndCashEquivalentsAtCarryingValue"),
            Alias.of("Inventories", "InventoryNet"),
            Alias.of("BasicEarningsLossPerShare", "EarningsPerShareBasic"),
            Alias.of("DilutedEarningsLossPerShare", "EarningsPerShareDiluted"));

    /**
     * @param facts                 고쳐 지은 {@code facts} 노드({@code dei} · {@code us-gaap}만)
     * @param latestAnnualCurrency  남은 연차 공시 중 가장 늦게 낸 것의 표시 통화(연차 공시가 없으면 null)
     * @param droppedAccessions     전환의 다리가 끊겨 버린 옛 기준 공시
     */
    record Result(ObjectNode facts, String latestAnnualCurrency, Set<String> droppedAccessions) {
    }

    private record Filing(String form, LocalDate filed) {
    }

    private TaxonomyFrame() {
    }

    static Result of(JsonNode facts) {
        Map<String, Map<String, Integer>> taxonomyCounts = new HashMap<>();
        Map<String, Map<String, Integer>> currencyCounts = new HashMap<>();
        Map<String, Filing> filings = new HashMap<>();
        Map<String, Set<String>> ifrsConcepts = new HashMap<>();
        for (String taxonomy : List.of(US_GAAP, IFRS)) {
            for (Map.Entry<String, JsonNode> concept : facts.path(taxonomy).properties()) {
                for (Map.Entry<String, JsonNode> unit : concept.getValue().path("units").properties()) {
                    boolean money = isCurrency(unit.getKey());
                    for (JsonNode x : unit.getValue()) {
                        String accn = x.path("accn").asString("");
                        taxonomyCounts.computeIfAbsent(accn, a -> new HashMap<>()).merge(taxonomy, 1, Integer::sum);
                        if (money) {
                            currencyCounts.computeIfAbsent(accn, a -> new HashMap<>()).merge(unit.getKey(), 1, Integer::sum);
                        }
                        filings.putIfAbsent(accn, new Filing(x.path("form").asString(""), date(x.path("filed"))));
                        if (IFRS.equals(taxonomy)) {
                            ifrsConcepts.computeIfAbsent(accn, a -> new HashSet<>()).add(concept.getKey());
                        }
                    }
                }
            }
        }
        Map<String, String> taxonomyOf = new HashMap<>();
        taxonomyCounts.forEach((accn, counts) -> taxonomyOf.put(accn,
                counts.getOrDefault(IFRS, 0) > counts.getOrDefault(US_GAAP, 0) ? IFRS : US_GAAP));
        Map<String, String> currencyOf = new HashMap<>();
        currencyCounts.forEach((accn, counts) -> currencyOf.put(accn, majority(counts)));
        Set<String> dropped = brokenBridge(facts, taxonomyOf, filings);

        ObjectNode out = JsonNodeFactory.instance.objectNode();
        ObjectNode dei = out.putObject(DEI);
        for (Map.Entry<String, JsonNode> concept : facts.path(DEI).properties()) {
            for (Map.Entry<String, JsonNode> unit : concept.getValue().path("units").properties()) {
                for (JsonNode x : unit.getValue()) {
                    if (!dropped.contains(x.path("accn").asString(""))) {
                        target(dei, concept.getKey(), unit.getKey()).add(x);
                    }
                }
            }
        }
        ObjectNode gaap = out.putObject(US_GAAP);
        for (Map.Entry<String, JsonNode> concept : facts.path(US_GAAP).properties()) {
            copy(concept.getValue(), gaap, concept.getKey(), US_GAAP, taxonomyOf, currencyOf, dropped, null, null);
        }
        for (Alias alias : ALIASES) {
            JsonNode concept = facts.path(IFRS).path(alias.ifrs());
            if (concept.isMissingNode()) {
                continue;
            }
            copy(concept, gaap, alias.usGaap(), IFRS, taxonomyOf, currencyOf, dropped, alias, ifrsConcepts);
        }
        return new Result(out, latestAnnualCurrency(filings, currencyOf, dropped), Collections.unmodifiableSet(dropped));
    }

    /** {@code source}의 팩트 중 그 공시의 기준이 {@code taxonomy}이고 단위가 표시 통화에 맞는 것을 {@code gaap[name]}에 싣는다. */
    private static void copy(JsonNode source, ObjectNode gaap, String name, String taxonomy,
                             Map<String, String> taxonomyOf, Map<String, String> currencyOf, Set<String> dropped,
                             Alias alias, Map<String, Set<String>> ifrsConcepts) {
        for (Map.Entry<String, JsonNode> unit : source.path("units").properties()) {
            for (JsonNode x : unit.getValue()) {
                String accn = x.path("accn").asString("");
                if (dropped.contains(accn) || !taxonomy.equals(taxonomyOf.get(accn))
                        || !unitFits(unit.getKey(), currencyOf.get(accn))) {
                    continue;
                }
                if (alias != null) {
                    Set<String> present = ifrsConcepts.getOrDefault(accn, Set.of());
                    if (alias.unlessPresent() != null && present.contains(alias.unlessPresent())) {
                        continue;
                    }
                    if (alias.onlyWithoutNoncontrolling() && present.stream().anyMatch(NONCONTROLLING::contains)) {
                        continue;
                    }
                }
                target(gaap, name, unit.getKey()).add(x);
            }
        }
    }

    /**
     * 옛 기준 공시를 버려야 하는가. 두 기준 모두 연차 공시가 있고, 새 기준(마지막 연차를 더 늦게 낸 쪽)의 가장 이른 자산 기말이
     * 옛 기준의 마지막 자산 기말보다 뒤일 때 옛 기준의 공시 전부.
     */
    private static Set<String> brokenBridge(JsonNode facts, Map<String, String> taxonomyOf, Map<String, Filing> filings) {
        Map<String, LocalDate> lastAnnual = new HashMap<>();
        taxonomyOf.forEach((accn, taxonomy) -> {
            Filing filing = filings.get(accn);
            if (filing != null && filing.filed() != null && ANNUAL_FORMS.contains(filing.form())) {
                lastAnnual.merge(taxonomy, filing.filed(), (a, b) -> a.isAfter(b) ? a : b);
            }
        });
        if (lastAnnual.size() < 2) {
            return new HashSet<>();
        }
        String newer = lastAnnual.get(IFRS).isAfter(lastAnnual.get(US_GAAP)) ? IFRS : US_GAAP;
        String older = IFRS.equals(newer) ? US_GAAP : IFRS;
        LocalDate oldLast = null;
        LocalDate newFirst = null;
        for (String taxonomy : List.of(US_GAAP, IFRS)) {
            for (Map.Entry<String, JsonNode> unit : facts.path(taxonomy).path("Assets").path("units").properties()) {
                for (JsonNode x : unit.getValue()) {
                    String accn = x.path("accn").asString("");
                    LocalDate end = date(x.path("end"));
                    if (end == null || !taxonomy.equals(taxonomyOf.get(accn))) {
                        continue;
                    }
                    if (older.equals(taxonomy) && (oldLast == null || end.isAfter(oldLast))) {
                        oldLast = end;
                    } else if (newer.equals(taxonomy) && (newFirst == null || end.isBefore(newFirst))) {
                        newFirst = end;
                    }
                }
            }
        }
        Set<String> dropped = new HashSet<>();
        if (oldLast != null && newFirst != null && newFirst.isAfter(oldLast)) {
            taxonomyOf.forEach((accn, taxonomy) -> {
                if (older.equals(taxonomy)) {
                    dropped.add(accn);
                }
            });
        }
        return dropped;
    }

    private static String latestAnnualCurrency(Map<String, Filing> filings, Map<String, String> currencyOf,
                                               Set<String> dropped) {
        String latest = null;
        LocalDate latestFiled = null;
        for (Map.Entry<String, Filing> e : filings.entrySet()) {
            Filing filing = e.getValue();
            String currency = currencyOf.get(e.getKey());
            if (currency == null || dropped.contains(e.getKey()) || filing.filed() == null
                    || !ANNUAL_FORMS.contains(filing.form())) {
                continue;
            }
            if (latestFiled == null || filing.filed().isAfter(latestFiled)
                    || (filing.filed().equals(latestFiled) && e.getKey().compareTo(latest) > 0)) {
                latestFiled = filing.filed();
                latest = e.getKey();
            }
        }
        return latest == null ? null : currencyOf.get(latest);
    }

    /** 가장 많은 단위. 동률이면 USD, 그다음 이름순 — 실측 동률 0이라 순서만 결정적으로 둔다. */
    private static String majority(Map<String, Integer> counts) {
        String best = null;
        int bestCount = -1;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            int c = e.getValue();
            if (c > bestCount || (c == bestCount && preferred(e.getKey(), best))) {
                best = e.getKey();
                bestCount = c;
            }
        }
        return best;
    }

    private static boolean preferred(String candidate, String current) {
        if ("USD".equals(candidate)) {
            return true;
        }
        return !"USD".equals(current) && candidate.compareTo(current) < 0;
    }

    /** 금액 단위는 표시 통화만 · 주당 값은 {@code <표시 통화>/shares}만 · 나머지(shares · pure …)는 그대로. */
    private static boolean unitFits(String unit, String currency) {
        if (isCurrency(unit)) {
            return unit.equals(currency);
        }
        int slash = unit.indexOf('/');
        if (slash == 3 && isCurrency(unit.substring(0, 3))) {
            return currency == null || unit.startsWith(currency + "/");
        }
        return true;
    }

    private static boolean isCurrency(String unit) {
        return unit.length() == 3 && unit.chars().allMatch(c -> c >= 'A' && c <= 'Z');
    }

    private static ArrayNode target(ObjectNode taxonomy, String concept, String unit) {
        ObjectNode node = taxonomy.has(concept) ? (ObjectNode) taxonomy.get(concept) : taxonomy.putObject(concept);
        ObjectNode units = node.has("units") ? (ObjectNode) node.get("units") : node.putObject("units");
        return units.has(unit) ? (ArrayNode) units.get(unit) : units.putArray(unit);
    }

    private static LocalDate date(JsonNode node) {
        String raw = node.asString("");
        return raw.isBlank() ? null : LocalDate.parse(raw);
    }
}
