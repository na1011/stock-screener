package com.stockscreener.pipeline.datasource.us;

import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.IdentitySource;
import com.stockscreener.pipeline.datasource.IdentitySource.BarAsOf;
import com.stockscreener.pipeline.datasource.IdentitySource.EntityBar;
import com.stockscreener.pipeline.datasource.SecurityNameCache;
import com.stockscreener.pipeline.datasource.StoredShareCounts;
import com.stockscreener.pipeline.datasource.StoredUsRows;
import com.stockscreener.pipeline.datasource.http.DataSourceProperties;
import com.stockscreener.pipeline.datasource.http.ExternalApiClientFactory;
import com.stockscreener.pipeline.datasource.http.TokenBucketRateLimiter;
import com.stockscreener.pipeline.datasource.us.listing.ListingEvent;
import com.stockscreener.pipeline.datasource.us.listing.Venue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 과거 날짜 순위(D4) — 후보는 행 · 그날 범주 · 닻 봉(탐색 {@code US-PAST-LISTING-PLAN-1017.md} §3 D4 · 구현 계획 U4). */
class UsPastRankingTest {

    private static final LocalDate ANCHOR = LocalDate.of(2026, 8, 28);
    private static final LocalDate D1 = LocalDate.of(2025, 11, 7);
    private static final LocalDate D2 = LocalDate.of(2026, 1, 9);

    private final Map<Long, StoredUsRows.Row> rows = new HashMap<>();
    private final Map<Long, BigDecimal> shares = new HashMap<>();
    private final Map<String, List<EntityBar>> barsBySymbol = new HashMap<>();
    private final List<String> barRequests = new ArrayList<>();
    private final Map<Long, List<ListingEvent>> events = new HashMap<>();
    private final Set<String> listedToday = new java.util.HashSet<>();

    @Test
    void rowsAreRankedByMarketCapFromAnchorBars() throws Exception {
        row(1, "BIG", 100L, ANCHOR, "1000", 50);
        row(2, "MID", 200L, ANCHOR, "100", 50);
        row(3, "SMALL", 300L, ANCHOR, "10", 50);

        List<UsPastRanking.Roster> rosters = ranking(2).rosters(List.of(D1, D2));

        assertThat(rosters).extracting(UsPastRanking.Roster::stockIds).containsExactly(List.of(1L, 2L), List.of(1L, 2L));
        assertThat(barRequests).as("닻 날짜 묶음 하나 — 격자일마다 다시 안 묻는다").hasSize(1);
    }

    /** LEU — 2025-12-03 상향 전에는 NYSE American이었다(탐색 ★10 · 복원이 09-24부터 편입했었다). */
    @Test
    void dateOutsideTheExchangeCategoryIsNotACandidate() throws Exception {
        row(1, "LEUT", 100L, ANCHOR, "1000", 50);
        row(2, "MID", 200L, ANCHOR, "100", 50);
        row(3, "SMALL", 300L, ANCHOR, "10", 50);
        events.put(100L, List.of(
                new ListingEvent(LocalDate.of(2025, 12, 3), new Venue("NYSE", true), true, "Class A Common Stock", Optional.of("A"),
                        "8-A12B", "r", Optional.empty()),
                new ListingEvent(LocalDate.of(2025, 12, 21), new Venue("NYSE American", false), false, "Class A Common Stock",
                        Optional.of("A"), "25", "x", Optional.of(LocalDate.of(2025, 12, 4)))));

        List<UsPastRanking.Roster> rosters = ranking(2).rosters(List.of(D1, D2));

        assertThat(rosters.get(0).stockIds()).containsExactly(2L, 3L);
        assertThat(rosters.get(0).note()).contains("범주 밖 1");
        assertThat(rosters.get(1).stockIds()).containsExactly(1L, 2L);
    }

    /** BIO.B꼴 — 거래가 64일 비어도 상장이다(★5). 종가는 그날 이하 마지막 거래 봉이다. */
    @Test
    void sparseTradingStaysListedWithTheLastTradedClose() throws Exception {
        row(1, "BIG", 100L, ANCHOR, "1000", 50);
        row(2, "SPARSE", 200L, ANCHOR, "100", 50);
        barsBySymbol.put("SPARSE", List.of(bar(D1.minusDays(70), "500", 3), bar(D2.plusDays(5), "500", 3)));
        row(3, "SMALL", 300L, ANCHOR, "10", 50);

        List<UsPastRanking.Roster> rosters = ranking(2).rosters(List.of(D1));

        assertThat(rosters.get(0).stockIds()).containsExactly(2L, 1L);
    }

    @Test
    void entityThatHasNotStartedTradingIsNotACandidate() throws Exception {
        row(1, "BIG", 100L, ANCHOR, "1000", 50);
        row(2, "IPO", 200L, ANCHOR, "5000", 50);
        barsBySymbol.put("IPO", List.of(bar(D2.minusDays(3), "50", 10)));
        row(3, "SMALL", 300L, ANCHOR, "10", 50);

        List<UsPastRanking.Roster> rosters = ranking(2).rosters(List.of(D1, D2));

        assertThat(rosters.get(0).stockIds()).containsExactly(1L, 3L);
        assertThat(rosters.get(1).stockIds()).containsExactly(2L, 1L);
    }

    /** 같은 등록인의 두 행(LBRDA · LBRDK꼴) — 거래대금 큰 하나가 대표다. 묶이지 않은 행은 남기고 센다. */
    @Test
    void sameRegistrantRowsKeepOneRepresentative() throws Exception {
        row(1, "LBRA", 100L, ANCHOR, "1000", 5);
        row(2, "LBRK", 100L, ANCHOR, "1000", 500);
        row(3, "FREE", null, ANCHOR, "10", 50);

        List<UsPastRanking.Roster> rosters = ranking(2).rosters(List.of(D1));

        assertThat(rosters.get(0).stockIds()).containsExactly(2L, 3L);
        assertThat(rosters.get(0).note()).contains("묶이지 않은 후보 1");
    }

    @Test
    void closedRowIsAskedByItsAnchorNotItsLabel() throws Exception {
        row(1, "WBDT~2026-10-05", 100L, ANCHOR, "1000", 50);
        rows.put(1L, new StoredUsRows.Row(1, "WBDT~2026-10-05", "Warner Common Stock", "WBDT", ANCHOR, 100L, null,
                LocalDate.of(2026, 10, 6)));
        row(2, "MID", 200L, ANCHOR, "100", 50);

        List<UsPastRanking.Roster> rosters = ranking(2).rosters(List.of(D1));

        assertThat(rosters.get(0).stockIds()).containsExactly(1L, 2L);
        assertThat(barRequests).singleElement().asString().contains("WBDT").doesNotContain("~");
    }

    @Test
    void tooFewCandidatesIsAPartialListNotARoster() {
        row(1, "BIG", 100L, ANCHOR, "1000", 50);

        assertThatThrownBy(() -> ranking(2).rosters(List.of(D1))).isInstanceOf(DataSourceException.class)
                .hasMessageContaining("상위 2를 채우지 못한다");
    }

    @Test
    void missingClosesBelowTheCoverageFloorStop() {
        for (int i = 1; i <= 10; i++) {
            row(i, "S" + i, (long) i, ANCHOR, "100", 50);
        }
        barsBySymbol.put("S1", List.of());
        barsBySymbol.put("S2", List.of());

        assertThatThrownBy(() -> ranking(2).rosters(List.of(D1))).isInstanceOf(DataSourceException.class)
                .hasMessageContaining("하한 미만");
    }

    // ---- 재료 -------------------------------------------------------------------------------------------------

    private void row(long id, String ticker, Long cik, LocalDate anchorDate, String sharesCount, long volume) {
        String symbol = ticker.contains("~") ? ticker.substring(0, ticker.indexOf('~')) : ticker;
        rows.put(id, new StoredUsRows.Row(id, ticker, symbol + " Inc. Common Stock", symbol, anchorDate, cik, null, null));
        shares.put(id, new BigDecimal(sharesCount));
        List<EntityBar> series = new ArrayList<>();
        for (LocalDate d = D1.minusDays(30); !d.isAfter(D2); d = d.plusDays(1)) {
            series.add(new EntityBar(d, new BigDecimal("10"), volume, 20));
        }
        barsBySymbol.put(symbol, series);
    }

    private static EntityBar bar(LocalDate date, String close, long trades) {
        return new EntityBar(date, new BigDecimal(close), 100, trades);
    }

    private UsPastRanking ranking(int topN) {
        StoredShareCounts counts = new StoredShareCounts() {
            @Override
            public List<ShareCount> activeByMarket(String marketCode, LocalDate oldestPeriodEnd, LocalDate asOf) {
                List<ShareCount> out = new ArrayList<>();
                shares.forEach((id, n) -> {
                    StoredUsRows.Row r = rows.get(id);
                    if (r.eraTo() == null || r.eraTo().isAfter(asOf)) {
                        out.add(new ShareCount(r.ticker(), n, "3711", asOf.minusMonths(3), asOf.minusMonths(1), false, r.name(), id));
                    }
                });
                return out;
            }
        };
        StoredUsRows stored = () -> List.copyOf(rows.values());
        IdentitySource identity = (IdentitySource) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {IdentitySource.class},
                (proxy, method, args) -> {
                    if (!"bars".equals(method.getName())) {
                        throw new UnsupportedOperationException(method.getName());
                    }
                    @SuppressWarnings("unchecked") Collection<String> symbols = (Collection<String>) args[0];
                    BarAsOf asOf = (BarAsOf) args[1];
                    barRequests.add(asOf.date() + " " + new java.util.TreeSet<>(symbols));
                    Map<String, List<EntityBar>> out = new HashMap<>();
                    symbols.forEach(s -> {
                        if (barsBySymbol.containsKey(s) && !barsBySymbol.get(s).isEmpty()) {
                            out.put(s, barsBySymbol.get(s));
                        }
                    });
                    return out;
                });
        DataSourceProperties properties = new DataSourceProperties();
        properties.getSecEdgar().setApiKey("Test Runner test@example.com");
        ExternalApiClientFactory factory = new ExternalApiClientFactory();
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter("x", 0, 0);
        SecListingFormClient sec = new SecListingFormClient(null, null, factory, properties, limiter) {
            @Override
            public Optional<Cover> lastCoverBefore(long cik, LocalDate before, LocalDate since) {
                return Optional.empty();
            }
        };
        ListingEventSource listingEvents = new ListingEventSource(sec) {
            @Override
            public Result events(Set<Long> ciks, LocalDate from, LocalDate to) {
                Map<Long, List<ListingEvent>> byCik = new HashMap<>();
                events.forEach((cik, list) -> {
                    if (ciks.contains(cik)) {
                        byCik.put(cik, list);
                    }
                });
                return new Result(byCik, 0, List.of());
            }
        };
        AlpacaUniverseAdapter listing = new AlpacaUniverseAdapter(factory, properties, limiter, new SecurityNameCache()) {
            @Override
            List<AlpacaAsset> fetchActiveAssets() {
                return listedToday.stream().map(s -> new AlpacaAsset(s, s, "NYSE", "active", true)).toList();
            }
        };
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("identity", identity);
        beans.addBean("events", listingEvents);
        beans.addBean("sec", sec);
        return new UsPastRanking(counts, stored, beans.getBeanProvider(IdentitySource.class),
                beans.getBeanProvider(ListingEventSource.class), beans.getBeanProvider(SecListingFormClient.class), listing, topN);
    }
}
