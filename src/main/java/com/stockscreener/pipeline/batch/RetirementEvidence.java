package com.stockscreener.pipeline.batch;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 종목마다 <b>상장폐지 증거</b>가 있는가 — 신선한 매핑 판의 부재를 「소스가 답했다」로 셀 수 있는지의 재료(ADR-0174).
 *
 * <p>증거 = 실행 시작 때 <b>현재 활성 편입이 아님</b>(보존 창 · 과거 편입 · 비활성 유니버스) ∨ <b>최근 {@value #RECENT_TRADING_DAYS}거래일
 * 봉이 없음</b>. 표지의 근거였던 CRNX·APGE는 둘 다 과거 편입이고 마지막 봉이 08-31·09-02였다. 거꾸로 현재 편입이고 봉이 오는
 * 종목이 매핑에 없으면 신규 상장의 등록 지연 꼴이라 다시 물으면 답이 바뀔 수 있다 — 그때는 답 못 받음이다.
 *
 * <p>「최근 n거래일」은 그 종목 시장의 거래일로 센다 — {@code price_daily}에 그 시장이 가진 가장 최근 날짜 n개 중 가장 이른 날
 * 이후에 봉이 있는가. 그 시장의 봉이 최근 60일에 하나도 없으면 「봉 있음」을 말할 근거가 없어 증거로 본다.
 *
 * <p>MUST: 잡이 <b>시작 때 한 번</b> 대상 전체로 묻는다 — 종목마다 물으면 1,350번 질의가 붙는다.
 */
@Component
public class RetirementEvidence {

    /** 봉이 없으면 상장폐지 증거로 보는 최근 거래일 수. 주말·공휴일 하나가 껴도 정상 종목이 걸리지 않게 일주일 몫이다. */
    static final int RECENT_TRADING_DAYS = 5;

    private final JdbcTemplate jdbcTemplate;

    public RetirementEvidence(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 상장폐지 증거가 있는 종목. 대상이 비면 비어 있다. */
    @Transactional(readOnly = true)
    public Set<Long> retiredAmong(Collection<Long> stockIds) {
        if (stockIds.isEmpty()) {
            return Set.of();
        }
        String ids = stockIds.stream().map(String::valueOf).collect(Collectors.joining(",", "{", "}"));
        return new HashSet<>(jdbcTemplate.queryForList("""
                WITH target AS (SELECT unnest(?::bigint[]) AS stock_id),
                current_member AS (
                    SELECT DISTINCT m.stock_id FROM universe_membership m
                    JOIN universe u ON u.code = m.universe_code AND u.is_active
                    WHERE m.effective_to IS NULL AND m.stock_id IN (SELECT stock_id FROM target)),
                market_dates AS (
                    SELECT market_code, trade_date,
                           dense_rank() OVER (PARTITION BY market_code ORDER BY trade_date DESC) AS r
                    FROM (SELECT DISTINCT s.market_code, p.trade_date FROM price_daily p
                          JOIN stock s ON s.id = p.stock_id
                          WHERE p.trade_date > current_date - 60) d),
                recent_since AS (
                    SELECT market_code, min(trade_date) AS since FROM market_dates WHERE r <= ? GROUP BY market_code),
                last_bar AS (
                    SELECT p.stock_id, max(p.trade_date) AS last_date FROM price_daily p
                    WHERE p.stock_id IN (SELECT stock_id FROM target) GROUP BY p.stock_id)
                SELECT t.stock_id FROM target t
                JOIN stock s ON s.id = t.stock_id
                LEFT JOIN recent_since rs ON rs.market_code = s.market_code
                LEFT JOIN last_bar lb ON lb.stock_id = t.stock_id
                WHERE t.stock_id NOT IN (SELECT stock_id FROM current_member)
                   OR rs.since IS NULL OR lb.last_date IS NULL OR lb.last_date < rs.since
                """, Long.class, ids, RECENT_TRADING_DAYS));
    }
}
