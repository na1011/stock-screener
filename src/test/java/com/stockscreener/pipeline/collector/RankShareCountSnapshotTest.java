package com.stockscreener.pipeline.collector;

import com.stockscreener.pipeline.datasource.StoredShareCounts;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 미국 시총 순위가 읽는 주식수 선택({@link JdbcStoredShareCounts#activeByMarket})을 격자 날짜마다 TSV로 찍는다.
 *
 * <p>재무 행을 바꾸는 작업(이른 판 삽입 · 재번호 · 정정) 전후에 찍어 diff하면 <b>외부 콜 없이</b> 순위 재료가 움직였는지
 * 답한다. {@code universe-restore --dates}는 과거 날짜에도 오늘의 상장 목록을 써 같은 물음의 판정 재료가 흔들린다.
 * 질의를 다시 짜지 않고 운영 코드를 그대로 부른다 — 조건을 다시 쓰면 정상 경로가 차이로 신고된다.
 *
 * <p>격자는 복원과 같은 「그 주의 마지막 거래일」에 최신 거래일을 더한 것이고, 주식수 창은
 * {@code UsMarketCapUniverseAdapter}의 2년이다. 접속은 읽기 전용이다.
 *
 * <pre>
 * RANK_SNAPSHOT_DB=stock_screener_q4v_r0 RANK_SNAPSHOT_OUT=rank-r0.tsv \
 *   ./gradlew test --tests "*RankShareCountSnapshotTest*" -PskipFrontend
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "RANK_SNAPSHOT_DB", matches = "stock_screener.*")
class RankShareCountSnapshotTest {

    /** {@code UsMarketCapUniverseAdapter.SHARES_MAX_AGE_YEARS}와 같아야 한다. */
    private static final int SHARES_MAX_AGE_YEARS = 2;

    @Test
    void snapshot() throws Exception {
        String db = System.getenv("RANK_SNAPSHOT_DB");
        Path out = Path.of(Optional.ofNullable(System.getenv("RANK_SNAPSHOT_OUT")).orElse("build/rank-shares.tsv"));
        String user = Optional.ofNullable(System.getenv("DB_USERNAME")).orElse(System.getProperty("user.name"));
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:postgresql://localhost:5432/" + db + "?readOnlyMode=always", user, "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        StoredShareCounts counts = new JdbcStoredShareCounts(jdbc);

        List<LocalDate> grid = jdbc.queryForList("""
                SELECT trade_date FROM (
                    SELECT trade_date,
                           row_number() OVER (PARTITION BY date_trunc('week', trade_date) ORDER BY trade_date DESC) AS rn
                    FROM indicator_trade_date WHERE market_code = 'US') t
                WHERE rn = 1 ORDER BY trade_date
                """, LocalDate.class);

        long rows = 0;
        Files.createDirectories(out.toAbsolutePath().getParent());
        try (BufferedWriter w = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
            w.write("as_of\tticker\tshares\tperiod_end\tpublished_at\tcurrency_mismatched");
            w.newLine();
            for (LocalDate asOf : grid) {
                for (StoredShareCounts.ShareCount c : counts.activeByMarket("US", asOf.minusYears(SHARES_MAX_AGE_YEARS), asOf)) {
                    w.write(asOf + "\t" + c.ticker() + "\t" + c.shares().stripTrailingZeros().toPlainString() + "\t"
                            + c.periodEnd() + "\t" + c.publishedAt() + "\t" + c.currencyMismatched());
                    w.newLine();
                    rows++;
                }
            }
        }
        System.out.println("RANK_SNAPSHOT db=" + db + " 격자=" + grid.size() + " 행=" + rows + " out=" + out.toAbsolutePath());
    }
}
