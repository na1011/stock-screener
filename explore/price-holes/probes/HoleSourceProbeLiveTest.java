package com.stockscreener.probe;

import com.stockscreener.pipeline.datasource.RawPriceBar;
import com.stockscreener.pipeline.datasource.SecurityRef;
import com.stockscreener.pipeline.datasource.http.DataSourceProperties;
import com.stockscreener.pipeline.datasource.http.ExternalApiClientFactory;
import com.stockscreener.pipeline.datasource.http.TokenBucketRateLimiter;
import com.stockscreener.pipeline.datasource.kr.DataGoKrPriceAdapter;
import com.stockscreener.pipeline.datasource.us.AlpacaPriceAdapter;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

/** 임시 탐침 — 시세 구멍 날짜의 봉을 소스가 주는가. 커밋하지 않는다. */
@Tag("live")
class HoleSourceProbeLiveTest {

    private record Ask(String market, String ticker, String from, String to) {}

    private static final List<Ask> ASKS = List.of(
            new Ask("KOSDAQ", "094170", "2026-09-22", "2026-09-25"),
            new Ask("KOSDAQ", "358570", "2026-09-22", "2026-09-25"),
            new Ask("KOSDAQ", "475830", "2026-09-22", "2026-09-25"),
            new Ask("KOSPI", "069620", "2026-09-22", "2026-09-25"),
            new Ask("KOSPI", "010620", "2025-12-11", "2025-12-19"),
            new Ask("KOSPI", "042670", "2026-01-22", "2026-01-30"),
            new Ask("KOSDAQ", "335890", "2025-12-08", "2025-12-12"),
            new Ask("KOSPI", "012510", "2026-07-13", "2026-07-16"),
            new Ask("KOSPI", "001740", "2026-09-14", "2026-09-18"),
            new Ask("KOSDAQ", "490470", "2026-09-14", "2026-09-18"),
            new Ask("US", "APGE", "2026-09-01", "2026-09-05"),
            new Ask("US", "CRNX", "2026-08-28", "2026-09-04"),
            new Ask("US", "AXTI", "2026-09-09", "2026-09-17"),
            new Ask("US", "TAP.A", "2026-09-29", "2026-10-02"),
            new Ask("US", "WSO.B", "2026-09-29", "2026-10-02"));

    @Test
    void probeLateFirstBars() throws Exception {
        String file = System.getProperty("probe.file");
        if (file == null) {
            System.out.println("PROBE2 probe.file 없음 — 건너뜀");
            return;
        }
        DataSourceProperties properties = new DataSourceProperties();
        properties.getDataGoKr().setApiKey(System.getenv("DATA_GO_KR_SERVICE_KEY"));
        properties.getAlpaca().setApiKey(System.getenv("APCA_API_KEY_ID"));
        properties.getAlpaca().setSecretKey(System.getenv("APCA_API_SECRET_KEY"));
        DataGoKrPriceAdapter kr = new DataGoKrPriceAdapter(new ExternalApiClientFactory(), properties,
                new TokenBucketRateLimiter("data-go-kr", 120, 0));
        AlpacaPriceAdapter us = new AlpacaPriceAdapter(new ExternalApiClientFactory(), properties,
                new TokenBucketRateLimiter("alpaca", 0, 0));
        for (String line : java.nio.file.Files.readAllLines(java.nio.file.Path.of(file))) {
            String[] f = line.split("\t");
            SecurityRef ref = new SecurityRef(0L, f[0], f[1]);
            LocalDate from = LocalDate.parse(f[2]);
            LocalDate to = LocalDate.parse(f[3]).minusDays(1);
            if (to.isBefore(from)) {
                continue;
            }
            try {
                List<RawPriceBar> bars = "US".equals(f[0])
                        ? us.fetchDailyBars(ref, from, to) : kr.fetchDailyBars(ref, from, to);
                System.out.printf("PROBE2\t%s\t%s\t%s\t%s\t%d\t%s\t%s%n", f[0], f[1], from, f[3], bars.size(),
                        bars.isEmpty() ? "-" : bars.get(0).tradeDate(),
                        bars.isEmpty() ? "-" : bars.get(bars.size() - 1).tradeDate());
                if (System.getProperty("probe.verbose") != null) {
                    for (RawPriceBar bar : bars) {
                        System.out.printf("PROBE3\t%s\t%s\to=%s\tc=%s\tv=%s%n", f[1], bar.tradeDate(), bar.open(), bar.close(), bar.volume());
                    }
                }
            } catch (Exception e) {
                System.out.printf("PROBE2\t%s\t%s\t%s\t%s\tERR\t%s%n", f[0], f[1], from, f[3], e);
            }
        }
    }

    @Test
    void probe() throws Exception {
        if (System.getProperty("probe.file") != null) {
            return;
        }
        DataSourceProperties properties = new DataSourceProperties();
        properties.getDataGoKr().setApiKey(System.getenv("DATA_GO_KR_SERVICE_KEY"));
        properties.getAlpaca().setApiKey(System.getenv("APCA_API_KEY_ID"));
        properties.getAlpaca().setSecretKey(System.getenv("APCA_API_SECRET_KEY"));
        DataGoKrPriceAdapter kr = new DataGoKrPriceAdapter(new ExternalApiClientFactory(), properties,
                new TokenBucketRateLimiter("data-go-kr", 120, 0));
        AlpacaPriceAdapter us = new AlpacaPriceAdapter(new ExternalApiClientFactory(), properties,
                new TokenBucketRateLimiter("alpaca", 0, 0));
        for (Ask ask : ASKS) {
            SecurityRef ref = new SecurityRef(0L, ask.market(), ask.ticker());
            LocalDate from = LocalDate.parse(ask.from());
            LocalDate to = LocalDate.parse(ask.to());
            try {
                List<RawPriceBar> bars = "US".equals(ask.market())
                        ? us.fetchDailyBars(ref, from, to) : kr.fetchDailyBars(ref, from, to);
                StringBuilder line = new StringBuilder();
                for (RawPriceBar bar : bars) {
                    line.append(bar.tradeDate()).append(' ').append(bar.close())
                            .append(" v").append(bar.volume()).append(" | ");
                }
                System.out.printf("PROBE %s %s [%s~%s] %d봉: %s%n",
                        ask.market(), ask.ticker(), from, to, bars.size(), line);
            } catch (Exception e) {
                System.out.printf("PROBE %s %s [%s~%s] 예외: %s%n", ask.market(), ask.ticker(), from, to, e);
            }
        }
    }
}
