package com.stockscreener.pipeline.datasource.us.shareclass;

import com.stockscreener.pipeline.datasource.DataSourceException;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 공시 인스턴스에서 클래스 축 판정의 팩트를 읽는다(탐색 `SHARES-CLASS-PLAN-V5-1015.md` §0-5·6 · 시뮬레이터 {@code load5.py}).
 *
 * <p><b>기존 보충 리더({@code XbrlInstanceReader})와 따로 둔다.</b> 그 리더는 판 고정 범위 안이고 차원 맥락을 전부 버린다 —
 * 그것을 고치면 판이 올라 저장 보충 행 전부를 다시 받는다(v4 반증 15). 이 리더는 그 범위를 참조하지 않는다.
 *
 * <p><b>맥락은 축의 뜻으로 받는다 — 종목 갈래가 아니다.</b>
 * <ul>
 *   <li>클래스 축({@code StatementClassOfStockAxis})만 단 맥락과 축 없는 맥락을 받는다.</li>
 *   <li>클래스 축 + {@code StatementEquityComponentsAxis=CommonStockMember}는 같은 사실을 한 번 더 이름 단 것이라 <b>클래스만
 *       단 맥락이 없을 때</b> 그 클래스 사실로 받는다({@code NET}·{@code DASH}는 클래스 사실을 이 꼴로만 싣는다). 클래스 축 없이
 *       자본 구성요소 축만 단 맥락은 안 받는다 — {@code UHAL}에서 그것은 의결권 보통주 하나다.</li>
 *   <li>표지 주식수의 {@code SubsequentEventTypeAxis}는 받는다 — 표지는 원래 기말 뒤 시점 값이다({@code F}·{@code BIO}). 그 축
 *       없는 값이 있으면 그것이 먼저다.</li>
 *   <li>재작성·연결실체·법인 축은 다른 판·다른 실체라 버린다.</li>
 * </ul>
 *
 * <p>남의 CIK 맥락(공동 등록)은 기존 리더처럼 건너뛴다. 수로 안 읽히는 값은 건너뛰고 {@link Result#unreadableValues()}로 센다.
 */
public final class ClassFactReader {

    /**
     * 저장 팩트({@code sec_filing_class_fact.facts})를 정하는 읽기 규칙의 판 — 이 파일과 {@link ClassFacts}의 저장 형태를 바꾸면
     * 올린다. 판이 다른 행은 없는 것으로 치고 다음 받기가 다시 받는다.
     */
    public static final int READER_VERSION = 1;

    public static final String SHARES_OUTSTANDING = "EntityCommonStockSharesOutstanding";
    public static final String TRADING_SYMBOL = "TradingSymbol";
    public static final String SECURITY_TITLE = "Security12bTitle";
    /** 표지 거래소 코드({@code NYSE} · {@code NASDAQ} · {@code NYSEAMER} …) — 상장 판정({@code listing} 패키지)만 읽는다. 저장 팩트에는 안 든다. */
    public static final String SECURITY_EXCHANGE = "SecurityExchangeName";
    public static final List<String> WEIGHTED_TAGS = List.of("WeightedAverageNumberOfSharesOutstandingBasic",
            "WeightedAverageNumberOfShareOutstandingBasicAndDiluted", "WeightedAverageNumberOfSharesIssuedBasic",
            "WeightedAverageLimitedPartnershipUnitsOutstanding");
    public static final List<String> EPS_TAGS = List.of("EarningsPerShareBasic", "EarningsPerShareBasicAndDiluted",
            "IncomeLossFromContinuingOperationsPerBasicShare");
    public static final String DECLARED_NUMERATOR = "NetIncomeLossAvailableToCommonStockholdersBasic";
    public static final List<String> NUMERATOR_TAGS = List.of(DECLARED_NUMERATOR, "NetIncomeLoss", "ProfitLoss");

    private static final Set<String> COVER = Set.of(SHARES_OUTSTANDING, TRADING_SYMBOL, SECURITY_TITLE);
    /** 상장 판정의 표지 — 기호 · 거래소 · 증권 이름. 저장 팩트({@link #COVER})와 따로 둬 그 판을 안 바꾼다. */
    private static final Set<String> LISTING_COVER = Set.of(TRADING_SYMBOL, SECURITY_EXCHANGE, SECURITY_TITLE);
    private static final Set<String> DURATION = Set.copyOf(concat(WEIGHTED_TAGS, EPS_TAGS, NUMERATOR_TAGS));
    private static final String CLASS_AXIS = "StatementClassOfStockAxis";
    private static final String EQUITY_AXIS = "StatementEquityComponentsAxis";
    private static final String SUBSEQUENT_AXIS = "SubsequentEventTypeAxis";
    private static final String XBRLI = "http://www.xbrl.org/2003/instance";
    private static final String XBRLDI = "http://xbrl.org/2006/xbrldi";
    private static final String XSI = "http://www.w3.org/2001/XMLSchema-instance";
    private static final String US_GAAP_PREFIX = "http://fasb.org/us-gaap/";
    private static final String DEI_PREFIX = "http://xbrl.sec.gov/dei/";
    private static final String SOURCE = "sec:filing-instance";

    private ClassFactReader() {
    }

    /** @param unreadableValues 수로 안 읽혀 건너뛴 기간 팩트의 수 */
    public record Result(ClassFacts facts, int unreadableValues) {
    }

    /** 축 로컬 이름 → 구성원 QName 글자. */
    private record Context(long cik, Map<String, String> dims, LocalDate start, LocalDate end) {
    }

    /** 맥락을 풀기 전의 사실 — 사실이 맥락보다 앞에 나오는 인스턴스가 있어 파일 끝에서 푼다(JOBY 2021-03). */
    private record Raw(String concept, String contextRef, String decimals, String text) {
    }

    private record Late(boolean cover, String concept, String member, String text, ClassFacts.Period period,
                        ClassFacts.Measured measured) {
    }

    public static Result read(InputStream xml, long cik, String context) throws DataSourceException {
        return read(xml, cik, context, COVER);
    }

    /**
     * 상장 판정의 표지 — 클래스 축 구성원(없으면 {@link ClassFacts#AXISLESS})마다 기호 · 거래소 코드 · 증권 이름. 맥락 해석은
     * {@link #read}와 같다(같은 축 규칙 · 남의 CIK 맥락은 건너뜀). 기간 팩트도 함께 읽히지만 상장 판정은 표지만 쓴다.
     */
    public static ClassFacts readListingCover(InputStream xml, long cik, String context) throws DataSourceException {
        return read(xml, cik, context, LISTING_COVER).facts();
    }

    private static Result read(InputStream xml, long cik, String context, Set<String> coverConcepts)
            throws DataSourceException {
        Map<String, Context> contexts = new HashMap<>();
        Map<String, LinkedHashMap<ClassFacts.MemberPeriod, ClassFacts.Measured>> durations = new LinkedHashMap<>();
        Map<String, LinkedHashMap<String, String>> cover = new LinkedHashMap<>();
        List<Late> late = new ArrayList<>();
        List<Raw> raws = new ArrayList<>();
        String documentType = null;
        int unreadable = 0;
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
                    boolean gaap = ns != null && ns.startsWith(US_GAAP_PREFIX);
                    boolean dei = ns != null && ns.startsWith(DEI_PREFIX);
                    if (!gaap && !dei) {
                        continue;
                    }
                    if (dei && "DocumentType".equals(local)) {
                        documentType = r.getElementText().trim();
                        continue;
                    }
                    if (!coverConcepts.contains(local) && !DURATION.contains(local)) {
                        continue;
                    }
                    String contextRef = r.getAttributeValue(null, "contextRef");
                    String decimals = r.getAttributeValue(null, "decimals");
                    boolean nil = "true".equals(r.getAttributeValue(XSI, "nil"));
                    String text = nil ? null : r.getElementText();
                    if (text != null && !text.isBlank()) {
                        raws.add(new Raw(local, contextRef, decimals, text.trim()));
                    }
                }
            } finally {
                r.close();
            }
        } catch (XMLStreamException e) {
            throw new DataSourceException(DataSourceException.Kind.MALFORMED, SOURCE, "인스턴스를 읽지 못했다. " + context, e);
        }
        for (Raw raw : raws) {
            Context c = contexts.get(raw.contextRef());
            if (c == null || c.cik() != cik) {
                continue;
            }
            String local = raw.concept();
            String text = raw.text();
            String decimals = raw.decimals();
            Set<String> extra = new java.util.HashSet<>(c.dims().keySet());
            extra.remove(CLASS_AXIS);
            String member = c.dims().getOrDefault(CLASS_AXIS, ClassFacts.AXISLESS);
            if (coverConcepts.contains(local)) {
                if (!extra.isEmpty() && !(SHARES_OUTSTANDING.equals(local) && extra.equals(Set.of(SUBSEQUENT_AXIS)))) {
                    continue;
                }
                if (extra.isEmpty()) {
                    cover.computeIfAbsent(local, k -> new LinkedHashMap<>()).put(member, text);
                } else {
                    late.add(new Late(true, local, member, text, null, null));
                }
                continue;
            }
            if (c.start() == null || c.end() == null) {
                continue;
            }
            if (!extra.isEmpty() && !(extra.equals(Set.of(EQUITY_AXIS)) && c.dims().containsKey(CLASS_AXIS)
                    && c.dims().get(EQUITY_AXIS).endsWith(":CommonStockMember"))) {
                continue;
            }
            ClassFacts.Measured measured;
            try {
                measured = new ClassFacts.Measured(Double.parseDouble(text), halfWidth(decimals));
            } catch (NumberFormatException e) {
                unreadable++;
                continue;
            }
            ClassFacts.Period period = new ClassFacts.Period(c.start(), c.end());
            if (extra.isEmpty()) {
                durations.computeIfAbsent(local, k -> new LinkedHashMap<>()).put(new ClassFacts.MemberPeriod(member, period), measured);
            } else {
                late.add(new Late(false, local, member, null, period, measured));
            }
        }
        // 두 축 사실은 클래스만 단 사실이 없는 자리만 채운다(문서 순서와 무관하게 클래스만 단 쪽이 먼저다).
        for (Late l : late) {
            if (l.cover()) {
                cover.computeIfAbsent(l.concept(), k -> new LinkedHashMap<>()).putIfAbsent(l.member(), l.text());
            } else {
                durations.computeIfAbsent(l.concept(), k -> new LinkedHashMap<>())
                        .putIfAbsent(new ClassFacts.MemberPeriod(l.member(), l.period()), l.measured());
            }
        }
        return new Result(new ClassFacts(durations, cover, documentType), unreadable);
    }

    /** 선언 정밀도의 반올림 반폭. 정밀도가 수로 안 읽히면 그 팩트를 못 읽은 것으로 센다(호출자). */
    private static double halfWidth(String decimals) {
        if (decimals == null || "INF".equals(decimals)) {
            return 0.0;
        }
        return 0.5 * Math.pow(10, -Integer.parseInt(decimals.trim()));
    }

    private static Context readContext(XMLStreamReader r, String context) throws XMLStreamException, DataSourceException {
        long cik = -1;
        LocalDate start = null;
        LocalDate end = null;
        Map<String, String> dims = new HashMap<>();
        int depth = 1;
        while (depth > 0 && r.hasNext()) {
            int event = r.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                depth++;
                String ns = r.getNamespaceURI();
                String local = r.getLocalName();
                if (XBRLDI.equals(ns) && "explicitMember".equals(local)) {
                    String dimension = r.getAttributeValue(null, "dimension");
                    String member = r.getElementText().trim();
                    depth--;
                    dims.put(ClassFacts.local(dimension.trim()), member);
                } else if (XBRLDI.equals(ns) && "typedMember".equals(local)) {
                    // 형 붙은 차원은 클래스 축이 아니다 — 축으로 세어 그 맥락을 「다른 축이 붙은 것」으로 만든다.
                    dims.put(ClassFacts.local(r.getAttributeValue(null, "dimension").trim()), "typed");
                } else if (XBRLI.equals(ns) && "identifier".equals(local)) {
                    String text = r.getElementText().trim();
                    depth--;
                    try {
                        cik = Long.parseLong(text);
                    } catch (NumberFormatException e) {
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
        return new Context(cik, dims, start, end);
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

    @SafeVarargs
    private static List<String> concat(List<String>... lists) {
        List<String> out = new ArrayList<>();
        for (List<String> l : lists) {
            out.addAll(l);
        }
        return out;
    }
}
