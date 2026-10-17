package com.stockscreener.pipeline.datasource.us;

import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.FilingListSource.DayOutcome;
import com.stockscreener.pipeline.datasource.FilingListSource.FilingListing;
import com.stockscreener.pipeline.datasource.FilingListSource.ListedStock;
import com.stockscreener.pipeline.datasource.us.SecDailyIndexClient.IndexLine;
import com.stockscreener.pipeline.datasource.us.SecDailyIndexClient.QuarterListing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 미국 공시 목록(ADR-0176) — 색인 없는 날 판정 · 기다림 · 실패에서 멈춤 · 정기 공시 거름 · 색인 줄 해석.
 */
class SecFilingListSourceTest {

    private static final SecTickerCikCache.Snapshot SNAPSHOT = new SecTickerCikCache.Snapshot(Map.of(
            "MKC", new SecTickerCikCache.Listing(63754L, "NYSE"),
            "GOOGL", new SecTickerCikCache.Listing(1652044L, "Nasdaq"),
            "GOOG", new SecTickerCikCache.Listing(1652044L, "Nasdaq"),
            "BRK-B", new SecTickerCikCache.Listing(1067983L, "NYSE")), Instant.parse("2026-10-12T00:00:00Z"));

    private static final Map<String, ListedStock> STOCKS = Map.of(
            "MKC", new ListedStock(10L, null),
            "GOOGL", new ListedStock(20L, null),
            "GOOG", new ListedStock(21L, null),
            "BRK.B", new ListedStock(30L, null));

    @Test
    @DisplayName("목록의 마지막 날보다 앞인데 없는 날은 색인 없음으로 닫고, 뒤는 기다린다 — 10-03·04 넘김 · 10-10~13 기다림")
    void closesMissingDaysBeforeTheLastListedDayAndWaitsAfterIt() {
        FakeIndex index = new FakeIndex();
        index.quarters.put("2026Q4", quarter(2026, 4, "2026-10-01", "2026-10-02", "2026-10-05", "2026-10-06",
                "2026-10-07", "2026-10-08", "2026-10-09"));

        FilingListing listing = new SecFilingListSource(index, () -> SNAPSHOT)
                .list(LocalDate.of(2026, 9, 30), LocalDate.of(2026, 10, 13), STOCKS);

        assertThat(listing.failure()).isNull();
        assertThat(listing.closedDays()).extracting(d -> d.day() + " " + d.outcome()).containsExactly(
                "2026-10-01 LISTED", "2026-10-02 LISTED", "2026-10-03 NO_INDEX", "2026-10-04 NO_INDEX",
                "2026-10-05 LISTED", "2026-10-06 LISTED", "2026-10-07 LISTED", "2026-10-08 LISTED",
                "2026-10-09 LISTED");
        assertThat(index.asked).as("색인 없는 날은 날짜로 묻지 않는다 — 그 날의 403은 「막힘」과 못 가른다")
                .doesNotContain(LocalDate.of(2026, 10, 3), LocalDate.of(2026, 10, 4));
    }

    @Test
    @DisplayName("분기를 건너는 커서 — 앞 분기 끝의 빈 날도 뒤 분기 목록이 있으면 닫는다")
    void spansQuarters() {
        FakeIndex index = new FakeIndex();
        index.quarters.put("2026Q3", quarter(2026, 3, "2026-09-28", "2026-09-29", "2026-09-30"));
        index.quarters.put("2026Q4", quarter(2026, 4, "2026-10-01"));

        FilingListing listing = new SecFilingListSource(index, () -> SNAPSHOT)
                .list(LocalDate.of(2026, 9, 28), LocalDate.of(2026, 10, 2), STOCKS);

        assertThat(listing.closedDays()).extracting(d -> d.day().toString())
                .containsExactly("2026-09-29", "2026-09-30", "2026-10-01");
    }

    @Test
    @DisplayName("뒤 분기 목록이 실패하면 앞 분기의 마지막 날까지만 닫고 실패를 낸다 — 그 뒤 날이 없는 날인지 모른다")
    void laterQuarterFailureClosesOnlyThroughTheEarlierQuarter() {
        FakeIndex index = new FakeIndex();
        index.quarters.put("2026Q3", quarter(2026, 3, "2026-09-29"));
        index.failQuarter = "2026Q4";

        FilingListing listing = new SecFilingListSource(index, () -> SNAPSHOT)
                .list(LocalDate.of(2026, 9, 28), LocalDate.of(2026, 10, 5), STOCKS);

        assertThat(listing.closedDays()).extracting(d -> d.day().toString()).containsExactly("2026-09-29");
        assertThat(listing.failure()).isNotNull();
        assertThat(listing.failure().kind()).isEqualTo(DataSourceException.Kind.RATE_LIMITED);
    }

    @Test
    @DisplayName("그날 색인이 실패하면 그 앞날까지만 닫는다")
    void formIndexFailureStops() {
        FakeIndex index = new FakeIndex();
        index.quarters.put("2026Q4", quarter(2026, 4, "2026-10-01", "2026-10-02", "2026-10-05"));
        index.failDay = LocalDate.of(2026, 10, 2);

        FilingListing listing = new SecFilingListSource(index, () -> SNAPSHOT)
                .list(LocalDate.of(2026, 9, 30), LocalDate.of(2026, 10, 6), STOCKS);

        assertThat(listing.closedDays()).extracting(d -> d.day().toString()).containsExactly("2026-10-01");
        assertThat(listing.failure()).isNotNull();
    }

    @Test
    @DisplayName("매핑 갱신이 실패하면 아무 날도 닫지 않는다 — 낡은 매핑의 「없음」으로 공시를 버리지 않는다")
    void staleMappingClosesNothing() {
        FakeIndex index = new FakeIndex();
        index.quarters.put("2026Q4", quarter(2026, 4, "2026-10-01"));
        DataSourceException stale = new DataSourceException(DataSourceException.Kind.TRANSIENT, "sec-edgar", "x");

        FilingListing listing = new SecFilingListSource(index, () -> {
            throw stale;
        }).list(LocalDate.of(2026, 9, 30), LocalDate.of(2026, 10, 2), STOCKS);

        assertThat(listing.closedDays()).isEmpty();
        assertThat(listing.failure()).isSameAs(stale);
    }

    @Test
    @DisplayName("행에 묶인 등록인이 오늘 파일보다 먼저다 — 교체 기호(REAX꼴)의 새 등록인 공시가 옛 행에 안 붙는다(ADR-0180 · ★16)")
    void boundRegistrantWinsOverTodaysFile() {
        SecTickerCikCache.Snapshot file = new SecTickerCikCache.Snapshot(Map.of(
                "REAX", new SecTickerCikCache.Listing(2136387L, "NYSE")), Instant.parse("2026-10-12T00:00:00Z"));
        Map<String, ListedStock> stocks = Map.of(
                "REAX~2026-08-24", new ListedStock(40L, null, 1862461L),
                "REAX", new ListedStock(41L, null, 2136387L),
                "NEWCO", new ListedStock(42L, null));

        Map<Long, List<Long>> byCik = SecFilingListSource.stocksByCik(file, stocks);

        assertThat(byCik).containsEntry(1862461L, List.of(40L)).containsEntry(2136387L, List.of(41L));
        assertThat(byCik.values().stream().flatMap(List::stream)).as("파일에 없는 미결합 행은 안 붙는다").doesNotContain(42L);
    }

    @Test
    @DisplayName("정기 공시만 세고, 한 CIK의 종목 둘에 한 줄씩 · 점 표기 티커도 붙인다 · 8-K는 안 간다")
    void filtersPeriodicFormsAndFansOutPerStock() {
        LocalDate day = LocalDate.of(2026, 10, 1);
        Map<Long, List<Long>> byCik = SecFilingListSource.stocksByCik(SNAPSHOT, STOCKS);
        var listed = SecFilingListSource.toDay(day, List.of(
                new IndexLine("10-Q", 63754L, day, "0000063754-26-000329", "MCCORMICK & CO INC"),
                new IndexLine("8-K", 63754L, day, "0000063754-26-000330", "MCCORMICK & CO INC"),
                new IndexLine("10-Q", 1652044L, day, "0001652044-26-000100", "Alphabet Inc."),
                new IndexLine("6-K", 1067983L, day, "0001067983-26-000001", "BERKSHIRE HATHAWAY INC"),
                new IndexLine("10-K", 999L, day, "0000000999-26-000001", "UNMAPPED CO")), byCik);

        assertThat(listed.outcome()).isEqualTo(DayOutcome.LISTED);
        assertThat(listed.sourceRows()).as("정기 공시 넷(8-K 뺌)").isEqualTo(4);
        assertThat(listed.filings()).extracting(f -> f.filingNo() + " " + f.stockId())
                .containsExactlyInAnyOrder("0000063754-26-000329 10", "0001652044-26-000100 20",
                        "0001652044-26-000100 21", "0001067983-26-000001 30");
    }

    @Test
    @DisplayName("색인 줄 — 폼·회사 이름의 공백과 숫자 · 머리 건너뛰기 · 앞선 제출일은 받고 뒤선 제출일·꼴이 다른 줄은 본문 이상")
    void parsesFormIndexLines() throws Exception {
        LocalDate day = LocalDate.of(2026, 10, 1);
        String text = """
                Description:           Daily Index of EDGAR Dissemination Feed by Form Type
                Last Data Received:    Oct 1, 2026
                \s
                Form Type   Company Name                                                  CIK
                      Date Filed  File Name
                ---------------------------------------------------------------------------------------------------------------------------------------------
                10-Q             MCCORMICK & CO INC                                            63754       20261001    edgar/data/63754/0000063754-26-000329.txt                                                \s
                SC 13D/A         Fund 2 Holdings  LP                                           2014151     20261001    edgar/data/2014151/0002014151-26-000003.txt
                \s
                """;
        List<IndexLine> lines = SecDailyIndexClient.parseFormIndex(text, day, "t");

        assertThat(lines).containsExactly(
                new IndexLine("10-Q", 63754L, day, "0000063754-26-000329", "MCCORMICK & CO INC"),
                new IndexLine("SC 13D/A", 2014151L, day, "0002014151-26-000003", "Fund 2 Holdings  LP"));

        String lateDisseminated = text.replace("20261001    edgar/data/63754", "20260930    edgar/data/63754");
        assertThat(SecDailyIndexClient.parseFormIndex(lateDisseminated, day, "t").get(0).filed())
                .as("늦게 배포된 공시는 원래 제출일로 뒷날 색인에만 실린다 — 버리면 어느 날 목록에도 없다")
                .isEqualTo(LocalDate.of(2026, 9, 30));
        String future = text.replace("20261001    edgar/data/63754", "20261002    edgar/data/63754");
        assertThatThrownBy(() -> SecDailyIndexClient.parseFormIndex(future, day, "t"))
                .isInstanceOf(DataSourceException.class);
        String cikMismatch = text.replace("edgar/data/63754/", "edgar/data/63755/");
        assertThatThrownBy(() -> SecDailyIndexClient.parseFormIndex(cikMismatch, day, "t"))
                .isInstanceOf(DataSourceException.class);
        assertThatThrownBy(() -> SecDailyIndexClient.parseFormIndex("Description: only a head\n", day, "t"))
                .as("구분선 없는 본문 — 머리만 왔다").isInstanceOf(DataSourceException.class);
    }

    @Test
    @DisplayName("분기 목록 — 폼 색인만 날짜순 · 크기는 KB 올림이고 받은 길이와 맞춘다")
    void parsesQuarterListingAndChecksSize() throws Exception {
        String json = """
                {"directory":{"item":[
                  {"name":"company.20261001.idx","size":"792 KB"},
                  {"name":"form.20261002.idx","size":"1362 KB"},
                  {"name":"form.20261001.idx","size":"1051 KB"},
                  {"name":"master.20261001.idx","size":"700 KB"}]}}""";
        QuarterListing q = SecDailyIndexClient.parseQuarter(2026, 4, new ObjectMapper().readTree(json), "t");

        assertThat(q.formIndexKb()).containsExactly(
                Map.entry(LocalDate.of(2026, 10, 1), 1051), Map.entry(LocalDate.of(2026, 10, 2), 1362));
        assertThat(q.lastDay()).isEqualTo(LocalDate.of(2026, 10, 2));

        // 2026-10-01 저장분: 1,076,081바이트 = 1051 KB(올림) · 1,393,682바이트 = 1362 KB
        assertThat(SecDailyIndexClient.sizeMismatch(new byte[1_076_081], 1051)).isNull();
        assertThat(SecDailyIndexClient.sizeMismatch(new byte[1_393_682], 1362)).isNull();
        assertThat(SecDailyIndexClient.sizeMismatch(new byte[628_013], 613)).as("2024 QTR3 목록은 내림이다(2024-09-05)").isNull();
        assertThat(SecDailyIndexClient.sizeMismatch(new byte[1_000_000], 1051)).as("끊긴 본문").isNotNull();
        assertThat(SecDailyIndexClient.sizeMismatch("x".getBytes(StandardCharsets.UTF_8), 0)).isNotNull();
    }

    private static QuarterListing quarter(int year, int q, String... days) {
        TreeMap<LocalDate, Integer> map = new TreeMap<>();
        for (String d : days) {
            map.put(LocalDate.parse(d), 1);
        }
        return new QuarterListing(year, q, map);
    }

    private static final class FakeIndex implements SecFilingListSource.DailyIndex {
        final Map<String, QuarterListing> quarters = new HashMap<>();
        final List<LocalDate> asked = new ArrayList<>();
        String failQuarter;
        LocalDate failDay;

        @Override
        public QuarterListing quarter(int year, int quarter) throws DataSourceException {
            String key = year + "Q" + quarter;
            if (key.equals(failQuarter)) {
                throw new DataSourceException(DataSourceException.Kind.RATE_LIMITED, "sec-edgar", "HTTP 403");
            }
            return quarters.getOrDefault(key, new QuarterListing(year, quarter, new TreeMap<>()));
        }

        @Override
        public List<IndexLine> formIndex(LocalDate day, int expectedKb) throws DataSourceException {
            asked.add(day);
            if (day.equals(failDay)) {
                throw new DataSourceException(DataSourceException.Kind.TRANSIENT, "sec-edgar", "HTTP 503");
            }
            return List.of(new IndexLine("10-Q", 63754L, day, "0000063754-26-" + day.getDayOfMonth(), "MCCORMICK & CO INC"));
        }
    }
}
