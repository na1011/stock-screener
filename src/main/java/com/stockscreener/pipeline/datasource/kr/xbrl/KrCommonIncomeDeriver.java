package com.stockscreener.pipeline.datasource.kr.xbrl;

import com.stockscreener.pipeline.datasource.kr.xbrl.KrCommonIncome.Register;
import com.stockscreener.pipeline.datasource.kr.xbrl.KrCommonIncome.ShareRow;
import com.stockscreener.pipeline.datasource.kr.xbrl.KrCommonIncomeRows.Resolved;
import com.stockscreener.pipeline.datasource.kr.xbrl.KrCommonIncomeRows.Row;
import com.stockscreener.pipeline.datasource.kr.xbrl.KrXbrlStore.Filing;
import com.stockscreener.pipeline.datasource.kr.xbrl.KrXbrlStore.RowVintage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 국내 재무 행마다 보통주 몫을 붙인다(ADR-0192 · <b>국내 관문 켬에서만</b> 부른다 — {@code IndicatorReconciler}). 외부 호출 0 — 재료 표 둘 ·
 * 재무 행 · 기업행위 원장만 읽고 {@code financial_common_income}의 국내 행을 종목마다 통째로 바꾼다.
 *
 * <p>종목 하나의 실패(예상 못 한 예외)는 그 종목만 건너뛰고 끝에 수를 남긴다 — 다른 종목의 판정을 막지 않는다. 건너뛴 종목은 옛 행이
 * 남지만 읽는 쪽이 스냅숏으로 낡음을 가른다(행이 바뀌었으면 「붙은 것 없음」).
 */
@Component
public class KrCommonIncomeDeriver {

    private static final Logger log = LoggerFactory.getLogger(KrCommonIncomeDeriver.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JdbcTemplate jdbc;
    private final KrXbrlStore store;
    private final TransactionTemplate transaction;

    public KrCommonIncomeDeriver(JdbcTemplate jdbc, KrXbrlStore store, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.store = store;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /** 결과 수 — 종목 · 붙인 행 · 값을 정한 행 · 건너뛴 종목(예외). */
    public record Derived(int stocks, int rows, int decided, int failedStocks) {
    }

    /** 국내 재무 행이 있는 종목 전원. */
    public Derived derive() {
        List<Long> stocks = jdbc.queryForList("""
                SELECT s.id FROM stock s WHERE s.market_code IN ('KOSPI', 'KOSDAQ')
                   AND EXISTS (SELECT 1 FROM financial_statement f WHERE f.stock_id = s.id) ORDER BY s.id
                """, Long.class);
        int rows = 0;
        int decided = 0;
        int failed = 0;
        for (long stockId : stocks) {
            try {
                int[] n = transaction.execute(status -> deriveStock(stockId));
                rows += n[0];
                decided += n[1];
            } catch (RuntimeException e) {
                failed++;
                log.error("국내 보통주 몫 유도가 종목 하나에서 실패했다 — 건너뛴다. stock_id={}", stockId, e);
            }
        }
        log.info("국내 보통주 몫을 붙였다. 종목 {} · 행 {} · 값을 정한 행 {} · 건너뛴 종목 {}", stocks.size(), rows, decided, failed);
        return new Derived(stocks.size(), rows, decided, failed);
    }

    /** 한 종목 — {@code [붙인 행, 값을 정한 행]}. */
    int[] deriveStock(long stockId) {
        List<Filing> filings = store.filings(stockId);
        Map<String, List<DartXbrlFactReader.Fact>> facts = store.okFacts(stockId);
        List<Row> rows = new ArrayList<>();
        Map<Long, String> reportCodes = new HashMap<>();
        jdbc.query("""
                SELECT id, fiscal_year, fiscal_period, period_start, period_end, published_at, net_income_controlling, net_income,
                       report_code
                  FROM financial_statement WHERE stock_id = ? ORDER BY fiscal_year, fiscal_period, published_at
                """, rs -> {
            long id = rs.getLong("id");
            int year = rs.getInt("fiscal_year");
            String period = rs.getString("fiscal_period");
            LocalDate published = rs.getObject("published_at", LocalDate.class);
            List<DartXbrlFactReader.Fact> f = null;
            if (!"Q4".equals(period)) {
                Filing bound = KrXbrlCollector.bound(new RowVintage(year, period, published), filings);
                f = bound == null ? null : facts.get(bound.rceptNo());
            }
            rows.add(new Row(id, year, period, rs.getObject("period_start", LocalDate.class),
                    rs.getObject("period_end", LocalDate.class), published, rs.getBigDecimal("net_income_controlling"),
                    rs.getBigDecimal("net_income"), f));
            reportCodes.put(id, rs.getString("report_code"));
        }, stockId);
        List<Resolved> resolved = KrCommonIncomeRows.resolve(rows, registers(stockId), multiple(stockId));
        jdbc.update("""
                DELETE FROM financial_common_income
                 WHERE financial_statement_id IN (SELECT id FROM financial_statement WHERE stock_id = ?)
                """, stockId);
        int decided = 0;
        List<Object[]> batch = new ArrayList<>();
        for (Resolved r : resolved) {
            if (r.common() != null) {
                decided++;
            }
            batch.add(new Object[]{r.row().id(), reportCodes.get(r.row().id()), r.common(), String.valueOf(r.basis()), r.branch(),
                    KrCommonIncome.ENGINE_VERSION, String.valueOf(r.statement()), r.payoutBase(), r.incomeSnapshot(),
                    r.row().published()});
        }
        jdbc.batchUpdate("""
                INSERT INTO financial_common_income (financial_statement_id, report_code, common, basis, branch, engine_version,
                                                     statement_basis, payout_base, income_snapshot, published_snapshot)
                VALUES (?, coalesce(?, ''), ?, ?, ?, ?, ?, ?, ?, ?)
                """, batch);
        return new int[]{resolved.size(), decided};
    }

    /** 사업연도 → 명부 요약. 비고 줄은 주석 문구라 수로 안 읽는다 · 종류 · 합계 줄의 수가 안 읽히면 그 해 명부를 안 쓴다. */
    Map<Integer, Register> registers(long stockId) {
        Map<Integer, Register> out = new HashMap<>();
        jdbc.query("""
                SELECT bsns_year, record_date, rows::text FROM kr_share_register WHERE stock_id = ? AND status = 'OK'
                """, rs -> {
            List<ShareRow> rows = new ArrayList<>();
            boolean readable = true;
            for (JsonNode row : MAPPER.readTree(rs.getString(3))) {
                String label = row.path("se").asString("").strip();
                if ("비고".equals(label)) {
                    continue;
                }
                BigDecimal issued = number(row.path("issued").asString(""));
                BigDecimal treasury = number(row.path("treasury").asString(""));
                if (issued == UNREADABLE || treasury == UNREADABLE) {
                    readable = false;
                    break;
                }
                rows.add(new ShareRow(label, issued, treasury));
            }
            if (readable) {
                Register r = Register.of(rs.getObject(2, LocalDate.class), rows);
                if (r != null) {
                    out.put(rs.getInt(1), r);
                }
            }
        }, stockId);
        return out;
    }

    private static final BigDecimal UNREADABLE = new BigDecimal("-1");

    /** 명부 수 칸 — 천 단위 쉼표 · {@code -}(없음 = 0) · 빈칸(없음). 그 밖 문구는 못 읽음({@link #UNREADABLE}). */
    static BigDecimal number(String raw) {
        String s = raw == null ? "" : raw.strip().replace(",", "");
        if (s.isEmpty()) {
            return null;
        }
        if ("-".equals(s)) {
            return BigDecimal.ZERO;
        }
        return s.matches("\\d+") ? new BigDecimal(s) : UNREADABLE;
    }

    /** APPLIED 기업행위의 배수 — {@code (from, to]}에 효력이 난 사건의 곱. */
    KrCommonIncome.Multiple multiple(long stockId) {
        List<Object[]> events = jdbc.query("""
                SELECT effective_date, ratio FROM corporate_action WHERE stock_id = ? AND status = 'APPLIED' AND ratio IS NOT NULL
                """, (rs, n) -> new Object[]{rs.getObject(1, LocalDate.class), rs.getBigDecimal(2)}, stockId);
        return (from, to) -> {
            double m = 1.0;
            for (Object[] e : events) {
                LocalDate day = (LocalDate) e[0];
                if (day != null && day.isAfter(from) && !day.isAfter(to)) {
                    m *= ((BigDecimal) e[1]).doubleValue();
                }
            }
            return m;
        };
    }
}
