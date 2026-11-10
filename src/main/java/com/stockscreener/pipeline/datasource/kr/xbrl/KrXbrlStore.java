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

    /**
     * 국내 관문({@code stockscreener.gate.kr-provisional}) — 켜면 재료를 판독기 판 {@link DartXbrlFactReader#READER_VERSION_KR_GATE}로 쓰고 읽는다
     * (희석 EPS까지 남긴 재료 · 계획 C+⑵). 꺼짐은 지금 판 그대로다. 시험은 생성자만 부른다.
     */
    private boolean krGate;

    @org.springframework.beans.factory.annotation.Autowired
    public void krGate(@org.springframework.beans.factory.annotation.Value("${stockscreener.gate.kr-provisional}") boolean on) {
        this.krGate = on;
    }

    /** 이 저장소가 쓰고 읽는 판독기 판. */
    public int readerVersion() {
        return DartXbrlFactReader.version(krGate);
    }

    /** 수집기가 판독기에 넘기는 관문 상태 — 쓰는 판과 남기는 재료가 한 설정에서 나온다. */
    public boolean krGate() {
        return krGate;
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

    /** 재무 행의 판(Q4 빼고 — Q4는 유도 행이라 판이 없다) · 그 라벨의 최신 판인가. */
    public record RowVintage(int fiscalYear, String fiscalPeriod, LocalDate published, boolean latest) {

        public RowVintage(int fiscalYear, String fiscalPeriod, LocalDate published) {
            this(fiscalYear, fiscalPeriod, published, false);
        }
    }

    public List<RowVintage> rowVintages(long stockId) {
        return jdbc.query("""
                SELECT fiscal_year, fiscal_period, published_at, bool_or(is_latest) FROM financial_statement
                 WHERE stock_id = ? AND fiscal_period <> 'Q4'
                 GROUP BY 1, 2, 3
                 ORDER BY 1, 2, 3
                """, (rs, n) -> new RowVintage(rs.getInt(1), rs.getString(2), rs.getObject(3, LocalDate.class), rs.getBoolean(4)),
                stockId);
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
                """, readerVersion(), encode(facts), unreadable, rceptNo, stockId);
    }

    public void recordNoFile(long stockId, String rceptNo) {
        jdbc.update("""
                UPDATE kr_report_xbrl SET xbrl_status = 'NO_FILE', reader_version = ?, facts = NULL, unreadable_values = NULL,
                       failure_kind = NULL, failure_message = NULL, fetched_at = now()
                 WHERE rcept_no = ? AND stock_id = ?
                """, readerVersion(), rceptNo, stockId);
    }

    public void recordFailure(long stockId, String rceptNo, String status, String kind, String message) {
        jdbc.update("""
                UPDATE kr_report_xbrl SET xbrl_status = ?, reader_version = ?, facts = NULL, unreadable_values = NULL,
                       failure_kind = ?, failure_message = ?, fetched_at = now()
                 WHERE rcept_no = ? AND stock_id = ?
                """, status, readerVersion(), kind, message, rceptNo, stockId);
    }

    /** 명부를 받은 사업연도. */
    /** 저장 이른 판의 짝 — 접수번호 · 회사 코드 · 그 접수의 판독 상태({@code OK}가 아니면 그 판은 인스턴스로 못 짓는다). */
    public record StoredVintage(String corpCode, String rceptNo, String status) {
    }

    /**
     * 그 (사업연도, 보고서 코드)의 저장 <b>이른</b> 판(최신 아님 · 그 라벨 저장 최신보다 이른 공시일 · {@code latestRceptNo}의 접수일도 아님 ·
     * 국내 관문 컬럼 {@code copied_from} · {@code derived_from}이 빈 소스 판) 각각에 짝인 접수 — 같은 날 여럿이면 가장 늦은 접수번호 하나(어댑터가
     * 공시일을 접수번호로 읽고 DART가 그날 마지막 판을 준 것과 같다 · 그 접수가 판독 전이면 더 이른 같은 날 접수로 물러나지 않는다).
     * 국내 관문 켬에서만 부른다(두 컬럼은 관문 시험 마이그레이션 V70_10 · V70_11).
     */
    public List<StoredVintage> storedEarlierVintages(long stockId, int year, String reprtCode, String latestRceptNo) {
        return jdbc.query("""
                SELECT DISTINCT ON (f.published_at) b.corp_code, b.rcept_no, b.xbrl_status
                  FROM financial_statement f
                  JOIN kr_report_xbrl b ON b.stock_id = f.stock_id AND b.bsns_year = f.fiscal_year AND b.reprt_code = f.report_code
                       AND left(b.rcept_no, 8) = to_char(f.published_at, 'YYYYMMDD')
                 WHERE f.stock_id = ? AND f.fiscal_year = ? AND f.report_code = ? AND f.fiscal_period <> 'Q4'
                   AND NOT f.is_latest AND f.copied_from IS NULL AND f.derived_from IS NULL
                   AND f.published_at < (SELECT max(l.published_at) FROM financial_statement l WHERE l.stock_id = f.stock_id
                                           AND l.fiscal_year = f.fiscal_year AND l.fiscal_period = f.fiscal_period)
                   AND to_char(f.published_at, 'YYYYMMDD') <> left(?, 8)
                 ORDER BY f.published_at, b.rcept_no DESC
                """, (rs, i) -> new StoredVintage(rs.getString(1), rs.getString(2), rs.getString(3)), stockId, year, reprtCode,
                latestRceptNo);
    }

    /**
     * 다시 묻지 않을 명부 해 — 받은 명부와, 받은 뒤로 그 해 사업보고서가 목록에 안 오른 「없음」. 사업보고서 전에 물은 「없음」(1~3월에 앞 해를
     * 물으면 원장이 {@code 013}을 준다)을 영구로 두면 그 해 행 전부가 앞 해 명부를 잃는다 — 그 해 사업보고서가 그 「없음」 뒤 날짜로 목록에 오르면
     * 다시 묻는다(계획 K3 반증 · 2027-01-02 첫 정기 실행이 2026 명부를 그렇게 물을 차례였다).
     */
    public List<Integer> registerYears(long stockId) {
        return jdbc.queryForList("""
                SELECT r.bsns_year FROM kr_share_register r
                 WHERE r.stock_id = ?
                   AND NOT (r.status = 'NO_DATA' AND EXISTS (SELECT 1 FROM kr_report_xbrl x WHERE x.stock_id = r.stock_id
                            AND x.bsns_year = r.bsns_year AND x.reprt_code = '11011' AND x.rcept_date >= r.fetched_at::date))
                 ORDER BY 1
                """, Integer.class, stockId);
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

    // ---- 정기보고서 주식수 재료(kr_share_material · 국내 관문 켬 · 계획 K3) ----

    /** 그 보고서의 주식수 표 하나 — 줄({@code rows}가 원장 필드 그대로) · 판(rcept_no) · 받은 판이 없으면 {@code rows} 빈 목록. */
    public record ShareMaterial(int bsnsYear, String reprtCode, String endpoint, String rceptNo, List<JsonNode> rows) {
    }

    /**
     * 다시 묻지 않을 (사업연도, 보고서 코드, 표) — 받은 것과, 받은 뒤로 그 보고서 판이 목록에 안 오른 「없음」(명부와 같은 규칙 ·
     * {@link #registerYears}). 키는 {@code 연도/보고서/표}.
     */
    public java.util.Set<String> materialKeys(long stockId) {
        return new java.util.HashSet<>(jdbc.queryForList("""
                SELECT m.bsns_year || '/' || m.reprt_code || '/' || m.endpoint FROM kr_share_material m
                 WHERE m.stock_id = ?
                   AND NOT (m.status = 'NO_DATA' AND EXISTS (SELECT 1 FROM kr_report_xbrl x WHERE x.stock_id = m.stock_id
                            AND x.bsns_year = m.bsns_year AND x.reprt_code = m.reprt_code AND x.rcept_date >= m.fetched_at::date))
                """, String.class, stockId));
    }

    public void recordMaterial(long stockId, int bsnsYear, String reprtCode, String endpoint, List<JsonNode> rows) {
        if (rows.isEmpty()) {
            jdbc.update("""
                    INSERT INTO kr_share_material (stock_id, bsns_year, reprt_code, endpoint, status) VALUES (?, ?, ?, ?, 'NO_DATA')
                    ON CONFLICT (stock_id, bsns_year, reprt_code, endpoint) DO UPDATE SET status = 'NO_DATA', rcept_no = NULL, rows = NULL,
                        fetched_at = now()
                    """, stockId, (short) bsnsYear, reprtCode, endpoint);
            return;
        }
        ArrayNode kept = MAPPER.createArrayNode();
        String rceptNo = null;
        for (JsonNode row : rows) {
            ObjectNode o = kept.addObject();
            row.properties().forEach(e -> {
                if (!java.util.Set.of("corp_code", "corp_name", "corp_cls").contains(e.getKey())) {
                    o.put(e.getKey(), e.getValue().asString(""));
                }
            });
            rceptNo = row.path("rcept_no").asString(rceptNo);
        }
        jdbc.update("""
                INSERT INTO kr_share_material (stock_id, bsns_year, reprt_code, endpoint, status, rcept_no, rows)
                VALUES (?, ?, ?, ?, 'OK', ?, ?::jsonb)
                ON CONFLICT (stock_id, bsns_year, reprt_code, endpoint) DO UPDATE SET status = 'OK', rcept_no = EXCLUDED.rcept_no,
                    rows = EXCLUDED.rows, fetched_at = now()
                """, stockId, (short) bsnsYear, reprtCode, endpoint, rceptNo, kept.toString());
    }

    /** 한 종목의 받은 주식수 재료 전부(OK만). */
    public List<ShareMaterial> shareMaterial(long stockId) {
        return jdbc.query("""
                SELECT bsns_year, reprt_code, endpoint, rcept_no, rows::text FROM kr_share_material
                 WHERE stock_id = ? AND status = 'OK' ORDER BY 1, 2, 3
                """, (rs, i) -> {
            List<JsonNode> rows = new ArrayList<>();
            MAPPER.readTree(rs.getString(5)).forEach(rows::add);
            return new ShareMaterial(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getString(4), rows);
        }, stockId);
    }

    /** 미해결 수 — 관문 전제(재료 받기 미해결 0)가 읽는다. FAILED 전부 + 지금 판독기와 판이 다른 OK · UNREADABLE. */
    public long unresolved() {
        Long n = jdbc.queryForObject("""
                SELECT count(*) FROM kr_report_xbrl
                 WHERE xbrl_status = 'FAILED' OR (xbrl_status IN ('OK', 'UNREADABLE') AND reader_version <> ?)
                """, Long.class, readerVersion());
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
        }, stockId, readerVersion());
        return out;
    }
}
