package com.stockscreener.pipeline.datasource.us;

import com.stockscreener.pipeline.collector.JdbcStoredShareCounts;
import com.stockscreener.pipeline.collector.JdbcStoredUsRows;
import com.stockscreener.pipeline.collector.UniverseLedgerRestorer;
import com.stockscreener.pipeline.datasource.IdentitySource;
import com.stockscreener.pipeline.datasource.SecurityNameCache;
import com.stockscreener.pipeline.datasource.http.DataSourceProperties;
import com.stockscreener.pipeline.datasource.http.ExternalApiClientFactory;
import com.stockscreener.pipeline.datasource.http.TokenBucketRateLimiter;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * R-D4a — 과거 날짜 순위를 <b>잡이 부를 함수 그대로</b>({@link UsPastRanking#rosters}) 운영의 후보 풀 전체에 대 지금의 복원 평면과 격자일마다
 * 견준다(탐색 {@code US-PAST-LISTING-IMPL-1018.md} §4). 운영 DB는 읽기 전용 세션이고 쓰지 않는다. 운영에 클래스 축 표가 아직 없어 주식수는
 * 꺼진 판독기로 읽는다 — 클래스 보류는 이 탐침이 못 잰다(관문 날 몫).
 *
 * <p>칠 때: {@code ( set -a; . ./.env; set +a; PAST_PROBE=1 PAST_PROBE_OUT=<디렉터리> PAST_PROBE_CACHE=<SEC 캐시> ./gradlew liveTest
 * --tests '*PastRankingProbeLiveTest*' -PskipFrontend -i )}
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "PAST_PROBE", matches = ".+")
class PastRankingProbeLiveTest {

    @Test
    void probe() throws Exception {
        Path out = Files.createDirectories(Path.of(System.getenv().getOrDefault("PAST_PROBE_OUT", "build/past-probe")));
        Path cache = Path.of(System.getenv().getOrDefault("PAST_PROBE_CACHE", out.resolve("cache").toString()));
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:postgresql://localhost:5432/stock_screener?options=-c%20default_transaction_read_only%3Don",
                System.getProperty("user.name"), "");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        DataSourceProperties properties = new DataSourceProperties();
        properties.getAlpaca().setBaseUrl("https://paper-api.alpaca.markets");
        properties.getAlpaca().setApiKey(System.getenv("APCA_API_KEY_ID"));
        properties.getAlpaca().setSecretKey(System.getenv("APCA_API_SECRET_KEY"));
        properties.getAlpaca().setReadTimeout(Duration.ofSeconds(60));
        properties.getSecEdgar().setApiKey(System.getenv("SEC_EDGAR_USER_AGENT"));
        properties.getSecEdgar().setReadTimeout(Duration.ofSeconds(60));
        ExternalApiClientFactory factory = new ExternalApiClientFactory();
        TokenBucketRateLimiter alpaca = new TokenBucketRateLimiter("alpaca", 150, 0);
        TokenBucketRateLimiter sec = new TokenBucketRateLimiter("sec-edgar", 480, 0);
        IdentitySource identity = IdentityProbeSupport.source(properties, factory, alpaca, sec);
        CachingListingFormClient secClient = new CachingListingFormClient(new SecDailyIndexClient(factory, properties, sec),
                new SecFilingClient(factory, properties, sec), factory, properties, sec, cache);
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("identity", identity);
        beans.addBean("events", new ListingEventSource(secClient));
        beans.addBean("sec", secClient);
        UsPastRanking ranking = new UsPastRanking(new JdbcStoredShareCounts(jdbc), new JdbcStoredUsRows(jdbc),
                beans.getBeanProvider(IdentitySource.class), beans.getBeanProvider(ListingEventSource.class),
                beans.getBeanProvider(SecListingFormClient.class),
                new AlpacaUniverseAdapter(factory, properties, alpaca, new SecurityNameCache()));

        UniverseLedgerRestorer restorer = new UniverseLedgerRestorer(jdbc);
        List<LocalDate> grid = restorer.weeklyGrid("US", restorer.observedFloor("US_TOP1000").orElseThrow());
        List<UsPastRanking.Roster> rosters = ranking.rosters(grid);

        Map<Long, String> tickers = new HashMap<>();
        jdbc.query("SELECT id, ticker FROM stock WHERE market_code = 'US'", rs -> {
            tickers.put(rs.getLong(1), rs.getString(2));
        });
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(out.resolve("diff.tsv")))) {
            w.println("date\tnew\trestored\tkept\tentered\tleft\tnote");
            for (UsPastRanking.Roster roster : rosters) {
                Set<Long> restored = new LinkedHashSet<>(jdbc.queryForList("""
                        SELECT stock_id FROM universe_membership WHERE universe_code = 'US_TOP1000' AND origin = 'RESTORED'
                          AND effective_from <= ? AND effective_to > ?""", Long.class, roster.date(), roster.date()));
                Set<Long> now = new LinkedHashSet<>(roster.stockIds());
                Set<Long> entered = new LinkedHashSet<>(now);
                entered.removeAll(restored);
                Set<Long> left = new LinkedHashSet<>(restored);
                left.removeAll(now);
                Set<Long> kept = new LinkedHashSet<>(now);
                kept.retainAll(restored);
                w.println(String.join("\t", roster.date().toString(), String.valueOf(now.size()), String.valueOf(restored.size()),
                        String.valueOf(kept.size()), names(entered, tickers), names(left, tickers), roster.note()));
            }
        }
        System.out.println("R-D4a 격자 " + grid.size() + " · 결과 " + out.resolve("diff.tsv"));
    }

    private static String names(Set<Long> ids, Map<Long, String> tickers) {
        return ids.stream().map(id -> tickers.getOrDefault(id, "#" + id)).sorted().toList().toString();
    }
}
