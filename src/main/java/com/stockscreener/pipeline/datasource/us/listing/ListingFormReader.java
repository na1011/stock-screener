package com.stockscreener.pipeline.datasource.us.listing;

import com.stockscreener.pipeline.datasource.DataSourceException;

import org.springframework.web.util.HtmlUtils;
import org.w3c.dom.Document;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 거래소 양식 셋을 읽는다 — 순수 함수다(탐색 {@code US-PAST-LISTING-PLAN-1017.md} ★10·★12·★17 · D5).
 *
 * <ul>
 *   <li>{@code 25-NSE} — 거래소가 내는 제거 통지(XML). 거래소 = {@code exchange/entityName} · 종류 = {@code descriptionClassSecurity}.</li>
 *   <li>발행사 {@code 25} — 발행사가 내는 제거 통지(HTML). 거래소는 「(Exact name of Issuer … name of Exchange」 라벨 바로 앞에 발행사
 *       이름에 붙어 온다 · 종류는 「(Description of class」 라벨 앞이다.</li>
 *   <li>{@code 8-A12B} — 거래소 등록(HTML). 「Title of each class … Name of each exchange」 표가 종류마다 거래소 이름을 붙인다.</li>
 * </ul>
 *
 * <p><b>못 읽은 것은 지어내지 않는다</b> — 거래소 이름을 모르면 {@link Removal#venue()}가 비고, 라벨이 없으면
 * {@link Removal#unreadable()}에 사유가 든다. 부르는 쪽이 그 양식을 사건으로 안 쓰고 센다.
 */
public final class ListingFormReader {

    private static final String SOURCE = "sec:listing-form";
    private static final String ISSUER_LABEL = "(exact name of issuer";
    private static final String ADDRESS_LABEL = "principal executive offices)";
    private static final String CLASS_LABEL = "(description of class";
    private static final String REGISTER_HEADER = "name of each exchange";
    private static final String REGISTER_END = "if this form relates";

    private ListingFormReader() {
    }

    /**
     * 제거 통지 하나.
     *
     * @param venue      제거한 거래소. 이름을 모르면 비었다
     * @param rawVenue   원장이 적은 거래소 글자(못 읽었을 때의 귀속 재료)
     * @param lines      종류 글을 가른 증권 줄({@link ClassLines#split})
     * @param unreadable 못 읽은 사유. 읽었으면 {@code null}
     */
    public record Removal(Optional<Venue> venue, String rawVenue, List<String> lines, String unreadable) {
    }

    /** 등록 표의 한 줄 — 그 종류를 등록하는 거래소. */
    public record Registered(String line, Optional<Venue> venue) {
    }

    /** @param unreadable 표를 못 찾았으면 사유 */
    public record Registration(List<Registered> rows, String unreadable) {
    }

    /** {@code 25-NSE} 본문. XML이 아니면 MALFORMED다(받기 실패와 같이 다시 받는다). */
    public static Removal readExchangeRemoval(byte[] xml, String context) throws DataSourceException {
        Document doc;
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setExpandEntityReferences(false);
            doc = f.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
        } catch (ParserConfigurationException | SAXException | IOException e) {
            throw new DataSourceException(DataSourceException.Kind.MALFORMED, SOURCE, "25-NSE를 XML로 못 읽었다. " + context, e);
        }
        String exchange = firstText(doc, "exchange", "entityName");
        String description = firstText(doc, "notificationOfRemoval", "descriptionClassSecurity");
        if (exchange == null || description == null) {
            return new Removal(Optional.empty(), exchange, List.of(), "25-NSE에 거래소나 종류가 없다");
        }
        return new Removal(Venue.ofName(exchange), exchange, ClassLines.split(description), null);
    }

    /** 발행사 {@code 25} 본문(HTML). */
    public static Removal readIssuerRemoval(String html) {
        String text = text(html);
        String lower = text.toLowerCase(Locale.ROOT);
        int label = lower.indexOf(ISSUER_LABEL);
        int classEnd = lower.indexOf(CLASS_LABEL);
        if (label < 0 || classEnd < 0 || classEnd < label) {
            return new Removal(Optional.empty(), null, List.of(), "발행사 25의 라벨(발행사 이름 · 종류)을 못 찾았다");
        }
        String before = text.substring(0, label).strip();
        Optional<Venue.Tail> tail = Venue.ofTail(before);
        String rawVenue = tail.map(t -> before.substring(t.start())).orElse(lastWords(before, 6));
        int addressEnd = lower.indexOf(ADDRESS_LABEL, label);
        int classStart = addressEnd >= 0 && addressEnd < classEnd ? addressEnd + ADDRESS_LABEL.length() : -1;
        if (classStart < 0) {
            return new Removal(tail.map(Venue.Tail::venue), rawVenue, List.of(), "발행사 25의 주소 라벨을 못 찾았다 — 종류 글의 시작을 모른다");
        }
        return new Removal(tail.map(Venue.Tail::venue), rawVenue,
                ClassLines.split(text.substring(classStart, classEnd)), null);
    }

    /**
     * {@code 8-A12B} 본문(HTML). 표의 머리 「Name of each exchange …」 뒤에서 첫 「If this form relates」까지가 표다. 거래소 이름이 나올
     * 때마다 그 앞(앞 거래소 이름 뒤부터)이 한 종류다.
     */
    public static Registration readRegistration(String html) {
        String text = text(html);
        String lower = text.toLowerCase(Locale.ROOT);
        int header = lower.indexOf(REGISTER_HEADER);
        if (header < 0) {
            return new Registration(List.of(), "8-A12B 등록 표의 머리를 못 찾았다");
        }
        int headerEnd = lower.indexOf("registered", header);
        int end = lower.indexOf(REGISTER_END, header);
        if (headerEnd < 0 || end < 0 || end < headerEnd) {
            return new Registration(List.of(), "8-A12B 등록 표의 끝을 못 찾았다");
        }
        String table = text.substring(headerEnd + "registered".length(), end);
        List<Registered> rows = new ArrayList<>();
        int cursor = 0;
        for (Venue.Found found : Venue.findAll(table)) {
            String line = table.substring(cursor, found.start()).strip();
            if (!line.isEmpty()) {
                rows.add(new Registered(line.replaceAll("\\s+", " "), Optional.of(found.venue())));
            }
            cursor = found.end();
        }
        String rest = table.substring(cursor).strip();
        if (!rest.isEmpty()) {
            rows.add(new Registered(rest.replaceAll("\\s+", " "), Optional.empty()));
        }
        if (rows.isEmpty()) {
            return new Registration(List.of(), "8-A12B 등록 표가 비었다");
        }
        return new Registration(rows, null);
    }

    /** HTML을 글자로 편다 — 스크립트·스타일을 빼고 태그를 공백으로 바꾼 뒤 엔티티를 풀고 공백을 하나로. */
    static String text(String html) {
        String noScript = html.replaceAll("(?is)<(script|style)\\b.*?</\\1>", " ");
        String noTags = noScript.replaceAll("<[^>]+>", " ");
        return HtmlUtils.htmlUnescape(noTags).replace(' ', ' ').replaceAll("\\s+", " ").strip();
    }

    private static String lastWords(String text, int n) {
        String[] words = text.split(" ");
        return String.join(" ", java.util.Arrays.copyOfRange(words, Math.max(0, words.length - n), words.length));
    }

    private static String firstText(Document doc, String parent, String child) {
        var parents = doc.getElementsByTagName(parent);
        for (int i = 0; i < parents.getLength(); i++) {
            var kids = parents.item(i).getChildNodes();
            for (int j = 0; j < kids.getLength(); j++) {
                if (child.equals(kids.item(j).getNodeName())) {
                    String value = kids.item(j).getTextContent();
                    return value == null || value.isBlank() ? null : value.strip();
                }
            }
        }
        return null;
    }
}
