package com.stockscreener.pipeline.collector;

import com.stockscreener.pipeline.datasource.StoredShareCounts;
import com.stockscreener.pipeline.datasource.us.shareclass.ShareClassStore;

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
 * <p>격자는 복원({@code UniverseLedgerRestorer.weeklyGrid})과 같은 「그 주의 마지막 거래일 + 보존 창 첫날」이고, 주식수 창은
 * {@code UsMarketCapUniverseAdapter}의 2년이다. 접속은 읽기 전용이다.
 *
 * <p><b>{@code RANK_SNAPSHOT_CLASS_AXIS=true}면 관문이 켜진 판독기</b>(클래스 축 판정 · ADR-0179)로 읽는다 — 관문 전은 꺼진 판독기,
 * 관문 뒤는 켠 판독기로 찍어야 「그때 순위가 읽은 것」과 「켠 뒤 순위가 읽는 것」을 대조한다({@code ops/apply-window/02-snapshot.sh}).
 * 켠 판독기는 보이지 않는 줄을 {@link StoredShareCounts#classHolds}로 따로 내므로 그 줄도 {@code hold:<상태>}로 찍는다.
 * SIC 열은 순위 어댑터의 비사업회사 거름 재료다 — 업종이 새로 붙은 후보는 주식수가 같아도 이 열로 차이가 난다.
 *
 * <pre>
 * RANK_SNAPSHOT_DB=stock_screener_q4v_r0 RANK_SNAPSHOT_OUT=rank-r0.tsv [RANK_SNAPSHOT_CLASS_AXIS=true] \
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
        boolean classAxis = Boolean.parseBoolean(System.getenv("RANK_SNAPSHOT_CLASS_AXIS"));
        StoredShareCounts counts = classAxis
                ? new JdbcStoredShareCounts(jdbc, new ShareClassStore(jdbc), true)
                : new JdbcStoredShareCounts(jdbc);

        List<LocalDate> grid = jdbc.queryForList("""
                SELECT trade_date FROM (
                    SELECT trade_date,
                           row_number() OVER (PARTITION BY date_trunc('week', trade_date) ORDER BY trade_date DESC) AS rn,
                           min(trade_date) OVER () AS earliest
                    FROM indicator_trade_date WHERE market_code = 'US') t
                WHERE rn = 1 OR trade_date = earliest ORDER BY trade_date
                """, LocalDate.class);

        long rows = 0;
        Files.createDirectories(out.toAbsolutePath().getParent());
        try (BufferedWriter w = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
            w.write("as_of\tticker\tshares\tperiod_end\tpublished_at\tcurrency_mismatched\tsic");
            w.newLine();
            for (LocalDate asOf : grid) {
                for (StoredShareCounts.ShareCount c : counts.activeByMarket("US", asOf.minusYears(SHARES_MAX_AGE_YEARS), asOf)) {
                    w.write(asOf + "\t" + c.ticker() + "\t" + c.shares().stripTrailingZeros().toPlainString() + "\t"
                            + c.periodEnd() + "\t" + c.publishedAt() + "\t" + c.currencyMismatched() + "\t" + c.sicCode());
                    w.newLine();
                    rows++;
                }
                for (StoredShareCounts.ClassHold h : new java.util.TreeMap<>(counts.classHolds("US", asOf)).values()) {
                    w.write(asOf + "\t" + h.ticker() + "\t"
                            + (h.heldShares() == null ? "" : h.heldShares().stripTrailingZeros().toPlainString())
                            + "\thold:" + h.state() + "\t\t" + h.currencyMismatched() + "\t" + h.sicCode());
                    w.newLine();
                    rows++;
                }
            }
        }
        System.out.println("RANK_SNAPSHOT db=" + db + " 클래스축=" + classAxis + " 격자=" + grid.size() + " 행=" + rows + " out=" + out.toAbsolutePath());
    }
}
