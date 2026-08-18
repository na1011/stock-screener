package com.stockscreener.probe.backtest;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * as-of부터 6개월 뒤까지의 사후 수익률. 조건 단위 측정의 종속변수다(P17, ADR-0071).
 *
 * <p><b>{@code adj_close}로 잰다.</b> 액면분할·무상증자가 낀 종목을 {@code close}로 재면
 * 수익률이 −50%나 +100%로 나오는데 값이 그럴듯해 드러나지 않는다.
 *
 * <p><b>미보정 기업 행위는 종목이 아니라 종목·기간으로 뺀다</b>(사전등록 그대로). 원장이
 * 비율을 못 낸 사건({@code UNSUPPORTED}·{@code OBSERVED})은 그 자리에서 {@code adj_close}가
 * 실제로 끊겨 있어 −81%~+328%의 가짜 수익률을 낸다. 그러나 <b>7종목 중 셋은 백테스트 창 밖
 * 사건</b>(2020-08·2021-01·2021-11)이라 창 안에서는 멀쩡하다 — 종목 통째로 빼면 표본이
 * 조용히 좁아진다.
 */
@Component
public class ForwardReturns {

    private final JdbcTemplate jdbcTemplate;

    public ForwardReturns(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 한 구간의 수익률과 그 구간에서 빼야 할 종목.
     *
     * @param returns  종목 → 수익률(0.1 = +10%). 시작·종료 봉이 없으면 담기지 않는다
     * @param excluded 미보정 기업 행위가 <b>이 구간에</b> 걸린 종목. 결과에 수를 함께 낸다
     */
    public record Window(LocalDate asOf, LocalDate end,
                         Map<Long, Double> returns, Set<Long> excluded) {
    }

    /**
     * {@code asOf}부터 {@code months}개월 뒤까지.
     *
     * <p>종료일은 <b>그 날짜 이하의 마지막 거래일</b>이다. 이후로 잡으면 그만큼 미래를 본다.
     */
    public Window measure(LocalDate asOf, int months) {
        LocalDate end = jdbcTemplate.queryForObject(
                "SELECT max(trade_date) FROM price_daily WHERE trade_date <= ?",
                LocalDate.class, asOf.plusMonths(months));

        Map<Long, Double> returns = new HashMap<>();
        jdbcTemplate.query("""
                SELECT s.stock_id,
                       s.adj_close AS start_price,
                       e.adj_close AS end_price
                FROM price_daily s
                JOIN price_daily e ON e.stock_id = s.stock_id AND e.trade_date = ?
                WHERE s.trade_date = ?
                """, rs -> {
            double start = rs.getDouble("start_price");
            double finish = rs.getDouble("end_price");
            // 시작가가 0이면 비율이 뜻을 잃는다. 0으로 두면 무한대가 평균을 통째로 먹는다.
            if (start > 0) {
                returns.put(rs.getLong("stock_id"), finish / start - 1.0);
            }
        }, end, asOf);

        Set<Long> excluded = new HashSet<>(jdbcTemplate.queryForList("""
                SELECT DISTINCT stock_id FROM corporate_action
                WHERE status IN ('OBSERVED', 'UNSUPPORTED')
                  AND effective_date >= ? AND effective_date <= ?
                """, Long.class, asOf, end));

        return new Window(asOf, end, returns, excluded);
    }
}
