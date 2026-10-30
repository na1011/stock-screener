package com.stockscreener.pipeline.collector.identity;

import com.stockscreener.pipeline.collector.FinancialWriter;
import com.stockscreener.pipeline.collector.IndicatorInvalidationPort;

import com.stockscreener.pipeline.collector.PriceObservationWindow;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 같은 실체 · 같은 등록인의 두 미국 행을 한 행으로 접는다(ADR-0180 · 계획 v3.1 「이미 생긴 중복 행 접기」). 신원 판정이 들어오기 전
 * 토요일 리밸런싱이 개명된 실체를 새 행 S로 등록했다 — 옛 행 T가 그 실체의 이력(재무 · 가격 · 관측 편입)을 든다.
 *
 * <p><b>지우지 않고 옮긴다.</b> {@code stock}을 참조하는 표 중 다시 받을 수 없는 것이 넷이다(관측 편입 · 관심 종목 · 실패 기록 · 공시
 * 소비 — ★14). S의 자식 행은 표마다 규칙으로 T에 옮기거나, T가 같은 사건을 이미 들고 있으면(값까지 같으면) 버린다. 값이 다르면 그
 * 짝 전체를 멈춘다 — 한 행이라도 판정을 못 하면 접지 않는다.
 *
 * <p><b>MUST: 규칙이 없는 자식 표가 하나라도 있으면 아무 짝도 안 접는다.</b> 자식 표는 실행 시점에 {@code pg_constraint}로 센다 —
 * 손으로 든 목록은 새로 생긴 표를 못 본다(그 표의 S 행이 S와 함께 지워진다).
 */
@Component
public class StockFold {

    private final JdbcTemplate jdbc;
    private final FinancialWriter financialWriter;
    private final IndicatorInvalidationPort invalidation;
    private final PriceObservationWindow window;

    public StockFold(JdbcTemplate jdbc, FinancialWriter financialWriter, IndicatorInvalidationPort invalidation,
                     PriceObservationWindow window) {
        this.jdbc = jdbc;
        this.financialWriter = financialWriter;
        this.invalidation = invalidation;
        this.window = window;
    }

    /**
     * S의 봉 중 T의 관측 구간(경계 · 등록인 시대) 밖인 것 — 옮기지 않고 버린다(ADR-0181). 접기는 {@code PriceWriter}를 안 지나는 쓰기라 여기서
     * 같은 술어를 건다. T 자신의 옛 봉과 머리 평봉은 교정 잡({@code price-boundary})과 감시가 다룬다 — 접기는 짝의 쓰기만 한다.
     */
    private String outsideTargetWindow(long target) {
        PriceObservationWindow.Window w = window.of(target);
        StringBuilder sb = new StringBuilder("false");
        if (w.floor() != null) {
            sb.append(" OR s.trade_date < DATE '").append(w.floor()).append('\'');
        }
        if (w.ceiling() != null) {
            sb.append(" OR s.trade_date >= DATE '").append(w.ceiling()).append('\'');
        }
        return sb.toString();
    }

    /** 대조 갈래 — 겹친 키에서 무엇을 하나. */
    enum OnConflict {
        /** 대조 열이 같으면 S를 버리고, 다르면 멈춘다 */
        COMPARE,
        /** 같은 사건이다 — S를 버린다(대조 열 없음) */
        DROP_SOURCE,
        /** 갱신 표지 — 이른 쪽을 남긴다(보수적으로 다음 잡이 다시 돈다) */
        KEEP_EARLIER
    }

    /**
     * 표 규칙. 키 = (stock_id 빼고) 겹침을 가르는 열 · 대조 = 같은 사건인지 보는 열.
     */
    record Rule(String table, List<String> key, List<String> compare, OnConflict onConflict) {
    }

    /** {@code universe_membership} · {@code financial_statement}는 따로 다룬다(구간 · 판 번호). */
    static final List<Rule> RULES = List.of(
            new Rule("price_daily", List.of("trade_date"),
                    List.of("open", "high", "low", "close", "adj_close", "adj_factor", "volume", "trade_value", "listed_shares"),
                    OnConflict.COMPARE),
            new Rule("indicator_value", List.of("trade_date", "indicator_code"), List.of("value"), OnConflict.COMPARE),
            // 같은 날 · 같은 종류 · 같은 비율이면 같은 사건이다(계획 v3.1 표) — 탐지 출처·판정 상태는 행마다 다를 수 있다.
            new Rule("corporate_action", List.of("effective_date"), List.of("type", "ratio"), OnConflict.COMPARE),
            new Rule("governance_fact", List.of("fiscal_year", "metric_key", "receipt_no"), List.of("value", "published_at"),
                    OnConflict.COMPARE),
            new Rule("governance_report_row", List.of("endpoint", "bsns_year", "reprt_code", "receipt_no", "row_ordinal"),
                    List.of("published_at", "payload"), OnConflict.COMPARE),
            // 같은 CIK라 같은 답이다 — 관측일은 행마다 다르다.
            new Rule("financial_column_absence", List.of("column_name"), List.of("state", "source_name"), OnConflict.COMPARE),
            new Rule("filing_consumption", List.of("market", "filing_no"), List.of(), OnConflict.DROP_SOURCE),
            new Rule("watchlist_item", List.of("user_id"), List.of(), OnConflict.DROP_SOURCE),
            new Rule("batch_execution_failure", List.of("execution_id", "failed_phase"), List.of(), OnConflict.DROP_SOURCE),
            new Rule("stock_job_refresh", List.of("job_name"), List.of(), OnConflict.KEEP_EARLIER),
            // 관문 마이그레이션(V70)의 두 표 — 운영에는 아직 없다(있을 때만 다룬다).
            new Rule("sec_share_class_input", List.of(), List.of("cik"), OnConflict.COMPARE),
            new Rule("financial_share_class_filing", List.of("accession"), List.of(), OnConflict.DROP_SOURCE),
            // 관문 마이그레이션(V70_1) — 같은 등록인의 같은 공시면 정한 k가 같아야 한다. 다르면 멈춘다(ADR-0183).
            new Rule("sec_depositary_ratio", List.of("accession"), List.of("k"), OnConflict.COMPARE),
            // 국내 보통주 몫 재료(V69_5 · ADR-0192) — 같은 회사의 같은 판 · 같은 해 명부는 같은 원장 사실이다(재료는 다시 받을 수 있다).
            new Rule("kr_report_xbrl", List.of("rcept_no"), List.of(), OnConflict.DROP_SOURCE),
            new Rule("kr_share_register", List.of("bsns_year"), List.of(), OnConflict.DROP_SOURCE),
            // 실적 발표(V69_6 · ADR-0195) — 같은 시장의 같은 접수번호는 같은 원장 사실이다. 훑기 표지는 종목당 하나 — T에 있으면 T 것을
            // 남기고, 없으면 S 것이 T로 간다(같은 증권이라 같은 이력이다 · 모자라면 다음 훑기가 아니라 일일 목록이 잇는다).
            new Rule("earnings_release", List.of("market", "filing_no"), List.of(), OnConflict.DROP_SOURCE),
            new Rule("earnings_release_sweep", List.of(), List.of(), OnConflict.DROP_SOURCE));

    /** 따로 다루는 표 + S와 함께 지워도 되는 표(S의 기호 시대 — 접힌 S에는 없다 · 있어도 T가 새로 쓴다). */
    static final Set<String> SPECIAL = Set.of("universe_membership", "financial_statement", "stock_symbol_era");

    /** 규칙이 없는 자식 표. 비어야 접는다. */
    public List<String> uncoveredChildTables() {
        Set<String> covered = new TreeSet<>(SPECIAL);
        RULES.forEach(r -> covered.add(r.table()));
        List<String> uncovered = new ArrayList<>();
        for (String table : jdbc.queryForList("""
                SELECT DISTINCT conrelid::regclass::text FROM pg_constraint
                WHERE confrelid = 'stock'::regclass AND contype = 'f' ORDER BY 1
                """, String.class)) {
            if (!covered.contains(table)) {
                uncovered.add(table);
            }
        }
        return uncovered;
    }

    /**
     * 한 표의 셈 — 옮김 · 같은 사건으로 버림 · 멈춤 · 버린 것 중 값이 달랐던 것(재무 — 같은 공시의 추출 판 차이 · 멈추지 않는다).
     */
    public record Tally(int moved, int dropped, int conflicting, int droppedDiffering) {
        public Tally(int moved, int dropped, int conflicting) {
            this(moved, dropped, conflicting, 0);
        }
    }

    /** 짝 하나의 표마다 셈. 멈춤이 하나라도 있으면 접지 않는다. */
    public record Plan(long target, long source, Map<String, Tally> tables) {
        public boolean blocked() {
            return tables.values().stream().anyMatch(t -> t.conflicting() > 0);
        }

        public String describe() {
            StringBuilder sb = new StringBuilder();
            tables.forEach((table, t) -> {
                if (t.moved() + t.dropped() + t.conflicting() > 0) {
                    sb.append(' ').append(table).append("(옮김 ").append(t.moved()).append(" · 버림 ").append(t.dropped())
                            .append(t.droppedDiffering() > 0 ? "(그중 추출 판 다름 " + t.droppedDiffering() + ")" : "")
                            .append(t.conflicting() > 0 ? " · 다름 " + t.conflicting() : "").append(')');
                }
            });
            return sb.length() == 0 ? " 자식 행 없음" : sb.toString();
        }
    }

    /** 쓰지 않고 센다. */
    public Plan plan(long target, long source) {
        Map<String, Tally> tables = new LinkedHashMap<>();
        for (Rule rule : RULES) {
            if (exists(rule.table())) {
                tables.put(rule.table(), PRICE_TABLE.equals(rule.table()) ? countPrices(rule, target, source)
                        : countRule(rule, target, source, "true"));
            }
        }
        tables.put("financial_statement", countFinancials(target, source));
        tables.put("universe_membership", countMemberships(target, source));
        return new Plan(target, source, tables);
    }

    /**
     * 접는다 — 호출부의 트랜잭션 안에서. 계획을 다시 세어 멈춤이 있으면 던진다(그 사이 행이 바뀌었을 수 있다). 끝에 S를 지우고 T의
     * 표지를 S의 것으로 바꾸고 옛 표지를 기호 시대로 남긴다.
     *
     * @param renamedOn T의 옛 표지가 끝난 날(S 기호의 첫 날)
     * @return 그때의 계획(실제로 옮긴 수)
     */
    public Plan apply(long target, long source, LocalDate renamedOn) {
        Plan plan = plan(target, source);
        if (plan.blocked()) {
            throw new IllegalStateException("접기 직전에 다시 셌더니 값이 다른 자식 행이 있다 — 접지 않는다:" + plan.describe());
        }
        for (Rule rule : RULES) {
            if (exists(rule.table())) {
                if (PRICE_TABLE.equals(rule.table())) {
                    jdbc.update("DELETE FROM price_daily s WHERE s.stock_id = ? AND (" + outsideTargetWindow(target) + ")", source);
                }
                applyRule(rule, target, source);
            }
        }
        applyFinancials(target, source);
        applyMemberships(target, source);
        Map<String, Object> s = jdbc.queryForMap("SELECT ticker, name, listing_exchange FROM stock WHERE id = ?", source);
        String oldTicker = jdbc.queryForObject("SELECT ticker FROM stock WHERE id = ?", String.class, target);
        jdbc.update("DELETE FROM stock WHERE id = ?", source);
        jdbc.update("UPDATE stock SET ticker = ?, name = ?, listing_exchange = coalesce(?, listing_exchange), updated_at = now() WHERE id = ?",
                s.get("ticker"), s.get("name"), s.get("listing_exchange"), target);
        LocalDate validFrom = jdbc.queryForObject("SELECT max(valid_to) FROM stock_symbol_era WHERE stock_id = ?", LocalDate.class, target);
        jdbc.update("INSERT INTO stock_symbol_era (stock_id, symbol, valid_from, valid_to) VALUES (?, ?, ?, ?)",
                target, oldTicker, validFrom, renamedOn);
        return plan;
    }

    private boolean exists(String table) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean.class, "public." + table));
    }

    private static String joinOn(List<String> key) {
        StringBuilder sb = new StringBuilder("t.stock_id = ?");
        for (String column : key) {
            sb.append(" AND t.").append(column).append(" IS NOT DISTINCT FROM s.").append(column);
        }
        return sb.toString();
    }

    private static String same(List<String> compare) {
        if (compare.isEmpty()) {
            return "true";
        }
        StringBuilder sb = new StringBuilder();
        for (String column : compare) {
            if (!sb.isEmpty()) {
                sb.append(" AND ");
            }
            sb.append("t.").append(column).append(" IS NOT DISTINCT FROM s.").append(column);
        }
        return sb.toString();
    }

    private static final String PRICE_TABLE = "price_daily";

    /** 봉은 T의 관측 구간 밖을 먼저 「버림」으로 센다 — 대조에 안 넣는다(다른 증권 봉이라 T와 값이 달라도 멈출 이유가 아니다). */
    private Tally countPrices(Rule rule, long target, long source) {
        String outside = outsideTargetWindow(target);
        Tally inside = countRule(rule, target, source, "NOT (" + outside + ")");
        Integer dropped = jdbc.queryForObject("SELECT count(*) FROM price_daily s WHERE s.stock_id = ? AND (" + outside + ")",
                Integer.class, source);
        return new Tally(inside.moved(), inside.dropped() + (dropped == null ? 0 : dropped), inside.conflicting());
    }

    private Tally countRule(Rule rule, long target, long source, String sourceFilter) {
        String overlapping = "EXISTS (SELECT 1 FROM %s t WHERE %s)".formatted(rule.table(), joinOn(rule.key()));
        String sameValue = "EXISTS (SELECT 1 FROM %s t WHERE %s AND %s)".formatted(rule.table(), joinOn(rule.key()), same(rule.compare()));
        boolean compare = rule.onConflict() == OnConflict.COMPARE;
        String matched = compare ? sameValue : "true";
        // 자리표시자: overlapping·sameValue가 하나씩(T) — 대조 갈래면 둘째·셋째 셈에 하나씩 더 붙는다.
        List<Object> args = new ArrayList<>(List.of(target, target));
        if (compare) {
            args.add(target);
        }
        args.add(target);
        if (compare) {
            args.add(target);
        }
        args.add(source);
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT count(*) FILTER (WHERE NOT %s) AS moved,
                       count(*) FILTER (WHERE %s AND (%s)) AS dropped,
                       count(*) FILTER (WHERE %s AND NOT (%s)) AS conflicting
                FROM %s s WHERE s.stock_id = ? AND (%s)
                """.formatted(overlapping, overlapping, matched, overlapping, matched, rule.table(), sourceFilter), args.toArray());
        return new Tally(((Number) row.get("moved")).intValue(), ((Number) row.get("dropped")).intValue(),
                ((Number) row.get("conflicting")).intValue());
    }

    private void applyRule(Rule rule, long target, long source) {
        String overlapping = "EXISTS (SELECT 1 FROM %s t WHERE %s)".formatted(rule.table(), joinOn(rule.key()));
        if (rule.onConflict() == OnConflict.KEEP_EARLIER) {
            // 이른 갱신 시각을 T에 남긴다 — 다음 잡이 보수적으로 다시 돈다.
            jdbc.update("""
                    UPDATE %s t SET refreshed_at = s.refreshed_at, execution_id = s.execution_id
                    FROM %s s WHERE s.stock_id = ? AND t.stock_id = ? AND t.job_name = s.job_name AND s.refreshed_at < t.refreshed_at
                    """.formatted(rule.table(), rule.table()), source, target);
        }
        jdbc.update("DELETE FROM %s s WHERE s.stock_id = ? AND %s".formatted(rule.table(), overlapping), source, target);
        jdbc.update("UPDATE %s SET stock_id = ? WHERE stock_id = ?".formatted(rule.table()), target, source);
    }

    /**
     * 재무 판의 키는 (연도 · 분기 · 접수번호)다 — {@code revision_no}는 공시일 서수라 키가 아니다. S에만 있는 접수번호는 T로 옮긴 뒤 라벨마다
     * 공시일 순으로 다시 매긴다.
     *
     * <p><b>같은 키는 값이 달라도 같은 사건이다 — T를 남기고 S를 버린다.</b> 접수번호는 바뀌지 않는 원장 사실이고 짝은 이미 같은 등록인으로
     * 판정됐다(포함 검사 ALL · {@link FoldPlanner}). 그러면 남는 차이는 우리 추출 코드의 판 차이뿐이다 — 실측(2026-10-17 작은 DB · HLX/HOS
     * 39행): T가 빈 칸(08-30 뒤 생긴 컬럼 · 총이익·지배주주 순이익·자본 각 39)과 순이익 11칸(그 사이 바뀐 규칙)이 전부였다. 재무 행은 다시
     * 받을 수 있는 표이고(★14의 넷 밖), 버린 S의 값은 다음 FR이 T 최신 판에 채운다 — T는 다른 행들과 같은 「그 jar가 쓴 상태」로 남고 관문
     * 재적재가 한꺼번에 고친다. 값이 달랐던 수는 셈에 따로 싣는다.
     */
    private static final String FS_KEY = """
            t.stock_id = ? AND t.fiscal_year = s.fiscal_year AND t.fiscal_period = s.fiscal_period
              AND split_part(t.report_code, ':', 2) = split_part(s.report_code, ':', 2)""";
    private static final String FS_SAME = """
            (to_jsonb(t) - 'id' - 'stock_id' - 'revision_no' - 'is_latest' - 'created_at' - 'updated_at')
              = (to_jsonb(s) - 'id' - 'stock_id' - 'revision_no' - 'is_latest' - 'created_at' - 'updated_at')""";

    private Tally countFinancials(long target, long source) {
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT count(*) FILTER (WHERE NOT EXISTS (SELECT 1 FROM financial_statement t WHERE %1$s)) AS moved,
                       count(*) FILTER (WHERE EXISTS (SELECT 1 FROM financial_statement t WHERE %1$s)) AS dropped,
                       count(*) FILTER (WHERE EXISTS (SELECT 1 FROM financial_statement t WHERE %1$s)
                                          AND NOT EXISTS (SELECT 1 FROM financial_statement t WHERE %1$s AND %2$s)) AS differing
                FROM financial_statement s WHERE s.stock_id = ?
                """.formatted(FS_KEY, FS_SAME), target, target, target, target, source);
        return new Tally(((Number) row.get("moved")).intValue(), ((Number) row.get("dropped")).intValue(), 0,
                ((Number) row.get("differing")).intValue());
    }

    private void applyFinancials(long target, long source) {
        jdbc.update("DELETE FROM financial_statement s WHERE s.stock_id = ? AND EXISTS (SELECT 1 FROM financial_statement t WHERE %s)"
                .formatted(FS_KEY), source, target);
        LocalDate earliest = jdbc.queryForObject("SELECT min(period_start) FROM financial_statement WHERE stock_id = ?",
                LocalDate.class, source);
        // 옮길 판의 최신 표지를 먼저 내리고 번호를 비킨다 — uq_fs_latest(부분 유일)와 uq_fs_revision이 옮기는 순간 걸린다.
        int moved = jdbc.update("""
                UPDATE financial_statement SET stock_id = ?, is_latest = false, revision_no = revision_no + 2000
                WHERE stock_id = ?""", target, source);
        if (moved > 0) {
            financialWriter.realignLabels(target);
            // T가 처음 보는 판이 들어왔다 — 그 판의 기간부터 T의 지표가 낡았다(재무를 고쳐 쓴 것과 같은 갈래).
            invalidation.invalidate(target, earliest, IndicatorInvalidationPort.InvalidationReason.FINANCIAL_REVISED);
        }
    }

    /**
     * 편입 — 「T의 {@code US_LISTED}가 그날 닫히고 S의 같은 유니버스가 그날 열렸다」는 같은 실체의 이음이다: S 행을 지우고 T의 끝을 S의
     * 끝으로 둔다. T의 같은 유니버스 구간이 S 구간을 통째로 덮으면 같은 편입이다 — S 행을 버린다(목록이 옛 기호와 새 기호를 둘 다 활성으로
     * 실어 두 행이 같은 날 함께 편입된 꼴 · HKIT/BDAI 2026-10-17). 그 밖 S 편입은 T와 기간이 안 겹치면 옮기고, 일부만 겹치면 멈춘다.
     */
    private Tally countMemberships(long target, long source) {
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT count(*) FILTER (WHERE seam OR covered) AS dropped,
                       count(*) FILTER (WHERE NOT seam AND NOT covered AND NOT overlap) AS moved,
                       count(*) FILTER (WHERE NOT seam AND NOT covered AND overlap) AS conflicting
                FROM (SELECT EXISTS (SELECT 1 FROM universe_membership t WHERE t.stock_id = ? AND t.universe_code = s.universe_code
                                       AND t.effective_to = s.effective_from) AS seam,
                             %s AS covered,
                             EXISTS (SELECT 1 FROM universe_membership t WHERE t.stock_id = ? AND t.universe_code = s.universe_code
                                       AND daterange(t.effective_from, t.effective_to) && daterange(s.effective_from, s.effective_to)) AS overlap
                      FROM universe_membership s WHERE s.stock_id = ?) m
                """.formatted(COVERED), target, target, target, source);
        return new Tally(((Number) row.get("moved")).intValue(), ((Number) row.get("dropped")).intValue(),
                ((Number) row.get("conflicting")).intValue());
    }

    /** T의 같은 유니버스 구간이 S 구간을 통째로 덮는다(자리표시자 하나 — T). */
    private static final String COVERED = """
            EXISTS (SELECT 1 FROM universe_membership t WHERE t.stock_id = ? AND t.universe_code = s.universe_code
                      AND daterange(t.effective_from, t.effective_to) @> daterange(s.effective_from, s.effective_to))""";

    private void applyMemberships(long target, long source) {
        jdbc.update("DELETE FROM universe_membership s WHERE s.stock_id = ? AND " + COVERED, source, target);
        List<Map<String, Object>> seams = jdbc.queryForList("""
                SELECT s.id AS source_id, t.id AS target_id, s.effective_to FROM universe_membership s
                JOIN universe_membership t ON t.stock_id = ? AND t.universe_code = s.universe_code AND t.effective_to = s.effective_from
                WHERE s.stock_id = ?""", target, source);
        for (Map<String, Object> seam : seams) {
            // 지우고 늘린다 — 순서를 바꾸면 늘린 T 구간이 S 구간과 겹쳐 ex_membership_no_overlap에 걸린다.
            jdbc.update("DELETE FROM universe_membership WHERE id = ?", seam.get("source_id"));
            jdbc.update("UPDATE universe_membership SET effective_to = ? WHERE id = ?", seam.get("effective_to"), seam.get("target_id"));
        }
        jdbc.update("UPDATE universe_membership SET stock_id = ? WHERE stock_id = ?", target, source);
    }
}
