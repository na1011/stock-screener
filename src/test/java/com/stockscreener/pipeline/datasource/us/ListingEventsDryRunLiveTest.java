package com.stockscreener.pipeline.datasource.us;

import com.stockscreener.pipeline.collector.UniverseLedgerRestorer;
import com.stockscreener.pipeline.datasource.SecurityRef;
import com.stockscreener.pipeline.datasource.http.DataSourceProperties;
import com.stockscreener.pipeline.datasource.http.ExternalApiClientFactory;
import com.stockscreener.pipeline.datasource.http.TokenBucketRateLimiter;
import com.stockscreener.pipeline.datasource.us.listing.ListedTimeline;
import com.stockscreener.pipeline.datasource.us.listing.ListingCover;
import com.stockscreener.pipeline.datasource.us.listing.ListingEvent;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * R-D5 드라이런 — 묶인 등록인 전원의 창 안 거래소 사건을 <b>잡이 쓸 함수 그대로</b>({@link ListingEventSource} → {@link ListedTimeline})
 * 태워 행마다 복원 격자일의 범주를 낸다(탐색 {@code US-PAST-LISTING-IMPL-1018.md} §4). 운영 DB는 읽기 전용 세션이고 쓰지 않는다.
 * 사람이 구성원마다 열어 판정한다 — 이 시험은 단언하지 않고 TSV만 낸다.
 *
 * <p>칠 때: {@code ( set -a; . ./.env; set +a; LISTING_DRY=1 LISTING_DRY_OUT=<디렉터리> ./gradlew liveTest --tests
 * '*ListingEventsDryRunLiveTest*' -PskipFrontend -i )}
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "LISTING_DRY", matches = ".+")
class ListingEventsDryRunLiveTest {

    /** 짝 창 — 잡과 같은 값. */
    private static final int PAIR_DAYS = ListedTimeline.PAIR_DAYS;

    private record Row(long id, String ticker, long cik, LocalDate anchor, LocalDate eraFrom, LocalDate eraTo, String name) {
        String symbol() {
            int cut = ticker.indexOf(SecurityRef.VACATED);
            return cut < 0 ? ticker : ticker.substring(0, cut);
        }
    }

    @Test
    void dryRun() throws Exception {
        Path out = Path.of(System.getenv().getOrDefault("LISTING_DRY_OUT", "build/listing-dry"));
        Files.createDirectories(out);
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:postgresql://localhost:5432/stock_screener?options=-c%20default_transaction_read_only%3Don",
                System.getProperty("user.name"), "");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        List<Row> rows = jdbc.query("""
                SELECT id, ticker, sec_cik, alpaca_anchor_date, era_from, era_to, name FROM stock
                WHERE market_code = 'US' AND sec_cik IS NOT NULL ORDER BY sec_cik, ticker
                """, (rs, n) -> new Row(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getObject(4, LocalDate.class),
                rs.getObject(5, LocalDate.class), rs.getObject(6, LocalDate.class), rs.getString(7)));
        UniverseLedgerRestorer restorer = new UniverseLedgerRestorer(jdbc);
        LocalDate floor = restorer.observedFloor("US_TOP1000").orElseThrow();
        List<LocalDate> grid = restorer.weeklyGrid("US", floor);
        LocalDate today = LocalDate.now(ZoneId.of("America/New_York"));

        DataSourceProperties properties = new DataSourceProperties();
        properties.getSecEdgar().setApiKey(System.getenv("SEC_EDGAR_USER_AGENT"));
        properties.getSecEdgar().setReadTimeout(Duration.ofSeconds(60));
        ExternalApiClientFactory factory = new ExternalApiClientFactory();
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter("sec-edgar", 480, 0);
        SecFilingClient filings = new SecFilingClient(factory, properties, limiter);
        SecListingFormClient client = new CachingListingFormClient(new SecDailyIndexClient(factory, properties, limiter), filings,
                factory, properties, limiter, out.resolve("cache"));
        ListingEventSource source = new ListingEventSource(client);

        Map<Long, List<Row>> byCik = rows.stream().collect(Collectors.groupingBy(Row::cik, TreeMap::new, Collectors.toList()));
        LocalDate from = grid.get(0).minusDays(PAIR_DAYS + 15);
        ListingEventSource.Result result = source.events(byCik.keySet(), from, today);

        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(out.resolve("unreadable.tsv")))) {
            result.unreadable().forEach(w::println);
        }
        int changed = 0;
        int unknown = 0;
        int unmatched = 0;
        try (PrintWriter ev = new PrintWriter(Files.newBufferedWriter(out.resolve("events.tsv")));
             PrintWriter rv = new PrintWriter(Files.newBufferedWriter(out.resolve("rows.tsv")))) {
            ev.println("cik\ttickers\tticker\tform\taccession\teffective\tvenue\tlisted\tregistration\tletter\tkind\tline");
            rv.println("ticker\tcik\tanchor\tera\tletter\tverdicts(격자일 중 안이 아닌 것)\tevents");
            for (Map.Entry<Long, List<ListingEvent>> e : result.byCik().entrySet()) {
                // 공유 판정은 보통주 행만 센다 — 순위 이름 축과 같은 술어(우선주 BMNP가 BMNR을 「둘」로 만들었다 · R-D5 1판).
                List<Row> cikRows = byCik.getOrDefault(e.getKey(), List.of()).stream()
                        .filter(r -> !UsMarketCapUniverseAdapter.nonCommonName(r.name())).toList();
                boolean shared = cikRows.size() > 1;
                Map<String, Optional<String>> letters = new LinkedHashMap<>();
                if (shared) {
                    LocalDate first = e.getValue().stream().map(ListingEvent::date).min(LocalDate::compareTo).orElse(today);
                    Optional<SecListingFormClient.Cover> cover = client.lastCoverBefore(e.getKey(), first.plusDays(1), first.minusYears(2));
                    for (Row r : cikRows) {
                        letters.put(r.ticker(), cover.flatMap(c -> ListingCover.lineFor(c.facts(), r.symbol())).flatMap(ListingCover.Line::letter));
                    }
                    List<ListingEvent> lost = ListedTimeline.unmatched(e.getValue(),
                            letters.values().stream().flatMap(Optional::stream).collect(Collectors.toSet()));
                    unmatched += lost.size();
                    for (ListingEvent l : lost) {
                        ev.println(String.join("\t", String.valueOf(e.getKey()), tickers(cikRows), "-", l.form(), l.accession(),
                                l.date().toString(), l.venue().name(), String.valueOf(l.venue().listed()),
                                String.valueOf(l.registration()), l.letter().orElse(""), "UNMATCHED", l.line()));
                    }
                }
                for (Row r : cikRows) {
                    List<ListingEvent> mine = ListedTimeline.forRow(e.getValue(), shared, letters.getOrDefault(r.ticker(), Optional.empty()));
                    ListedTimeline t = ListedTimeline.of(r.anchor(), true, mine, PAIR_DAYS);
                    for (ListedTimeline.Classified c : t.events()) {
                        ListingEvent l = c.event();
                        ev.println(String.join("\t", String.valueOf(e.getKey()), tickers(cikRows), r.ticker(), l.form(), l.accession(),
                                l.date().toString(), l.venue().name(), String.valueOf(l.venue().listed()),
                                String.valueOf(l.registration()), l.letter().orElse(""), c.kind().name(), l.line()));
                    }
                    List<String> off = new ArrayList<>();
                    for (LocalDate d : grid) {
                        boolean inEra = (r.eraFrom() == null || !r.eraFrom().isAfter(d)) && (r.eraTo() == null || r.eraTo().isAfter(d));
                        if (!inEra) {
                            continue;
                        }
                        ListedTimeline.Verdict v = t.on(d);
                        if (v != ListedTimeline.Verdict.LISTED) {
                            off.add(d + "=" + v);
                            if (v == ListedTimeline.Verdict.UNKNOWN) {
                                unknown++;
                            }
                        }
                    }
                    if (!off.isEmpty()) {
                        changed++;
                    }
                    rv.println(String.join("\t", r.ticker(), String.valueOf(r.cik()), String.valueOf(r.anchor()),
                            r.eraFrom() + "~" + r.eraTo(), letters.getOrDefault(r.ticker(), Optional.empty()).orElse(""),
                            off.isEmpty() ? "" : off.get(0) + (off.size() > 1 ? " … " + off.get(off.size() - 1) + " (" + off.size() + ")" : ""),
                            t.events().stream().map(c -> c.event().form() + "@" + c.event().date() + ":" + c.kind()).collect(Collectors.joining(","))));
                }
            }
        }
        String summary = "R-D5 등록인=%d 행=%d 격자=%d(%s~%s) 창=%s~%s 양식=%d 사건 등록인=%d 못 읽음=%d 범주가 바뀐 행=%d 모름 행·날=%d 못 붙인 사건=%d"
                .formatted(byCik.size(), rows.size(), grid.size(), grid.get(0), grid.get(grid.size() - 1), from, today,
                        result.forms(), result.byCik().size(), result.unreadable().size(), changed, unknown, unmatched);
        Files.writeString(out.resolve("summary.txt"), summary + "\n");
        System.out.println(summary);
    }

    private static String tickers(List<Row> rows) {
        return rows.stream().map(Row::ticker).collect(Collectors.joining(","));
    }
}
