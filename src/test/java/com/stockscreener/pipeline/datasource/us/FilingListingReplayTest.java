package com.stockscreener.pipeline.datasource.us;

import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.FilingListSource.FilingListing;
import com.stockscreener.pipeline.datasource.FilingListSource.ListedDay;
import com.stockscreener.pipeline.datasource.FilingListSource.ListedFiling;
import com.stockscreener.pipeline.datasource.FilingListSource.ListedStock;
import com.stockscreener.pipeline.datasource.us.SecDailyIndexClient.IndexLine;
import com.stockscreener.pipeline.datasource.us.SecDailyIndexClient.QuarterListing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 공시 목록 드라이런 D1의 재생(0콜 · ADR-0176) — 2026-10-11에 받아 둔 EDGAR 일일 색인 7일 · QTR4 목록 · 운영 매핑 · 그날 미국 편입
 * 1,000을 <b>운영 코드</b>({@link SecFilingListSource} · {@link SecDailyIndexClient}의 해석)에 먹여, 따로 뽑아 둔 21건
 * ({@code hits.json})과 같은지 묻는다.
 *
 * <p>재료는 저장소 밖 영구 위치에 있다 — 환경변수 {@code FILING_SAMPLES_DIR}를 줄 때만 돈다(gradle의 {@code -D}는 시험 JVM에 안 닿는다).
 * {@code FILING_SAMPLES_DIR="$HOME/Library/Application Support/stock-screener/p2-ledger-samples-20261011" ./gradlew test --tests '*FilingListingReplayTest'}
 */
@EnabledIfEnvironmentVariable(named = "FILING_SAMPLES_DIR", matches = ".+")
class FilingListingReplayTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final Path dir = Path.of(System.getenv().getOrDefault("FILING_SAMPLES_DIR", "."));

    @Test
    @DisplayName("D1 — 색인 7일 × 편입 1,000의 정기 공시가 hits.json 21건과 같고, 10-03·04는 색인 없음 · 10-10 뒤는 기다린다")
    void replaysTheSavedIndexes() throws Exception {
        SecTickerCikCache.Snapshot snapshot = new SecTickerCikCache.Snapshot(
                SecTickerCikCache.parse(MAPPER.readTree(Files.readString(dir.resolve("company_tickers_exchange.json"))), 1),
                Instant.parse("2026-10-12T00:00:00Z"));
        Map<String, ListedStock> stocks = new HashMap<>();
        Map<Long, String> tickerOf = new HashMap<>();
        long id = 1;
        for (String ticker : Files.readAllLines(dir.resolve("members.txt"))) {
            if (ticker.isBlank()) {
                continue;
            }
            stocks.put(ticker.strip(), new ListedStock(id, null));
            tickerOf.put(id++, ticker.strip());
        }
        QuarterListing q4 = SecDailyIndexClient.parseQuarter(2026, 4, MAPPER.readTree(Files.readString(dir.resolve("qtr4.json"))), "qtr4");
        SecFilingListSource.DailyIndex saved = new SecFilingListSource.DailyIndex() {
            @Override
            public QuarterListing quarter(int year, int quarter) {
                return q4;
            }

            @Override
            public List<IndexLine> formIndex(LocalDate day, int expectedKb) throws DataSourceException {
                try {
                    byte[] body = Files.readAllBytes(dir.resolve("form." + DateTimeFormatter.BASIC_ISO_DATE.format(day) + ".idx"));
                    assertThat(SecDailyIndexClient.sizeMismatch(body, expectedKb)).as("받은 길이 = 목록 KB 올림 · " + day).isNull();
                    return SecDailyIndexClient.parseFormIndex(new String(body, StandardCharsets.ISO_8859_1), day, "saved " + day);
                } catch (java.io.IOException e) {
                    throw new IllegalStateException(e);
                }
            }
        };

        FilingListing listing = new SecFilingListSource(saved, () -> snapshot)
                .list(LocalDate.of(2026, 9, 30), LocalDate.of(2026, 10, 13), stocks);

        assertThat(listing.failure()).isNull();
        assertThat(listing.closedDays()).extracting(d -> d.day() + " " + d.outcome()).containsExactly(
                "2026-10-01 LISTED", "2026-10-02 LISTED", "2026-10-03 NO_INDEX", "2026-10-04 NO_INDEX",
                "2026-10-05 LISTED", "2026-10-06 LISTED", "2026-10-07 LISTED", "2026-10-08 LISTED", "2026-10-09 LISTED");
        assertThat(stocks.keySet().stream().filter(t -> snapshot.findCik(t).isPresent()).count())
                .as("편입 1,000이 운영 매핑으로 전부 CIK를 얻는다").isEqualTo(1000);

        Set<String> ours = new TreeSet<>();
        for (ListedDay day : listing.closedDays()) {
            for (ListedFiling f : day.filings()) {
                ours.add("%s %s %d %s %s".formatted(DateTimeFormatter.BASIC_ISO_DATE.format(f.filedOn()), f.form(), f.cik(),
                        tickerOf.get(f.stockId()), f.filingNo()));
            }
        }
        JsonNode hits = MAPPER.readTree(Files.readString(dir.resolve("hits.json")));
        Set<String> expected = new TreeSet<>();
        for (JsonNode h : hits) {
            expected.add("%s %s %d %s %s".formatted(h.get(0).asString(), h.get(1).asString(), h.get(2).asLong(),
                    h.get(3).asString(), h.get(4).asString()));
        }
        assertThat(expected).hasSize(21);
        assertThat(ours).as("어느 쪽에만 있는 줄: 우리만=%s 기대만=%s",
                        ours.stream().filter(s -> !expected.contains(s)).collect(Collectors.toList()),
                        expected.stream().filter(s -> !ours.contains(s)).collect(Collectors.toList()))
                .isEqualTo(expected);
    }
}
