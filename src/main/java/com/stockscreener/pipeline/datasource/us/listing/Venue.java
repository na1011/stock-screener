package com.stockscreener.pipeline.datasource.us.listing;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 증권이 상장된 거래소와 그 범주 — 미국 유니버스는 NYSE · Nasdaq만 「안」이다(ADR-0086 · 목록 조회의 {@code exchange=NYSE,NASDAQ}).
 * 탐색 {@code US-PAST-LISTING-PLAN-1017.md} D5.
 *
 * <p>원장이 거래소를 적는 꼴이 셋이다. 셋 다 <b>알려진 이름으로만</b> 읽고, 모르는 글자는 범주를 지어내지 않고 비운다 — 부르는 쪽이
 * 그 양식을 「못 읽음」으로 센다.
 * <ul>
 *   <li>{@code 25-NSE} XML의 {@code exchange/entityName}(「NEW YORK STOCK EXCHANGE LLC」 · 「NYSE AMERICAN LLC」)</li>
 *   <li>발행사 {@code 25}와 {@code 8-A12B} 본문의 거래소 이름(「The New York Stock Exchange」 · 「THE NASDAQ STOCK MARKET LLC」)</li>
 *   <li>표지 {@code dei:SecurityExchangeName}의 코드({@code NYSE} · {@code NASDAQ} · {@code NYSEAMER} …)</li>
 * </ul>
 *
 * <p>SEC 티커 파일의 거래소 표기는 쓰지 않는다 — NYSE American을 「NYSE」로 적는다(탐색 ★11).
 */
public record Venue(String name, boolean listed) {

    /**
     * 본문 이름. <b>긴 것이 먼저다</b> — 「NYSE American」을 「NYSE」로, 「Nasdaq Global Select Market」을 「Nasdaq」로 먼저 읽으면 안 된다.
     * 대소문자는 안 가린다. 앞의 정관사 「The」와 뒤의 「LLC」 · 「, Inc.」는 꼴의 일부다.
     */
    private static final List<Map.Entry<Pattern, Venue>> NAMES = List.of(
            entry("New\\s+York\\s+Stock\\s+Exchange\\s+American(?:\\s+LLC)?", "NYSE American", false),
            entry("NYSE\\s+American(?:\\s+LLC)?", "NYSE American", false),
            entry("NYSE\\s+MKT(?:\\s+LLC)?", "NYSE American", false),
            entry("NYSE\\s+Arca(?:,?\\s+Inc\\.?)?", "NYSE Arca", false),
            entry("NYSE\\s+Texas(?:,?\\s+Inc\\.?)?", "NYSE Texas", false),
            entry("NYSE\\s+Chicago(?:,?\\s+Inc\\.?)?", "NYSE Chicago", false),
            entry("NYSE\\s+National(?:,?\\s+Inc\\.?)?", "NYSE National", false),
            entry("Cboe\\s+[A-Z]{2,3}\\s+Exchange(?:,?\\s+Inc\\.?)?", "Cboe", false),
            entry("Texas\\s+Stock\\s+Exchange(?:\\s+LLC)?", "Texas Stock Exchange", false),
            entry("Investors\\s+Exchange(?:\\s+LLC)?", "IEX", false),
            entry("(?:The\\s+)?New\\s+York\\s+Stock\\s+Exchange(?:\\s+LLC)?", "NYSE", true),
            entry("(?:The\\s+)?Nasdaq\\s+(?:Global\\s+Select\\s+|Global\\s+|Capital\\s+)?(?:Stock\\s+)?Market(?:\\s+LLC)?", "Nasdaq", true),
            entry("(?:The\\s+)?NYSE(?:\\s+LLC)?", "NYSE", true),
            entry("(?:The\\s+)?NASDAQ(?:\\s+LLC)?", "Nasdaq", true));

    /** 표지 코드 — dei 거래소 코드 목록에서 나온 값. 모르는 코드는 비운다. */
    private static final Map<String, Venue> DEI_CODES = Map.ofEntries(
            Map.entry("NYSE", new Venue("NYSE", true)),
            Map.entry("NASDAQ", new Venue("Nasdaq", true)),
            Map.entry("NYSEAMER", new Venue("NYSE American", false)),
            Map.entry("NYSEARCA", new Venue("NYSE Arca", false)),
            Map.entry("NYSENAT", new Venue("NYSE National", false)),
            Map.entry("NYSETEXAS", new Venue("NYSE Texas", false)),
            Map.entry("CHX", new Venue("NYSE Chicago", false)),
            Map.entry("CBOE", new Venue("Cboe", false)),
            Map.entry("CBOEBZX", new Venue("Cboe", false)),
            Map.entry("CBOEBYX", new Venue("Cboe", false)),
            Map.entry("CBOEEDGA", new Venue("Cboe", false)),
            Map.entry("CBOEEDGX", new Venue("Cboe", false)),
            Map.entry("BOX", new Venue("BOX", false)),
            Map.entry("IEX", new Venue("IEX", false)),
            Map.entry("LTSE", new Venue("LTSE", false)),
            Map.entry("MIAX", new Venue("MIAX", false)),
            Map.entry("TXSE", new Venue("Texas Stock Exchange", false)));

    private static Map.Entry<Pattern, Venue> entry(String regex, String name, boolean listed) {
        return Map.entry(Pattern.compile(regex, Pattern.CASE_INSENSITIVE), new Venue(name, listed));
    }

    /** 이름 하나가 통째로 거래소 이름인가({@code 25-NSE}의 {@code entityName}). */
    public static Optional<Venue> ofName(String text) {
        String trimmed = text == null ? "" : text.strip();
        for (Map.Entry<Pattern, Venue> e : NAMES) {
            if (e.getKey().matcher(trimmed).matches()) {
                return Optional.of(e.getValue());
            }
        }
        return Optional.empty();
    }

    /**
     * 글자가 거래소 이름으로 <b>끝나는가</b> — 발행사 {@code 25}는 발행사 이름 바로 뒤에 거래소 이름을 붙여 적는다(「Centrus Energy Corp.
     * NYSE American LLC (Exact name of Issuer …」). 맞으면 그 거래소와 이름이 시작한 자리를 낸다.
     */
    public static Optional<Tail> ofTail(String text) {
        String trimmed = text == null ? "" : text.strip();
        for (Map.Entry<Pattern, Venue> e : NAMES) {
            Matcher m = Pattern.compile("(?:^|\\s)(" + e.getKey().pattern() + ")$", Pattern.CASE_INSENSITIVE).matcher(trimmed);
            if (m.find()) {
                return Optional.of(new Tail(e.getValue(), m.start(1)));
            }
        }
        return Optional.empty();
    }

    /** 표지 {@code dei:SecurityExchangeName}의 코드. */
    public static Optional<Venue> ofDeiCode(String code) {
        return Optional.ofNullable(code == null ? null : DEI_CODES.get(code.strip().toUpperCase(Locale.ROOT)));
    }

    /** @param start 이름이 시작한 글자 자리(그 앞이 발행사 이름) */
    public record Tail(Venue venue, int start) {
    }

    /**
     * 본문에서 거래소 이름이 나오는 자리 전부 — {@code 8-A12B}의 「종류 · 거래소」 표가 종류마다 거래소 이름을 되풀이한다. 겹치면 긴 이름이
     * 이긴다(「NYSE American」 안의 「NYSE」를 따로 세지 않는다).
     */
    public static List<Found> findAll(String text) {
        java.util.List<Found> out = new java.util.ArrayList<>();
        for (Map.Entry<Pattern, Venue> e : NAMES) {
            Matcher m = Pattern.compile("(?<![A-Za-z])(" + e.getKey().pattern() + ")(?![A-Za-z])", Pattern.CASE_INSENSITIVE)
                    .matcher(text);
            while (m.find()) {
                int s = m.start(1);
                int t = m.end(1);
                boolean covered = out.stream().anyMatch(f -> f.start() < t && s < f.end());
                if (!covered) {
                    out.add(new Found(e.getValue(), s, t));
                }
            }
        }
        out.sort(java.util.Comparator.comparingInt(Found::start));
        return out;
    }

    /** 본문 안의 거래소 이름 한 자리. */
    public record Found(Venue venue, int start, int end) {
    }
}
