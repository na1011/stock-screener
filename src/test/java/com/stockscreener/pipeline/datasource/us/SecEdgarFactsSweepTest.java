package com.stockscreener.pipeline.datasource.us;

import com.stockscreener.pipeline.datasource.RawFinancialReport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import tools.jackson.databind.ObjectMapper;

import java.io.BufferedWriter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * 저장해 둔 {@code companyfacts} <b>전량</b>을 어댑터에 먹여 보고서를 TSV로 찍는다.
 * DB도 배치도 외부 호출도 없다.
 *
 * <p><b>왜 한 종목짜리 {@link SecEdgarFactsReplayTest}로 모자랐나</b>: 2026-09-15에 매출
 * 오태깅의 규모를 <b>팩트 캐시 418종목</b>으로 재고 「1종목 20칸」이라 적었는데, DB 전수는
 * <b>18종목 46행</b>이었다. {@code ENS}·{@code ORA}·{@code WDC}가 캐시에 없어 <b>원리적으로
 * 안 보였다.</b> 표본으로 전수를 주장한 것이고, 같은 실수를 그날 두 번 했다.
 *
 * <p><b>이 도구의 목적은 「고치기 전에 무엇이 바뀌는지 전수로 보는 것」이다.</b>
 * 코드를 고치고 다시 찍어 두 TSV를 diff하면 <b>재적재 없이</b> 영향 범위가 나온다.
 * 재적재는 {@code invalidate}를 불러 지표를 지우므로 <b>검증 없이 돌리는 것 자체가 정합성을
 * 해친다</b>(2026-09-15 사용자 결정: <b>모든 수정이 끝난 뒤에 재적재·백필을 한 번</b>).
 *
 * <pre>
 * REPLAY_SWEEP_DIR=.factcache REPLAY_SWEEP_OUT=/tmp/sweep-before.tsv \
 *   ./gradlew test --tests "*SecEdgarFactsSweepTest*"
 * </pre>
 *
 * <p><b>환경변수로 받는다</b> — Gradle의 {@code -D}는 데몬 JVM에만 붙어 포크된 테스트에 안 닿는다.
 *
 * <h2>공시 보충(ADR-0173)</h2>
 * <p>{@code REPLAY_SUPPLEMENT_DIR}를 주면 운영 코드({@link SecFilingSupplement})를 <b>한 줄도 안 바꾸고</b> 태운다 — 공시
 * 목록·색인·인스턴스를 그 디렉터리({@link SecFilingSupplementCacheLiveTest}가 같은 날 모은 것)에서 로컬 서버로 내주고,
 * 원장은 메모리다. 안 주면 출력이 보충 전과 바이트까지 같다. {@code REPLAY_REMOVE_ACCNS=티커:접수번호,…}는 제거 대조다 —
 * companyfacts에서 그 공시의 us-gaap를 지운 뒤 보충한다(보충이 원래 트리와 같은 출력을 내는가).
 */
@EnabledIfEnvironmentVariable(named = "REPLAY_SWEEP_DIR", matches = ".+")
class SecEdgarFactsSweepTest {

    /** TSV 컬럼. <b>순서를 바꾸면 전/후 diff가 통째로 어긋난다.</b> */
    private static final List<String> HEADER = List.of(
            "ticker", "fy", "fp", "start", "end", "pub", "rc", "ccy", "derived",
            "revenue", "costOfSales", "grossProfit", "operatingIncome",
            "netIncome", "netIncomeControlling",
            "totalAssets", "totalLiabilities", "totalEquity", "totalEquityControlling",
            "currentAssets", "currentLiabilities", "profitBeforeTax", "operatingCashFlow",
            "costsAndExpenses", "operatingExpenses", "benefitsLossesAndExpenses",
            "interestAndDividendIncome", "noninterestIncome", "interestExpense", "noninterestExpense",
            "incomeTaxExpense", "inventories", "capex", "cashAndEquivalents", "retainedEarnings",
            "sharesOutstanding", "eps", "bps");

    private static final List<Function<RawFinancialReport, BigDecimal>> MONEY = List.of(
            RawFinancialReport::revenue, RawFinancialReport::costOfSales,
            RawFinancialReport::grossProfit, RawFinancialReport::operatingIncome,
            RawFinancialReport::netIncome, RawFinancialReport::netIncomeControlling,
            RawFinancialReport::totalAssets, RawFinancialReport::totalLiabilities,
            RawFinancialReport::totalEquity, RawFinancialReport::totalEquityControlling,
            RawFinancialReport::currentAssets, RawFinancialReport::currentLiabilities,
            RawFinancialReport::profitBeforeTax, RawFinancialReport::operatingCashFlow,
            RawFinancialReport::costsAndExpenses, RawFinancialReport::operatingExpenses,
            RawFinancialReport::benefitsLossesAndExpenses,
            RawFinancialReport::interestAndDividendIncome, RawFinancialReport::noninterestIncome,
            RawFinancialReport::interestExpense, RawFinancialReport::noninterestExpense,
            RawFinancialReport::incomeTaxExpense, RawFinancialReport::inventories,
            RawFinancialReport::capex, RawFinancialReport::cashAndEquivalents,
            RawFinancialReport::retainedEarnings,
            // MUST: 이 셋을 빼면 「저장 행이 달라졌는가」를 스윕으로 못 본다. 실제로 그 사각
            // 때문에 새 판 10,262 중 5,196이 「측정 컬럼 동일」로 보였고, 원인(주식수)이 안 보였다.
            RawFinancialReport::sharesOutstanding, RawFinancialReport::eps,
            RawFinancialReport::bps);

    @Test
    void sweep() throws Exception {
        Path dir = Path.of(System.getenv("REPLAY_SWEEP_DIR"));
        Path out = Path.of(Optional.ofNullable(System.getenv("REPLAY_SWEEP_OUT"))
                .orElse("build/sweep.tsv"));
        int from = Integer.parseInt(
                Optional.ofNullable(System.getenv("REPLAY_SWEEP_FROM")).orElse("2015"));
        // 미국 재적재 관문 설정. 어댑터와 같은 갈래를 태운다 — 꺼진 출력은 설정이 생기기 전과 같아야 한다.
        boolean usGate = "true".equals(System.getenv("US_GATE"));
        // TSV 밖의 출력(버린 팩트 수 · 컬럼 부재 · 행별 assertedAbsent). 「꺼진 출력이 같다」를 이것까지 대조한다 —
        // TSV만 같아도 Writer가 읽는 부재 판정이 갈리면 저장이 갈린다.
        Optional<Path> extra = Optional.ofNullable(System.getenv("REPLAY_SWEEP_EXTRA")).map(Path::of);
        Files.createDirectories(out.toAbsolutePath().getParent());

        ObjectMapper mapper = new ObjectMapper();
        Optional<SupplementReplay> supplement = Optional.ofNullable(System.getenv("REPLAY_SUPPLEMENT_DIR"))
                .map(Path::of).map(SupplementReplay::start);
        java.util.Map<String, java.util.Set<String>> removals = new java.util.HashMap<>();
        Optional.ofNullable(System.getenv("REPLAY_REMOVE_ACCNS")).ifPresent(v -> {
            for (String pair : v.split(",")) {
                String[] parts = pair.split(":");
                removals.computeIfAbsent(parts[0], k -> new java.util.HashSet<>()).add(parts[1]);
            }
        });
        List<Path> files;
        try (Stream<Path> stream = Files.list(dir)) {
            files = stream.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .sorted().toList();
        }

        int stocks = 0;
        int rows = 0;
        long dropped = 0;
        long absences = 0;
        int failed = 0;
        StringBuilder failures = new StringBuilder();
        StringBuilder extraLines = new StringBuilder();
        try (BufferedWriter w = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
            w.write(String.join("\t", HEADER));
            w.newLine();
            for (Path file : files) {
                String ticker = file.getFileName().toString().replaceFirst("\\.json$", "");
                SecEdgarFacts.Result result;
                try {
                    tools.jackson.databind.JsonNode root = mapper.readTree(Files.readString(file));
                    tools.jackson.databind.JsonNode facts = root.path("facts");
                    if (removals.containsKey(ticker)) {
                        removeUsGaap((tools.jackson.databind.node.ObjectNode) facts, removals.get(ticker));
                    }
                    if (supplement.isPresent()) {
                        SecFilingSupplement.Outcome outcome = supplement.get().supplement.supplement(
                                root.path("cik").asLong(), (tools.jackson.databind.node.ObjectNode) facts, from, ticker);
                        if (extra.isPresent() && (outcome.fetched() + outcome.fromStore() > 0
                                || !outcome.failed().isEmpty() || outcome.vintagesUncertain())) {
                            extraLines.append(ticker).append("\tsupplement\t").append(outcome.fetched())
                                    .append('\t').append(outcome.merged()).append('\t').append(outcome.failed())
                                    .append('\t').append(outcome.unconfirmedVintages()).append('\t')
                                    .append(outcome.vintagesUncertain()).append('\n');
                        }
                    }
                    result = SecEdgarFacts.build(facts, from, usGate);
                } catch (RuntimeException | java.io.IOException
                        | com.stockscreener.pipeline.datasource.DataSourceException e) {
                    // MUST: DataSourceException도 잡는다. checked라 목록에 없던 탓에 한 종목의
                    // 「같은 라벨에 기간이 둘이다」가 **스윕 전체를 죽여** 나머지 종목의 영향
                    // 범위를 못 봤다(2026-09-16 실측: AM에서 끊겨 1,003 중 약 50종목만 찍혔다).
                    // 적재는 종목 단위로 실패를 격리하므로 드라이런도 같아야 한다.
                    // MUST NOT: 조용히 넘기지 않는다. 한 종목이 못 읽히면 그 종목의 결함이
                    // 「없음」으로 보이고, 그 침묵이 곧 41% 표본을 전수라 믿게 만든 것이다.
                    failed++;
                    failures.append(ticker).append('=').append(e.getClass().getSimpleName())
                            .append(':').append(e.getMessage()).append('\n');
                    continue;
                }
                stocks++;
                dropped += result.dropped();
                absences += result.absences().size();
                if (extra.isPresent()) {
                    extraLines.append(ticker).append("\tdropped\t").append(result.dropped()).append('\n');
                    result.absences().stream().map(String::valueOf).sorted()
                            .forEach(a -> extraLines.append(ticker).append("\tabsence\t").append(a).append('\n'));
                }
                List<RawFinancialReport> reports = result.reports().stream()
                        .sorted(Comparator.comparing(RawFinancialReport::fiscalYear)
                                .thenComparing(RawFinancialReport::fiscalPeriod)
                                .thenComparing(RawFinancialReport::publishedAt))
                        .toList();
                for (RawFinancialReport r : reports) {
                    StringBuilder line = new StringBuilder();
                    line.append(ticker).append('\t')
                            .append(r.fiscalYear()).append('\t')
                            .append(r.fiscalPeriod()).append('\t')
                            .append(r.periodStart()).append('\t')
                            .append(r.periodEnd()).append('\t')
                            .append(r.publishedAt()).append('\t')
                            .append(r.reportCode()).append('\t')
                            .append(r.currencyCode()).append('\t')
                            .append(r.derived());
                    for (Function<RawFinancialReport, BigDecimal> f : MONEY) {
                        BigDecimal v = f.apply(r);
                        line.append('\t').append(v == null ? "" : v.stripTrailingZeros().toPlainString());
                    }
                    w.write(line.toString());
                    w.newLine();
                    rows++;
                    if (extra.isPresent() && !r.assertedAbsent().isEmpty()) {
                        extraLines.append(ticker).append('\t').append(r.fiscalYear()).append('/')
                                .append(r.fiscalPeriod()).append('@').append(r.publishedAt())
                                .append("\tassertedAbsent\t")
                                .append(String.join(",", new java.util.TreeSet<>(r.assertedAbsent())))
                                .append('\n');
                    }
                }
            }
        }
        if (extra.isPresent()) {
            Files.writeString(extra.get(), extraLines.toString(), StandardCharsets.UTF_8);
        }
        supplement.ifPresent(r -> System.out.println("SWEEP 보충 원자료 없음(404)=" + r.missing.get()));
        supplement.ifPresent(SupplementReplay::close);
        System.out.println("SWEEP 관문=" + usGate + " 파일=" + files.size() + " 종목=" + stocks + " 보고서=" + rows
                + " 버린팩트=" + dropped + " 부재=" + absences + " 읽기실패=" + failed
                + " out=" + out.toAbsolutePath());
        if (failed > 0) {
            System.out.println("SWEEP 실패목록\n" + failures);
        }
    }

    /** 제거 대조 — 그 접수번호의 us-gaap 노드를 지운다(dei는 남긴다 · 「dei만 있고 us-gaap가 빠진」 실물 모양). */
    private static void removeUsGaap(tools.jackson.databind.node.ObjectNode facts, java.util.Set<String> accessions) {
        tools.jackson.databind.JsonNode usGaap = facts.path("us-gaap");
        for (String concept : usGaap.propertyNames()) {
            tools.jackson.databind.JsonNode units = usGaap.path(concept).path("units");
            for (String unit : units.propertyNames()) {
                tools.jackson.databind.node.ArrayNode array = (tools.jackson.databind.node.ArrayNode) units.path(unit);
                for (int i = array.size() - 1; i >= 0; i--) {
                    if (accessions.contains(array.get(i).path("accn").asString(""))) {
                        array.remove(i);
                    }
                }
            }
        }
    }

    /** 모은 원자료를 SEC 두 호스트처럼 내주는 로컬 서버 + 메모리 원장. */
    private static final class SupplementReplay {
        private final okhttp3.mockwebserver.MockWebServer server;
        private final SecFilingSupplement supplement;
        private final java.util.concurrent.atomic.AtomicInteger missing = new java.util.concurrent.atomic.AtomicInteger();

        private SupplementReplay(Path dir) throws java.io.IOException {
            Path root = dir.resolve("sec");
            server = new okhttp3.mockwebserver.MockWebServer();
            server.setDispatcher(new okhttp3.mockwebserver.Dispatcher() {
                @Override
                public okhttp3.mockwebserver.MockResponse dispatch(okhttp3.mockwebserver.RecordedRequest request) {
                    Path file = root.resolve(request.getPath().substring(1));
                    Path status = file.resolveSibling(file.getFileName() + ".status");
                    try {
                        if (Files.exists(status)) {
                            return new okhttp3.mockwebserver.MockResponse()
                                    .setResponseCode(Integer.parseInt(Files.readString(status).trim()));
                        }
                        if (Files.exists(file)) {
                            return new okhttp3.mockwebserver.MockResponse().setResponseCode(200)
                                    .setBody(new okio.Buffer().write(Files.readAllBytes(file)));
                        }
                    } catch (java.io.IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                    missing.incrementAndGet();
                    return new okhttp3.mockwebserver.MockResponse().setResponseCode(404);
                }
            });
            server.start();
            com.stockscreener.pipeline.datasource.http.DataSourceProperties properties =
                    new com.stockscreener.pipeline.datasource.http.DataSourceProperties();
            properties.getSecEdgar().setApiKey("sweep");
            String base = server.url("/").toString();
            SecFilingClient client = new SecFilingClient(
                    new com.stockscreener.pipeline.datasource.http.ExternalApiClientFactory(), properties,
                    new com.stockscreener.pipeline.datasource.http.TokenBucketRateLimiter("sec-edgar", 0, 0), base, base);
            supplement = new SecFilingSupplement(client, new MemorySupplementLedger());
        }

        static SupplementReplay start(Path dir) {
            try {
                return new SupplementReplay(dir);
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }

        void close() {
            try {
                server.shutdown();
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }
    }
}
