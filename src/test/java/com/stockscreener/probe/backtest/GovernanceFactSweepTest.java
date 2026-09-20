package com.stockscreener.probe.backtest;

import com.stockscreener.pipeline.collector.GovernanceFactDeriver;
import com.stockscreener.pipeline.datasource.GovernanceFact;
import com.stockscreener.pipeline.datasource.GovernanceFactMapper;
import com.stockscreener.support.ReadOnlyDatabaseGuard;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>거버넌스 유도의 전/후를 재적재 없이 대조할 기준선</b> — 저장된 원문 전량을 유도기에 먹여
 * 사실을 TSV로 찍는다. <b>외부 호출 0 · DB 쓰기 0.</b>
 *
 * <p>왜 있는가: 재무에는 {@code SecEdgarFactsSweepTest}가 있어 «고치기 전/후 TSV를 diff하면
 * 재적재 없이 영향 범위가 나온다»가 성립했는데, <b>거버넌스에는 그 도구가 없었다.</b> 그래서
 * 거버넌스 변경의 수용 기준이 언제나 «재적재 뒤에 재는 것»이었고, 그것 자체가 순서를 어기는
 * 일이다(CLAUDE.md 「재적재·백필」).
 *
 * <p><b>MUST: 유도 질의를 여기에 복사하지 않는다.</b> {@link GovernanceFactDeriver#factsFor}를
 * 그대로 부른다 — 복사하면 두 곳이 어긋나기 시작할 때까지 안 드러나고, 그때 이 스윕은
 * <b>실제로 저장될 것과 다른 것</b>을 보증한다.
 *
 * <p>실행: {@code ./gradlew backtestProbe --tests '*GovernanceFactSweepTest*'
 * -Dsweep.out=<경로.tsv>}. 출력 경로가 없으면 찍지 않는다({@code backtestProbe}가
 * {@code @Tag("backtest")} 전부를 돌므로, 섞여 돌 때 붉히지 않는다).
 */
@Tag("backtest")
@SpringBootTest(
        classes = {BacktestHarnessContext.class, GovernanceFactSweepTest.DeriverContext.class},
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("backtest")
@Import(ReadOnlyDatabaseGuard.class)
class GovernanceFactSweepTest {

    /**
     * 유도기와 매퍼만 들인다.
     *
     * <p>{@link BacktestHarnessContext}가 {@code pipeline.collector}를 통째로 스캔하지 않는 이유가
     * 여기도 그대로 걸린다 — 그 패키지에는 외부 어댑터를 요구하는 수집기가 함께 산다. 그래서
     * 유도기는 <b>타입으로</b> 집어 오고, 매퍼는 {@code useDefaultFilters = false} + 인터페이스
     * 필터로 <b>정확히 그 둘만</b> 등록한다(둘 다 package-private이라 이름으로 못 집는다).
     */
    @Configuration(proxyBeanMethods = false)
    @Import(GovernanceFactDeriver.class)
    @ComponentScan(
            basePackages = "com.stockscreener.pipeline.datasource",
            useDefaultFilters = false,
            includeFilters = @ComponentScan.Filter(
                    type = FilterType.ASSIGNABLE_TYPE, classes = GovernanceFactMapper.class))
    static class DeriverContext {
    }

    @Autowired
    private GovernanceFactDeriver deriver;

    @Autowired
    private List<GovernanceFactMapper> mappers;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private record Target(long stockId, String marketCode, String ticker) {
    }

    @Test
    @DisplayName("저장된 거버넌스 원문 전량을 유도해 사실을 TSV로 찍는다")
    void sweepDerivedFacts() throws Exception {
        String out = System.getProperty("sweep.out");
        Assumptions.assumeTrue(out != null && !out.isBlank(),
                "-Dsweep.out=<경로.tsv> 가 없어 기준선을 찍지 않는다");

        // MUST: 매퍼가 붙었는지 **먼저** 단언한다. 하나도 없으면 factsFor가 전 종목에서
        // Optional.empty()를 내고, TSV는 0행인데 테스트는 초록이며 diff도 언제나 비어 있다 —
        // 「바뀐 것이 없다」와 「아무것도 안 돌았다」가 구분되지 않는 바로 그 자리다.
        assertThat(mappers).as("거버넌스 매퍼가 하나도 등록되지 않았다 — 스윕이 빈 파일을 낸다")
                .isNotEmpty();
        assertThat(mappers.stream()
                .flatMap(mapper -> mapper.supportedMarketCodes().stream())
                .toList())
                .as("스윕이 덮는 시장")
                .contains("KOSPI", "KOSDAQ", "US");

        // 모집단은 **유도기의 입력 집합 그대로**다 — 원문이 한 행이라도 있는 종목.
        // 편입으로 좁히면 편입이 바뀔 때마다 diff에 노이즈가 섞인다.
        List<Target> targets = jdbcTemplate.query("""
                SELECT DISTINCT s.id, s.market_code, s.ticker
                  FROM governance_report_row r
                  JOIN stock s ON s.id = r.stock_id
                 ORDER BY s.market_code, s.ticker
                """, (rs, rowNum) -> new Target(
                rs.getLong("id"), rs.getString("market_code"), rs.getString("ticker")));
        assertThat(targets).as("거버넌스 원문이 있는 종목이 하나도 없다").isNotEmpty();

        long facts = 0;
        int noMapper = 0;
        List<String> lines = new ArrayList<>();
        for (Target target : targets) {
            List<GovernanceFact> derived = deriver.factsFor(target.stockId()).orElse(null);
            if (derived == null) {
                noMapper++;
                lines.add("%s\t%s\t\t\t\t\t\t(매퍼 없음)".formatted(
                        target.marketCode(), target.ticker()));
                continue;
            }
            facts += derived.size();
            // 정렬을 여기서 못박는다. 유도 순서는 엔드포인트 맵의 순회 순서에 달려 있어
            // 코드를 안 고쳐도 흔들릴 수 있고, 그러면 diff가 통째로 빨개진다.
            derived.stream()
                    .sorted(java.util.Comparator
                            .comparingInt(GovernanceFact::fiscalYear)
                            .thenComparing(fact -> fact.metric().name())
                            .thenComparing(GovernanceFact::receiptNo))
                    .forEach(fact -> lines.add("%s\t%s\t%d\t%s\t%s\t%s\t%s".formatted(
                            target.marketCode(), target.ticker(), fact.fiscalYear(),
                            fact.metric().name(), fact.value().toPlainString(),
                            fact.publishedAt(), fact.receiptNo())));
        }

        try (PrintWriter writer = new PrintWriter(
                Files.newBufferedWriter(Path.of(out), StandardCharsets.UTF_8))) {
            writer.println("market\tticker\tfiscal_year\tmetric_key\tvalue\tpublished_at\treceipt_no");
            lines.forEach(writer::println);
        }

        System.out.printf("거버넌스 스윕: 종목 %d · 사실 %d행 · 매퍼 없음 %d · 출력 %s%n",
                targets.size(), facts, noMapper, out);
        // 「돌았는데 0건」과 「안 돌았다」를 가르는 마지막 단언이다.
        assertThat(facts).as("사실이 한 건도 안 나왔다 — 아래 diff는 무엇도 보증하지 않는다")
                .isPositive();
    }
}
