package com.stockscreener.pipeline.collector;

import com.stockscreener.pipeline.datasource.FilingListSource.ListedDay;
import com.stockscreener.pipeline.datasource.FilingListSource.ListedFiling;
import com.stockscreener.pipeline.datasource.FilingListSource.ListedStock;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 원장 공시 목록의 커서({@code filing_listing_day})와 소비 장부({@code filing_consumption})를 쓴다(ADR-0176 · V67).
 *
 * <p>MUST: 하루치 장부 행과 그날을 닫는 행은 한 트랜잭션이다({@link #recordDay}) — 따로 쓰면 장부 행 없이 닫힌 날이 생겨 그 공시가
 * 영영 안 들어온다. 장부 행은 {@code (시장, 접수번호, 종목)}이 같으면 다시 넣지 않는다 — 국내의 「오늘」은 닫지 않고 다음 실행이
 * 다시 받으므로 같은 줄이 두 번 온다.
 */
@Component
public class FilingLedgerStore {

    private final JdbcTemplate jdbc;

    public FilingLedgerStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 그 시장에서 닫은 마지막 날. 한 번도 안 닫았으면 비어 있다. */
    public Optional<LocalDate> cursor(String market) {
        LocalDate last = jdbc.queryForObject(
                "SELECT max(listing_date) FROM filing_listing_day WHERE market = ?", LocalDate.class, market);
        return Optional.ofNullable(last);
    }

    /** 장부 행을 넣고 그날을 닫는다. 넣은(새) 장부 행 수를 낸다. */
    @Transactional(rollbackFor = Exception.class)
    public int recordDay(String market, ListedDay day) {
        int inserted = insert(market, day.filings());
        jdbc.update("""
                INSERT INTO filing_listing_day (market, listing_date, outcome, source_rows, kept_rows)
                VALUES (?, ?, ?, ?, ?)
                """, market, Date.valueOf(day.day()), day.outcome().name(), day.sourceRows(), day.filings().size());
        return inserted;
    }

    /** 장부 행만 넣는다 — 닫지 않는 날(국내의 오늘)이 쓴다. 넣은(새) 장부 행 수를 낸다. */
    @Transactional(rollbackFor = Exception.class)
    public int recordOpen(String market, List<ListedFiling> filings) {
        return insert(market, filings);
    }

    private int insert(String market, List<ListedFiling> filings) {
        int inserted = 0;
        for (ListedFiling f : filings) {
            inserted += jdbc.update("""
                    INSERT INTO filing_consumption
                        (market, filing_no, stock_id, cik, corp_code, form, report_name, filed_on, bsns_year, reprt_code,
                         last_reason)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (market, filing_no, stock_id) DO NOTHING
                    """, market, f.filingNo(), f.stockId(), f.cik(), f.corpCode(), f.form(), f.reportName(),
                    Date.valueOf(f.filedOn()), f.bsnsYear(), f.reprtCode(), f.lastReason());
        }
        return inserted;
    }

    /**
     * 그 시장의 우리 종목 전부를 티커로. <b>편입 여부는 거르지 않는다</b> — 재편입 종목의 공시를 잃지 않게(ADR-0176). 소비 단계가
     * 소비 시점의 편입으로 고른다.
     */
    public Map<String, ListedStock> stocksByTicker(String market) {
        String where = switch (market) {
            case "US" -> "market_code = 'US'";
            case "KR" -> "market_code IN ('KOSPI', 'KOSDAQ')";
            default -> throw new IllegalArgumentException("장부의 시장이 아니다: " + market);
        };
        Map<String, ListedStock> out = new HashMap<>();
        jdbc.query("SELECT id, ticker, accounting_month FROM stock WHERE " + where, rs -> {
            int month = rs.getInt("accounting_month");
            out.put(rs.getString("ticker"), new ListedStock(rs.getLong("id"), rs.wasNull() ? null : month));
        });
        return out;
    }

    // ── 소비(ADR-0176 2차) ──────────────────────────────────────────────────────────────────────────────────────────────

    /** 소비 단계가 읽는 열린 장부 행. {@code frOpen}·{@code fsrOpen}은 그 몫이 아직 안 끝났는가(미국은 FSR 몫이 없다). */
    public record OpenRow(long id, String market, long stockId, String ticker, String filingNo, Long cik, String form,
                          LocalDate filedOn, Integer bsnsYear, String reprtCode, boolean frOpen, boolean fsrOpen,
                          Instant firstSeenAt, String lastReason) {
    }

    private static final String OPEN_COLUMNS = """
            c.id, c.market, c.stock_id, s.ticker, c.filing_no, c.cik, c.form, c.filed_on, c.bsns_year, c.reprt_code,
            c.fr_consumed_at IS NULL AS fr_open,
            (c.market = 'KR' AND c.fsr_consumed_at IS NULL) AS fsr_open,
            c.first_seen_at, c.last_reason
            """;

    /**
     * 그 시장의 열린 행 중 <b>지금 활성 편입</b>인 종목의 것 — 소비 대상이다. 편입 밖 행은 잠든 채 남는다(재편입하면 다시 잡힌다).
     * 활성 편입의 술어는 {@code StockRegistrar.activeUniverseMemberIds}와 같다.
     */
    public List<OpenRow> openForActiveMembers(String market) {
        return jdbc.query("SELECT " + OPEN_COLUMNS + """
                FROM filing_consumption c JOIN stock s ON s.id = c.stock_id
                WHERE c.market = ?
                  AND (c.fr_consumed_at IS NULL OR (c.market = 'KR' AND c.fsr_consumed_at IS NULL))
                  AND s.is_active
                  AND EXISTS (SELECT 1 FROM universe_membership m JOIN universe u ON u.code = m.universe_code
                              WHERE m.stock_id = c.stock_id AND m.effective_to IS NULL AND u.is_active)
                ORDER BY c.stock_id, c.filing_no
                """, (rs, i) -> openRow(rs), market);
    }

    /** 한 종목의 FR 몫이 열린 행 — 소비 훅이 그 종목의 쓰기 트랜잭션 안에서 읽는다. */
    public List<OpenRow> openFrOf(String market, long stockId) {
        return jdbc.query("SELECT " + OPEN_COLUMNS + """
                FROM filing_consumption c JOIN stock s ON s.id = c.stock_id
                WHERE c.market = ? AND c.stock_id = ? AND c.fr_consumed_at IS NULL
                ORDER BY c.filing_no
                """, (rs, i) -> openRow(rs), market, stockId);
    }

    /** 한 종목의 FSR 몫만 열린 행(FR 몫은 끝남 · 국내만). */
    public List<OpenRow> openFsrOf(long stockId) {
        return jdbc.query("SELECT " + OPEN_COLUMNS + """
                FROM filing_consumption c JOIN stock s ON s.id = c.stock_id
                WHERE c.market = 'KR' AND c.stock_id = ? AND c.fr_consumed_at IS NOT NULL AND c.fsr_consumed_at IS NULL
                ORDER BY c.filing_no
                """, (rs, i) -> openRow(rs), stockId);
    }

    /** 보충 표의 (접수번호 → 상태) — 그 CIK의 주어진 접수번호만. 같은 트랜잭션이면 이번 FR이 막 쓴 행도 보인다. */
    public Map<String, String> supplementStatus(long cik, Collection<String> accessions) {
        Map<String, String> out = new HashMap<>();
        if (accessions.isEmpty()) {
            return out;
        }
        jdbc.query("SELECT accession, status FROM sec_filing_supplement WHERE cik = ? AND accession = ANY (?)",
                rs -> {
                    out.put(rs.getString("accession"), rs.getString("status"));
                }, cik, accessions.toArray(String[]::new));
        return out;
    }

    /** FR 몫을 소비됨으로 표시한다. 이미 끝난 행이면 아무것도 안 바꾸고 0을 낸다. */
    public int markFrConsumed(long id, String ground, String evidence, long executionId) {
        return jdbc.update("""
                UPDATE filing_consumption
                SET fr_consumed_at = now(), fr_ground = ?, fr_evidence = ?, consumed_by_execution = ?,
                    attempts = attempts + 1, last_attempt_at = now(), last_reason = NULL
                WHERE id = ? AND fr_consumed_at IS NULL
                """, ground, evidence, executionId, id);
    }

    /** FSR 몫을 소비됨으로 표시한다(국내 · FR 몫이 끝난 행만). */
    public int markFsrConsumed(long id, String evidence, long executionId) {
        return jdbc.update("""
                UPDATE filing_consumption
                SET fsr_consumed_at = now(), fsr_evidence = ?, fsr_by_execution = ?,
                    attempts = attempts + 1, last_attempt_at = now(), last_reason = NULL
                WHERE id = ? AND market = 'KR' AND fr_consumed_at IS NOT NULL AND fsr_consumed_at IS NULL
                """, evidence, executionId, id);
    }

    /** 소비하지 못한 시도를 남긴다 — 행은 열린 채이고 사유만 바뀐다. */
    public int recordAttempt(long id, String reason) {
        return jdbc.update("""
                UPDATE filing_consumption
                SET attempts = attempts + 1, last_attempt_at = now(), last_reason = ?
                WHERE id = ?
                """, reason, id);
    }

    private static OpenRow openRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        long cik = rs.getLong("cik");
        Long cikOrNull = rs.wasNull() ? null : cik;
        int year = rs.getInt("bsns_year");
        Integer yearOrNull = rs.wasNull() ? null : year;
        return new OpenRow(rs.getLong("id"), rs.getString("market"), rs.getLong("stock_id"), rs.getString("ticker"),
                rs.getString("filing_no"), cikOrNull, rs.getString("form"), rs.getDate("filed_on").toLocalDate(),
                yearOrNull, rs.getString("reprt_code"), rs.getBoolean("fr_open"), rs.getBoolean("fsr_open"),
                rs.getTimestamp("first_seen_at").toInstant(), rs.getString("last_reason"));
    }
}
