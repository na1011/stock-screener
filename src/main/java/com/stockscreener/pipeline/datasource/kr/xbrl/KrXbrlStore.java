package com.stockscreener.pipeline.datasource.kr.xbrl;

import com.stockscreener.pipeline.datasource.kr.xbrl.DartXbrlFactReader.Basis;
import com.stockscreener.pipeline.datasource.kr.xbrl.DartXbrlFactReader.Fact;
import com.stockscreener.pipeline.datasource.kr.xbrl.DartXbrlFactReader.Member;
import com.stockscreener.pipeline.datasource.kr.xbrl.DartXbrlFactReader.Source;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 국내 보통주 몫 재료 표 둘({@code kr_report_xbrl} · {@code kr_share_register} · V69_5 · ADR-0192)의 읽기 · 쓰기. 값을 바꾸는 표는 안 건드린다.
 */
@Repository
public class KrXbrlStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JdbcTemplate jdbc;

    public KrXbrlStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 받기 대상 — 국내 재무 행이 있는 종목 전원(편입 · 과거 편입 · 비활성 무관 · ADR-0192). */
    public record Target(long stockId, String ticker, String market, Integer accountingMonth) {
    }

    public List<Target> targets(List<String> tickers) {
        String byTicker = tickers == null || tickers.isEmpty() ? "" : " AND s.ticker = ANY (?)";
        Object[] args = tickers == null || tickers.isEmpty() ? new Object[0] : new Object[]{tickers.toArray(String[]::new)};
        return jdbc.query("""
                SELECT s.id, s.ticker, s.market_code, s.accounting_month FROM stock s
                 WHERE s.market_code IN ('KOSPI', 'KOSDAQ')
                   AND EXISTS (SELECT 1 FROM financial_statement f WHERE f.stock_id = s.id)%s
                 ORDER BY s.id
                """.formatted(byTicker), (rs, n) -> new Target(rs.getLong(1), rs.getString(2),
                rs.getString(3), (Integer) rs.getObject(4)), args);
    }

    /** 재무 행의 판(Q4 빼고 — Q4는 유도 행이라 판이 없다). */
    public record RowVintage(int fiscalYear, String fiscalPeriod, LocalDate published) {
    }

    public List<RowVintage> rowVintages(long stockId) {
        return jdbc.query("""
                SELECT DISTINCT fiscal_year, fiscal_period, published_at FROM financial_statement
                 WHERE stock_id = ? AND fiscal_period <> 'Q4'
                 ORDER BY 1, 2, 3
                """, (rs, n) -> new RowVintage(rs.getInt(1), rs.getString(2), rs.getObject(3, LocalDate.class)), stockId);
    }

    /** 목록의 판 하나와 그 재료 상태. */
    public record Filing(String rceptNo, LocalDate rceptDate, Integer bsnsYear, String reprtCode, String status,
                         Integer readerVersion) {

        /** 재무 행 published_at과 같은 날인가 — 접수번호 앞 8자리 또는 rcept_dt(reload-gate 「공시일 교정」이 닫혀도 짝이 산다). */
        public boolean publishedOn(LocalDate day) {
            return day.equals(rceptDate) || day.equals(LocalDate.parse(rceptNo.substring(0, 8),
                    java.time.format.DateTimeFormatter.BASIC_ISO_DATE));
        }
    }

    public List<Filing> filings(long stockId) {
        return jdbc.query("""
                SELECT rcept_no, rcept_date, bsns_year, reprt_code, xbrl_status, reader_version
                  FROM kr_report_xbrl WHERE stock_id = ? ORDER BY rcept_no
                """, (rs, n) -> new Filing(rs.getString(1), rs.getObject(2, LocalDate.class), (Integer) rs.getObject(3),
                rs.getString(4), rs.getString(5), (Integer) rs.getObject(6)), stockId);
    }

    /** 목록 줄을 넣는다 — 이미 있는 판은 안 건드린다(판은 한 번 나오면 안 바뀐다). */
    public int upsertListed(long stockId, String corpCode, String detailType, String reportName, String rceptNo,
                            LocalDate rceptDate, Integer bsnsYear, String reprtCode, String keyReason) {
        return jdbc.update("""
                INSERT INTO kr_report_xbrl (rcept_no, stock_id, corp_code, detail_type, report_name, rcept_date,
                                              bsns_year, reprt_code, key_reason)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (rcept_no, stock_id) DO NOTHING
                """, rceptNo, stockId, corpCode, detailType, reportName, rceptDate,
                bsnsYear == null ? null : bsnsYear.shortValue(), reprtCode, keyReason);
    }

    public void recordOk(long stockId, String rceptNo, List<Fact> facts, int unreadable) {
        jdbc.update("""
                UPDATE kr_report_xbrl SET xbrl_status = 'OK', reader_version = ?, facts = ?::jsonb, unreadable_values = ?,
                       failure_kind = NULL, failure_message = NULL, fetched_at = now()
                 WHERE rcept_no = ? AND stock_id = ?
                """, DartXbrlFactReader.READER_VERSION, encode(facts), unreadable, rceptNo, stockId);
    }

    public void recordNoFile(long stockId, String rceptNo) {
        jdbc.update("""
                UPDATE kr_report_xbrl SET xbrl_status = 'NO_FILE', reader_version = ?, facts = NULL, unreadable_values = NULL,
                       failure_kind = NULL, failure_message = NULL, fetched_at = now()
                 WHERE rcept_no = ? AND stock_id = ?
                """, DartXbrlFactReader.READER_VERSION, rceptNo, stockId);
    }

    public void recordFailure(long stockId, String rceptNo, String status, String kind, String message) {
        jdbc.update("""
                UPDATE kr_report_xbrl SET xbrl_status = ?, reader_version = ?, facts = NULL, unreadable_values = NULL,
                       failure_kind = ?, failure_message = ?, fetched_at = now()
                 WHERE rcept_no = ? AND stock_id = ?
                """, status, DartXbrlFactReader.READER_VERSION, kind, message, rceptNo, stockId);
    }

    /** 명부를 받은 사업연도. */
    public List<Integer> registerYears(long stockId) {
        return jdbc.queryForList("SELECT bsns_year FROM kr_share_register WHERE stock_id = ? ORDER BY 1", Integer.class, stockId);
    }

    public void recordRegister(long stockId, int bsnsYear, List<JsonNode> rows) {
        if (rows.isEmpty()) {
            jdbc.update("""
                    INSERT INTO kr_share_register (stock_id, bsns_year, status) VALUES (?, ?, 'NO_DATA')
                    ON CONFLICT (stock_id, bsns_year) DO UPDATE SET status = 'NO_DATA', rcept_no = NULL, record_date = NULL,
                        rows = NULL, fetched_at = now()
                    """, stockId, (short) bsnsYear);
            return;
        }
        ArrayNode kept = MAPPER.createArrayNode();
        String rceptNo = null;
        LocalDate recordDate = null;
        for (JsonNode row : rows) {
            ObjectNode o = kept.addObject();
            o.put("se", row.path("se").asString(""));
            o.put("issued", row.path("istc_totqy").asString(""));
            o.put("treasury", row.path("tesstk_co").asString(""));
            rceptNo = row.path("rcept_no").asString(rceptNo);
            String stlm = row.path("stlm_dt").asString("");
            if (stlm.matches("\\d{4}-\\d{2}-\\d{2}")) {
                recordDate = LocalDate.parse(stlm);
            }
        }
        jdbc.update("""
                INSERT INTO kr_share_register (stock_id, bsns_year, status, rcept_no, record_date, rows)
                VALUES (?, ?, 'OK', ?, ?, ?::jsonb)
                ON CONFLICT (stock_id, bsns_year) DO UPDATE SET status = 'OK', rcept_no = EXCLUDED.rcept_no,
                    record_date = EXCLUDED.record_date, rows = EXCLUDED.rows, fetched_at = now()
                """, stockId, (short) bsnsYear, rceptNo, recordDate, kept.toString());
    }

    /** 미해결 수 — 관문 전제(재료 받기 미해결 0)가 읽는다. FAILED 전부 + 지금 판독기와 판이 다른 OK · UNREADABLE. */
    public long unresolved() {
        Long n = jdbc.queryForObject("""
                SELECT count(*) FROM kr_report_xbrl
                 WHERE xbrl_status = 'FAILED' OR (xbrl_status IN ('OK', 'UNREADABLE') AND reader_version <> ?)
                """, Long.class, DartXbrlFactReader.READER_VERSION);
        return n == null ? 0 : n;
    }

    // ---- 재료 사실 JSON ----

    static String encode(List<Fact> facts) {
        ArrayNode array = MAPPER.createArrayNode();
        for (Fact f : facts) {
            ObjectNode o = array.addObject();
            o.put("s", code(f.source()));
            o.put("c", f.concept());
            if (f.start() == null) {
                o.putNull("b");
            } else {
                o.put("b", f.start().toString());
            }
            o.put("e", f.end().toString());
            o.put("k", switch (f.basis()) {
                case CONSOLIDATED -> "C";
                case SEPARATE -> "S";
                case NONE -> "N";
            });
            ArrayNode members = o.putArray("m");
            for (Member m : f.members()) {
                members.addArray().add(m.axis()).add(m.member()).add(code(m.source()));
            }
            o.put("v", f.value().toPlainString());
        }
        return array.toString();
    }

    public static List<Fact> decode(String json) {
        List<Fact> out = new ArrayList<>();
        for (JsonNode o : MAPPER.readTree(json)) {
            List<Member> members = new ArrayList<>();
            for (JsonNode m : o.path("m")) {
                members.add(new Member(m.get(0).asString(), m.get(1).asString(), source(m.get(2).asString())));
            }
            JsonNode b = o.path("b");
            out.add(new Fact(source(o.path("s").asString()), o.path("c").asString(),
                    b.isNull() || b.isMissingNode() ? null : LocalDate.parse(b.asString()), LocalDate.parse(o.path("e").asString()),
                    switch (o.path("k").asString()) {
                        case "C" -> Basis.CONSOLIDATED;
                        case "S" -> Basis.SEPARATE;
                        default -> Basis.NONE;
                    }, List.copyOf(members), new BigDecimal(o.path("v").asString())));
        }
        return out;
    }

    private static String code(Source s) {
        return switch (s) {
            case IFRS -> "I";
            case DART -> "D";
            case ENTITY -> "E";
        };
    }

    private static Source source(String code) {
        return switch (code) {
            case "I" -> Source.IFRS;
            case "D" -> Source.DART;
            default -> Source.ENTITY;
        };
    }

    /** 유도가 읽는 꼴 — 접수번호마다 재료(OK만). */
    public Map<String, List<Fact>> okFacts(long stockId) {
        Map<String, List<Fact>> out = new java.util.LinkedHashMap<>();
        jdbc.query("""
                SELECT rcept_no, facts::text FROM kr_report_xbrl
                 WHERE stock_id = ? AND xbrl_status = 'OK' AND reader_version = ?
                """, rs -> {
            out.put(rs.getString(1), decode(rs.getString(2)));
        }, stockId, DartXbrlFactReader.READER_VERSION);
        return out;
    }
}
