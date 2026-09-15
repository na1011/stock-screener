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
            "incomeTaxExpense", "inventories", "capex", "cashAndEquivalents", "retainedEarnings");

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
            RawFinancialReport::retainedEarnings);

    @Test
    void sweep() throws Exception {
        Path dir = Path.of(System.getenv("REPLAY_SWEEP_DIR"));
        Path out = Path.of(Optional.ofNullable(System.getenv("REPLAY_SWEEP_OUT"))
                .orElse("build/sweep.tsv"));
        int from = Integer.parseInt(
                Optional.ofNullable(System.getenv("REPLAY_SWEEP_FROM")).orElse("2015"));
        Files.createDirectories(out.toAbsolutePath().getParent());

        ObjectMapper mapper = new ObjectMapper();
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
        try (BufferedWriter w = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
            w.write(String.join("\t", HEADER));
            w.newLine();
            for (Path file : files) {
                String ticker = file.getFileName().toString().replaceFirst("\\.json$", "");
                SecEdgarFacts.Result result;
                try {
                    result = SecEdgarFacts.build(
                            mapper.readTree(Files.readString(file)).path("facts"), from);
                } catch (RuntimeException | java.io.IOException e) {
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
                }
            }
        }
        System.out.println("SWEEP 파일=" + files.size() + " 종목=" + stocks + " 보고서=" + rows
                + " 버린팩트=" + dropped + " 부재=" + absences + " 읽기실패=" + failed
                + " out=" + out.toAbsolutePath());
        if (failed > 0) {
            System.out.println("SWEEP 실패목록\n" + failures);
        }
    }
}
