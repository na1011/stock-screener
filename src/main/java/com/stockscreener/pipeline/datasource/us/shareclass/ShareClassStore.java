package com.stockscreener.pipeline.datasource.us.shareclass;

import com.stockscreener.stock.StockReader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 클래스 축의 세 표(관문 마이그레이션 {@code V70__share_class_axis.sql}) — 받은 재료 둘과 판정.
 *
 * <p><b>MUST: 관문 설정({@code stockscreener.gate.us})이 켜진 갈래에서만 부른다.</b> 꺼진 jar의 DB에는 이 표가 없다 —
 * 부르는 자리가 설정을 보고 막는다(이 클래스는 설정을 모른다 · 빈이 있는 것만으로는 아무 질의도 안 나간다).
 *
 * <p>호출자의 트랜잭션에 그대로 참여한다(전파 지정 없음) — 받기는 종목별 {@code REQUIRES_NEW} 안에서 재무 쓰기와 함께
 * 커밋되고({@code SecFilingSupplementStore}와 같은 자리), 판정은 유도 단계가 종목마다 쓴다.
 */
@Repository
public class ShareClassStore {

    /** 판정 하나의 저장 형태. */
    public record VerdictRow(String accession, String form, LocalDate filed, char kind, char status, String branch,
                             boolean currentPath, String failure, Double lineShares, Double denominator) {
    }

    /** 받은 인스턴스 행의 상태 — 다시 받을지를 정한다. */
    public record FactRow(String accession, int readerVersion, String status, String failureKind) {
    }

    /**
     * 유도 단위 — 상장 줄 하나와 그 companyfacts 재료.
     *
     * @param ticker 행의 표지(로그 · 실패 목록) — 닫힌 행은 비운 표지({@code T~마지막 날} · ADR-0180)다
     * @param symbol 표지 기호와 맞댈 기호 — 닫힌 행은 비운 표지에서 되찾은 마지막 기호. 비운 표지를 그대로 맞대면 어떤 표지 기호와도
     *               안 맞아 다중 클래스의 상장 클래스가 원리적으로 안 이어진다(클래스 스윕 2026-10-21 WKEY~2026-10-04)
     */
    public record Input(long stockId, String ticker, String symbol, long cik, String companyFacts) {
    }

    private final JdbcTemplate jdbcTemplate;

    public ShareClassStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 줄의 companyfacts 재료를 갈아 넣는다 — 같으면 안 쓴다(매달 FR이 같은 트리를 다시 쓰며 행을 부풀리지 않게). */
    public void saveInput(long stockId, long cik, String companyFacts) {
        jdbcTemplate.update("""
                INSERT INTO sec_share_class_input (stock_id, cik, company_facts) VALUES (?, ?, ?::jsonb)
                ON CONFLICT (stock_id) DO UPDATE SET cik = EXCLUDED.cik, company_facts = EXCLUDED.company_facts, updated_at = now()
                WHERE sec_share_class_input.cik <> EXCLUDED.cik
                   OR sec_share_class_input.company_facts IS DISTINCT FROM EXCLUDED.company_facts
                """, stockId, cik, companyFacts);
    }

    public Map<String, FactRow> factRows(long cik) {
        Map<String, FactRow> out = new HashMap<>();
        jdbcTemplate.query("""
                SELECT accession, reader_version, status, failure_kind FROM sec_filing_class_fact WHERE cik = ?
                """, rs -> {
            out.put(rs.getString("accession"), new FactRow(rs.getString("accession"), rs.getInt("reader_version"),
                    rs.getString("status"), rs.getString("failure_kind")));
        }, cik);
        return out;
    }

    /** 인스턴스 하나의 결과를 갈아 넣는다(OK · NO_XBRL · FAILED). */
    public void saveFact(long cik, String accession, String form, LocalDate filed, String status, String facts,
                         String failureKind, String failureMessage) {
        jdbcTemplate.update("""
                INSERT INTO sec_filing_class_fact (cik, accession, form, filed, reader_version, status, facts, failure_kind,
                                                   failure_message)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
                ON CONFLICT (cik, accession) DO UPDATE SET form = EXCLUDED.form, filed = EXCLUDED.filed,
                    reader_version = EXCLUDED.reader_version, status = EXCLUDED.status, facts = EXCLUDED.facts,
                    failure_kind = EXCLUDED.failure_kind, failure_message = EXCLUDED.failure_message, fetched_at = now()
                """, cik, accession, form, filed, ClassFactReader.READER_VERSION, status, facts, failureKind, failureMessage);
    }

    /**
     * 공시 보충이 실은 정기 공시(companyfacts가 빠뜨린 것 · {@code sec_filing_supplement} OK) — 판정의 공시 목록에 더한다
     * ({@link IssuerFacts#withFilings}).
     */
    public Map<String, IssuerFacts.Filing> supplementedFilings(long cik) {
        Map<String, IssuerFacts.Filing> out = new LinkedHashMap<>();
        jdbcTemplate.query("""
                SELECT accession, form, filed FROM sec_filing_supplement
                WHERE cik = ? AND status = 'OK' ORDER BY filed, accession
                """, rs -> {
            String form = rs.getString("form");
            if (IssuerFacts.PERIODIC_FORMS.contains(form)) {
                out.put(rs.getString("accession"), new IssuerFacts.Filing(form, rs.getObject("filed", LocalDate.class)));
            }
        }, cik);
        return out;
    }

    /** 시장의 활성 상장 줄 중 재료가 있는 것. */
    public List<Input> inputs(String marketCode) {
        return jdbcTemplate.query("""
                SELECT i.stock_id, s.ticker, """ + StockReader.DISPLAY_TICKER + """
                 AS symbol, i.cik, i.company_facts::text AS company_facts
                FROM sec_share_class_input i JOIN stock s ON s.id = i.stock_id
                WHERE s.market_code = ? AND s.is_active
                -- 시대 무관: 행마다의 판정 재료 — 닫힌 행도 과거 기준일 순위가 읽는 판정이 있어야 한다
                ORDER BY s.ticker
                """, (rs, n) -> new Input(rs.getLong("stock_id"), rs.getString("ticker"), rs.getString("symbol"), rs.getLong("cik"),
                rs.getString("company_facts")), marketCode);
    }

    /** 그 CIK의 지금 판으로 읽은 인스턴스 팩트(OK). 판이 다른 행은 없는 것으로 친다 — 다음 받기가 다시 받는다. */
    public Map<String, ClassFacts> okFacts(long cik) {
        Map<String, ClassFacts> out = new HashMap<>();
        jdbcTemplate.query("""
                SELECT accession, facts::text AS facts FROM sec_filing_class_fact
                WHERE cik = ? AND status = 'OK' AND reader_version = ?
                """, rs -> {
            out.put(rs.getString("accession"), ClassFacts.fromJson(rs.getString("facts")));
        }, cik, ClassFactReader.READER_VERSION);
        return out;
    }

    /**
     * 줄의 분할·병합 계수 — <b>보통주 계수</b>다(클래스 판정의 연속성 · Q4 분모가 보통주 n끼리 맞댄다). 원장 사건은 ADS 시세의 사건이라
     * 예탁증권 줄은 비율 변경 몫을 뺀다(ADR-0183 · {@link #toOrdinary}).
     */
    public List<IssuerTimeline.Split> splits(long stockId) {
        return toOrdinary(adsSplits(stockId), depositaryRatios(stockId));
    }

    /** 줄의 분할·병합 계수(원장이 확정한 것 — 순위 판독기의 {@code SHIFT_TO_ASOF}와 같은 술어) — ADS 시세의 계수 그대로. */
    public List<IssuerTimeline.Split> adsSplits(long stockId) {
        return jdbcTemplate.query("""
                SELECT effective_date, ratio FROM corporate_action WHERE stock_id = ? AND status = 'APPLIED'
                ORDER BY effective_date, id
                """, (rs, n) -> new IssuerTimeline.Split(rs.getObject("effective_date", LocalDate.class),
                rs.getBigDecimal("ratio").doubleValue()), stockId);
    }

    /** 공시마다 정한 참 k(공시일 순) — ADS 단위 이탈 판(k_eff = 1)은 비율이 아니라 빼다. */
    private List<java.util.Map.Entry<LocalDate, Double>> depositaryRatios(long stockId) {
        return jdbcTemplate.query("""
                SELECT filed, k FROM sec_depositary_ratio WHERE stock_id = ? AND k IS NOT NULL AND how <> 'ADS_UNIT'
                ORDER BY filed, accession
                """, (rs, n) -> java.util.Map.entry(rs.getObject("filed", LocalDate.class), rs.getBigDecimal("k").doubleValue()),
                stockId);
    }

    /**
     * ADS 원장 계수를 보통주 계수로 — 사건마다 배수 × k_뒤/k_앞(k_앞 = 사건 전 마지막 정한 k · k_뒤 = 사건 뒤 첫 정한 k). UXIN 2022-10
     * 역병합(×0.1 · k 3 → 30)은 보통주 계수 1, HDB 2025-09(×2 · k 3 그대로)는 2다. 양쪽 k가 없으면 그 사건은 ADS 계수 그대로다(예탁증권
     * 줄이 아니거나 그 사이를 못 정했다).
     */
    static List<IssuerTimeline.Split> toOrdinary(List<IssuerTimeline.Split> ads, List<java.util.Map.Entry<LocalDate, Double>> ratios) {
        if (ads.isEmpty() || ratios.isEmpty()) {
            return ads;
        }
        List<IssuerTimeline.Split> out = new ArrayList<>();
        for (IssuerTimeline.Split s : ads) {
            Double before = null;
            Double after = null;
            for (java.util.Map.Entry<LocalDate, Double> r : ratios) {
                if (r.getKey().isBefore(s.date())) {
                    before = r.getValue();
                } else if (after == null) {
                    after = r.getValue();
                }
            }
            out.add(before != null && after != null ? new IssuerTimeline.Split(s.date(), s.ratio() * after / before) : s);
        }
        return out;
    }

    /**
     * 줄의 판정을 갈아 넣는다 — 바뀐 행만 쓰고, 이번 판정에 없는 공시의 행은 지운다(companyfacts가 공시를 거둔 경우).
     *
     * @return 쓴 행(새로 · 바뀜)과 지운 행의 수
     */
    public int[] replaceVerdicts(long stockId, long cik, List<VerdictRow> rows) {
        int written = 0;
        for (VerdictRow r : rows) {
            written += jdbcTemplate.update("""
                    INSERT INTO financial_share_class_filing (stock_id, accession, cik, form, filed, kind, status, branch,
                        current_path, failure, line_shares, denominator, engine_version)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (stock_id, accession) DO UPDATE SET cik = EXCLUDED.cik, form = EXCLUDED.form,
                        filed = EXCLUDED.filed, kind = EXCLUDED.kind, status = EXCLUDED.status, branch = EXCLUDED.branch,
                        current_path = EXCLUDED.current_path, failure = EXCLUDED.failure, line_shares = EXCLUDED.line_shares,
                        denominator = EXCLUDED.denominator, engine_version = EXCLUDED.engine_version, derived_at = now()
                    WHERE (financial_share_class_filing.cik, financial_share_class_filing.form, financial_share_class_filing.filed,
                           financial_share_class_filing.kind, financial_share_class_filing.status,
                           financial_share_class_filing.branch, financial_share_class_filing.current_path,
                           financial_share_class_filing.failure, financial_share_class_filing.line_shares,
                           financial_share_class_filing.denominator, financial_share_class_filing.engine_version)
                      IS DISTINCT FROM (EXCLUDED.cik, EXCLUDED.form, EXCLUDED.filed, EXCLUDED.kind, EXCLUDED.status,
                           EXCLUDED.branch, EXCLUDED.current_path, EXCLUDED.failure, EXCLUDED.line_shares,
                           EXCLUDED.denominator, EXCLUDED.engine_version)
                    """, stockId, r.accession(), cik, r.form(), r.filed(), String.valueOf(r.kind()), String.valueOf(r.status()),
                    r.branch(), r.currentPath(), r.failure(), decimal(r.lineShares()), decimal(r.denominator()),
                    IssuerTimeline.ENGINE_VERSION);
        }
        List<String> keep = rows.stream().map(VerdictRow::accession).toList();
        int removed = jdbcTemplate.update("""
                DELETE FROM financial_share_class_filing WHERE stock_id = ? AND NOT (accession = ANY (?))
                """, stockId, keep.toArray(new String[0]));
        return new int[]{written, removed};
    }

    /** 종목의 재무 행 전부(전 판) — 보통주 귀속 이익을 붙일 재료. 값은 재무 행 id. */
    public Map<Long, CommonIncomeRows.Row> financialRows(long stockId) {
        Map<Long, CommonIncomeRows.Row> out = new LinkedHashMap<>();
        jdbcTemplate.query("""
                SELECT id, fiscal_year, fiscal_period, revision_no, period_start, period_end, published_at, report_code,
                       net_income_controlling
                FROM financial_statement WHERE stock_id = ? ORDER BY fiscal_year, fiscal_period, revision_no
                """, rs -> {
            out.put(rs.getLong("id"), new CommonIncomeRows.Row(rs.getInt("fiscal_year"), rs.getString("fiscal_period"),
                    rs.getInt("revision_no"), rs.getObject("period_start", LocalDate.class), rs.getObject("period_end", LocalDate.class),
                    rs.getObject("published_at", LocalDate.class), rs.getString("report_code"),
                    rs.getBigDecimal("net_income_controlling")));
        }, stockId);
        return out;
    }

    /**
     * 재무 행마다 보통주 귀속 이익과 보통주 자본 재료(탐색 {@code PERSHARE-DEN-PLAN-1026.md} v1.1)를 갈아 넣는다 — 바뀐 행만 쓴다. 행이 지워지면
     * 표가 같이 지운다(FK).
     *
     * @return 쓴 행 수
     */
    public int replaceCommonIncome(Map<Long, CommonIncomeRows.Resolved> rows, Map<Long, CommonEquityRows.Resolved> equity) {
        int written = 0;
        for (Map.Entry<Long, CommonIncomeRows.Resolved> e : rows.entrySet()) {
            CommonIncomeRows.Resolved r = e.getValue();
            CommonEquityRows.Resolved q = equity.get(e.getKey());
            written += jdbcTemplate.update("""
                    INSERT INTO financial_common_income (financial_statement_id, report_code, common, basis, branch, engine_version,
                                                         non_common, ncav_non_common, equity_branch)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (financial_statement_id) DO UPDATE SET report_code = EXCLUDED.report_code, common = EXCLUDED.common,
                        basis = EXCLUDED.basis, branch = EXCLUDED.branch, engine_version = EXCLUDED.engine_version,
                        non_common = EXCLUDED.non_common, ncav_non_common = EXCLUDED.ncav_non_common,
                        equity_branch = EXCLUDED.equity_branch, derived_at = now()
                    WHERE (financial_common_income.report_code, financial_common_income.common, financial_common_income.basis,
                           financial_common_income.branch, financial_common_income.engine_version, financial_common_income.non_common,
                           financial_common_income.ncav_non_common, financial_common_income.equity_branch)
                      IS DISTINCT FROM (EXCLUDED.report_code, EXCLUDED.common, EXCLUDED.basis, EXCLUDED.branch, EXCLUDED.engine_version,
                           EXCLUDED.non_common, EXCLUDED.ncav_non_common, EXCLUDED.equity_branch)
                    """, e.getKey(), r.row().reportCode() == null ? "" : r.row().reportCode(),
                    r.common() == null ? null : r.common().setScale(2, java.math.RoundingMode.HALF_UP), r.basis().name(),
                    r.branch(), IssuerTimeline.ENGINE_VERSION, money(q == null ? null : q.nonCommon()),
                    money(q == null ? null : q.ncavNonCommon()), q == null ? null : q.branch());
        }
        return written;
    }

    private static java.math.BigDecimal money(Double v) {
        return v == null ? null : java.math.BigDecimal.valueOf(v).setScale(2, java.math.RoundingMode.HALF_UP);
    }

    /** 시장의 활성 줄마다 판정과 분할 원장 — 순위 판독기·보류가 한 번에 읽는다. 판정이 없는 줄은 키가 없다. */
    public Map<Long, ShareClassLine> lines(String marketCode) {
        Map<Long, List<ShareClassLine.Verdict>> verdicts = verdictsByStock(marketCode);
        Map<Long, List<ShareClassLine.Split>> splits = new HashMap<>();
        jdbcTemplate.query("""
                SELECT c.stock_id, c.effective_date, c.ratio FROM corporate_action c JOIN stock s ON s.id = c.stock_id
                WHERE s.market_code = ? AND s.is_active AND c.status = 'APPLIED'
                -- 시대 무관: 행 id로 찾는 분할 표 — 고르기는 순위 질의가 그 기준일 시대로 한다
                ORDER BY c.stock_id, c.effective_date, c.id
                """, rs -> {
            splits.computeIfAbsent(rs.getLong("stock_id"), k -> new ArrayList<>()).add(
                    new ShareClassLine.Split(rs.getObject("effective_date", LocalDate.class), rs.getBigDecimal("ratio")));
        }, marketCode);
        Map<Long, ShareClassLine> out = new HashMap<>();
        verdicts.forEach((stockId, rows) -> {
            List<ShareClassLine.Split> ads = splits.getOrDefault(stockId, List.of());
            List<java.util.Map.Entry<LocalDate, Double>> ratios = ads.isEmpty() ? List.of() : depositaryRatios(stockId);
            if (ratios.isEmpty()) {
                out.put(stockId, new ShareClassLine(rows, ads));
                return;
            }
            List<IssuerTimeline.Split> ordinary = toOrdinary(
                    ads.stream().map(s -> new IssuerTimeline.Split(s.date(), s.ratio().doubleValue())).toList(), ratios);
            out.put(stockId, new ShareClassLine(rows, ads,
                    ordinary.stream().map(s -> new ShareClassLine.Split(s.date(), BigDecimal.valueOf(s.ratio()))).toList()));
        });
        return out;
    }

    /**
     * 시장의 활성 줄마다 판정 행 — {@link #lines}가 분할 원장과 묶는다. 후보 순위 효과 스윕(관문 2단계)이 운영 DB에 안 쓰고 판정을
     * 메모리에 둔 채 같은 순위 경로를 돌리려고 덮어쓰는 자리다.
     */
    protected Map<Long, List<ShareClassLine.Verdict>> verdictsByStock(String marketCode) {
        Map<Long, List<ShareClassLine.Verdict>> verdicts = new LinkedHashMap<>();
        jdbcTemplate.query("""
                SELECT v.stock_id, v.accession, v.filed, v.kind, v.status, v.current_path, v.failure, v.line_shares, v.denominator
                FROM financial_share_class_filing v JOIN stock s ON s.id = v.stock_id
                WHERE s.market_code = ? AND s.is_active
                -- 시대 무관: 행 id로 찾는 판정 표 — 고르기는 순위 질의가 그 기준일 시대로 한다
                """, rs -> {
            verdicts.computeIfAbsent(rs.getLong("stock_id"), k -> new ArrayList<>()).add(verdict(rs));
        }, marketCode);
        return verdicts;
    }

    /** 저장 형태를 판독기 형태로 — 저장한 뒤 다시 읽은 것과 같다({@link #verdict}와 같은 규칙). */
    public static ShareClassLine.Verdict lineVerdict(VerdictRow r) {
        return new ShareClassLine.Verdict(r.accession(), r.filed(), r.kind(), r.status(), r.currentPath(),
                ledgerContradiction(r.failure()), decimal(r.lineShares()), decimal(r.denominator()));
    }

    /** 줄 하나의 판정과 분할 원장. 판정이 없으면 {@link ShareClassLine#none()}. */
    public ShareClassLine line(long stockId) {
        List<ShareClassLine.Verdict> verdicts = jdbcTemplate.query("""
                SELECT accession, filed, kind, status, current_path, failure, line_shares, denominator
                FROM financial_share_class_filing WHERE stock_id = ?
                """, (rs, n) -> verdict(rs), stockId);
        if (verdicts.isEmpty()) {
            return ShareClassLine.none();
        }
        List<ShareClassLine.Split> ads = adsSplits(stockId).stream()
                .map(s -> new ShareClassLine.Split(s.date(), BigDecimal.valueOf(s.ratio()))).toList();
        List<ShareClassLine.Split> ordinary = splits(stockId).stream()
                .map(s -> new ShareClassLine.Split(s.date(), BigDecimal.valueOf(s.ratio()))).toList();
        return new ShareClassLine(verdicts, ads, ordinary);
    }

    private static ShareClassLine.Verdict verdict(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ShareClassLine.Verdict(rs.getString("accession"), rs.getObject("filed", LocalDate.class),
                rs.getString("kind").charAt(0), rs.getString("status").charAt(0), rs.getBoolean("current_path"),
                ledgerContradiction(rs.getString("failure")), rs.getBigDecimal("line_shares"), rs.getBigDecimal("denominator"));
    }

    /** 실패 출처 중 원장 어긋남만 보류 함수가 따로 본다(발행사 제외의 재료). */
    private static boolean ledgerContradiction(String failure) {
        return "LEDGER".equals(failure);
    }

    private static BigDecimal decimal(Double v) {
        return v == null ? null : BigDecimal.valueOf(v);
    }
}
