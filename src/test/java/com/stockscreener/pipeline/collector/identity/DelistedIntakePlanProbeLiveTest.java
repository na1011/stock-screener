package com.stockscreener.pipeline.collector.identity;

import com.stockscreener.pipeline.datasource.IdentitySource;
import com.stockscreener.pipeline.datasource.http.DataSourceProperties;
import com.stockscreener.pipeline.datasource.http.ExternalApiClientFactory;
import com.stockscreener.pipeline.datasource.http.TokenBucketRateLimiter;
import com.stockscreener.pipeline.datasource.us.CachingListingFormClient;
import com.stockscreener.pipeline.datasource.us.IdentityProbeSupport;
import com.stockscreener.pipeline.datasource.us.SecDailyIndexClient;
import com.stockscreener.pipeline.datasource.us.SecFilingClient;
import com.stockscreener.pipeline.datasource.us.SecRegistrantLookup;

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

/**
 * R-D6-0 — 폐지 실체 인입이 운영에 무엇을 들일지, <b>잡의 {@link DelistedIntake#plan}을 그대로</b> 운영 상태에 대 본다(탐색
 * {@code US-PAST-LISTING-IMPL-1018.md} §4). 운영 DB는 읽기 전용 세션이고 {@code apply}를 안 부른다 — 실행 기록도 안 쓴다. SEC 원자료는
 * 디스크에 담아 다시 돌리면 0콜이다(Alpaca 봉 · 기업 행위는 매번 받는다).
 *
 * <p>칠 때: {@code ( set -a; . ./.env; set +a; INTAKE_PROBE=1 INTAKE_PROBE_OUT=<디렉터리> ./gradlew liveTest --tests
 * '*DelistedIntakePlanProbeLiveTest*' -PskipFrontend -i )}
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "INTAKE_PROBE", matches = ".+")
class DelistedIntakePlanProbeLiveTest {

    @Test
    void probe() throws Exception {
        Path out = Files.createDirectories(Path.of(System.getenv().getOrDefault("INTAKE_PROBE_OUT", "build/intake-probe")));
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:postgresql://localhost:5432/stock_screener?options=-c%20default_transaction_read_only%3Don",
                System.getProperty("user.name"), "");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        DataSourceProperties properties = properties();
        ExternalApiClientFactory factory = new ExternalApiClientFactory();
        TokenBucketRateLimiter alpaca = new TokenBucketRateLimiter("alpaca", 150, 0);
        TokenBucketRateLimiter sec = new TokenBucketRateLimiter("sec-edgar", 480, 0);
        IdentitySource source = IdentityProbeSupport.source(properties, factory, alpaca, sec);
        SecRegistrantLookup lookup = new SecRegistrantLookup(new CachingListingFormClient(new SecDailyIndexClient(factory, properties, sec),
                new SecFilingClient(factory, properties, sec), factory, properties, sec, out.resolve("cache")));

        LocalDate from = jdbc.queryForObject("SELECT min(trade_date) FROM indicator_trade_date WHERE market_code = 'US'", LocalDate.class);
        LocalDate asOf = LocalDate.now(ZoneId.of("America/New_York"));
        DelistedIntake.Plan plan = new DelistedIntake(jdbc).plan(from, asOf, source, lookup);

        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(out.resolve("intakes.tsv")))) {
            w.println("label\tsymbol\teffective\tera_to\tcik\tvenue\tname\tevidence");
            plan.intakes().forEach(i -> w.println(String.join("\t", i.label(), i.symbol(), i.effective().toString(), i.eraTo().toString(),
                    String.valueOf(i.cik()), i.venue(), i.name(), i.evidence())));
        }
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(out.resolve("skipped.tsv")))) {
            w.println("reason\tsymbol\teffective\tevidence");
            plan.skipped().forEach(s -> w.println(String.join("\t", s.reason(), s.symbol(), s.effective().toString(), s.evidence())));
        }
        String summary = "R-D6-0 창 %s~%s 들일 것 %d · 안 들인 것 %s".formatted(from, asOf, plan.intakes().size(), plan.reasons());
        Files.writeString(out.resolve("summary.txt"), summary + "\n");
        System.out.println(summary);
    }

    private static DataSourceProperties properties() {
        DataSourceProperties properties = new DataSourceProperties();
        properties.getAlpaca().setBaseUrl("https://paper-api.alpaca.markets");
        properties.getAlpaca().setApiKey(System.getenv("APCA_API_KEY_ID"));
        properties.getAlpaca().setSecretKey(System.getenv("APCA_API_SECRET_KEY"));
        properties.getAlpaca().setReadTimeout(Duration.ofSeconds(60));
        properties.getSecEdgar().setApiKey(System.getenv("SEC_EDGAR_USER_AGENT"));
        properties.getSecEdgar().setReadTimeout(Duration.ofSeconds(60));
        return properties;
    }
}
