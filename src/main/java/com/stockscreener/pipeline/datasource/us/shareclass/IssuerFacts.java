package com.stockscreener.pipeline.datasource.us.shareclass;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 발행사 하나의 companyfacts에서 클래스 축 판정이 쓰는 축 없는 값(탐색 `SHARES-CLASS-PLAN-V5-1015.md` · 시뮬레이터 {@code cfdata}).
 *
 * <p>공시 목록(접수번호 → 폼 · 제출일)은 us-gaap 개념을 처음 실은 줄을 따른다. 분모·EPS·분자는 (접수번호 · 기간)마다
 * 태그 순서에서 처음 나온 값이다. 표지 주식수는 접수번호마다 마지막 값이다.
 */
public record IssuerFacts(Map<String, Filing> filings,
                          Map<Key, Double> weighted,
                          Map<Key, Double> eps,
                          Map<Key, Double> declaredNumerator,
                          Map<Key, Double> proxyNumerator,
                          Map<String, Double> coverShares,
                          Witnesses witnesses,
                          Numerators numerators,
                          boolean preferredSignal) {

    /**
     * @param preferredSignal 우선주 · LP 비보통 청구권 신호({@link CommonEquity#SIGNAL})가 {@code IssuerTimeline.DENOMINATOR_FROM} 뒤 공시에
     *                        0 아닌 값으로 실렸는가 — 거짓이면 보통주 자본은 지배 자본 그대로다(그 발행사의 행은 판정 {@code none})
     */
    public IssuerFacts(Map<String, Filing> filings, Map<Key, Double> weighted, Map<Key, Double> eps, Map<Key, Double> declaredNumerator,
                       Map<Key, Double> proxyNumerator, Map<String, Double> coverShares, Witnesses witnesses, Numerators numerators) {
        this(filings, weighted, eps, declaredNumerator, proxyNumerator, coverShares, witnesses, numerators, false);
    }

    public IssuerFacts(Map<String, Filing> filings, Map<Key, Double> weighted, Map<Key, Double> eps,
                       Map<Key, Double> declaredNumerator, Map<Key, Double> proxyNumerator, Map<String, Double> coverShares) {
        this(filings, weighted, eps, declaredNumerator, proxyNumerator, coverShares, Witnesses.NONE, Numerators.NONE);
    }

    public IssuerFacts(Map<String, Filing> filings, Map<Key, Double> weighted, Map<Key, Double> eps,
                       Map<Key, Double> declaredNumerator, Map<Key, Double> proxyNumerator, Map<String, Double> coverShares,
                       Witnesses witnesses) {
        this(filings, weighted, eps, declaredNumerator, proxyNumerator, coverShares, witnesses, Numerators.NONE);
    }

    /**
     * 지금 경로 분모를 확인 · 대신하는 재료(ADR-0189). {@code laterTags}: 같은 (공시, 기간)에 태그 순서 뒤에 실린 가중평균 값 —
     * 앞 태그 값이 단위 오태깅이면 다음 값을 본다(AMCR {@code WeightedAverageNumberOfSharesIssuedBasic}). {@code diluted}: 희석
     * 가중평균 — 원장이 기본 = 희석을 선언한 기간만 쓴다. {@code proForma}: {@code WeightedAverageBasicSharesOutstandingProForma} —
     * 그 기간의 기본 EPS와 엄격히 닫힐 때만 쓴다(VTRS). {@code basicAndDilutedEps}: EPS가 {@code EarningsPerShareBasicAndDiluted}로 실린
     * 기간. {@code epsDiluted} · {@code dilutedNumerator}: 희석 EPS와 희석 분자.
     */
    public record Witnesses(Map<Key, List<Double>> laterTags, Map<Key, Double> diluted, Map<Key, Double> proForma,
                            Map<Key, Double> epsDiluted, Map<Key, Double> dilutedNumerator, java.util.Set<Key> basicAndDilutedEps,
                            java.util.Set<String> witnessedCovers, java.util.Set<String> absentCovers) {
        public static final Witnesses NONE = new Witnesses(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), java.util.Set.of());

        /**
         * @param witnessedCovers 표지가 앞 표지 증인과 자릿수가 맞는 공시({@link CoverUnitJudge}) — 그 공시의 n이 표지에서 단위만큼 갈리면
         *                        n이 틀린 쪽이다
         * @param absentCovers    표지가 단위로 틀렸고 사다리에 맞는 칸이 없는 공시 — 판정이 그 공시를 숨긴다(원장이 주식수를 확인해 주지 않음)
         */
        public Witnesses(Map<Key, List<Double>> laterTags, Map<Key, Double> diluted, Map<Key, Double> proForma,
                         Map<Key, Double> epsDiluted, Map<Key, Double> dilutedNumerator, java.util.Set<Key> basicAndDilutedEps) {
            this(laterTags, diluted, proForma, epsDiluted, dilutedNumerator, basicAndDilutedEps, java.util.Set.of(), java.util.Set.of());
        }
    }

    /**
     * 보통주 귀속 이익을 정하는 재료(탐색 {@code PERSHARE-NUM-PLAN-1024.md} v2.1 · {@link CommonIncome}). 분모 판정은 이것을 안 읽는다.
     * {@code totalEps}: 총 EPS(기본 · 기본겸희석 · LP 단위 기본)를 태그 순서 첫 값으로 — {@link #eps}는 계속영업 EPS가 섞인다
     * ({@code putIfAbsent} · 280키). {@code continuingEps}: 계속영업 기본 EPS. {@code netIncome}: {@code NetIncomeLoss}만(ProfitLoss 아님).
     * 배분 태그는 묶음마다 하나 — 우선주 배당(우선순위 첫 값) · 참가증권(총액, 없으면 미배분) · GP 배분 — 대체 태그를 더하지 않는다(AIG [8M, 8M]).
     */
    public record Numerators(Map<Key, Double> totalEps, Map<Key, Double> continuingEps, Map<Key, Double> netIncome,
                             Map<Key, Double> profitLoss, Map<Key, Double> noncontrolling, Map<Key, Double> preferred,
                             Map<Key, Double> participating, Map<Key, Double> generalPartner, Map<Key, Double> limitedPartners) {
        public static final Numerators NONE = new Numerators(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                Map.of(), Map.of());
    }

    public record Filing(String form, LocalDate filed) {
    }

    /** 인스턴스를 받는 구간의 시작 — 그 앞의 축 없는 표지 없는 공시는 판정이 기준 n만 잇는다(드라이런 1과 같은 경계). */
    public static final LocalDate INSTANCE_FROM = LocalDate.of(2020, 9, 1);

    /** 판정하는 정기 공시의 폼(정정 · 6-K 제외 — 시뮬레이터와 같다). */
    public static final java.util.Set<String> PERIODIC_FORMS = java.util.Set.of("10-K", "10-Q", "20-F", "40-F");

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<String> WEIGHTED = List.of("WeightedAverageNumberOfSharesOutstandingBasic",
            "WeightedAverageNumberOfShareOutstandingBasicAndDiluted");
    /**
     * 판독기 목록({@link ClassFactReader#WEIGHTED_TAGS})에서 위 둘 밖의 태그 — 그 공시의 축 없는 표지 주식수로 확인될 때만 받는다
     * (|log10(가중평균 ÷ 표지)| ≤ {@link IssuerTimeline#COVER_WITNESS_LOG}). companyfacts에는 축이 없어 그 태그가 무엇을 셌는지 모른다 —
     * REG의 축 없는 LP 단위는 운영 파트너십의 외부 조합원 단위(표지의 0.5%)이고 NRP · VANI는 천 단위로 잘못 실었다(2026-10-23
     * 탐색 {@code LP-UNIT-PLAN-1023.md} 반증). 확인 안 되면 비운다 — 틀린 n보다 값 없음이다.
     */
    private static final List<String> WEIGHTED_CONFIRMED = ClassFactReader.WEIGHTED_TAGS.stream()
            .filter(t -> !WEIGHTED.contains(t)).toList();
    private static final List<String> PROXY = List.of("NetIncomeLoss", "ProfitLoss");
    static final String DILUTED = "WeightedAverageNumberOfDilutedSharesOutstanding";
    static final String PRO_FORMA = "WeightedAverageBasicSharesOutstandingProForma";
    static final String EPS_DILUTED = "EarningsPerShareDiluted";
    static final String EPS_BASIC_AND_DILUTED = "EarningsPerShareBasicAndDiluted";
    static final String DILUTED_NUMERATOR = "NetIncomeLossAvailableToCommonStockholdersDiluted";
    /** 가지친 트리에서 공시 목록을 싣는 자리 — us-gaap 개념 이름과 안 겹친다(밑줄로 시작하는 개념 없음). */
    private static final String FILINGS = "_filings";

    public record Key(String accession, ClassFacts.Period period) {
    }

    public static IssuerFacts fromCompanyFacts(JsonNode root) {
        JsonNode gaap = root.path("facts").path("us-gaap");
        Map<String, Filing> filings = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> concept : gaap.properties()) {
            for (Map.Entry<String, JsonNode> unit : concept.getValue().path("units").properties()) {
                for (JsonNode x : unit.getValue()) {
                    filings.putIfAbsent(x.path("accn").asString(),
                            new Filing(x.path("form").asString(""), LocalDate.parse(x.path("filed").asString())));
                }
            }
        }
        Map<String, Double> cover = new LinkedHashMap<>();
        for (JsonNode x : root.path("facts").path("dei").path(ClassFactReader.SHARES_OUTSTANDING).path("units").path("shares")) {
            cover.put(x.path("accn").asString(), x.path("val").asDouble());
        }
        // 표지 단위 판정(ADR-0193) — 어댑터가 공시 보충 뒤에 한 번 낸 결정을 입는다. 단위가 틀린 표지는 사다리 다음 칸이 대신하고(이 뒤의
        // 확인 가중평균 · 판정 · 보통주 몫이 모두 그 값을 본다), 맞는 칸이 없는 표지는 열쇠를 둔 채 판정이 숨긴다.
        CoverUnitJudge.Result coverUnit = CoverUnitJudge.Result.fromJson(root.path("facts").path(CoverUnitJudge.NODE));
        // 결정은 보충 뒤 트리로 냈다 — companyfacts 표지가 있는 공시에만 입힌다(보충 공시에 표지 열쇠를 새로 만들면 판정 사건 종류가 바뀐다).
        coverUnit.replaced().forEach((a, v) -> cover.computeIfPresent(a, (k, old) -> v));
        Map<Key, Double> weighted = pick(gaap, WEIGHTED, "shares");
        pick(gaap, WEIGHTED_CONFIRMED, "shares").forEach((k, v) -> {
            Double c = cover.get(k.accession());
            if (c != null && c > 0 && v > 0 && Math.abs(Math.log10(v / c)) <= IssuerTimeline.COVER_WITNESS_LOG) {
                weighted.putIfAbsent(k, v);
            }
        });
        Map<Key, List<Double>> later = new LinkedHashMap<>();
        for (String tag : WEIGHTED) {
            for (JsonNode x : gaap.path(tag).path("units").path("shares")) {
                if (x.has("start")) {
                    Key k = new Key(x.path("accn").asString(), new ClassFacts.Period(
                            LocalDate.parse(x.path("start").asString()), LocalDate.parse(x.path("end").asString())));
                    double v = x.path("val").asDouble();
                    List<Double> seen = later.computeIfAbsent(k, z -> new java.util.ArrayList<>());
                    if (!seen.contains(v)) {
                        seen.add(v);
                    }
                }
            }
        }
        later.replaceAll((k, v) -> v.size() > 1 ? List.copyOf(v.subList(1, v.size())) : List.of());
        later.values().removeIf(List::isEmpty);
        java.util.Set<Key> basicAndDiluted = new java.util.HashSet<>(pick(gaap, List.of(EPS_BASIC_AND_DILUTED), "USD/shares").keySet());
        basicAndDiluted.removeAll(pick(gaap, List.of("EarningsPerShareBasic"), "USD/shares").keySet());
        return new IssuerFacts(filings,
                weighted,
                pick(gaap, ClassFactReader.EPS_TAGS, "USD/shares"),
                pick(gaap, List.of(ClassFactReader.DECLARED_NUMERATOR), "USD"),
                pick(gaap, PROXY, "USD"),
                cover,
                new Witnesses(later, pick(gaap, List.of(DILUTED), "shares"), pick(gaap, List.of(PRO_FORMA), "shares"),
                        pick(gaap, List.of(EPS_DILUTED), "USD/shares"), pick(gaap, List.of(DILUTED_NUMERATOR), "USD"),
                        basicAndDiluted, coverUnit.witnessed(), coverUnit.absent()),
                new Numerators(pick(gaap, ClassFactReader.TOTAL_EPS, "USD/shares"), pick(gaap, ClassFactReader.CONTINUING_EPS, "USD/shares"),
                        pick(gaap, List.of("NetIncomeLoss"), "USD"), pick(gaap, List.of("ProfitLoss"), "USD"),
                        pick(gaap, List.of(ClassFactReader.NONCONTROLLING), "USD"), pick(gaap, ClassFactReader.PREFERRED_DIVIDENDS, "USD"),
                        pick(gaap, ClassFactReader.PARTICIPATING, "USD"), pick(gaap, List.of(ClassFactReader.GENERAL_PARTNER), "USD"),
                        pick(gaap, List.of(ClassFactReader.LIMITED_PARTNERS), "USD")),
                preferredSignal(root.path("facts")));
    }

    /** {@link CommonEquity#SIGNAL} · {@link CommonEquity#SIGNAL_IFRS} 중 하나가 {@code DENOMINATOR_FROM} 뒤 공시에 0 아닌 값으로 실렸는가. */
    static boolean preferredSignal(JsonNode facts) {
        for (String taxonomy : List.of("us-gaap", "ifrs-full")) {
            for (String tag : "us-gaap".equals(taxonomy) ? CommonEquity.SIGNAL : CommonEquity.SIGNAL_IFRS) {
                for (Map.Entry<String, JsonNode> unit : facts.path(taxonomy).path(tag).path("units").properties()) {
                    for (JsonNode x : unit.getValue()) {
                        if (x.path("val").asDouble() != 0 && x.hasNonNull("filed")
                                && !LocalDate.parse(x.path("filed").asString()).isBefore(IssuerTimeline.DENOMINATOR_FROM)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    /**
     * 그 공시의 당기 기말 — 판정 재료의 기간 사실 중 제출일 이하에서 가장 늦은 기말(ADR-0189). 지금 경로 분모는 이 기말의 기간만 받는다 —
     * 당기 값이 없으면 같은 공시의 전년 비교열이 「최신」이 되어 그 공시의 n으로 나갔다(VTRS 10-K 2024 · 2025 · CHRD 2021 · EXE · VAL).
     */
    public LocalDate currentEnd(String accession) {
        Filing filing = filings.get(accession);
        LocalDate best = null;
        for (Map<Key, Double> m : List.of(weighted, eps, declaredNumerator, proxyNumerator, witnesses.diluted(),
                witnesses.proForma())) {
            for (Key k : m.keySet()) {
                if (k.accession().equals(accession) && (filing == null || !k.period().end().isAfter(filing.filed()))
                        && (best == null || k.period().end().isAfter(best))) {
                    best = k.period().end();
                }
            }
        }
        return best;
    }

    /**
     * companyfacts를 이 판정이 읽는 것만 남긴 트리로 가지친다 — 고르기 <b>전</b>의 원 항목 그대로다(sec_share_class_input).
     * 고르기 규칙을 바꿔도 다시 받지 않고 다시 판정할 수 있게. 공시 목록은 전 개념의 첫 등장 순서를 {@value #FILINGS}에 싣는다.
     * {@code fromCompanyFacts(prune(x))}는 {@code fromCompanyFacts(x)}와 같다(시험이 묻는다).
     */
    public static ObjectNode prune(JsonNode root) {
        if (!root.path("facts").isObject()) {
            // MUST NOT: 응답의 facts 노드를 루트로 알고 가지쳐 빈 트리로 저장하지 않는다 — 판정이 「공시 0」으로 조용히 끝나고 아무도
            // 모른다(드라이런 3에서 그랬다). 루트에 facts 객체가 있는지로 가른다 — us-gaap가 없는 정상 응답(dei만 싣는 펀드 ·
            // MXE)은 공시 0이 사실이다(후보 손 FR 드라이런 10-16: us-gaap 유무로 가르자 그 종목의 재무 적재가 통째로 실패했다).
            throw new IllegalArgumentException("companyfacts 응답 루트가 아니다(facts 객체가 없다) — 응답의 facts 노드를 넘겼는가");
        }
        JsonNode gaap = root.path("facts").path("us-gaap");
        ObjectNode out = MAPPER.createObjectNode();
        ObjectNode facts = out.putObject("facts");
        ObjectNode prunedGaap = facts.putObject("us-gaap");
        ArrayNode filings = prunedGaap.putObject(FILINGS).putObject("units").putArray("filing");
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Map.Entry<String, JsonNode> concept : gaap.properties()) {
            for (Map.Entry<String, JsonNode> unit : concept.getValue().path("units").properties()) {
                for (JsonNode x : unit.getValue()) {
                    String accn = x.path("accn").asString();
                    if (seen.add(accn)) {
                        filings.addObject().put("accn", accn).put("form", x.path("form").asString(""))
                                .put("filed", x.path("filed").asString());
                    }
                }
            }
        }
        for (String tag : concat(WEIGHTED, WEIGHTED_CONFIRMED, ClassFactReader.EPS_TAGS, List.of(ClassFactReader.DECLARED_NUMERATOR), PROXY,
                List.of(DILUTED, PRO_FORMA, EPS_DILUTED, EPS_BASIC_AND_DILUTED, DILUTED_NUMERATOR), ClassFactReader.NUMERATOR_MATERIAL,
                CommonEquity.SIGNAL)) {
            if (gaap.has(tag)) {
                prunedGaap.set(tag, gaap.get(tag));
            }
        }
        JsonNode ifrs = root.path("facts").path("ifrs-full");
        ObjectNode prunedIfrs = null;
        for (String tag : CommonEquity.SIGNAL_IFRS) {
            if (ifrs.has(tag)) {
                if (prunedIfrs == null) {
                    prunedIfrs = facts.putObject("ifrs-full");
                }
                prunedIfrs.set(tag, ifrs.get(tag));
            }
        }
        JsonNode cover = root.path("facts").path("dei").path(ClassFactReader.SHARES_OUTSTANDING);
        if (!cover.isMissingNode()) {
            facts.putObject("dei").set(ClassFactReader.SHARES_OUTSTANDING, cover);
        }
        return out;
    }

    /**
     * companyfacts가 빠뜨려 공시 보충(ADR-0173)으로 실린 정기 공시를 공시 목록에 더한다(이미 있으면 그대로). 그 공시는 companyfacts에
     * 축 없는 값이 없으므로 앵커 후보가 되고 인스턴스로 판정된다 — 안 더하면 판정이 없어 클래스 축 줄에서 「미판정 = 숨김」으로
     * 영영 남는다(드라이런 3: {@code V} 2026-07-29 10-Q).
     */
    public IssuerFacts withFilings(Map<String, Filing> extra) {
        Map<String, Filing> merged = new LinkedHashMap<>(filings);
        extra.forEach(merged::putIfAbsent);
        return new IssuerFacts(merged, weighted, eps, declaredNumerator, proxyNumerator, coverShares, witnesses, numerators, preferredSignal);
    }

    /** 분모 값만 바꾼 사본(지금 경로 재료를 고친 판 · {@code IssuerTimeline}). */
    public IssuerFacts withWeighted(Map<Key, Double> replaced) {
        return new IssuerFacts(filings, replaced, eps, declaredNumerator, proxyNumerator, coverShares, witnesses, numerators, preferredSignal);
    }

    /**
     * 클래스 축 앵커 후보 — {@link #INSTANCE_FROM} 뒤 정기 공시 중 축 없는 표지 주식수가 없는 것. 받기({@code ShareClassCollector})와
     * 유도({@code ShareClassDeriver})가 같은 집합을 본다.
     */
    public java.util.Set<String> anchorCandidates() {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        filings.forEach((accession, filing) -> {
            if (PERIODIC_FORMS.contains(filing.form()) && !filing.filed().isBefore(INSTANCE_FROM)
                    && !coverShares.containsKey(accession)) {
                out.add(accession);
            }
        });
        return out;
    }

    @SafeVarargs
    private static List<String> concat(List<String>... lists) {
        List<String> out = new java.util.ArrayList<>();
        for (List<String> l : lists) {
            out.addAll(l);
        }
        return out;
    }

    private static Map<Key, Double> pick(JsonNode gaap, List<String> tags, String unit) {
        Map<Key, Double> out = new LinkedHashMap<>();
        for (String tag : tags) {
            for (JsonNode x : gaap.path(tag).path("units").path(unit)) {
                if (x.has("start")) {
                    out.putIfAbsent(new Key(x.path("accn").asString(), new ClassFacts.Period(
                            LocalDate.parse(x.path("start").asString()), LocalDate.parse(x.path("end").asString()))),
                            x.path("val").asDouble());
                }
            }
        }
        return out;
    }
}
