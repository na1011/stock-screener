package com.stockscreener.pipeline.datasource.us;

import com.stockscreener.pipeline.datasource.us.listing.DepositaryRatioJudge;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 판마다 예탁 비율(ADR-0183)의 두 표 — 표지 재료({@code sec_depositary_cover})와 공시마다 정한 k({@code sec_depositary_ratio}).
 * 관문 마이그레이션(시험 전용 {@code V70_1})이 만든 표라 관문 켬에서만 부른다.
 */
@Component
public class DepositaryCoverStore {

    /** 표지 판독 판 — 상장 판정 표지 읽기({@code ClassFactReader.readListingCover})의 규칙이 바뀌면 올린다. */
    static final int READER_VERSION = 1;

    public record CoverRow(String accession, int readerVersion, String status, String failureKind) {
    }

    public record Cover(String accession, String form, LocalDate filed, String facts) {
    }

    private final JdbcTemplate jdbcTemplate;

    public DepositaryCoverStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Map<String, CoverRow> coverRows(long cik) {
        Map<String, CoverRow> out = new HashMap<>();
        jdbcTemplate.query("SELECT accession, reader_version, status, failure_kind FROM sec_depositary_cover WHERE cik = ?",
                rs -> {
                    out.put(rs.getString("accession"), new CoverRow(rs.getString("accession"), rs.getInt("reader_version"),
                            rs.getString("status"), rs.getString("failure_kind")));
                }, cik);
        return out;
    }

    public void saveCover(long cik, String accession, String form, LocalDate filed, String status, String facts,
                          String failureKind, String failureMessage) {
        jdbcTemplate.update("""
                INSERT INTO sec_depositary_cover (cik, accession, form, filed, reader_version, status, facts, failure_kind,
                                                  failure_message)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
                ON CONFLICT (cik, accession) DO UPDATE SET form = EXCLUDED.form, filed = EXCLUDED.filed,
                    reader_version = EXCLUDED.reader_version, status = EXCLUDED.status, facts = EXCLUDED.facts,
                    failure_kind = EXCLUDED.failure_kind, failure_message = EXCLUDED.failure_message, fetched_at = now()
                """, cik, accession, form, filed, READER_VERSION, status, facts, failureKind, failureMessage);
    }

    /** 지금 판독 판으로 읽은 표지(OK)만 — 공시일 순. */
    public List<Cover> covers(long cik) {
        List<Cover> out = new ArrayList<>();
        jdbcTemplate.query("""
                SELECT accession, form, filed, facts::text AS facts FROM sec_depositary_cover
                WHERE cik = ? AND status = 'OK' AND reader_version = ? ORDER BY filed, accession
                """, rs -> {
            out.add(new Cover(rs.getString("accession"), rs.getString("form"), rs.getObject("filed", LocalDate.class),
                    rs.getString("facts")));
        }, cik, READER_VERSION);
        return out;
    }

    /** 저장 이름(목록 · 행)이 예탁증권을 말하는 종목인가. */
    public Optional<String> stockName(long stockId) {
        return jdbcTemplate.query("SELECT name FROM stock WHERE id = ?", rs -> rs.next()
                ? Optional.ofNullable(rs.getString("name")) : Optional.<String>empty(), stockId);
    }

    /** 공시마다 정한 k. */
    public Map<String, DepositaryRatioJudge.Decision> ratios(long stockId) {
        Map<String, DepositaryRatioJudge.Decision> out = new HashMap<>();
        jdbcTemplate.query("SELECT accession, k, how FROM sec_depositary_ratio WHERE stock_id = ?", rs -> {
            BigDecimal k = rs.getBigDecimal("k");
            out.put(rs.getString("accession"), new DepositaryRatioJudge.Decision(k == null ? null : k.doubleValue(),
                    DepositaryRatioJudge.How.valueOf(rs.getString("how"))));
        }, stockId);
        return out;
    }

    /**
     * 정한 k를 적는다. <b>MUST: 이미 정한 값(k가 있는 행)은 안 바꾼다</b> — 매일 다시 지으면 뒤에 온 표지·사건이 지난 판을 소급해 뒤집는다.
     * 미정 행만 새 판정으로 채운다(숨겨져 있던 판을 채울 뿐이다).
     */
    public void saveRatio(long stockId, String accession, LocalDate filed, Double k, DepositaryRatioJudge.How how) {
        jdbcTemplate.update("""
                INSERT INTO sec_depositary_ratio (stock_id, accession, filed, k, how) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (stock_id, accession) DO UPDATE SET k = EXCLUDED.k, how = EXCLUDED.how, decided_at = now()
                WHERE sec_depositary_ratio.k IS NULL AND EXCLUDED.k IS NOT NULL
                """, stockId, accession, filed, k == null ? null : BigDecimal.valueOf(k), how.name());
    }
}
