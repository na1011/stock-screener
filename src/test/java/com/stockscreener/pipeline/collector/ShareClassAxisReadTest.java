package com.stockscreener.pipeline.collector;

import com.stockscreener.pipeline.datasource.StoredShareCounts;
import com.stockscreener.pipeline.datasource.us.shareclass.MembershipHold;
import com.stockscreener.pipeline.datasource.us.shareclass.ShareClassLine;
import com.stockscreener.pipeline.datasource.us.shareclass.ShareClassStore;
import com.stockscreener.support.TestDatabaseGuard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 관문이 켜진 판독기가 판정 표를 읽는 법(관문 마이그레이션 V70 — 시험 프로파일만 만든다).
 *
 * <p>규칙의 정의가 둘이다 — 자바 {@link ShareClassLine}(판독기 셋)과 뷰 {@code us_share_class_effective}(펜스). 첫 시험이 두
 * 정의를 행마다 맞댄다. 갈리면 펜스가 판독기와 다른 것을 잰다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@Import(TestDatabaseGuard.class)
class ShareClassAxisReadTest {

    private static final LocalDate SPLIT = LocalDate.of(2025, 9, 1);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ShareClassStore store;

    @Autowired
    private ShareClassDeriver deriver;

    @Test
    @DisplayName("펜스의 뷰와 판독기의 규칙이 행마다 같다 — 자기 판정 · 정정은 서 있던 판정(분할 옮김) · 미판정 숨김 · 인스턴스 구간 앞")
    void viewMatchesTheReaderRule() {
        long stockId = seedLine("CLS1");

        ShareClassLine line = store.line(stockId);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT f.report_code, f.published_at, f.shares_outstanding, e.shares, e.hidden, e.denominator
                FROM financial_statement f JOIN us_share_class_effective e ON e.financial_statement_id = f.id
                WHERE f.stock_id = ? ORDER BY f.published_at
                """, stockId);
        assertThat(rows).hasSize(6);
        for (Map<String, Object> r : rows) {
            ShareClassLine.Read read = line.read((String) r.get("report_code"),
                    ((java.sql.Date) r.get("published_at")).toLocalDate(), (BigDecimal) r.get("shares_outstanding"));
            String where = (String) r.get("report_code");
            assertThat(r.get("hidden")).as(where).isEqualTo(read.state() == ShareClassLine.State.HIDDEN);
            assertThat(same((BigDecimal) r.get("shares"), read.shares())).as(where + " 주식수").isTrue();
            assertThat(same((BigDecimal) r.get("denominator"), read.denominator())).as(where + " 분모").isTrue();
        }
    }

    @Test
    @DisplayName("순위 판독기 — 정정 행은 서 있던 판정의 N_L(분할 옮김) · 숨긴 판은 안 낸다 · 보류는 마지막 보인 값 · 상한을 넘으면 끝")
    void rankingReaderAndHolds() {
        seedLine("CLS2");
        StoredShareCounts on = new JdbcStoredShareCounts(jdbcTemplate, store, true);

        assertThat(find(on, LocalDate.of(2025, 10, 1))).as("Q2 정정 행 → T2 N_L 1000 × 분할 2")
                .isEqualByComparingTo("2000");
        assertThat(find(on, LocalDate.of(2025, 12, 1))).as("Q3 숨김").isNull();

        StoredShareCounts.ClassHold held = on.classHolds("US", LocalDate.of(2025, 12, 1)).get("CLS2");
        assertThat(held.state()).isEqualTo(MembershipHold.State.HELD);
        assertThat(held.heldShares()).isEqualByComparingTo("2000");
        assertThat(on.classHolds("US", LocalDate.of(2026, 3, 1)).get("CLS2").state())
                .as("숨긴 판 11-01에서 120일").isEqualTo(MembershipHold.State.EXPIRED);
        assertThat(find(on, LocalDate.of(2026, 3, 1))).as("판정 없는 새 정기 공시 = 숨김").isNull();
    }

    @Test
    @DisplayName("유도기 — 축 없는 표지 공시는 지금 경로 판정(표지 · 3개월 분모)이 되고, 다시 돌리면 쓰는 행이 없다")
    void deriverWritesCurrentPathVerdictsIdempotently() {
        long stockId = insertStock("CLS3");
        store.saveInput(stockId, 4242, """
                {"facts":{"us-gaap":{
                  "_filings":{"units":{"filing":[{"accn":"0000004242-25-000001","form":"10-Q","filed":"2025-05-01"}]}},
                  "WeightedAverageNumberOfSharesOutstandingBasic":{"units":{"shares":[
                    {"start":"2025-01-01","end":"2025-03-31","val":480,"accn":"0000004242-25-000001","form":"10-Q","filed":"2025-05-01"}]}}},
                  "dei":{"EntityCommonStockSharesOutstanding":{"units":{"shares":[
                    {"end":"2025-04-20","val":500,"accn":"0000004242-25-000001","form":"10-Q","filed":"2025-05-01"}]}}}}}
                """);

        ShareClassDeriver.Tally first = deriver.derive("US");
        ShareClassDeriver.Tally second = deriver.derive("US");

        Map<String, Object> v = jdbcTemplate.queryForMap(
                "SELECT kind, status, current_path, line_shares, denominator FROM financial_share_class_filing WHERE stock_id = ?",
                stockId);
        assertThat(v.get("kind")).isEqualTo("d");
        assertThat(v.get("status")).isEqualTo("O");
        assertThat(v.get("current_path")).isEqualTo(true);
        assertThat((BigDecimal) v.get("line_shares")).isEqualByComparingTo("500");
        assertThat((BigDecimal) v.get("denominator")).isEqualByComparingTo("480");
        assertThat(first.failedLines()).isEmpty();
        assertThat(second.written()).as("같은 재료면 행을 안 건드린다").isZero();
    }

    /**
     * 한 줄의 재무 여섯 행과 판정 넷. 공시일 순: 축 없는 표지(d) → 앵커(T 보임 1000) → 정정(서 있는 T2 · 사이에 분할 2) → 앵커
     * 숨김 → 판정 없는 새 정기 공시 → (인스턴스 구간 앞의 옛 공시).
     */
    private long seedLine(String ticker) {
        long stockId = insertStock(ticker);
        insertRow(stockId, "10-K:OLD", 2019, "FY", LocalDate.of(2019, 12, 31), LocalDate.of(2020, 3, 1), "700", 0, true);
        insertRow(stockId, "10-Q:D1", 2025, "Q1", LocalDate.of(2025, 3, 31), LocalDate.of(2025, 5, 1), "800", 0, true);
        insertRow(stockId, "10-Q:T2", 2025, "Q2", LocalDate.of(2025, 6, 30), LocalDate.of(2025, 8, 1), null, 0, false);
        insertRow(stockId, "10-Q/A:AM", 2025, "Q2", LocalDate.of(2025, 6, 30), LocalDate.of(2025, 9, 15), null, 1, true);
        insertRow(stockId, "10-Q:T3", 2025, "Q3", LocalDate.of(2025, 9, 30), LocalDate.of(2025, 11, 1), null, 0, true);
        insertRow(stockId, "10-K:NEW", 2025, "FY", LocalDate.of(2025, 12, 31), LocalDate.of(2026, 2, 1), null, 0, true);
        store.replaceVerdicts(stockId, 4141, List.of(
                new ShareClassStore.VerdictRow("D1", "10-Q", LocalDate.of(2025, 5, 1), 'd', 'O', "d", true, null, 800.0, 790.0),
                new ShareClassStore.VerdictRow("T2", "10-Q", LocalDate.of(2025, 8, 1), 'T', 'O', "P-c", false, null, 1000.0, 950.0),
                new ShareClassStore.VerdictRow("T3", "10-Q", LocalDate.of(2025, 11, 1), 'T', 'X', "jump", false, "PROXY", null, 960.0)));
        jdbcTemplate.update("""
                INSERT INTO corporate_action (stock_id, effective_date, type, ratio, detection_source, status)
                VALUES (?, ?, 'SPLIT', 2, 'SOURCE_DISCLOSURE', 'APPLIED')
                """, stockId, SPLIT);
        return stockId;
    }

    private BigDecimal find(StoredShareCounts counts, LocalDate asOf) {
        return counts.activeByMarket("US", asOf.minusYears(2), asOf).stream()
                .filter(c -> c.ticker().startsWith("CLS")).map(StoredShareCounts.ShareCount::shares).findFirst().orElse(null);
    }

    private long insertStock(String ticker) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO stock (market_code, ticker, name) VALUES ('US', ?, ?) RETURNING id
                """, Long.class, ticker, ticker + " Test Inc.");
    }

    private void insertRow(long stockId, String reportCode, int year, String period, LocalDate periodEnd,
                           LocalDate publishedAt, String shares, int revision, boolean latest) {
        jdbcTemplate.update("""
                INSERT INTO financial_statement
                    (stock_id, fiscal_year, fiscal_period, period_start, period_end, published_at, revision_no, is_latest,
                     currency_code, shares_outstanding, report_code)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'USD', ?, ?)
                """, stockId, year, period, periodEnd.minusMonths(3), periodEnd, publishedAt, revision, latest,
                shares == null ? null : new BigDecimal(shares), reportCode);
    }

    private static boolean same(BigDecimal a, BigDecimal b) {
        return Objects.equals(a, b) || (a != null && b != null && a.compareTo(b) == 0);
    }
}
