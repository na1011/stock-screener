package com.stockscreener.pipeline.collector;

import com.stockscreener.pipeline.datasource.FilingListSource.ListedRelease;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 실적 발표 원장 사실({@code earnings_release})과 종목별 과거 훑기({@code earnings_release_sweep})를 쓴다(ADR-0195 · V69_6).
 *
 * <p><b>값을 바꾸는 표를 읽지도 쓰지도 않는다</b> — 화면의 재무 기준 표시만 이 두 표를 읽는다.
 */
@Component
public class EarningsReleaseStore {

    /**
     * 감지 시작일. 이보다 앞의 발표는 원장에 묻지 않는다 — 그래서 기준 기의 다음 기말이 이보다 앞인 판정은 「모른다」다.
     *
     * <p>지표 보존 창의 첫날(2026-10-30 실측 2025-10-15)에 기준이 되는 기는 2025 Q2(기말 06-30)이고, 그 다음 기말(09-30) 뒤의
     * 발표부터 센다. 결산월이 다르거나 공시가 밀린 종목의 기준 기가 더 오래될 수 있어 한 분기를 더 둔다.
     */
    public static final LocalDate FIRST_DATE = LocalDate.of(2025, 7, 1);

    private final JdbcTemplate jdbc;

    public EarningsReleaseStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 감지 범위 — 활성 유니버스 멤버십이 감지 시작일 뒤에 걸친 종목. 화면 결과에 나올 수 있는 종목이 전부 든다(과거 기준일 결과는 그날의
     * 멤버다). 활성 유니버스만 본다 — 비활성 {@code US_LISTED}에 현재 멤버가 8,511이다(2026-10-30 실측).
     */
    public static String inScope(String stockIdColumn) {
        return """
                EXISTS (SELECT 1 FROM universe_membership rm JOIN universe ru ON ru.code = rm.universe_code AND ru.is_active
                        WHERE rm.stock_id = %s AND (rm.effective_to IS NULL OR rm.effective_to >= DATE '%s'))"""
                .formatted(stockIdColumn, FIRST_DATE);
    }

    /**
     * 발표 줄을 넣는다(같은 시장 · 접수번호 · 종목이면 다시 넣지 않는다 — 국내의 「오늘」은 다음 실행이 다시 받는다). 넣은 행 수를 낸다.
     * 미국 줄은 항목을 모른 채 들어간다({@code items} NULL).
     *
     * <p>행의 시대가 그 공시일을 안 덮는 줄은 넣지 않는다 — 공시 장부와 같은 이유다(ADR-0180 · {@code FilingLedgerStore}): 등록인 교체로
     * 닫힌 행에 옛 등록인의 시대 끝 뒤 공시가, 새 등록인 행에 시대 시작 전 공시가 붙지 않게.
     */
    public int insert(String market, Collection<ListedRelease> releases) {
        int inserted = 0;
        for (ListedRelease r : releases) {
            Boolean covered = jdbc.queryForObject("SELECT " + StockRegistrar.eraCovers("s", "?::date")
                    + " FROM stock s WHERE s.id = ?", Boolean.class, Date.valueOf(r.filedOn()), Date.valueOf(r.filedOn()), r.stockId());
            if (!Boolean.TRUE.equals(covered)) {
                continue;
            }
            inserted += jdbc.update("""
                    INSERT INTO earnings_release (market, filing_no, stock_id, form, report_name, filed_on)
                    VALUES (?, ?, ?, ?, ?, ?)
                    ON CONFLICT (market, filing_no, stock_id) DO NOTHING
                    """, market, r.filingNo(), r.stockId(), r.form(), r.reportName(), Date.valueOf(r.filedOn()));
        }
        return inserted;
    }

    /** 항목을 아직 모르는 미국 줄 — CIK별로 묶는다(submissions 한 콜이 그 CIK의 줄을 다 답한다). */
    public record Unresolved(long id, long stockId, long cik, String filingNo, LocalDate filedOn) {
    }

    public List<Unresolved> unresolvedUs() {
        return jdbc.query("""
                SELECT r.id, r.stock_id, s.sec_cik, r.filing_no, r.filed_on
                FROM earnings_release r JOIN stock s ON s.id = r.stock_id
                WHERE r.market = 'US' AND r.items IS NULL AND s.sec_cik IS NOT NULL
                ORDER BY s.sec_cik, r.filed_on
                """, (rs, i) -> new Unresolved(rs.getLong("id"), rs.getLong("stock_id"), rs.getLong("sec_cik"),
                rs.getString("filing_no"), rs.getObject("filed_on", LocalDate.class)));
    }

    /** 미국 줄의 항목을 적는다. */
    public void resolve(long id, String items) {
        jdbc.update("UPDATE earnings_release SET items = ?, resolved_at = now() WHERE id = ? AND items IS NULL", items, id);
    }

    /** 훑지 않은 감지 범위 종목. 미국은 등록인이 묶인 행만 — 묶이지 않은 행은 submissions를 물을 CIK가 없다. */
    public record Unswept(long stockId, String market, String ticker, Long cik) {
    }

    public List<Unswept> unswept(String market, int limit) {
        String where = switch (market) {
            case "US" -> "s.market_code = 'US' AND s.sec_cik IS NOT NULL";
            case "KR" -> "s.market_code IN ('KOSPI', 'KOSDAQ')";
            default -> throw new IllegalArgumentException("시장이 아니다: " + market);
        };
        return jdbc.query("""
                SELECT s.id, s.ticker, s.sec_cik FROM stock s
                WHERE %s AND %s
                  AND NOT EXISTS (SELECT 1 FROM earnings_release_sweep w WHERE w.stock_id = s.id)
                ORDER BY s.id
                LIMIT ?
                """.formatted(where, inScope("s.id")),
                (rs, i) -> new Unswept(rs.getLong("id"), market, rs.getString("ticker"), rs.getObject("sec_cik", Long.class)),
                limit);
    }

    /**
     * 한 종목의 과거 훑기를 끝낸다 — 그 종목의 발표 행과 훑기 표지가 한 트랜잭션이다(표지만 남으면 빠진 이력이 「훑었다」가 된다).
     *
     * @param resolvedItems 미국 줄의 항목(접수번호 → items). 국내는 비어 있다
     */
    @Transactional(rollbackFor = Exception.class)
    public int recordSweep(String market, long stockId, List<ListedRelease> releases, Map<String, String> resolvedItems,
                           LocalDate sweptThrough) {
        int inserted = insert(market, releases);
        for (Map.Entry<String, String> e : resolvedItems.entrySet()) {
            jdbc.update("""
                    UPDATE earnings_release SET items = ?, resolved_at = now()
                    WHERE market = 'US' AND filing_no = ? AND stock_id = ? AND items IS NULL
                    """, e.getValue(), e.getKey(), stockId);
        }
        jdbc.update("""
                INSERT INTO earnings_release_sweep (stock_id, swept_from, swept_through) VALUES (?, ?, ?)
                ON CONFLICT (stock_id) DO UPDATE SET swept_through = GREATEST(earnings_release_sweep.swept_through,
                    EXCLUDED.swept_through), swept_at = now()
                """, stockId, Date.valueOf(FIRST_DATE), Date.valueOf(sweptThrough));
        return inserted;
    }

    /** 감지 범위 종목 id 전부 — 공시 목록이 실적 발표 줄을 낼 종목이다. */
    public List<Long> scopeStockIds() {
        return new ArrayList<>(jdbc.queryForList("SELECT s.id FROM stock s WHERE " + inScope("s.id"), Long.class));
    }
}
