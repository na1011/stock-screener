package com.stockscreener.pipeline.datasource.us;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * {@code sec_filing_supplement}(V64 · ADR-0173) — 보충한 공시를 접수번호마다 한 행으로 든다.
 *
 * <p><b>MUST: 호출자의 트랜잭션에 그대로 참여한다</b>(전파 지정 없음). 보충은 종목별 {@code REQUIRES_NEW} 안에서 돌고
 * ({@code StockTaskRunner}), 원장 행이 그 종목의 재무 쓰기와 <b>함께 커밋되거나 함께 롤백돼야</b> 실행 기록의 보충 수
 * (받음·실패·미해결)가 실제로 쓴 것과 맞는다. 판 열기는 원장 상태에 기대지 않는다 — 그 실행에서 실은 공시와 COVERED 행을
 * 매번 다시 본다(무상태).
 */
@Repository
public class SecFilingSupplementStore implements SupplementLedger {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<StoredFact>> FACTS = new TypeReference<>() {
    };

    /** 저장 형태의 원 팩트. 열 이름을 짧게 둔다 — 공시당 수백 개다. */
    record StoredFact(String t, String c, String u, String s, String e, String v, String d) {

        static StoredFact of(XbrlInstanceReader.RawFact f) {
            return new StoredFact(f.taxonomy(), f.concept(), f.unit(),
                    f.start() == null ? null : f.start().toString(), f.end().toString(),
                    f.value().toPlainString(), f.decimals());
        }

        XbrlInstanceReader.RawFact toRaw() {
            return new XbrlInstanceReader.RawFact(t, c, u, s == null ? null : LocalDate.parse(s),
                    LocalDate.parse(e), new BigDecimal(v), d);
        }
    }

    /** 한 행. {@code facts}는 {@code OK}·{@code NO_XBRL}일 때만 있다. */
    record Row(String accession, long cik, String form, LocalDate filed, int readerVersion, String status,
               String fiscalYearFocus, String fiscalPeriodFocus, List<XbrlInstanceReader.RawFact> facts,
               String failureKind) {

        /** 저장분을 합칠 수 있다(XBRL 없음은 빈 팩트). */
        boolean ok() {
            return "OK".equals(status) || "NO_XBRL".equals(status);
        }

        boolean failed() {
            return "FAILED".equals(status);
        }
    }

    private final JdbcTemplate jdbcTemplate;

    public SecFilingSupplementStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Map<String, Row> find(long cik, Collection<String> accessions) {
        if (accessions.isEmpty()) {
            return Map.of();
        }
        String placeholders = accessions.stream().map(a -> "?").collect(Collectors.joining(", "));
        Map<String, Row> out = new HashMap<>();
        jdbcTemplate.query("""
                SELECT accession, cik, form, filed, reader_version, status, fiscal_year_focus, fiscal_period_focus,
                       facts::text AS facts, failure_kind
                FROM sec_filing_supplement WHERE cik = ? AND accession IN (%s)
                """.formatted(placeholders), rs -> {
            String json = rs.getString("facts");
            List<XbrlInstanceReader.RawFact> facts = json == null ? List.of()
                    : MAPPER.readValue(json, FACTS).stream().map(StoredFact::toRaw).toList();
            Row row = new Row(rs.getString("accession"), rs.getLong("cik"), rs.getString("form"),
                    rs.getObject("filed", LocalDate.class), rs.getInt("reader_version"),
                    rs.getString("status"), rs.getString("fiscal_year_focus"),
                    rs.getString("fiscal_period_focus"), facts, rs.getString("failure_kind"));
            out.put(row.accession(), row);
        }, java.util.stream.Stream.concat(java.util.stream.Stream.of((Object) cik), accessions.stream()).toArray());
        return out;
    }

    @Override
    public void saveOk(SecFilingClient.Filing filing, long cik, String fileName, long fileSize,
                XbrlInstanceReader.Instance instance) {
        String json = MAPPER.writeValueAsString(instance.facts().stream().map(StoredFact::of).toList());
        int usGaap = (int) instance.facts().stream().filter(f -> f.taxonomy().equals("us-gaap")).count();
        jdbcTemplate.update("""
                INSERT INTO sec_filing_supplement
                    (accession, cik, form, filed, reader_version, status, file_name, file_size, document_type,
                     fiscal_year_focus, fiscal_period_focus, us_gaap_facts, facts, failure_kind, failure_message,
                     fetched_at)
                VALUES (?, ?, ?, ?, ?, 'OK', ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), NULL, NULL, now())
                ON CONFLICT (accession, cik) DO UPDATE SET
                    form = EXCLUDED.form, filed = EXCLUDED.filed,
                    reader_version = EXCLUDED.reader_version, status = 'OK', file_name = EXCLUDED.file_name,
                    file_size = EXCLUDED.file_size, document_type = EXCLUDED.document_type,
                    fiscal_year_focus = EXCLUDED.fiscal_year_focus, fiscal_period_focus = EXCLUDED.fiscal_period_focus,
                    us_gaap_facts = EXCLUDED.us_gaap_facts, facts = EXCLUDED.facts, failure_kind = NULL,
                    failure_message = NULL, fetched_at = now(), last_seen_at = now(), stale_used_at = NULL
                """, filing.accession(), cik, filing.form(), Date.valueOf(filing.filed()),
                XbrlInstanceReader.READER_VERSION, fileName, fileSize, instance.documentType(),
                instance.fiscalYearFocus(), instance.fiscalPeriodFocus(), usGaap, json);
    }

    @Override
    public void saveFailed(SecFilingClient.Filing filing, long cik, String failureKind, String message) {
        jdbcTemplate.update("""
                INSERT INTO sec_filing_supplement
                    (accession, cik, form, filed, reader_version, status, facts, failure_kind, failure_message,
                     fetched_at)
                VALUES (?, ?, ?, ?, ?, 'FAILED', NULL, ?, ?, now())
                ON CONFLICT (accession, cik) DO UPDATE SET
                    form = EXCLUDED.form, filed = EXCLUDED.filed,
                    reader_version = EXCLUDED.reader_version, status = 'FAILED', file_name = NULL, file_size = NULL,
                    document_type = NULL, fiscal_year_focus = NULL, fiscal_period_focus = NULL, us_gaap_facts = NULL,
                    facts = NULL, failure_kind = EXCLUDED.failure_kind, failure_message = EXCLUDED.failure_message,
                    fetched_at = now(), last_seen_at = now(), stale_used_at = NULL
                """, filing.accession(), cik, filing.form(), Date.valueOf(filing.filed()),
                XbrlInstanceReader.READER_VERSION, failureKind, message);
    }

    @Override
    public void saveNoXbrl(SecFilingClient.Filing filing, long cik) {
        jdbcTemplate.update("""
                INSERT INTO sec_filing_supplement
                    (accession, cik, form, filed, reader_version, status, facts, failure_kind, failure_message, fetched_at)
                VALUES (?, ?, ?, ?, ?, 'NO_XBRL', '[]'::jsonb, NULL, NULL, now())
                ON CONFLICT (accession, cik) DO UPDATE SET
                    form = EXCLUDED.form, filed = EXCLUDED.filed, reader_version = EXCLUDED.reader_version,
                    status = 'NO_XBRL', file_name = NULL, file_size = NULL, document_type = NULL,
                    fiscal_year_focus = NULL, fiscal_period_focus = NULL, us_gaap_facts = 0, facts = '[]'::jsonb,
                    failure_kind = NULL, failure_message = NULL, fetched_at = now(), last_seen_at = now(),
                    stale_used_at = NULL
                """, filing.accession(), cik, filing.form(), Date.valueOf(filing.filed()),
                XbrlInstanceReader.READER_VERSION);
    }

    @Override
    public Map<String, Row> rowsOf(long cik) {
        List<String> accessions = jdbcTemplate.queryForList(
                "SELECT accession FROM sec_filing_supplement WHERE cik = ?", String.class, cik);
        return find(cik, accessions);
    }

    @Override
    public void markCovered(String accession, long cik) {
        jdbcTemplate.update("""
                UPDATE sec_filing_supplement SET status = 'COVERED', facts = NULL, us_gaap_facts = NULL,
                       failure_kind = NULL, failure_message = NULL, stale_used_at = NULL, last_seen_at = now()
                WHERE accession = ? AND cik = ? AND status <> 'COVERED'
                """, accession, cik);
    }

    @Override
    public void markStaleUsed(String accession, long cik) {
        jdbcTemplate.update("UPDATE sec_filing_supplement SET stale_used_at = now() WHERE accession = ? AND cik = ?",
                accession, cik);
    }

    @Override
    public void touch(long cik, Collection<String> accessions) {
        if (accessions.isEmpty()) {
            return;
        }
        String placeholders = accessions.stream().map(a -> "?").collect(Collectors.joining(", "));
        jdbcTemplate.update("UPDATE sec_filing_supplement SET last_seen_at = now() WHERE cik = ? AND accession IN ("
                + placeholders + ")", java.util.stream.Stream.concat(java.util.stream.Stream.of((Object) cik),
                accessions.stream()).toArray());
    }

    /**
     * 한 실행이 보충에서 무엇을 했는가 — FR 실행 기록(note)의 「보충」 칸.
     *
     * @param fetched    이 실행이 받아 저장한 공시
     * @param failed     이 실행이 실패로 남긴 공시
     * @param unresolved 이 실행이 본 행 중 판정이 남은 것 — 이 실행이 대상으로 본 실패 행과, 이 실행이 옛 판 저장분을 쓴 행
     *                   (새 규칙으로 다시 받지 못했거나 공시 목록을 못 받았다)
     * @param allUnresolved 표 전체의 같은 수 — 실패 행과 옛 판을 쓴 적이 있는 행 전부(기본 FR이 안 도는 과거 편입까지).
     *                      관문이 구성원마다 판정한다(ADR-0173). 「판이 낮다」만으로는 안 센다 — 안 쓰이는 행은 판정할 것이 없다
     */
    public record Tally(long fetched, long failed, long unresolved, long allUnresolved) {

        public boolean isEmpty() {
            return fetched == 0 && failed == 0 && unresolved == 0 && allUnresolved == 0;
        }
    }

    /** DB 시각. 행의 {@code fetched_at}이 트랜잭션 시작 시각이라 같은 시계로 잰다. */
    public Instant now() {
        return jdbcTemplate.queryForObject("SELECT now()", Timestamp.class).toInstant();
    }

    public Tally tallySince(Instant since) {
        return jdbcTemplate.queryForObject("""
                SELECT count(*) FILTER (WHERE fetched_at >= ? AND status IN ('OK', 'NO_XBRL')),
                       count(*) FILTER (WHERE fetched_at >= ? AND status = 'FAILED'),
                       count(*) FILTER (WHERE (last_seen_at >= ? AND status = 'FAILED') OR stale_used_at >= ?),
                       count(*) FILTER (WHERE status = 'FAILED' OR stale_used_at IS NOT NULL)
                FROM sec_filing_supplement
                """, (rs, rowNum) -> new Tally(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)),
                Timestamp.from(since), Timestamp.from(since), Timestamp.from(since), Timestamp.from(since));
    }

    @Override
    public Map<String, Row> okRowsOf(long cik) {
        List<String> accessions = jdbcTemplate.queryForList(
                "SELECT accession FROM sec_filing_supplement WHERE cik = ? AND status IN ('OK', 'NO_XBRL')",
                String.class, cik);
        return find(cik, accessions);
    }
}
