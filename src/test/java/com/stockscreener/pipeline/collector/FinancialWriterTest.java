package com.stockscreener.pipeline.collector;

import com.stockscreener.pipeline.datasource.RawFinancialReport;
import com.stockscreener.support.JpaSliceTest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 정정공시 처리를 검증한다.
 *
 * <p>핵심은 <b>덮어쓰지 않는다</b>는 것이다. 하나의 가변 행으로 두면 "그 시점에 공개되어
 * 있던 값"이 영구히 파괴되어 어떤 마이그레이션으로도 복구할 수 없다.
 */
@JpaSliceTest
@Import(FinancialWriter.class)
class FinancialWriterTest {

    @Autowired
    private FinancialWriter writer;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long stockId;

    @BeforeEach
    void insertStock() {
        stockId = jdbcTemplate.queryForObject("""
                INSERT INTO stock (market_code, ticker, name) VALUES ('KOSPI', '005930', '삼성전자')
                RETURNING id
                """, Long.class);
    }

    @Test
    @DisplayName("보고서를 저장하고 최신본으로 표시한다")
    void writesReportAsLatest() {
        long written = writer.write(stockId, List.of(report(2025, "FY", "2026-03-15", "1000")));

        assertThat(written).isEqualTo(1);
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT * FROM financial_statement WHERE stock_id = ?", stockId);
        assertThat(row.get("revision_no")).isEqualTo(0);
        assertThat(row.get("is_latest")).isEqualTo(true);
        assertThat((BigDecimal) row.get("net_income")).isEqualByComparingTo("1000");
    }

    @Test
    @DisplayName("정정공시는 덮어쓰지 않고 새 개정본으로 쌓는다 — 과거 공개값이 파괴되면 안 된다")
    void appendsRevisionInsteadOfOverwriting() {
        writer.write(stockId, List.of(report(2025, "FY", "2026-03-15", "1000")));
        long written = writer.write(stockId, List.of(report(2025, "FY", "2026-04-20", "950")));

        assertThat(written).isEqualTo(1);
        // 원본이 남아 있어야 한다.
        assertThat(rowCount()).isEqualTo(2);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT revision_no, is_latest, net_income FROM financial_statement
                WHERE stock_id = ? ORDER BY revision_no
                """, stockId);
        assertThat(rows.get(0).get("revision_no")).isEqualTo(0);
        assertThat(rows.get(0).get("is_latest")).isEqualTo(false);
        assertThat((BigDecimal) rows.get(0).get("net_income")).isEqualByComparingTo("1000");
        assertThat(rows.get(1).get("revision_no")).isEqualTo(1);
        assertThat(rows.get(1).get("is_latest")).isEqualTo(true);
        assertThat((BigDecimal) rows.get(1).get("net_income")).isEqualByComparingTo("950");
    }

    @Test
    @DisplayName("같은 공시일의 재수집은 아무것도 하지 않는다 — 멱등해야 한다")
    void isIdempotentForSamePublicationDate() {
        RawFinancialReport report = report(2025, "FY", "2026-03-15", "1000");
        writer.write(stockId, List.of(report));
        long written = writer.write(stockId, List.of(report));

        assertThat(written).isZero();
        assertThat(rowCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("이전 공시일의 보고서는 무시한다 — 소스가 오래된 값을 줘도 최신본을 덮지 않는다")
    void ignoresOlderPublication() {
        writer.write(stockId, List.of(report(2025, "FY", "2026-04-20", "950")));
        long written = writer.write(stockId, List.of(report(2025, "FY", "2026-03-15", "1000")));

        assertThat(written).isZero();
        assertThat(rowCount()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT net_income FROM financial_statement WHERE stock_id = ? AND is_latest",
                BigDecimal.class, stockId)).isEqualByComparingTo("950");
    }

    @Test
    @DisplayName("FY와 Q4를 별개로 저장한다 — 소스가 준 형태 그대로 두고 판단은 지표 계층에 맡긴다")
    void keepsAnnualAndFourthQuarterSeparate() {
        writer.write(stockId, List.of(
                report(2025, "FY", "2026-03-15", "1000"),
                report(2025, "Q4", "2026-02-10", "300")));

        assertThat(rowCount()).isEqualTo(2);
        assertThat(jdbcTemplate.queryForList(
                "SELECT fiscal_period FROM financial_statement WHERE stock_id = ? ORDER BY fiscal_period",
                String.class, stockId)).containsExactly("FY", "Q4");
    }

    @Test
    @DisplayName("여러 기간의 보고서를 한 번에 저장한다")
    void writesMultiplePeriods() {
        long written = writer.write(stockId, List.of(
                report(2023, "FY", "2024-03-15", "800"),
                report(2024, "FY", "2025-03-15", "900"),
                report(2025, "FY", "2026-03-15", "1000")));

        assertThat(written).isEqualTo(3);
        assertThat(rowCount()).isEqualTo(3);
    }

    private int rowCount() {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM financial_statement WHERE stock_id = ?", Integer.class, stockId);
    }

    private static RawFinancialReport report(int fiscalYear, String period,
                                             String publishedAt, String netIncome) {
        return new RawFinancialReport(
                fiscalYear, period,
                LocalDate.of(fiscalYear, 1, 1), LocalDate.of(fiscalYear, 12, 31),
                LocalDate.parse(publishedAt),
                "11011", "KRW",
                new BigDecimal("5000"), new BigDecimal("1200"), new BigDecimal(netIncome),
                new BigDecimal("20000"), new BigDecimal("12000"), new BigDecimal("8000"),
                new BigDecimal("1500"), new BigDecimal("100"),
                new BigDecimal("10"), new BigDecimal("120"));
    }
}
