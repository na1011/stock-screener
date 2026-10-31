package com.stockscreener.pipeline.datasource.us;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 제출인 정보가 선언한 발행사 속성 — 결산월({@code submissions.fiscalYearEnd} → {@code stock.accounting_month})과 업종 SIC
 * ({@code submissions.sic} → {@code stock.sic_code}) · 둘 다 {@code company-profile}이 적재한다. 재무 어댑터는 이것을 팩트를 해석할 증인으로만
 * 쓴다 — 연차 보고서 전 등록인의 회계연도 창(ADR-0198)과 업종 총매출 태그의 적용 범위(관문 켬 매출 폴백 · {@code SecEdgarColumn}).
 */
public interface IssuerProfile {

    /** 한 행의 속성. 값이 없으면 null — 그 증인이 필요한 해석은 안 선다(없음으로 남는다). */
    record Profile(Integer fiscalYearEndMonth, String sic) {
        public static final Profile UNKNOWN = new Profile(null, null);
    }

    /** 묻지 않는다 — 언제나 모름(시험 생성자). */
    IssuerProfile NONE = stockId -> Profile.UNKNOWN;

    Profile of(long stockId);

    @Component
    class Jdbc implements IssuerProfile {

        private final JdbcTemplate jdbcTemplate;

        public Jdbc(JdbcTemplate jdbcTemplate) {
            this.jdbcTemplate = jdbcTemplate;
        }

        @Override
        public Profile of(long stockId) {
            List<Profile> rows = jdbcTemplate.query("SELECT accounting_month::int AS m, sic_code FROM stock WHERE id = ?",
                    (rs, i) -> new Profile(rs.getObject("m", Integer.class), rs.getString("sic_code")), stockId);
            return rows.isEmpty() ? Profile.UNKNOWN : rows.get(0);
        }
    }
}
