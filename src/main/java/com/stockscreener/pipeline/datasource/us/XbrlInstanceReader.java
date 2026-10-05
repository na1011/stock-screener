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
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 공시 하나의 XBRL 인스턴스를 <b>companyfacts가 그 공시를 실었을 때의 팩트</b>로 읽는다(ADR-0173).
 *
 * <p>companyfacts는 공시의 축 없는 us-gaap·dei 팩트를 (개념 · 단위 · 기간)마다 하나씩 싣는다. 2026-10-06 실측으로 AAPL
 * {@code 0000320193-26-000020}의 companyfacts 팩트 283이 전부 이 규칙으로 인스턴스에서 같은 값으로 나왔다(JPM 787/788 ·
 * MSFT 597/601 — 어긋남은 전부 우리 매핑 밖 개념의 companyfacts 쪽 정규화). 그래서 이 리더는 그 규칙만 지킨다 — 회사
 * 고유 택소노미 · 축 붙은 컨텍스트 · 기간 없는 컨텍스트 · {@code unitRef} 없는 dei(문서 정보)는 안 싣는다.
 *
 * <p><b>택소노미는 네임스페이스 URI로 가른다</b> — 접두어는 공시마다 마음대로다.
 *
 * <p><b>읽기와 고르기를 나눈다.</b> {@link #read}는 같은 (개념 · 단위 · 기간)의 중복을 <b>정밀도째 전부</b> 내고,
 * {@link #choose}가 가장 정밀한 것을 고른다. 저장 표({@code sec_filing_supplement})가 앞의 것을 담으므로, 고르기 규칙을
 * 고쳐도 공시를 다시 받지 않는다.
 *
 * <p><b>MUST NOT: 고칠 일이 없는 것으로 공시 전체를 세우지 않는다.</b> 결정적 MALFORMED는 그 공시를 다시 받아도 같은
 * 실패라 매달 같은 칸이 빈다. 쓰지 않는 기간 없는 컨텍스트와 남의 CIK 컨텍스트(공동 등록)는 건너뛴다 — 남의 팩트를 우리
 * 행으로 안 들이는 것은 건너뛰기로 충분하다. 파일을 잘못 고른 것은 {@link #requireIdentity}가 따로 묻는다.
 */
final class XbrlInstanceReader {

    /**
     * 읽기 규칙의 판. <b>MUST: 무엇을 싣는지가 바뀌면 올린다</b> — 저장 표의 판이 다르면 그 공시를 다시 읽는다. 고르기만
     * 바꾸면 안 올린다(원 팩트가 이미 다 있다).
     */
    static final int READER_VERSION = 1;

    private static final String XBRLI = "http://www.xbrl.org/2003/instance";
    private static final String XBRLDI = "http://xbrl.org/2006/xbrldi";
    private static final String XSI = "http://www.w3.org/2001/XMLSchema-instance";
    private static final String US_GAAP_PREFIX = "http://fasb.org/us-gaap/";
    private static final String DEI_PREFIX = "http://xbrl.sec.gov/dei/";

    private XbrlInstanceReader() {
    }

    /**
     * 고르기 전의 팩트 하나.
     *
     * @param decimals 원문 그대로({@code INF} 포함). 없으면 {@code null}이고 INF로 읽는다.
     */
    record RawFact(String taxonomy, String concept, String unit, LocalDate start, LocalDate end,
                   BigDecimal value, String decimals) {
    }

    /** 고른 팩트 하나. */
    record Fact(String taxonomy, String concept, String unit, LocalDate start, LocalDate end, BigDecimal value) {
    }

    /**
     * 인스턴스 하나에서 읽은 것.
     *
     * @param ownContexts 식별자가 이 공시의 CIK인 컨텍스트 수 — 0이면 남의 파일이다({@link #requireIdentity})
     */
    record Instance(String documentType, String fiscalYearFocus, String fiscalPeriodFocus, int ownContexts,
                    List<RawFact> facts) {
    }

    private record Context(long cik, LocalDate start, LocalDate end, boolean dimensional) {
    }

    private record Raw(String taxonomy, String concept, String contextRef, String unitRef,
                       String decimals, String text) {
    }

    /**
     * @param cik 이 공시의 발행사. 식별자가 다른 컨텍스트의 팩트는 안 싣는다.
     */
    static Instance read(InputStream xml, long cik, String context) throws DataSourceException {
        Map<String, Context> contexts = new HashMap<>();
        Map<String, String> units = new HashMap<>();
        List<Raw> raws = new ArrayList<>();
        String documentType = null;
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
                        if ("dei".equals(taxonomy) && text != null) {
                            switch (local) {
                                case "DocumentType" -> documentType = text.trim();
                                case "DocumentFiscalYearFocus" -> fiscalYear = text.trim();
                                case "DocumentFiscalPeriodFocus" -> fiscalPeriod = text.trim();
                                default -> { }
                            }
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

        int ownContexts = (int) contexts.values().stream().filter(c -> c.cik() == cik).count();
        List<RawFact> facts = new ArrayList<>();
        for (Raw raw : raws) {
            Context c = contexts.get(raw.contextRef());
            if (c == null) {
                // 파일 자체가 깨진 것이다 — 고칠 수 있는 쪽은 SEC다.
                throw malformed("팩트가 없는 컨텍스트를 가리킨다: " + raw.contextRef() + " " + context, null);
            }
            if (c.dimensional() || c.end() == null || c.cik() != cik) {
                continue;
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
            facts.add(new RawFact(raw.taxonomy(), raw.concept(), unit, c.start(), c.end(), value, raw.decimals()));
        }
        return new Instance(documentType, fiscalYear, fiscalPeriod, ownContexts, List.copyOf(facts));
    }

    /**
     * 잘못 고른 파일을 막는다 — 그 공시의 인스턴스가 아니면 그 공시의 보충 실패로 남긴다.
     *
     * <p>둘을 다 본다: 이 CIK의 컨텍스트가 하나라도 있는가 · 문서 종류({@code dei:DocumentType})가 그 공시의 폼인가.
     * 정정본은 문서 종류에 {@code /A}를 붙이기도 안 붙이기도 해서(정정 여부는 {@code dei:AmendmentFlag}가 따로 말한다)
     * {@code /A}를 떼고 맞춘다.
     */
    static void requireIdentity(Instance instance, String form, String context) throws DataSourceException {
        if (instance.ownContexts() == 0) {
            throw malformed("이 공시의 CIK 컨텍스트가 하나도 없다 — 다른 회사의 파일을 골랐다. " + context, null);
        }
        if (instance.documentType() == null || !baseForm(instance.documentType()).equals(baseForm(form))) {
            throw malformed("문서 종류 %s가 공시 폼 %s가 아니다 — 다른 문서를 골랐다. %s"
                    .formatted(instance.documentType(), form, context), null);
        }
    }

    private static String baseForm(String form) {
        return form.endsWith("/A") ? form.substring(0, form.length() - 2) : form;
    }

    /**
     * 같은 (택소노미 · 개념 · 단위 · 기간)에서 <b>가장 정밀한 것</b>({@code decimals}가 큰 것 · {@code INF}가 최상)을 고른다.
     * 2026-10-06 실측으로 companyfacts도 같은 쪽을 골랐다(MSFT {@code UnrecognizedTaxBenefits} 3/3). ABT 10-Q는 같은
     * 영업권(2025-12-31)을 -6 정밀도 24,035,000,000과 -8 정밀도 24,000,000,000으로 두 번 싣는다.
     *
     * <p><b>같은 정밀도에 값이 다르면 MALFORMED다</b> — 어느 쪽을 골라도 근거가 없다(실측 인스턴스 8개에서 0건).
     */
    static List<Fact> choose(List<RawFact> facts, String context) throws DataSourceException {
        Map<List<Object>, RawFact> chosen = new LinkedHashMap<>();
        for (RawFact fact : facts) {
            List<Object> key = Arrays.asList(fact.taxonomy(), fact.concept(), fact.unit(), fact.start(), fact.end());
            RawFact held = chosen.get(key);
            if (held == null) {
                chosen.put(key, fact);
                continue;
            }
            int precision = comparePrecision(precisionOf(fact), precisionOf(held));
            if (precision > 0) {
                chosen.put(key, fact);
            } else if (precision == 0 && fact.value().compareTo(held.value()) != 0) {
                throw malformed("같은 정밀도에 값이 둘이다: %s:%s %s~%s %s · %s %s".formatted(fact.taxonomy(),
                        fact.concept(), fact.start(), fact.end(), held.value(), fact.value(), context), null);
            }
        }
        List<Fact> out = new ArrayList<>(chosen.size());
        for (RawFact f : chosen.values()) {
            out.add(new Fact(f.taxonomy(), f.concept(), f.unit(), f.start(), f.end(), f.value()));
        }
        return out;
    }

    /**
     * companyfacts의 {@code facts} 객체에 고른 팩트를 <b>companyfacts 노드 모양으로</b> 더한다. 읽는 쪽
     * ({@code SecEdgarFacts.readUnits})이 보는 칸만 싣는다 — accn · start · end · val · filed · form · fy · fp.
     *
     * @return 더한 노드 수. 이미 같은 accn·기간 슬롯이 있으면 안 더한다(companyfacts가 그 공시의 dei 몇 개를 이미 싣는
     *         경우 — GEHC 10-K FY2022 등).
     */
    static int mergeInto(ObjectNode facts, List<Fact> chosen, String accession, String form, LocalDate filed,
                         String fiscalYearFocus, String fiscalPeriodFocus) {
        int added = 0;
        int fiscalYear = parseYear(fiscalYearFocus);
        for (Fact fact : chosen) {
            ObjectNode taxonomy = child(facts, fact.taxonomy());
            ObjectNode concept = child(taxonomy, fact.concept());
            ObjectNode unitsNode = child(concept, "units");
            JsonNode existing = unitsNode.get(fact.unit());
            ArrayNode array = existing instanceof ArrayNode a ? a : unitsNode.putArray(fact.unit());
            if (alreadyThere(array, accession, fact)) {
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
            node.put("fp", Objects.requireNonNullElse(fiscalPeriodFocus, ""));
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

    /** {@code null}이면 INF다. */
    private static Integer precisionOf(RawFact fact) {
        return fact.decimals() == null || "INF".equals(fact.decimals()) ? null : Integer.valueOf(fact.decimals());
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

    /** 기간 없는 컨텍스트({@code forever})는 {@code end}가 {@code null}이다 — 그 컨텍스트의 팩트는 안 싣는다. */
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
                } else if (XBRLI.equals(ns) && ("segment".equals(local) || "scenario".equals(local))) {
                    dimensional = true;
                } else if (XBRLI.equals(ns) && "identifier".equals(local)) {
                    String text = r.getElementText().trim();
                    depth--;
                    try {
                        cik = Long.parseLong(text);
                    } catch (NumberFormatException e) {
                        // 식별자가 CIK가 아닌 컨텍스트는 이 공시의 것이 아니다 — 건너뛴다(-1은 어떤 CIK와도 안 같다).
                        cik = -1;
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
