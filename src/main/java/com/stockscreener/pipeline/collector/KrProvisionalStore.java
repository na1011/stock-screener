package com.stockscreener.pipeline.collector;

import com.stockscreener.pipeline.datasource.kr.DartProvisionalDocument;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 국내 실적 발표 공시 본문(잠정실적 I002 · 손익구조 변동 I001) — 접수번호마다 원문 파일 캐시({@code <dir>/<접수번호>.html} · 뷰어가 준 바이트
 * 그대로)와 관문 표 {@code kr_provisional_release}(해석 결과). 캐시는 관문 전에도 쌓고(운영 DB에 그 표가 없다), 표는 국내 잠정 관문 켬에서만
 * 쓴다({@code stockscreener.gate.kr-provisional}). 해석은 캐시만 읽는다(0콜) — 해석 규칙을 바꾸면 다시 올린다.
 *
 * <p>MUST: 못 읽은 서식도 행으로 남긴다(상태 = 사유) — 실적 발표 판정이 「본문을 읽어 분기 · 연도가 아님을 확인한 줄」만 발표에서 빼고, 못 읽은
 * 줄은 발표로 남겨 막는다. 행이 없으면 「아직 안 받음」과 「못 읽음」이 안 갈린다.
 */
@Component
public class KrProvisionalStore {

    private static final Logger log = LoggerFactory.getLogger(KrProvisionalStore.class);

    private final JdbcTemplate jdbc;
    private final Path dir;
    private final boolean gate;

    public KrProvisionalStore(JdbcTemplate jdbc, @Value("${stockscreener.dart-prov.cache-dir}") String dir,
                              @Value("${stockscreener.gate.kr-provisional}") boolean gate) {
        this.jdbc = jdbc;
        this.dir = Path.of(dir);
        this.gate = gate;
    }

    /** 국내 실적 발표 줄 하나 — 해석할 공시. */
    public record Release(String rceptNo, long stockId, String reportName, LocalDate filedOn) {
    }

    /** 국내 실적 발표 줄 전부(최근 접수부터). */
    public List<Release> releases() {
        return jdbc.query("""
                SELECT filing_no, stock_id, report_name, filed_on FROM earnings_release WHERE market = 'KR'
                ORDER BY filed_on DESC, filing_no DESC
                """, (rs, i) -> new Release(rs.getString("filing_no"), rs.getLong("stock_id"), rs.getString("report_name"),
                rs.getObject("filed_on", LocalDate.class)));
    }

    public boolean gateOn() {
        return gate;
    }

    public boolean cached(String rceptNo) {
        return Files.exists(file(rceptNo));
    }

    /** 원문을 캐시에 쓴다(원자적). */
    public void writeCache(String rceptNo, byte[] raw) {
        try {
            Files.createDirectories(dir);
            Path part = file(rceptNo).resolveSibling(rceptNo + ".html.part");
            Files.write(part, raw);
            Files.move(part, file(rceptNo), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("공시 본문 캐시를 못 썼다: " + file(rceptNo), e);
        }
    }

    public byte[] readCache(String rceptNo) {
        try {
            return Files.readAllBytes(file(rceptNo));
        } catch (IOException e) {
            throw new UncheckedIOException("공시 본문 캐시를 못 읽었다: " + file(rceptNo), e);
        }
    }

    /**
     * 캐시가 있는 실적 발표 줄 전부를 해석해 관문 표에 올린다(관문 켬 · 0콜 · 같은 줄은 덮는다).
     *
     * @return {@code [올린 줄, 그중 못 읽은 줄]}
     */
    public int[] loadAll() {
        if (!gate) {
            throw new IllegalStateException("국내 잠정 관문이 꺼져 있다 — 관문 표(kr_provisional_release)는 켬에서만 쓴다");
        }
        List<Object[]> batch = new ArrayList<>();
        int unreadable = 0;
        for (Release r : releases()) {
            if (!cached(r.rceptNo())) {
                continue;
            }
            Object[] row = row(r);
            if (!"OK".equals(row[4])) {
                unreadable++;
            }
            batch.add(row);
        }
        jdbc.batchUpdate("""
                INSERT INTO kr_provisional_release (rcept_no, stock_id, form, report_name, status, consolidated, period_start, period_end,
                    cumulative_start, cumulative_end, release_period, unit_won, revenue, operating_income, profit_before_tax, net_income,
                    net_income_controlling, cum_revenue, cum_operating_income, cum_profit_before_tax, cum_net_income,
                    cum_net_income_controlling, filed_on, parser_version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (rcept_no, stock_id) DO UPDATE SET form = EXCLUDED.form, report_name = EXCLUDED.report_name,
                    status = EXCLUDED.status, consolidated = EXCLUDED.consolidated, period_start = EXCLUDED.period_start,
                    period_end = EXCLUDED.period_end, cumulative_start = EXCLUDED.cumulative_start,
                    cumulative_end = EXCLUDED.cumulative_end, release_period = EXCLUDED.release_period, unit_won = EXCLUDED.unit_won,
                    revenue = EXCLUDED.revenue, operating_income = EXCLUDED.operating_income,
                    profit_before_tax = EXCLUDED.profit_before_tax, net_income = EXCLUDED.net_income,
                    net_income_controlling = EXCLUDED.net_income_controlling, cum_revenue = EXCLUDED.cum_revenue,
                    cum_operating_income = EXCLUDED.cum_operating_income, cum_profit_before_tax = EXCLUDED.cum_profit_before_tax,
                    cum_net_income = EXCLUDED.cum_net_income, cum_net_income_controlling = EXCLUDED.cum_net_income_controlling,
                    filed_on = EXCLUDED.filed_on, parser_version = EXCLUDED.parser_version, parsed_at = now()
                """, batch);
        return new int[]{batch.size(), unreadable};
    }

    /** 한 줄의 표 행 — 못 읽으면 상태에 사유를, 기간을 읽었으면 기간과 꼴을 싣는다. */
    Object[] row(Release r) {
        DartProvisionalDocument.Form form = DartProvisionalDocument.formOf(r.reportName());
        try {
            DartProvisionalDocument.Parsed p = DartProvisionalDocument.parse(readCache(r.rceptNo()), r.reportName());
            DartProvisionalDocument.Lines c = p.current();
            DartProvisionalDocument.Lines k = p.cumulative();
            return new Object[]{r.rceptNo(), r.stockId(), form.name(), r.reportName(), "OK", p.consolidated(),
                    Date.valueOf(p.periodStart()), Date.valueOf(p.periodEnd()), Date.valueOf(p.cumulativeStart()),
                    Date.valueOf(p.cumulativeEnd()), p.releasePeriod().name(), p.unitWon(), c.revenue(), c.operatingIncome(),
                    c.profitBeforeTax(), c.netIncome(), c.netIncomeControlling(), k.revenue(), k.operatingIncome(),
                    k.profitBeforeTax(), k.netIncome(), k.netIncomeControlling(), Date.valueOf(r.filedOn()),
                    DartProvisionalDocument.PARSER_VERSION};
        } catch (DartProvisionalDocument.Unreadable e) {
            log.warn("실적 발표 본문을 못 읽었다 — 발표로 남겨 막는다. rcept_no={} {} 사유={}", r.rceptNo(), r.reportName(), e.reason());
            String period = e.periodStart() == null || e.periodEnd() == null ? null
                    : DartProvisionalDocument.periodOf(e.periodStart(), e.periodEnd()).name();
            return new Object[]{r.rceptNo(), r.stockId(), form.name(), r.reportName(), e.reason(), null,
                    e.periodStart() == null ? null : Date.valueOf(e.periodStart()),
                    e.periodEnd() == null ? null : Date.valueOf(e.periodEnd()), null, null, period, null, null, null, null, null, null,
                    null, null, null, null, null, Date.valueOf(r.filedOn()), DartProvisionalDocument.PARSER_VERSION};
        }
    }

    private Path file(String rceptNo) {
        return dir.resolve(rceptNo + ".html");
    }
}
