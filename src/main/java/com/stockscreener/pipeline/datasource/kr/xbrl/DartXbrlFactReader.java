package com.stockscreener.pipeline.datasource.kr.xbrl;

import com.stockscreener.pipeline.datasource.DataSourceException;

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
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * DART 재무제표 원본(XBRL 인스턴스 · {@code fnlttXbrl.xml}의 ZIP 안 {@code .xbrl})에서 국내 보통주 몫 판정(ADR-0192)의 재료 사실만 읽는다.
 *
 * <p><b>세 형식을 이름공간으로 읽는다</b>({@code docs/api-spec/dart-xbrl.md}) — 2021-12 앞 판은 인스턴스 이름공간이 기본(접두 없음)이고
 * 주석 태그가 없다. 그중 2018년 앞 판은 IFRS 이름공간이 IASB 시절 URI({@code http://xbrl.iasb.org/taxonomy/2010-04-30/ifrs})다. 그 뒤 판은
 * {@code xbrli:} 접두다. 판독은 이름공간 URI로 하고 접두로 가르지 않는다.
 *
 * <p><b>남기는 것</b>: 표준 이름공간({@code ifrs-full} · DART 표준 {@code dart}) 개념 중 {@link #IFRS_MATERIAL} · {@link #DART_MATERIAL} ·
 * 이름에 {@code Hybrid} · {@code PreferredStock}이 든 DART 개념 · 이름에 {@code OtherThanOrdinaryShares}가 든 IFRS 개념. 회사 확장 개념({@code entity…})은 버린다 —
 * 값의 재료가 아니다(사용자 기준 2026-10-25 · ADR-0192). 회사 구성원은 맥락에 남긴다(판정이 축마다 거른다).
 *
 * <p><b>MUST: 판을 올린다</b> — 남기는 개념 · 맥락 해석을 바꾸면 {@link #READER_VERSION}을 올린다. 재료 표의 옛 판 행은 디스크 캐시에서
 * 다시 판독된다(외부 0콜).
 */
public final class DartXbrlFactReader {

    /** 판독기 판. 재료 표({@code kr_report_xbrl.reader_version})가 이 값과 다르면 캐시에서 다시 읽는다. */
    public static final int READER_VERSION = 1;

    static final String SOURCE = "dart";

    private static final String XBRLI = "http://www.xbrl.org/2003/instance";
    private static final String XBRLDI = "http://xbrl.org/2006/xbrldi";
    private static final String XSI = "http://www.w3.org/2001/XMLSchema-instance";
    private static final String CONSOLIDATION_AXIS = "ConsolidatedAndSeparateFinancialStatementsAxis";

    /** 남기는 IFRS 개념 — 보통주 · 우선 EPS와 가중평균 · 앵커 순이익 · 기말 주식수 · (국내 PBR 분모가 읽을) 자본 성분. */
    static final Set<String> IFRS_MATERIAL = Set.of(
            "BasicEarningsLossPerShare",
            "BasicEarningsLossPerShareFromContinuingOperations",
            "WeightedAverageShares",
            "ProfitLossAttributableToOrdinaryEquityHoldersOfParentEntity",
            "ProfitLossFromContinuingOperationsAttributableToOrdinaryEquityHoldersOfParentEntity",
            "ProfitLossAttributableToOwnersOfParent",
            "ProfitLossAttributableToNoncontrollingInterests",
            "ProfitLoss",
            "NumberOfSharesOutstanding",
            "EquityAttributableToOwnersOfParent",
            "Equity",
            "IssuedCapital");

    /** 남기는 DART 표준 개념(이름에 {@code Hybrid} · {@code PreferredStock}이 든 것은 따로 전부 남긴다). */
    static final Set<String> DART_MATERIAL = Set.of("IssuedCapitalOfCommonStock");

    private DartXbrlFactReader() {
    }

    /** 이름공간 갈래. 개념과 구성원 둘 다 이 셋으로 가른다. */
    public enum Source {
        IFRS, DART, ENTITY;

        static Source of(String uri) {
            if (uri == null) {
                return ENTITY;
            }
            // 2018년 앞 판은 IASB 시절 이름공간(http://xbrl.iasb.org/taxonomy/2010-04-30/ifrs · 접두 ifrs)이다 — 같은 IFRS 표준 개념이다.
            if (uri.startsWith("http://xbrl.ifrs.org/") || uri.startsWith("https://xbrl.ifrs.org/")
                    || uri.startsWith("http://xbrl.iasb.org/taxonomy/")) {
                return IFRS;
            }
            // DART 표준 분류체계: http://dart.fss.or.kr/taxonomy/<날짜>/ifrs/dart(-gcd 등 갈래 포함). 회사 확장은 …/entity<번호>.
            if (uri.startsWith("http://dart.fss.or.kr/taxonomy/") && !uri.contains("/entity")) {
                return DART;
            }
            return ENTITY;
        }
    }

    /** 맥락의 연결/별도 구성원. 축이 없으면 {@link #NONE}. */
    public enum Basis {
        CONSOLIDATED, SEPARATE, NONE
    }

    /** 연결 축 밖의 차원 하나 — 축 로컬 이름 · 구성원 로컬 이름 · 구성원 이름공간 갈래. 형 붙은 차원은 구성원이 {@code typed}다. */
    public record Member(String axis, String member, Source source) {
    }

    /**
     * 재료 사실 하나. {@code start}가 {@code null}이면 시점(기말) 사실이고 {@code end}가 그 시점이다.
     */
    public record Fact(Source source, String concept, LocalDate start, LocalDate end, Basis basis, List<Member> members,
                       BigDecimal value) {

        public boolean instant() {
            return start == null;
        }
    }

    /** 판독 결과 — 재료 사실과, 남길 개념인데 수로 못 읽은 값의 수(0이 아니면 판정이 그만큼 재료를 못 본다). */
    public record Result(List<Fact> facts, int unreadableValues) {
    }

    private record Context(LocalDate start, LocalDate end, Basis basis, List<Member> members) {
    }

    private record Raw(Source source, String concept, String contextRef, String text) {
    }

    public static Result read(InputStream xml, String context) throws DataSourceException {
        Map<String, Context> contexts = new HashMap<>();
        List<Raw> raws = new ArrayList<>();
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
                        contexts.put(r.getAttributeValue(null, "id"), readContext(r, context));
                        continue;
                    }
                    Source source = Source.of(ns);
                    if (!material(source, local)) {
                        continue;
                    }
                    String contextRef = r.getAttributeValue(null, "contextRef");
                    if (contextRef == null) {
                        continue;
                    }
                    boolean nil = "true".equals(r.getAttributeValue(XSI, "nil"));
                    String text = nil ? null : r.getElementText();
                    if (text != null && !text.isBlank()) {
                        raws.add(new Raw(source, local, contextRef, text.trim()));
                    }
                }
            } finally {
                r.close();
            }
        } catch (XMLStreamException e) {
            throw new DataSourceException(DataSourceException.Kind.MALFORMED, SOURCE, "인스턴스를 읽지 못했다. " + context, e);
        }
        List<Fact> facts = new ArrayList<>();
        int unreadable = 0;
        for (Raw raw : raws) {
            Context c = contexts.get(raw.contextRef());
            if (c == null) {
                throw new DataSourceException(DataSourceException.Kind.MALFORMED, SOURCE,
                        "사실이 없는 맥락을 가리킨다: " + raw.contextRef() + " " + context);
            }
            BigDecimal value;
            try {
                value = new BigDecimal(raw.text().replace(",", ""));
            } catch (NumberFormatException e) {
                // 이름에 Hybrid가 든 DART 개념에는 발행일 · 이자율 문구 같은 글 사실이 섞인다 — 수 재료가 아니라 센다.
                unreadable++;
                continue;
            }
            facts.add(new Fact(raw.source(), raw.concept(), c.start(), c.end(), c.basis(), c.members(), value));
        }
        return new Result(List.copyOf(facts), unreadable);
    }

    static boolean material(Source source, String local) {
        return switch (source) {
            case IFRS -> IFRS_MATERIAL.contains(local) || local.contains("OtherThanOrdinaryShares");
            case DART -> DART_MATERIAL.contains(local) || local.contains("Hybrid") || local.contains("PreferredStock");
            case ENTITY -> false;
        };
    }

    private static Context readContext(XMLStreamReader r, String context) throws XMLStreamException, DataSourceException {
        LocalDate start = null;
        LocalDate end = null;
        Basis basis = Basis.NONE;
        List<Member> members = new ArrayList<>();
        int depth = 1;
        while (depth > 0 && r.hasNext()) {
            int event = r.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                depth++;
                String ns = r.getNamespaceURI();
                String local = r.getLocalName();
                if (XBRLDI.equals(ns) && "explicitMember".equals(local)) {
                    String axis = localOf(r.getAttributeValue(null, "dimension").trim());
                    String qname = r.getElementText().trim();
                    depth--;
                    int colon = qname.indexOf(':');
                    String prefix = colon < 0 ? "" : qname.substring(0, colon);
                    String member = colon < 0 ? qname : qname.substring(colon + 1);
                    Source memberSource = Source.of(r.getNamespaceContext().getNamespaceURI(prefix));
                    if (CONSOLIDATION_AXIS.equals(axis)) {
                        basis = switch (member) {
                            case "ConsolidatedMember" -> Basis.CONSOLIDATED;
                            case "SeparateMember" -> Basis.SEPARATE;
                            default -> throw new DataSourceException(DataSourceException.Kind.MALFORMED, SOURCE,
                                    "연결 축 구성원을 모른다: " + qname + " " + context);
                        };
                    } else {
                        members.add(new Member(axis, member, memberSource));
                    }
                } else if (XBRLDI.equals(ns) && "typedMember".equals(local)) {
                    members.add(new Member(localOf(r.getAttributeValue(null, "dimension").trim()), "typed", Source.ENTITY));
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
            throw new DataSourceException(DataSourceException.Kind.MALFORMED, SOURCE, "기간이 없는 맥락이다. " + context);
        }
        return new Context(start, end, basis, List.copyOf(members));
    }

    private static String localOf(String qname) {
        int colon = qname.indexOf(':');
        return colon < 0 ? qname : qname.substring(colon + 1);
    }

    private static LocalDate date(String raw, String context) throws DataSourceException {
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            throw new DataSourceException(DataSourceException.Kind.MALFORMED, SOURCE, "날짜가 아니다: " + raw + " " + context, e);
        }
    }

    private static XMLInputFactory factory() {
        XMLInputFactory f = XMLInputFactory.newDefaultFactory();
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        f.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true);
        return f;
    }
}
