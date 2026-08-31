package com.stockscreener.probe.backtest;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Collection;
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
 *
 * <p><b>종료일을 시장으로 좁힌다</b>(ADR-0108). {@link AsOfCalendar#onOrBefore}와 같은 질의라
 * 같은 함정이 있다 — 미국 전용 거래일(실측 176일)이 종료일로 잡히면 아래 자기조인
 * ({@code e.trade_date = ?})이 <b>국내 종목을 전부 떨어뜨려</b> {@code returns}가 조용히 빈다.
 * 「수익률 있는 종목 0」으로만 드러나고 어떤 에러도 나지 않는다.
 */
@Component
public class ForwardReturns {

    private final JdbcTemplate jdbcTemplate;
    private final AsOfCalendar calendar;

    public ForwardReturns(JdbcTemplate jdbcTemplate, AsOfCalendar calendar) {
        this.jdbcTemplate = jdbcTemplate;
        this.calendar = calendar;
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
     * <p>종료일은 <b>그 시장에서</b> 그 날짜 이하의 마지막 거래일이다. 이후로 잡으면 그만큼
     * 미래를 본다.
     *
     * @param marketCodes 종료일을 고를 시장. <b>호출부가 반드시 고른다</b> — 위 javadoc 참조
     */
    public Window measure(LocalDate asOf, int months, Collection<String> marketCodes) {
        LocalDate end = calendar.onOrBefore(asOf.plusMonths(months), marketCodes);
        if (end == null) {
            return new Window(asOf, null, Map.of(), Set.of());
        }

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
