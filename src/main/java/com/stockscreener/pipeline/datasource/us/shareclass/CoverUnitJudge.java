package com.stockscreener.pipeline.datasource.us.shareclass;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * 표지(dei {@code EntityCommonStockSharesOutstanding})의 단위 판정(ADR-0193 · 관문 뒤). 제출자의 inline XBRL {@code scale}이 틀려
 * companyfacts가 표지를 10^±3 · ±6배로 싣는 일이 있다(ADR-0126 — PKG 89,213,394,000 · HXL 81조). 그 표지는 「없는 표지」로 보고 표지
 * 없는 공시와 같은 사다리 다음 칸(같은 공시의 {@code CommonStockSharesOutstanding} → 기본 가중평균 → 희석 가중평균 · ADR-0186)을 낸다 —
 * 원장이 준 값을 고르는 것이지 배율을 곱해 고치는 것이 아니다(ADR-0189 · 2026-10-25 사용자 기준).
 *
 * <p>증인은 둘이다 — ① 그 공시 당기 기본 가중평균(EPS × n이 분자와 닫힐 때만) ② 앞서 보인 표지 넷의 중앙값(분할 반영 · 바뀐 표지는 바뀐
 * 값으로). 표지가
 * <ul>
 *   <li>어느 증인에서 단위만큼({@link #BAND}) 갈리고, 어느 증인과도 자릿수(10배 안)가 안 맞고, 확인 안 된 가중평균과도 τ_c 안에서 안
 *       맞으면 틀린 표지다. 사다리 칸 중 <b>모든 증인과 자릿수가 맞는 첫 값</b>으로 바꾼다 — 다음 칸이 같은 배수로 틀린 공시가 있다(CB 2010
 *       CSO 3.4×10^14 · BKH 2012). 맞는 칸이 없으면 「없음」이다.</li>
 *   <li>한 증인에서 단위만큼 갈려도 다른 증인과 자릿수가 맞으면 표지를 둔다 — n과 EPS가 함께 반대로 틀려 폐합이 맞는 공시(HL 2017-05
 *       395,370 × 70)와, 앞 표지 증인이 첫 공시들의 오태깅으로 오염된 공시(TFC 2009-11)다.</li>
 * </ul>
 * 표지가 앞 표지 증인과 자릿수가 맞는 공시는 「증인과 맞음」이다 — 그 공시의 n이 표지에서 단위만큼 갈리면 n이 틀린 쪽이다
 * ({@link IssuerTimeline} 단위 확인이 읽는다).
 *
 * <p>한 번만 계산한다 — 관문 켬 어댑터가 공시 보충 <b>뒤</b> 트리로 부르고, 결과를 재무 추출({@code SecEdgarFacts})과 클래스 축 입력
 * ({@code sec_share_class_input})에 함께 싣는다. 두 쪽이 저마다 부르면 입력(보충 · IFRS 가중평균 별칭)이 달라 결정이 갈린다.
 */
public final class CoverUnitJudge {

    /** 단위 계단에서 이만큼(log10) 안이면 단위 오태깅이다 — 판정 술어로 잰 정직한 쪽 가장 가까운 값 0.142(ALLY 2014 · 앞 표지) · 0.21(QXO
     * 2024 · n), 오태깅 쪽 가장 먼 값 0.081(HBAN 2011 · CCL 2021) 사이(탐색 {@code HAND-REPAIR-PLAN-1025.md} v3.1). */
    static final double BAND = 0.1;

    private static final int[] STEPS = {-6, -3, 3, 6};
    /** 사다리 다음 칸 — 관문 켬 주식수 후보 순서에서 dei를 뺀 것({@code SecEdgarColumn.GATED_SHARES_ORDER}). */
    private static final List<String> LADDER = List.of("CommonStockSharesOutstanding", "WeightedAverageNumberOfSharesOutstandingBasic",
            "WeightedAverageNumberOfDilutedSharesOutstanding");
    /** 클래스 축 입력 트리({@code facts} 아래)에 결과를 싣는 자리 — 택소노미 이름과 안 겹친다. */
    static final String NODE = "_coverUnit";

    /**
     * @param replaced  표지를 바꾼 공시 → 사다리 값
     * @param absent    표지가 틀렸고 맞는 칸이 없는 공시
     * @param witnessed 표지가 앞 표지 증인과 자릿수가 맞는 공시
     * @param original  바꾼 · 없앤 공시의 원래 표지
     */
    public record Result(Map<String, Double> replaced, Set<String> absent, Set<String> witnessed, Map<String, Double> original) {
        public static final Result NONE = new Result(Map.of(), Set.of(), Set.of(), Map.of());

        public Result {
            replaced = Map.copyOf(replaced);
            absent = Set.copyOf(absent);
            witnessed = Set.copyOf(witnessed);
            original = Map.copyOf(original);
        }

        /** 표지를 버린(바꾸거나 없앤) 공시인가. */
        public boolean judged(String accession) {
            return replaced.containsKey(accession) || absent.contains(accession);
        }

        ObjectNode toJson() {
            ObjectNode out = JsonNodeFactory.instance.objectNode();
            ObjectNode r = out.putObject("replaced");
            replaced.forEach(r::put);
            ArrayNode a = out.putArray("absent");
            absent.stream().sorted().forEach(a::add);
            ArrayNode w = out.putArray("witnessed");
            witnessed.stream().sorted().forEach(w::add);
            ObjectNode o = out.putObject("original");
            original.forEach(o::put);
            return out;
        }

        static Result fromJson(JsonNode node) {
            if (node == null || node.isMissingNode() || node.isNull()) {
                return NONE;
            }
            Map<String, Double> replaced = new LinkedHashMap<>();
            node.path("replaced").properties().forEach(e -> replaced.put(e.getKey(), e.getValue().asDouble()));
            Set<String> absent = new LinkedHashSet<>();
            node.path("absent").forEach(x -> absent.add(x.asString()));
            Set<String> witnessed = new LinkedHashSet<>();
            node.path("witnessed").forEach(x -> witnessed.add(x.asString()));
            Map<String, Double> original = new LinkedHashMap<>();
            node.path("original").properties().forEach(e -> original.put(e.getKey(), e.getValue().asDouble()));
            return new Result(replaced, absent, witnessed, original);
        }
    }

    private CoverUnitJudge() {
    }

    /** 클래스 축 입력 트리(응답 루트 꼴 · {@code facts} 아래)에 결정을 싣는다 — {@link IssuerFacts#fromCompanyFacts}가 읽는다. */
    public static void attach(ObjectNode root, Result result) {
        ((ObjectNode) root.path("facts")).set(NODE, result.toJson());
    }

    /**
     * @param cf     증인 재료(관문 켬 클래스 축 틀 · 공시 보충 뒤)
     * @param gaap   사다리 재료 — 재무 열 틀({@code TaxonomyFrame.facts()})의 us-gaap. IFRS 가중평균 별칭이 없는 쪽이다 — 그 별칭을 주식수로
     *               내지 않는다(ADR-0182 · 표지와 10배 넘게 갈리는 쌍 22/636)
     * @param splits 보통주 분할 · 병합 계수(기업행위 원장)
     */
    public static Result judge(IssuerFacts cf, JsonNode gaap, List<IssuerTimeline.Split> splits) {
        Map<String, List<Double>> ladder = ladder(gaap);
        Map<String, Double> replaced = new LinkedHashMap<>();
        Set<String> absent = new LinkedHashSet<>();
        Set<String> witnessed = new LinkedHashSet<>();
        Map<String, Double> original = new LinkedHashMap<>();
        List<String> order = cf.coverShares().keySet().stream().filter(a -> cf.filings().containsKey(a))
                .sorted(Comparator.comparing((String a) -> cf.filings().get(a).filed()).thenComparing(a -> a)).toList();
        List<Map.Entry<LocalDate, Double>> shown = new ArrayList<>();
        for (String a : order) {
            Double c = cf.coverShares().get(a);
            if (c == null || c <= 0) {
                continue;
            }
            IssuerFacts.Filing filing = cf.filings().get(a);
            ClassFacts.Period own = filing.form().startsWith("10-Q") || filing.form().startsWith("6-K")
                    ? IssuerTimeline.quarterPeriod(cf, cf.weighted(), a)
                    : IssuerTimeline.currentPeriod(cf, cf.weighted(), a, 350, 380);
            IssuerFacts.Key key = own == null ? null : new IssuerFacts.Key(a, own);
            Double w = key == null ? null : cf.weighted().get(key);
            boolean confirmed = w != null && w > 0
                    && Boolean.TRUE.equals(IssuerTimeline.closes(cf, key, w, IssuerTimeline.PROXY_TOLERANCE));
            Double prior = prior(shown, splits, filing.filed());
            List<Double> witnesses = new ArrayList<>();
            if (confirmed) {
                witnesses.add(w);
            }
            if (prior != null) {
                witnesses.add(prior);
                if (IssuerTimeline.digit(c, prior)) {
                    witnessed.add(a);
                }
            }
            boolean shifted = witnesses.stream().anyMatch(x -> unitShifted(c, x));
            boolean agrees = witnesses.stream().anyMatch(x -> IssuerTimeline.digit(c, x));
            double kept = c;
            if (shifted && !agrees && !(w != null && w > 0 && IssuerTimeline.coverOk(c, w))) {
                original.put(a, c);
                Double next = null;
                for (double v : ladder.getOrDefault(a, List.of())) {
                    if (v > 0 && witnesses.stream().allMatch(x -> IssuerTimeline.digit(v, x))) {
                        next = v;
                        break;
                    }
                }
                if (next != null) {
                    replaced.put(a, next);
                    kept = next;
                } else {
                    absent.add(a);
                    kept = 0;
                }
            }
            if (kept > 0) {
                shown.add(Map.entry(filing.filed(), kept));
            }
        }
        return new Result(replaced, absent, witnessed, original);
    }

    /** {@code v}가 증인 {@code w}에서 단위(10^±3 · ±6)만큼 {@link #BAND} 안으로 갈렸는가. */
    static boolean unitShifted(double v, double w) {
        if (v <= 0 || w <= 0) {
            return false;
        }
        double l = Math.log10(v / w);
        for (int k : STEPS) {
            if (Math.abs(l - k) <= BAND) {
                return true;
            }
        }
        return false;
    }

    private static Double prior(List<Map.Entry<LocalDate, Double>> shown, List<IssuerTimeline.Split> splits, LocalDate filed) {
        if (shown.isEmpty()) {
            return null;
        }
        List<Double> window = new ArrayList<>();
        for (Map.Entry<LocalDate, Double> x : shown.subList(Math.max(0, shown.size() - 4), shown.size())) {
            window.add(x.getValue() * IssuerTimeline.splitFactor(splits, x.getKey(), filed));
        }
        window.sort(Comparator.naturalOrder());
        return window.get(window.size() / 2);
    }

    /** 공시마다 사다리 칸 값 — 칸 순서대로, 같은 공시의 가장 늦은 기말 · 가장 짧은 창({@code SecEdgarFacts.factFor} 관문 켬과 같은 술어). */
    static Map<String, List<Double>> ladder(JsonNode gaap) {
        Map<String, List<Double>> out = new LinkedHashMap<>();
        for (String tag : LADDER) {
            Map<String, JsonNode> best = new LinkedHashMap<>();
            for (JsonNode x : gaap.path(tag).path("units").path("shares")) {
                String a = x.path("accn").asString();
                JsonNode b = best.get(a);
                if (b == null || later(x, b)) {
                    best.put(a, x);
                }
            }
            best.forEach((a, x) -> out.computeIfAbsent(a, z -> new ArrayList<>()).add(x.path("val").asDouble()));
        }
        return out;
    }

    /** 기말이 늦거나, 같으면 시작이 늦은(짧은 창) 쪽 — 시작이 없는 시점 값이 가장 늦다(그 술어의 {@code LocalDate.MAX}). */
    private static boolean later(JsonNode x, JsonNode b) {
        int byEnd = x.path("end").asString().compareTo(b.path("end").asString());
        if (byEnd != 0) {
            return byEnd > 0;
        }
        String xs = x.path("start").asString("9999-12-31");
        String bs = b.path("start").asString("9999-12-31");
        return xs.compareTo(bs) > 0;
    }
}
