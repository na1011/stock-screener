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
                          Map<String, Double> coverShares) {

    public record Filing(String form, LocalDate filed) {
    }

    /** 인스턴스를 받는 구간의 시작 — 그 앞의 축 없는 표지 없는 공시는 판정이 기준 n만 잇는다(드라이런 1과 같은 경계). */
    public static final LocalDate INSTANCE_FROM = LocalDate.of(2020, 9, 1);

    /** 판정하는 정기 공시의 폼(정정 · 6-K 제외 — 시뮬레이터와 같다). */
    public static final java.util.Set<String> PERIODIC_FORMS = java.util.Set.of("10-K", "10-Q", "20-F", "40-F");

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<String> WEIGHTED = List.of("WeightedAverageNumberOfSharesOutstandingBasic",
            "WeightedAverageNumberOfShareOutstandingBasicAndDiluted");
    private static final List<String> PROXY = List.of("NetIncomeLoss", "ProfitLoss");
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
        return new IssuerFacts(filings,
                pick(gaap, WEIGHTED, "shares"),
                pick(gaap, ClassFactReader.EPS_TAGS, "USD/shares"),
                pick(gaap, List.of(ClassFactReader.DECLARED_NUMERATOR), "USD"),
                pick(gaap, PROXY, "USD"),
                cover);
    }

    /**
     * companyfacts를 이 판정이 읽는 것만 남긴 트리로 가지친다 — 고르기 <b>전</b>의 원 항목 그대로다(sec_share_class_input).
     * 고르기 규칙을 바꿔도 다시 받지 않고 다시 판정할 수 있게. 공시 목록은 전 개념의 첫 등장 순서를 {@value #FILINGS}에 싣는다.
     * {@code fromCompanyFacts(prune(x))}는 {@code fromCompanyFacts(x)}와 같다(시험이 묻는다).
     */
    public static ObjectNode prune(JsonNode root) {
        if (!root.path("facts").path("us-gaap").isObject()) {
            // MUST NOT: 빈 트리로 가지쳐 저장하지 않는다 — 판정이 「공시 0」으로 조용히 끝나고 아무도 모른다(드라이런 3에서 그랬다).
            throw new IllegalArgumentException("companyfacts 응답 루트가 아니다(facts.us-gaap 객체가 없다) — 응답의 facts 노드를 넘겼는가");
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
        for (String tag : concat(WEIGHTED, ClassFactReader.EPS_TAGS, List.of(ClassFactReader.DECLARED_NUMERATOR), PROXY)) {
            if (gaap.has(tag)) {
                prunedGaap.set(tag, gaap.get(tag));
            }
        }
        JsonNode cover = root.path("facts").path("dei").path(ClassFactReader.SHARES_OUTSTANDING);
        if (!cover.isMissingNode()) {
            facts.putObject("dei").set(ClassFactReader.SHARES_OUTSTANDING, cover);
        }
        return out;
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
