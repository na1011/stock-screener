package com.stockscreener.pipeline.datasource.us.shareclass;

import tools.jackson.databind.JsonNode;

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
                pick(gaap, List.of("WeightedAverageNumberOfSharesOutstandingBasic",
                        "WeightedAverageNumberOfShareOutstandingBasicAndDiluted"), "shares"),
                pick(gaap, ClassFactReader.EPS_TAGS, "USD/shares"),
                pick(gaap, List.of(ClassFactReader.DECLARED_NUMERATOR), "USD"),
                pick(gaap, List.of("NetIncomeLoss", "ProfitLoss"), "USD"),
                cover);
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
