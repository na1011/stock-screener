package com.stockscreener.pipeline.collector;

import com.stockscreener.pipeline.datasource.FinancialColumnAbsence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * 「그 종목의 이 컬럼을 한 팩트도 못 얻었다」를 표에 남긴다.
 *
 * <p><b>MUST: 그 종목·그 소스의 기존 행을 먼저 지우고 이번 관측만 넣는다.</b> UPSERT만 하면
 * 후보 태그를 더해 그 컬럼이 채워진 뒤에도 <b>옛 부재 행이 살아남아 화면이 거짓을 말한다.</b>
 * 관측일만 보는 감시로는 그 거짓을 못 잡는다 — 재적재가 관측일을 새로 찍기 때문이다.
 *
 * <p><b>MUST NOT: 빈 목록을 「부재 없음」으로 쓰지 않는다.</b> 부재 축을 안 보는 소스도 빈
 * 목록을 내므로(지금 DART가 그렇다), 그것으로 지우면 <b>다른 소스가 남긴 사실이 사라진다.</b>
 * 그래서 지우는 범위를 <b>(종목, 소스)</b>로 좁히고, 빈 목록이면 아무것도 안 한다.
 */
@Component
public class FinancialColumnAbsenceWriter {

    private final JdbcTemplate jdbcTemplate;

    public FinancialColumnAbsenceWriter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * @param absences 이번 수집에서 한 팩트도 못 얻은 컬럼. <b>비면 아무것도 하지 않는다</b> —
     *                 그 소스가 이 축을 안 본다는 뜻이지 부재가 없다는 뜻이 아니다.
     */
    @Transactional
    public void write(long stockId, String sourceName, List<FinancialColumnAbsence> absences,
                      LocalDate observedAt) {
        if (absences.isEmpty()) {
            return;
        }
        jdbcTemplate.update(
                "DELETE FROM financial_column_absence WHERE stock_id = ? AND source_name = ?",
                stockId, sourceName);
        jdbcTemplate.batchUpdate("""
                INSERT INTO financial_column_absence
                    (stock_id, column_name, state, source_name, observed_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (stock_id, column_name) DO UPDATE
                    SET state = EXCLUDED.state,
                        source_name = EXCLUDED.source_name,
                        observed_at = EXCLUDED.observed_at
                """,
                absences.stream()
                        .map(a -> new Object[] {
                                stockId, a.columnName(), a.state().name(), sourceName, observedAt })
                        .toList());
    }
}
