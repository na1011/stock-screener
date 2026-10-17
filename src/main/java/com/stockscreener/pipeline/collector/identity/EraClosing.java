package com.stockscreener.pipeline.collector.identity;

import com.stockscreener.pipeline.datasource.SecurityRef;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 행의 시대를 닫는 쓰기 하나(ADR-0180 · 계획 {@code US-PAST-LISTING-PLAN-1017.md} P0-a). 시대 끝과 <b>표지 비우기</b>를 함께 한다.
 *
 * <p>왜 함께인가: 신원 판정은 열린 행만 본다. 닫힌 행이 기호를 쥐고 있으면 Alpaca가 그 기호를 새 실체에 줬을 때 판정에는 「주인 없는
 * 기호」로 보이고, 등록({@code StockRegistrar.resolveOrCreate})이 표지로 <b>닫힌 행에 새 실체를 붙인다</b> — 그 행은 {@code era_to}
 * 때문에 순위에 안 들어 새 실체가 소리 없이 빠진다. 비운 표지({@code T~마지막날})는 어떤 오늘 기호와도 안 맞으므로 그 길이 원리적으로
 * 막힌다. 기호를 그때의 기호로 묻는 소스(기업 행위 · 배당)는 {@link SecurityRef#currentSymbol()}로 되찾고 시대 끝에서 닫는다.
 *
 * <p>이미 비운 표지는 그대로 둔다(다시 비우면 날짜가 겹쳐 쌓인다). 호출부의 트랜잭션 안에서 돈다.
 */
@Component
public class EraClosing {

    private final JdbcTemplate jdbcTemplate;

    public EraClosing(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** @param eraTo 시대 끝(제외) — 표지의 마지막 날은 그 전날이다 */
    public void close(long stockId, LocalDate eraTo) {
        int updated = jdbcTemplate.update("""
                UPDATE stock SET ticker = CASE WHEN strpos(ticker, ?) > 0 THEN ticker ELSE ticker || ? END,
                                 era_to = ?, updated_at = now()
                WHERE id = ?
                """, String.valueOf(SecurityRef.VACATED), SecurityRef.VACATED + eraTo.minusDays(1).toString(), eraTo, stockId);
        if (updated != 1) {
            throw new IllegalStateException("시대를 닫을 행이 없다: stock_id=" + stockId);
        }
    }
}
