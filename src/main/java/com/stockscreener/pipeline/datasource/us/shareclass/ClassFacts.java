package com.stockscreener.pipeline.datasource.us.shareclass;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 공시 하나의 인스턴스에서 클래스 축 판정에 쓰는 팩트(ADR 예정 · 탐색 `SHARES-CLASS-PLAN-V5-1015.md` §1b·§10).
 *
 * <p>기간 팩트는 개념마다 <b>파일에 나온 순서</b>를 지킨다 — 판정이 「그 기간에 처음 나온 구성원」을 고르는 자리가 있어서다
 * (시뮬레이터 {@code rule5.py}와 같은 순서). 구성원은 파일에 적힌 QName 글자 그대로이고({@code us-gaap:CommonClassAMember}),
 * 축 없는 맥락은 {@link #AXISLESS}다. 접두어는 공시마다 다르지만 비교는 한 공시 안에서만 한다.
 *
 * @param durations 개념 → (구성원 · 기간) → 값. 기간이 있는 맥락만.
 * @param cover     표지 개념({@code EntityCommonStockSharesOutstanding} · {@code TradingSymbol} · {@code Security12bTitle}) → 구성원 → 글자
 */
public record ClassFacts(Map<String, LinkedHashMap<MemberPeriod, Measured>> durations,
                         Map<String, LinkedHashMap<String, String>> cover,
                         String documentType,
                         Map<String, LinkedHashMap<Slot, Measured>> equity) {

    public static final String AXISLESS = "-";

    public ClassFacts(Map<String, LinkedHashMap<MemberPeriod, Measured>> durations, Map<String, LinkedHashMap<String, String>> cover,
                      String documentType) {
        this(durations, cover, documentType, new LinkedHashMap<>());
    }

    /**
     * 보통주 자본 재료의 칸(탐색 {@code PERSHARE-DEN-PLAN-1026.md} v1.1) — 축 없으면 {@link #AXISLESS}, 축 하나면 {@code 축=구성원}.
     * 시점 팩트는 {@code start}가 null이다.
     */
    public record Slot(String key, LocalDate start, LocalDate end) {
    }

    public Map<Slot, Measured> equityOf(String concept) {
        return equity.getOrDefault(concept, new LinkedHashMap<>());
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public record Period(LocalDate start, LocalDate end) {
        /** 시작과 끝 사이 날 수(끝 − 시작 · 시뮬레이터의 {@code span}). */
        public long days() {
            return ChronoUnit.DAYS.between(start, end);
        }
    }

    public record MemberPeriod(String member, Period period) {
    }

    /** 값과 선언 정밀도에서 나온 반올림 반폭({@code decimals}가 없거나 {@code INF}면 0). */
    public record Measured(double value, double halfWidth) {
    }

    public Map<MemberPeriod, Measured> of(String concept) {
        return durations.getOrDefault(concept, new LinkedHashMap<>());
    }

    public Map<String, String> coverOf(String concept) {
        return cover.getOrDefault(concept, new LinkedHashMap<>());
    }

    /** 클래스 축 앵커 = 표지 주식수가 클래스 구성원에 실린 공시(§0-5). */
    public boolean isClassAnchor() {
        return coverOf(ClassFactReader.SHARES_OUTSTANDING).keySet().stream().anyMatch(m -> !AXISLESS.equals(m));
    }

    static String local(String member) {
        int i = member.indexOf(':');
        return i < 0 ? member : member.substring(i + 1);
    }

    /**
     * 저장 형태({@code sec_filing_class_fact.facts}). 순서를 지킨다 — 판정이 「그 기간에 처음 나온 구성원」을 고르는 자리가 있다.
     * 값은 {@code double}을 그대로 싣는다(가장 짧은 십진 표기 · 되읽으면 같은 비트).
     */
    public String toJson() {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("documentType", documentType);
        ObjectNode d = root.putObject("durations");
        durations.forEach((concept, facts) -> {
            ArrayNode rows = d.putArray(concept);
            facts.forEach((mp, m) -> rows.addArray().add(mp.member()).add(mp.period().start().toString())
                    .add(mp.period().end().toString()).add(m.value()).add(m.halfWidth()));
        });
        ObjectNode c = root.putObject("cover");
        cover.forEach((concept, members) -> {
            ArrayNode rows = c.putArray(concept);
            members.forEach((member, text) -> rows.addArray().add(member).add(text));
        });
        // 자본 재료가 없는 공시는 판 7 저장 형태와 같은 글자다 — 분모 · 분자 재료가 판 8에서도 그대로인지 맞대는 자리(계획 5 ⑥).
        if (!equity.isEmpty()) {
            ObjectNode q = root.putObject("equity");
            equity.forEach((concept, facts) -> {
                ArrayNode rows = q.putArray(concept);
                facts.forEach((slot, m) -> rows.addArray().add(slot.key()).add(slot.start() == null ? null : slot.start().toString())
                        .add(slot.end().toString()).add(m.value()).add(m.halfWidth()));
            });
        }
        return MAPPER.writeValueAsString(root);
    }

    public static ClassFacts fromJson(String json) {
        JsonNode root = MAPPER.readTree(json);
        Map<String, LinkedHashMap<MemberPeriod, Measured>> durations = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> e : root.path("durations").properties()) {
            LinkedHashMap<MemberPeriod, Measured> facts = new LinkedHashMap<>();
            for (JsonNode x : e.getValue()) {
                facts.put(new MemberPeriod(x.get(0).asString(), new Period(LocalDate.parse(x.get(1).asString()),
                        LocalDate.parse(x.get(2).asString()))), new Measured(x.get(3).asDouble(), x.get(4).asDouble()));
            }
            durations.put(e.getKey(), facts);
        }
        Map<String, LinkedHashMap<String, String>> cover = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> e : root.path("cover").properties()) {
            LinkedHashMap<String, String> members = new LinkedHashMap<>();
            for (JsonNode x : e.getValue()) {
                members.put(x.get(0).asString(), x.get(1).asString());
            }
            cover.put(e.getKey(), members);
        }
        Map<String, LinkedHashMap<Slot, Measured>> equity = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> e : root.path("equity").properties()) {
            LinkedHashMap<Slot, Measured> facts = new LinkedHashMap<>();
            for (JsonNode x : e.getValue()) {
                facts.put(new Slot(x.get(0).asString(), x.get(1).isNull() ? null : LocalDate.parse(x.get(1).asString()),
                        LocalDate.parse(x.get(2).asString())), new Measured(x.get(3).asDouble(), x.get(4).asDouble()));
            }
            equity.put(e.getKey(), facts);
        }
        JsonNode type = root.path("documentType");
        return new ClassFacts(durations, cover, type.isNull() || type.isMissingNode() ? null : type.asString(), equity);
    }
}
