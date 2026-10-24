package com.stockscreener.pipeline.datasource.us;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 종목의 재무가 companyfacts 없이 공시 보충만으로 쌓였는가(ADR-0190) — companyfacts 404를 「빈 facts」로 받아 보충에 넘겨도 되는지를 가른다.
 *
 * <p>참 = 그 종목의 저장 재무 행이 전부 같은 CIK의 보충 행(OK · NO_XBRL)에서 왔고(행이 없으면 참) 그 CIK에 COVERED 보충 행이 없다.
 * companyfacts가 한 번이라도 그 발행사의 공시를 실었으면 거짓이다 — 그때의 404는 일시 사건이거나 등록인이 바뀐 것이라, 빈 트리로 이으면
 * 클래스 축 재료 · 부재 표 · 보충 판이 그 한 판에 덮인다(계획 v1 반증 · 2026-10-24).
 */
public interface CompanyFactsHistory {

    /** 묻지 않는다 — 언제나 거짓(시험 생성자 · 관문 꺼짐과 같다). */
    CompanyFactsHistory NONE = (stockId, cik) -> false;

    boolean supplementOnly(long stockId, long cik);

    @Component
    class Jdbc implements CompanyFactsHistory {

        private final JdbcTemplate jdbcTemplate;

        public Jdbc(JdbcTemplate jdbcTemplate) {
            this.jdbcTemplate = jdbcTemplate;
        }

        @Override
        public boolean supplementOnly(long stockId, long cik) {
            Boolean only = jdbcTemplate.queryForObject("""
                    SELECT NOT EXISTS (SELECT 1 FROM sec_filing_supplement WHERE cik = ? AND status = 'COVERED')
                       AND NOT EXISTS (
                           SELECT 1 FROM financial_statement f
                           WHERE f.stock_id = ?
                             AND NOT EXISTS (SELECT 1 FROM sec_filing_supplement s
                                             WHERE s.cik = ? AND s.status IN ('OK', 'NO_XBRL')
                                               AND s.accession = split_part(f.report_code, ':', 2)))
                    """, Boolean.class, cik, stockId, cik);
            return Boolean.TRUE.equals(only);
        }
    }
}
