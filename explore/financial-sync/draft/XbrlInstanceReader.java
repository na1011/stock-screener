package com.stockscreener.pipeline.datasource.us;

import com.stockscreener.pipeline.datasource.DataSourceException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 공시 하나의 XBRL 인스턴스({@code …_htm.xml})를 <b>companyfacts가 그 공시를 실었을 때의 팩트</b>로 읽는다(ADR-0173).
 *
 * <p>companyfacts는 공시의 축 없는 us-gaap·dei 팩트를 (개념 · 단위 · 기간)마다 하나씩 싣는다. 2026-10-06 실측으로 AAPL
 * {@code 0000320193-26-000020}의 companyfacts 팩트 283이 전부 이 규칙으로 인스턴스에서 같은 값으로 나왔다. 그래서 이 리더는
 * 그 규칙만 지킨다 — 회사 고유 택소노미·축 붙은 컨텍스트·{@code unitRef} 없는 dei(문서 정보)는 안 싣는다.
 *
 * <p><b>택소노미는 네임스페이스 URI로 가른다</b> — 접두어는 공시마다 마음대로다.
 *
 * <p><b>같은 (개념 · 단위 · 기간)이 여럿이면 가장 정밀한 것({@code decimals}가 큰 것 · {@code INF}가 최상)</b>이다.
 * 실측: ABT 10-Q가 같은 영업권(2025-12-31)을 -6 정밀도 24,035,000,000과 -8 정밀도 24,000,000,000으로 두 번 싣는다.
 * <b>같은 정밀도에 값이 다르면 MALFORMED로 세운다</b> — 어느 쪽을 골라도 근거가 없다.
 */
final class XbrlInstanceReader {

    private static final String XBRLI = "http://www.xbrl.org/2003/instance";
    private static final String XBRLDI = "http://xbrl.org/2006/xbrldi";
    private static final String XSI = "http://www.w3.org/2001/XMLSchema-instance";
    private static final String US_GAAP_PREFIX = "http://fasb.org/us-gaap/";
    private static final String DEI_PREFIX = "http://xbrl.sec.gov/dei/";

    private XbrlInstanceReader() {
    }

    /** 한 팩트. {@code decimals}가 {@code null}이면 {@code INF}다. */
    record Fact(String taxonomy, String concept, String unit, LocalDate start, LocalDate end,
                BigDecimal value, Integer decimals) {
    }

    /** 인스턴스 하나에서 읽은 것. */
    record Instance(String fiscalYearFocus, String fiscalPeriodFocus, List<Fact> facts) {
    }

    private record Context(long cik, LocalDate start, LocalDate end, boolean dimensional) {
    }

    private record Raw(String taxonomy, String concept, String contextRef, String unitRef,
                       String decimals, String text) {
    }

    /**
     * @param cik 이 공시의 발행사. 컨텍스트 식별자가 다르면 MALFORMED다 — 남의 팩트를 우리 행으로 들이지 않는다.
     */
    static Instance read(InputStream xml, long cik, String context) throws DataSourceException {
        Map<String, Context> contexts = new HashMap<>();
        Map<String, String> units = new HashMap<>();
        List<Raw> raws = new ArrayList<>();
        String fiscalYear = null;
        String fiscalPeriod = null;
        try {
            XMLStreamReader r = factory().createXMLStreamReader(xml);
            try {
                while (r.hasNext()) {
                    if (r.next() != XMLStreamConstants.START_ELEMENT) {
                        continue;
                    }
                    String ns = r.getNamespaceURI();
                    String local = r.getLocalName();
                    if (XBRLI.equals(ns) && "context".equals(local)) {
                        String id = r.getAttributeValue(null, "id");
                        contexts.put(id, readContext(r, context));
                    } else if (XBRLI.equals(ns) && "unit".equals(local)) {
                        String id = r.getAttributeValue(null, "id");
                        units.put(id, readUnit(r));
                    } else if (ns != null && (ns.startsWith(US_GAAP_PREFIX) || ns.startsWith(DEI_PREFIX))) {
                        String taxonomy = ns.startsWith(US_GAAP_PREFIX) ? "us-gaap" : "dei";
                        String contextRef = r.getAttributeValue(null, "contextRef");
                        String unitRef = r.getAttributeValue(null, "unitRef");
                        String decimals = r.getAttributeValue(null, "decimals");
                        boolean nil = "true".equals(r.getAttributeValue(XSI, "nil"));
                        String text = nil ? null : r.getElementText();
                        if ("dei".equals(taxonomy) && "DocumentFiscalYearFocus".equals(local) && text != null) {
                            fiscalYear = text.trim();
                        } else if ("dei".equals(taxonomy) && "DocumentFiscalPeriodFocus".equals(local) && text != null) {
                            fiscalPeriod = text.trim();
                        }
                        if (contextRef != null && unitRef != null && text != null) {
                            raws.add(new Raw(taxonomy, local, contextRef, unitRef, decimals, text.trim()));
                        }
                    }
                }
            } finally {
                r.close();
            }
        } catch (XMLStreamException e) {
            throw malformed("인스턴스를 읽지 못했다. " + context, e);
        }

        Map<List<Object>, Fact> chosen = new LinkedHashMap<>();
        for (Raw raw : raws) {
            Context c = contexts.get(raw.contextRef());
            if (c == null) {
                throw malformed("팩트가 없는 컨텍스트를 가리킨다: " + raw.contextRef() + " " + context, null);
            }
            if (c.dimensional()) {
                continue;
            }
            if (c.cik() != cik) {
                throw malformed("컨텍스트 식별자가 이 공시의 CIK가 아니다: %d ≠ %d %s".formatted(c.cik(), cik, context), null);
            }
            String unit = units.get(raw.unitRef());
            if (unit == null) {
                throw malformed("팩트가 없는 단위를 가리킨다: " + raw.unitRef() + " " + context, null);
            }
            BigDecimal value;
            try {
                value = new BigDecimal(raw.text());
            } catch (NumberFormatException e) {
                throw malformed("수가 아닌 값: %s:%s=%s %s".formatted(raw.taxonomy(), raw.concept(), raw.text(), context), e);
            }
            Integer decimals = "INF".equals(raw.decimals()) || raw.decimals() == null
                    ? null : Integer.valueOf(raw.decimals());
            Fact fact = new Fact(raw.taxonomy(), raw.concept(), unit, c.start(), c.end(), value, decimals);
            List<Object> key = java.util.Arrays.asList(raw.taxonomy(), raw.concept(), unit, c.start(), c.end());
            Fact held = chosen.get(key);
            if (held == null) {
                chosen.put(key, fact);
                continue;
            }
            int precision = comparePrecision(fact.decimals(), held.decimals());
            if (precision > 0) {
                chosen.put(key, fact);
            } else if (precision == 0 && fact.value().compareTo(held.value()) != 0) {
                throw malformed("같은 정밀도에 값이 둘이다: %s:%s %s~%s %s · %s %s".formatted(raw.taxonomy(), raw.concept(),
                        c.start(), c.end(), held.value(), fact.value(), context), null);
            }
        }
        return new Instance(fiscalYear, fiscalPeriod, List.copyOf(chosen.values()));
    }

    /**
     * companyfacts의 {@code facts} 객체에 이 인스턴스의 팩트를 <b>companyfacts 노드 모양으로</b> 더한다. 읽는 쪽
     * ({@code SecEdgarFacts.readUnits})이 보는 칸만 싣는다 — accn · start · end · val · filed · form · fy · fp.
     */
    static int mergeInto(ObjectNode facts, Instance instance, String accession, String form, LocalDate filed) {
        int added = 0;
        int fiscalYear = parseYear(instance.fiscalYearFocus());
        for (Fact fact : instance.facts()) {
            ObjectNode taxonomy = child(facts, fact.taxonomy());
            ObjectNode concept = child(taxonomy, fact.concept());
            ObjectNode unitsNode = child(concept, "units");
            JsonNode existing = unitsNode.get(fact.unit());
            ArrayNode array = existing instanceof ArrayNode a ? a : unitsNode.putArray(fact.unit());
            if (alreadyThere(array, accession, fact)) {
                // companyfacts가 그 공시의 dei 몇 개를 이미 싣는 경우(GEHC 10-K FY2022 등) — 같은 슬롯을 두 번 넣지 않는다.
                continue;
            }
            ObjectNode node = array.addObject();
            if (fact.start() != null) {
                node.put("start", fact.start().toString());
            }
            node.put("end", fact.end().toString());
            node.put("val", fact.value().toPlainString());
            node.put("accn", accession);
            node.put("fy", fiscalYear);
            node.put("fp", Objects.requireNonNullElse(instance.fiscalPeriodFocus(), ""));
            node.put("form", form);
            node.put("filed", filed.toString());
            added++;
        }
        return added;
    }

    private static boolean alreadyThere(ArrayNode array, String accession, Fact fact) {
        String start = fact.start() == null ? "" : fact.start().toString();
        for (JsonNode node : array) {
            if (accession.equals(node.path("accn").asString(""))
                    && fact.end().toString().equals(node.path("end").asString(""))
                    && start.equals(node.path("start").asString(""))) {
                return true;
            }
        }
        return false;
    }

    private static ObjectNode child(ObjectNode parent, String name) {
        JsonNode existing = parent.get(name);
        if (existing instanceof ObjectNode o) {
            return o;
        }
        return parent.putObject(name);
    }

    private static int parseYear(String raw) {
        if (raw == null) {
            return 0;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 양수면 {@code a}가 더 정밀하다. {@code null}은 INF다. */
    private static int comparePrecision(Integer a, Integer b) {
        if (Objects.equals(a, b)) {
            return 0;
        }
        if (a == null) {
            return 1;
        }
        if (b == null) {
            return -1;
        }
        return Integer.compare(a, b);
    }

    private static Context readContext(XMLStreamReader r, String context) throws XMLStreamException, DataSourceException {
        long cik = -1;
        LocalDate start = null;
        LocalDate end = null;
        boolean dimensional = false;
        int depth = 1;
        while (depth > 0 && r.hasNext()) {
            int event = r.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                depth++;
                String ns = r.getNamespaceURI();
                String local = r.getLocalName();
                if (XBRLDI.equals(ns)) {
                    dimensional = true;
                } else if (XBRLI.equals(ns) && "segment".equals(local)) {
                    dimensional = true;
                } else if (XBRLI.equals(ns) && "identifier".equals(local)) {
                    String text = r.getElementText().trim();
                    depth--;
                    try {
                        cik = Long.parseLong(text);
                    } catch (NumberFormatException e) {
                        throw malformed("컨텍스트 식별자가 수가 아니다: " + text + " " + context, e);
                    }
                } else if (XBRLI.equals(ns) && ("instant".equals(local) || "endDate".equals(local))) {
                    end = date(r.getElementText(), context);
                    depth--;
                } else if (XBRLI.equals(ns) && "startDate".equals(local)) {
                    start = date(r.getElementText(), context);
                    depth--;
                }
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                depth--;
            }
        }
        if (end == null) {
            throw malformed("기간이 없는 컨텍스트 " + context, null);
        }
        return new Context(cik, start, end, dimensional);
    }

    private static String readUnit(XMLStreamReader r) throws XMLStreamException {
        List<String> numerator = new ArrayList<>();
        List<String> denominator = new ArrayList<>();
        List<String> target = numerator;
        int depth = 1;
        while (depth > 0 && r.hasNext()) {
            int event = r.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                depth++;
                String local = r.getLocalName();
                if ("unitDenominator".equals(local)) {
                    target = denominator;
                } else if ("unitNumerator".equals(local)) {
                    target = numerator;
                } else if ("measure".equals(local)) {
                    String text = r.getElementText().trim();
                    depth--;
                    target.add(text.substring(text.indexOf(':') + 1));
                }
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                depth--;
            }
        }
        String num = String.join("*", numerator);
        return denominator.isEmpty() ? num : num + "/" + String.join("*", denominator);
    }

    private static LocalDate date(String raw, String context) throws DataSourceException {
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            throw malformed("날짜가 아니다: " + raw + " " + context, e);
        }
    }

    private static XMLInputFactory factory() {
        XMLInputFactory f = XMLInputFactory.newFactory();
        // 외부 엔티티·DTD를 끈다 — 소스가 보내는 XML을 그대로 신뢰하지 않는다.
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        f.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true);
        return f;
    }

    private static DataSourceException malformed(String message, Throwable cause) {
        return cause == null
                ? new DataSourceException(DataSourceException.Kind.MALFORMED, SecEdgarResponse.SOURCE_NAME, message)
                : new DataSourceException(DataSourceException.Kind.MALFORMED, SecEdgarResponse.SOURCE_NAME, message, cause);
    }
}
