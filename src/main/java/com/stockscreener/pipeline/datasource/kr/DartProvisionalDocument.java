package com.stockscreener.pipeline.datasource.kr;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>국내 실적 발표 공시 본문</b>(I002 영업(잠정)실적 · I001 매출액또는손익구조 변동) — DART 웹 뷰어({@code report/viewer.do})가 내는 표준 서식
 * 한 장을 읽는다. 순수 함수다(원문 바이트 → 값) — 저장한 원문을 0콜로 다시 읽어 해석 규칙을 바꿀 수 있다.
 *
 * <p>서식(2026-07~11 원문 1,091건 실측 · {@code docs/api-spec/dart-filing-list.md}):
 * <ul>
 *   <li>I002 — 「실적기간」 표가 당기 · 전기 · 전년동기 · 당기누계 기간을 주고, 실적 표가 줄마다(매출액 · 영업이익 ·
 *       법인세비용차감전계속사업이익 · 당기순이익 · 지배기업 소유주지분 순이익) 「당해실적」 · 「누계실적」 두 줄에 당기 값을 첫 칸으로 준다.
 *       단위는 표 머리 「단위 : 조원, %」다. 연결 · 별도는 제목이 가른다({@code 연결재무제표기준…}) — 본문 앞 300자는 서식 CSS라 못 가른다.</li>
 *   <li>I001 — 「재무제표의 종류」(연결 · 별도), 결산기간 시작 · 종료일, 손익 네 줄(「- 매출액」 …)의 당해사업연도 값. 단위는
 *       「변동내용(단위:천원)」이다. 지배 순이익 줄은 없다(추가공시에 드물게 있다 — 안 읽는다).</li>
 *   <li><b>옛 I002 서식</b>(2025-09 무렵까지)에는 「실적기간」 표가 없고 당기 칸 머리에 회사가 쓴 분기 표지만 있다 — {@code ('25.2Q)} ·
 *       {@code (2025.2Q)} · {@code (25.2분기)} · {@code ('25년2분기)} · {@code (2025년 2분기)}(2026-11-10 표본 18건). 연도와 분기 숫자로 읽고 누계는
 *       그 해 1월 1일부터로 본다 — 그 가정이 틀린 공시는 계산 쪽 자체 검사(당해 = 누계 − 정식 앞 분기 합)가 못 읽음으로 거른다. 표지가
 *       {@code (-)}이면(옛 월간 실적) 원장이 기간을 안 준 것이라 못 읽음이다(발표로 남아 막는다).</li>
 *   <li>정정 공시는 정정전 · 정정후 표 뒤에 <b>고친 서식 전체</b>를 다시 싣는다 — 서식의 마지막 닻부터 읽는다. 정정 표의 칸은 닻 앞이라
 *       안 읽힌다(정정 표의 열을 값으로 읽은 오독이 046890에 있었다 · 그것은 계산 쪽 자체 검사가 따로 거른다).</li>
 * </ul>
 *
 * <p>값은 문서 단위 그대로의 수(소수 자릿수 보존)와 단위를 함께 낸다 — 반올림 폭(문서 끝자리)이 계산 쪽 자체 검사의 허용 오차다.
 * 값 칸이 {@code -}이면 그 항목은 없다(월간 실적 · 매출만 낸 첫 잠정).
 */
public final class DartProvisionalDocument {

    /** 해석 규칙의 판 — 저장 행이 이 판으로 읽혔는지 가른다. 규칙을 바꾸면 올리고 캐시를 0콜로 다시 읽는다. */
    public static final int PARSER_VERSION = 1;

    public enum Form { I001, I002 }

    /** 본문 기간의 꼴 — 실적 발표로 셈하는 것은 분기 · 연도뿐이다(월간 실적은 같은 제목으로 온다 · 477건 중 34). */
    public enum ReleasePeriod { QUARTER, YEAR, OTHER }

    /** 손익 다섯 줄 — 문서 단위 그대로. 없으면 {@code null}. */
    public record Lines(BigDecimal revenue, BigDecimal operatingIncome, BigDecimal profitBeforeTax, BigDecimal netIncome,
                        BigDecimal netIncomeControlling) {

        static final Lines EMPTY = new Lines(null, null, null, null, null);
    }

    /**
     * 읽은 서식 한 장. I001은 당기 = 누계 = 결산기간이고 두 줄이 같은 값이다.
     *
     * @param unitWon 문서 단위(원) — 조원이면 10^12
     */
    public record Parsed(Form form, boolean consolidated, LocalDate periodStart, LocalDate periodEnd, LocalDate cumulativeStart,
                         LocalDate cumulativeEnd, long unitWon, Lines current, Lines cumulative) {

        public ReleasePeriod releasePeriod() {
            return periodOf(periodStart, periodEnd);
        }
    }

    /** 서식을 못 읽었다 — 사유가 그대로 저장 상태가 된다. 기간을 읽었으면 함께 싣는다(실적 발표 판정은 기간만 본다). */
    public static final class Unreadable extends Exception {

        private final String reason;
        private final LocalDate periodStart;
        private final LocalDate periodEnd;

        Unreadable(String reason, LocalDate periodStart, LocalDate periodEnd) {
            super(reason);
            this.reason = reason;
            this.periodStart = periodStart;
            this.periodEnd = periodEnd;
        }

        public String reason() {
            return reason;
        }

        public LocalDate periodStart() {
            return periodStart;
        }

        public LocalDate periodEnd() {
            return periodEnd;
        }
    }

    private static final Map<String, Long> UNITS = Map.of("조원", 1_000_000_000_000L, "십억원", 1_000_000_000L, "억원", 100_000_000L,
            "백만원", 1_000_000L, "천원", 1_000L, "원", 1L);
    private static final Pattern UNIT = Pattern.compile("단위\\s*[:：]\\s*(조원|십억원|억원|백만원|천원|원)");
    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    /** 옛 서식의 분기 표지(공백을 지운 칸) — 연도 2 · 4자리 + 분기 숫자. */
    private static final Pattern QUARTER_LABEL = Pattern.compile("'?(\\d{4}|\\d{2})[.년]?([1-4])(?:Q|분기)");
    private static final Pattern CELL_OPEN = Pattern.compile("(?is)<t[dh]\\b[^>]*>");
    private static final Pattern CELL_CLOSE = Pattern.compile("(?i)</t[dh]>");
    private static final Pattern TAG = Pattern.compile("(?s)<[^>]*>");
    private static final Pattern NUMERIC_ENTITY = Pattern.compile("&#(\\d+);");
    private static final Charset KOREAN = Charset.forName("x-windows-949");

    private DartProvisionalDocument() {
    }

    /** 원문 바이트를 읽는다 — 머리가 UTF-8이라 말해도 본문은 EUC-KR(MS949)로 온다(2026-11-10 실측 · 20261029800099). */
    public static String decode(byte[] raw) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString();
        } catch (CharacterCodingException e) {
            return new String(raw, KOREAN);
        }
    }

    /** 제목이 어느 서식인가 — 실적 발표 이름 판정({@link DartEarningsReleaseNames})을 지난 제목만 온다. */
    public static Form formOf(String reportName) {
        String name = reportName.replaceAll("\\s", "");
        if (name.contains("(잠정)실적")) {
            return Form.I002;
        }
        if (name.contains("매출액또는손익구조")) {
            return Form.I001;
        }
        throw new IllegalArgumentException("실적 발표 서식이 아니다: " + reportName);
    }

    public static Parsed parse(byte[] raw, String reportName) throws Unreadable {
        List<String> cells = cells(decode(raw));
        return formOf(reportName) == Form.I002 ? i002(cells, reportName) : i001(cells);
    }

    private static Parsed i002(List<String> cells, String reportName) throws Unreadable {
        int anchor = lastIndex(cells, "실적기간");
        LocalDate[] current;
        LocalDate[] cumulative;
        if (anchor >= 0) {
            current = periodAfter(cells, anchor, "당기실적");
            cumulative = periodAfter(cells, anchor, "당기누계실적");
        } else {
            anchor = lastMatching(cells, "^\\d\\.(연결)?실적내용$");
            if (anchor < 0) {
                throw new Unreadable("no-anchor", null, null);
            }
            current = quarterLabelAfter(cells, anchor);
            if (current == null) {
                throw new Unreadable("no-period-label", null, null);
            }
            cumulative = new LocalDate[]{current[1].withDayOfYear(1), current[1]};
        }
        if (current == null) {
            throw new Unreadable("no-period", null, null);
        }
        if (cumulative == null) {
            throw new Unreadable("no-cumulative-period", current[0], current[1]);
        }
        Long unit = unitAfter(cells, anchor);
        if (unit == null) {
            throw new Unreadable("no-unit", current[0], current[1]);
        }
        BigDecimal[][] values = new BigDecimal[5][];
        String[] labels = {"매출액", "영업이익", "법인세비용차감전", "당기순이익", "지배기업소유주"};
        for (int k = 0; k < labels.length; k++) {
            values[k] = i002Line(cells, anchor, labels[k], k >= 2);
        }
        boolean consolidated = reportName.replaceAll("\\s", "").contains("연결재무제표");
        return new Parsed(Form.I002, consolidated, current[0], current[1], cumulative[0], cumulative[1], unit,
                new Lines(values[0][0], values[1][0], values[2][0], values[3][0], values[4][0]),
                new Lines(values[0][1], values[1][1], values[2][1], values[3][1], values[4][1]));
    }

    /** I002 한 줄의 {당해, 누계} — 줄 이름 칸 뒤 「당해실적」 다음 칸 · 「누계실적」 다음 칸이 당기 값이다. 줄이 없으면 둘 다 없음. */
    private static BigDecimal[] i002Line(List<String> cells, int anchor, String label, boolean prefix) {
        for (int i = anchor + 1; i < cells.size(); i++) {
            String c = compact(cells.get(i));
            if (prefix ? c.startsWith(label) : c.equals(label)) {
                BigDecimal quarter = null;
                BigDecimal cumulative = null;
                for (int j = i + 1; j < Math.min(cells.size() - 1, i + 4); j++) {
                    if (compact(cells.get(j)).equals("당해실적")) {
                        quarter = number(cells.get(j + 1));
                        break;
                    }
                }
                for (int j = i + 1; j < Math.min(cells.size() - 1, i + 21); j++) {
                    if (compact(cells.get(j)).equals("누계실적")) {
                        cumulative = number(cells.get(j + 1));
                        break;
                    }
                }
                return new BigDecimal[]{quarter, cumulative};
            }
        }
        return new BigDecimal[]{null, null};
    }

    private static Parsed i001(List<String> cells) throws Unreadable {
        int anchor = -1;
        for (int i = 0; i < cells.size(); i++) {
            if (compact(cells.get(i)).endsWith("재무제표의종류")) {
                anchor = i;
            }
        }
        if (anchor < 0 || anchor + 1 >= cells.size()) {
            throw new Unreadable("no-anchor", null, null);
        }
        String kind = compact(cells.get(anchor + 1));
        boolean consolidated;
        if (kind.startsWith("연결")) {
            consolidated = true;
        } else if (kind.startsWith("별도") || kind.startsWith("개별")) {
            consolidated = false;
        } else {
            throw new Unreadable("no-basis", null, null);
        }
        LocalDate start = dateAfterLabel(cells, anchor, "-시작일");
        LocalDate end = dateAfterLabel(cells, anchor, "-종료일");
        if (start == null || end == null) {
            throw new Unreadable("no-period", start, end);
        }
        Long unit = unitAfter(cells, anchor);
        if (unit == null) {
            throw new Unreadable("no-unit", start, end);
        }
        Lines year = new Lines(valueAfterLabel(cells, anchor, "-매출액"), valueAfterLabel(cells, anchor, "-영업이익"),
                valueAfterLabel(cells, anchor, "-법인세비용차감전계속사업이익"), valueAfterLabel(cells, anchor, "-당기순이익"), null);
        return new Parsed(Form.I001, consolidated, start, end, start, end, unit, year, year);
    }

    /** 닻 뒤 첫 {@code label} 칸 다음의 날짜 둘(시작 · 끝). */
    private static LocalDate[] periodAfter(List<String> cells, int anchor, String label) {
        for (int i = anchor + 1; i < cells.size(); i++) {
            if (compact(cells.get(i)).equals(label)) {
                List<LocalDate> dates = new ArrayList<>();
                for (int j = i + 1; j < Math.min(cells.size(), i + 6) && dates.size() < 2; j++) {
                    LocalDate d = date(cells.get(j));
                    if (d != null) {
                        dates.add(d);
                    }
                }
                return dates.size() == 2 ? new LocalDate[]{dates.get(0), dates.get(1)} : null;
            }
        }
        return null;
    }

    private static LocalDate dateAfterLabel(List<String> cells, int anchor, String label) {
        for (int i = anchor + 1; i < cells.size() - 1; i++) {
            if (compact(cells.get(i)).equals(label)) {
                return date(cells.get(i + 1));
            }
        }
        return null;
    }

    private static BigDecimal valueAfterLabel(List<String> cells, int anchor, String label) {
        for (int i = anchor + 1; i < cells.size() - 1; i++) {
            if (compact(cells.get(i)).equals(label)) {
                return number(cells.get(i + 1));
            }
        }
        return null;
    }

    private static Long unitAfter(List<String> cells, int anchor) {
        for (int i = anchor + 1; i < cells.size(); i++) {
            Matcher m = UNIT.matcher(cells.get(i));
            if (m.find()) {
                return UNITS.get(m.group(1));
            }
        }
        return null;
    }

    /** 옛 서식 — 닻 뒤 첫 분기 표지(당기 칸 머리 · 당기 · 전기 · 전년동기 순서로 온다)의 분기 {시작, 끝}. */
    private static LocalDate[] quarterLabelAfter(List<String> cells, int anchor) {
        for (int i = anchor + 1; i < Math.min(cells.size(), anchor + 20); i++) {
            String c = compact(cells.get(i));
            if (c.startsWith("매출액")) {
                return null;
            }
            Matcher m = QUARTER_LABEL.matcher(c);
            if (m.find()) {
                int year = Integer.parseInt(m.group(1));
                year = year < 100 ? 2000 + year : year;
                int quarter = Integer.parseInt(m.group(2));
                LocalDate start = LocalDate.of(year, quarter * 3 - 2, 1);
                return new LocalDate[]{start, start.plusMonths(3).minusDays(1)};
            }
        }
        return null;
    }

    private static int lastMatching(List<String> cells, String regex) {
        int found = -1;
        for (int i = 0; i < cells.size(); i++) {
            if (compact(cells.get(i)).matches(regex)) {
                found = i;
            }
        }
        return found;
    }

    private static int lastIndex(List<String> cells, String label) {
        int found = -1;
        for (int i = 0; i < cells.size(); i++) {
            if (compact(cells.get(i)).equals(label)) {
                found = i;
            }
        }
        return found;
    }

    /** 본문 기간의 꼴 — 분기 말에 끝나는 석 달이면 분기, 열두 달이면 연도, 나머지(월간 · 반기 누계 단독 …)는 실적 발표가 아니다. */
    public static ReleasePeriod periodOf(LocalDate start, LocalDate end) {
        if (start == null || end == null || !end.equals(end.withDayOfMonth(end.lengthOfMonth())) || end.getMonthValue() % 3 != 0
                || start.getDayOfMonth() != 1) {
            return ReleasePeriod.OTHER;
        }
        long months = java.time.temporal.ChronoUnit.MONTHS.between(start, end.plusDays(1));
        return months == 3 ? ReleasePeriod.QUARTER : months == 12 ? ReleasePeriod.YEAR : ReleasePeriod.OTHER;
    }

    /**
     * 표 칸의 글자 — 칸마다 그 칸을 연 {@code <td>} 뒤만 쓴다(표 앞 제목 글자가 첫 칸에 붙지 않게). 태그 · 엔터티를 걷고 공백을 접는다.
     */
    static List<String> cells(String html) {
        String body = html.replaceAll("(?is)<(style|script)\\b.*?</\\1>", " ");
        List<String> out = new ArrayList<>();
        for (String segment : CELL_CLOSE.split(body)) {
            Matcher open = CELL_OPEN.matcher(segment);
            int start = -1;
            while (open.find()) {
                start = open.end();
            }
            if (start < 0) {
                continue;
            }
            out.add(text(segment.substring(start)));
        }
        return out;
    }

    private static String text(String fragment) {
        String s = TAG.matcher(fragment).replaceAll(" ")
                .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'");
        Matcher m = NUMERIC_ENTITY.matcher(s);
        StringBuilder b = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(b, Matcher.quoteReplacement(String.valueOf((char) Integer.parseInt(m.group(1)))));
        }
        m.appendTail(b);
        return b.toString().replace("&amp;", "&").replace(' ', ' ').replaceAll("\\s+", " ").strip();
    }

    private static String compact(String cell) {
        return cell.replaceAll("\\s", "");
    }

    private static LocalDate date(String cell) {
        Matcher m = DATE.matcher(cell);
        if (!m.find()) {
            return null;
        }
        try {
            return LocalDate.parse(m.group());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** 칸의 수 — {@code -}(없음) · 빈 칸은 {@code null}. {@code △}/{@code ▲} · 괄호는 음수다. 수가 아니면 {@code null}. */
    static BigDecimal number(String cell) {
        String s = cell.replace(",", "").replace(" ", "");
        boolean negative = false;
        if (s.startsWith("△") || s.startsWith("▲")) {
            negative = true;
            s = s.substring(1);
        } else if (s.startsWith("(") && s.endsWith(")")) {
            negative = true;
            s = s.substring(1, s.length() - 1);
        }
        if (!s.matches("-?\\d+(\\.\\d+)?")) {
            return null;
        }
        BigDecimal v = new BigDecimal(s);
        return negative ? v.negate() : v;
    }
}
