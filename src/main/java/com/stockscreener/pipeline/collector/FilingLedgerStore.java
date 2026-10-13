package com.stockscreener.pipeline.collector;

import com.stockscreener.pipeline.datasource.FilingListSource.ListedDay;
import com.stockscreener.pipeline.datasource.FilingListSource.ListedFiling;
import com.stockscreener.pipeline.datasource.FilingListSource.ListedStock;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.time.LocalDate;
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
}
