package com.stockscreener.pipeline.datasource.kr;

import com.stockscreener.pipeline.datasource.DataSourceException;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>DART 웹 「오늘의 공시」 한 쪽</b>(ADR-0204 · {@code dart.fss.or.kr/dsac001}) — 공시마다 게시 시각(분)과 접수번호를 준다. OpenAPI
 * ({@code list.json})와 공시 뷰어에는 시각이 없다. 쪽은 100줄 · 시간 내림차순이고 둘째 쪽부터는 POST {@code /dsac001/search.ax}
 * ({@code selectDate} · {@code currentPage})다. 한 날짜가 끝났는지는 <b>모은 고유 접수번호 수 = 총 건수</b>로 가린다 — 쪽 넘김이 먹지
 * 않으면 같은 첫 쪽이 되풀이돼 줄 수만으로는 맞아 버린다.
 */
public final class DartDayList {

    public static final String SOURCE = "dart-web";

    private static final Pattern ROW = Pattern.compile("<tr>(.*?)</tr>", Pattern.DOTALL);
    private static final Pattern TIME = Pattern.compile("<td>\\s*([0-2]\\d):([0-5]\\d)\\s*</td>");
    private static final Pattern CORP = Pattern.compile("openCorpInfoNew\\('(\\d{8})'[^>]*>\\s*([^<]+?)\\s*</a>", Pattern.DOTALL);
    // 보고서명에 이스케이프 안 된 큰따옴표가 든다(2019-07-15 「…\"Super K-POP Festival…\"…」) — 고정 꼬리 「 공시뷰어 새창"」로 끝을 잡는다.
    private static final Pattern REPORT = Pattern.compile("rcpNo=(\\d{14})\"[^>]*?title=\"(.*?) 공시뷰어 새창\"", Pattern.DOTALL);
    private static final Pattern LISTED = Pattern.compile("<td>(\\d{4}\\.\\d{2}\\.\\d{2})</td>");
    private static final Pattern TOTAL = Pattern.compile("name=\"totalCnt\"[^>]*value=\"(\\d+)\"|value=\"(\\d+)\"[^>]*name=\"totalCnt\"");
    private static final Pattern PAGES = Pattern.compile("\\[(\\d+)/(\\d+)\\]");
    private static final DateTimeFormatter DOTTED = DateTimeFormatter.ofPattern("yyyy.MM.dd");

    private DartDayList() {
    }

    /** 한 줄 — 게시 시각은 분 단위다. {@code corpCode}는 비어 있을 수 있다(기업개황 연결이 없는 제출인). */
    public record Row(String rceptNo, String corpCode, String corpName, String reportName, LocalDate receiptDate, LocalTime time) {
    }

    /** 한 쪽 — 줄 · 그 날짜의 총 건수 · 총 쪽 수. */
    public record Page(List<Row> rows, int total, int pages) {
    }

    /**
     * 한 쪽을 읽는다. 시각과 접수번호가 있는 줄만 공시 줄이다(머리줄 · 빈 줄은 건너뛴다). 그런 줄인데 칸 하나라도 못 읽으면 본문
     * 이상이다 — 조용히 그 줄을 빼면 그 공시는 「시각 모름」이 되고 완결 판정(고유 수 = 총 건수)이 그것을 못 본다.
     */
    public static Page parse(String html, String context) throws DataSourceException {
        Matcher total = TOTAL.matcher(html);
        if (!total.find()) {
            throw malformed("총 건수(totalCnt)가 없다", context);
        }
        int count = Integer.parseInt(total.group(1) != null ? total.group(1) : total.group(2));
        Matcher pages = PAGES.matcher(html);
        int pageCount = pages.find() ? Integer.parseInt(pages.group(2)) : (count == 0 ? 0 : 1);
        List<Row> rows = new ArrayList<>();
        Matcher row = ROW.matcher(html);
        while (row.find()) {
            String body = row.group(1);
            Matcher time = TIME.matcher(body);
            Matcher report = REPORT.matcher(body);
            boolean hasTime = time.find();
            boolean hasReport = report.find();
            if (!hasTime && !hasReport) {
                continue;
            }
            Matcher corp = CORP.matcher(body);
            Matcher listed = LISTED.matcher(body);
            if (!hasTime || !hasReport || !listed.find()) {
                throw malformed("공시 줄의 칸을 못 읽었다: " + body.replaceAll("\\s+", " ").substring(0, Math.min(200, body.length())), context);
            }
            boolean hasCorp = corp.find();
            String reportName = report.group(2).replaceAll("&amp;", "&").strip();
            rows.add(new Row(report.group(1), hasCorp ? corp.group(1) : null, hasCorp ? corp.group(2).strip() : "",
                    reportName, LocalDate.parse(listed.group(1), DOTTED),
                    LocalTime.of(Integer.parseInt(time.group(1)), Integer.parseInt(time.group(2)))));
        }
        return new Page(rows, count, pageCount);
    }

    private static DataSourceException malformed(String message, String context) {
        return new DataSourceException(DataSourceException.Kind.MALFORMED, SOURCE, message + " " + context);
    }
}
