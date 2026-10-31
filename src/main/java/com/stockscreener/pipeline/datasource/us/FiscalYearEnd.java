package com.stockscreener.pipeline.datasource.us;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 제출인 정보가 선언한 결산월({@code submissions.fiscalYearEnd} → {@code stock.accounting_month} · {@code company-profile}이 적재). 재무
 * 어댑터는 연차 보고서를 아직 안 낸 등록인의 회계연도 창을 세울 때만 이것을 증인으로 묻는다({@code SecEdgarFacts.interimSeed}).
 */
public interface FiscalYearEnd {

    /** 묻지 않는다 — 언제나 없음(시험 생성자). 없으면 그 창을 안 세운다. */
    FiscalYearEnd NONE = stockId -> null;

    Integer monthOf(long stockId);

    @Component
    class Jdbc implements FiscalYearEnd {

        private final JdbcTemplate jdbcTemplate;

        public Jdbc(JdbcTemplate jdbcTemplate) {
            this.jdbcTemplate = jdbcTemplate;
        }

        @Override
        public Integer monthOf(long stockId) {
            List<Integer> rows = jdbcTemplate.queryForList(
                    "SELECT accounting_month::int FROM stock WHERE id = ?", Integer.class, stockId);
            return rows.isEmpty() ? null : rows.get(0);
        }
    }
}
