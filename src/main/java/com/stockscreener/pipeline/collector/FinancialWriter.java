package com.stockscreener.pipeline.collector;

import com.stockscreener.pipeline.datasource.RawFinancialReport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * 수집한 재무제표를 {@code financial_statement}에 쓴다.
 *
 * <p>IMPORTANT — 정정공시는 <b>덮어쓰지 않고 새 행으로 쌓는다.</b> 하나의 가변 행으로 두면
 * "그 시점에 공개되어 있던 값"이 영구히 파괴되어 어떤 마이그레이션으로도 복구할 수 없다.
 * v1은 그 질의를 구현하지 않고 항상 최신본을 쓰지만, 질의는 미루되 데이터는 미루지 않는다.
 *
 * <p>따라서 이 클래스의 핵심은 upsert가 아니라 <b>개정 판별</b>이다. 같은 회계기간의 보고서가
 * 다시 들어왔을 때 그것이 재수집인지 정정공시인지 구분해야 한다. 기준은
 * {@code published_at}이다 — 공시일이 더 늦으면 정정본이다.
 */
@Component
public class FinancialWriter {

    private static final Logger log = LoggerFactory.getLogger(FinancialWriter.class);

    private final JdbcTemplate jdbcTemplate;

    public FinancialWriter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 한 종목의 재무제표를 저장한다.
     *
     * @return 실제로 저장한 행 수. 이미 있는 보고서는 세지 않는다.
     */
    public long write(long stockId, List<RawFinancialReport> reports) {
        long written = 0;
        for (RawFinancialReport report : reports) {
            written += writeOne(stockId, report);
        }
        return written;
    }

    private long writeOne(long stockId, RawFinancialReport report) {
        Existing existing = findLatest(stockId, report);

        if (existing != null) {
            if (!report.publishedAt().isAfter(existing.publishedAt())) {
                // 같거나 이전 공시일 — 재수집이다. 아무것도 하지 않는다.
                // MUST NOT: 여기서 덮어쓰지 않는다. 값이 달라졌다면 그것은 소스의 오류이거나
                // 우리 파싱의 오류이며, 조용히 갱신하면 어느 쪽인지 영영 알 수 없다.
                return 0L;
            }
            // 정정공시다. 기존 최신본을 내리고 새 개정본을 쌓는다.
            log.info("정정공시를 반영한다. stockId={} {}/{} 기존공시일={} 새공시일={}",
                    stockId, report.fiscalYear(), report.fiscalPeriod(),
                    existing.publishedAt(), report.publishedAt());
            jdbcTemplate.update("""
                    UPDATE financial_statement SET is_latest = false
                    WHERE stock_id = ? AND fiscal_year = ? AND fiscal_period = ? AND is_latest
                    """, stockId, report.fiscalYear(), report.fiscalPeriod());
        }

        int revisionNo = existing == null ? 0 : existing.revisionNo() + 1;
        insert(stockId, report, revisionNo);
        return 1L;
    }

    /**
     * 같은 회계기간의 현재 최신본을 찾는다.
     *
     * <p>{@code uq_fs_latest} 부분 유니크 인덱스가 최신본이 정확히 하나임을 보장하므로
     * 여러 행이 돌아올 수 없다.
     */
    private Existing findLatest(long stockId, RawFinancialReport report) {
        List<Existing> found = jdbcTemplate.query("""
                SELECT published_at, revision_no FROM financial_statement
                WHERE stock_id = ? AND fiscal_year = ? AND fiscal_period = ? AND is_latest
                """,
                (rs, rowNum) -> new Existing(
                        rs.getObject("published_at", LocalDate.class),
                        rs.getInt("revision_no")),
                stockId, report.fiscalYear(), report.fiscalPeriod());
        return found.isEmpty() ? null : found.get(0);
    }

    private void insert(long stockId, RawFinancialReport report, int revisionNo) {
        jdbcTemplate.update("""
                INSERT INTO financial_statement
                    (stock_id, fiscal_year, fiscal_period, period_start, period_end, published_at,
                     revision_no, is_latest, report_code, currency_code,
                     revenue, operating_income, net_income, total_assets, total_equity,
                     total_liabilities, operating_cash_flow, shares_outstanding, eps, bps,
                     created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, true, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
                """,
                stockId, report.fiscalYear(), report.fiscalPeriod(),
                report.periodStart(), report.periodEnd(), report.publishedAt(),
                revisionNo, report.reportCode(), report.currencyCode(),
                report.revenue(), report.operatingIncome(), report.netIncome(),
                report.totalAssets(), report.totalEquity(), report.totalLiabilities(),
                report.operatingCashFlow(), report.sharesOutstanding(),
                report.eps(), report.bps());
    }

    private record Existing(LocalDate publishedAt, int revisionNo) {
    }
}
