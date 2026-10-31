package com.stockscreener.pipeline.datasource.kr.xbrl;

import com.stockscreener.pipeline.datasource.DataSourceException;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 원본 ZIP의 한국어 이름표 링크베이스({@code _lab-ko.xml})에서 <b>회사 확장 개념의 표준 이름표</b>(역할 {@code .../role/label})를 읽는다 —
 * DART 전체 재무제표 응답이 표준계정코드 없는 줄에 싣는 계정명이 이것이다(2026-11-01 실측: 089970 2025 3분기 비표준 줄 15 전부 일치).
 */
public final class DartXbrlLabels {

    private static final String LINK = "http://www.xbrl.org/2003/linkbase";
    private static final String XLINK = "http://www.w3.org/1999/xlink";
    private static final String STANDARD_ROLE = "http://www.xbrl.org/2003/role/label";

    private DartXbrlLabels() {
    }

    /** 회사 확장 개념의 로컬 이름(인스턴스 원소 이름) → 표준 이름표. */
    public static Map<String, String> entityLabels(byte[] linkbase, String context) throws DataSourceException {
        Map<String, String> locs = new HashMap<>();
        Map<String, String> labels = new HashMap<>();
        List<String[]> arcs = new ArrayList<>();
        try {
            XMLInputFactory f = XMLInputFactory.newDefaultFactory();
            f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
            f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
            f.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true);
            XMLStreamReader r = f.createXMLStreamReader(new ByteArrayInputStream(linkbase));
            try {
                while (r.hasNext()) {
                    if (r.next() != XMLStreamConstants.START_ELEMENT || !LINK.equals(r.getNamespaceURI())) {
                        continue;
                    }
                    switch (r.getLocalName()) {
                        case "loc" -> {
                            String href = r.getAttributeValue(XLINK, "href");
                            String fragment = href == null ? "" : href.substring(href.indexOf('#') + 1);
                            int cut = fragment.indexOf('_');
                            if (fragment.startsWith("entity") && cut > 0) {
                                locs.put(r.getAttributeValue(XLINK, "label"), fragment.substring(cut + 1));
                            }
                        }
                        case "label" -> {
                            if (STANDARD_ROLE.equals(r.getAttributeValue(XLINK, "role"))) {
                                String key = r.getAttributeValue(XLINK, "label");
                                labels.put(key, r.getElementText().trim());
                            }
                        }
                        case "labelArc" -> arcs.add(new String[]{r.getAttributeValue(XLINK, "from"), r.getAttributeValue(XLINK, "to")});
                        default -> {
                        }
                    }
                }
            } finally {
                r.close();
            }
        } catch (XMLStreamException e) {
            throw new DataSourceException(DataSourceException.Kind.MALFORMED, DartXbrlFactReader.SOURCE, "이름표를 못 읽었다. " + context, e);
        }
        Map<String, String> out = new HashMap<>();
        for (String[] arc : arcs) {
            String concept = locs.get(arc[0]);
            String label = labels.get(arc[1]);
            if (concept != null && label != null) {
                out.putIfAbsent(concept, label);
            }
        }
        return out;
    }
}
