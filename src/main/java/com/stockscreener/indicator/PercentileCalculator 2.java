package com.stockscreener.indicator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * 지표 값의 유니버스 내 백분위를 산출한다.
 *
 * <p>개별 지표 계산이 <b>전부 끝난 뒤</b> 하루 단위로 한 번 돈다. 종목 단위 러너
 * ({@link IndicatorRunner})와 분리한 이유는 계산의 단위가 다르기 때문이다 — 백분위는 같은 날
 * 모든 종목의 값이 있어야 정해지므로, 종목 루프 안에서는 원리적으로 계산할 수 없다.
 *
 * <p>SQL로 계산하는 이유는 이것이 순수한 집합 연산이기 때문이다. 850종목 × 지표 수만큼의 행을
 * 애플리케이션으로 올려 정렬한 뒤 다시 내려보내는 것보다, 이미 정렬된 인덱스를 가진 DB에서
 * {@code PERCENT_RANK()}로 처리하는 편이 단순하고 빠르다.
 *
 * <p>IMPORTANT — 백분위는 항상 <b>오름차순 기준</b>이다. 값이 작을수록 0에 가깝다.
 * "PER 하위 20%"는 {@code PER_PCTL <= 0.2}가 되고, "ROE 상위 20%"는
 * {@code ROE_PCTL >= 0.8}이 된다. 지표마다 유불리 방향이 다르다고 해서 여기서 뒤집지 않는다 —
 * 방향은 {@code indicator.higher_is_better}가 이미 들고 있고, 저장 규칙까지 지표마다
 * 달라지면 조건을 읽는 사람이 매번 그 지표의 방향을 확인해야 한다.
 */
@Component
public class PercentileCalculator {

    private static final Logger log = LoggerFactory.getLogger(PercentileCalculator.class);

    /**
     * 같은 날짜·같은 지표의 값들에 순위를 매겨 {@code <원본코드>_PCTL}로 저장한다.
     *
     * <p>대상은 <b>현재 편입 중인 종목</b>으로 한정한다. 지수에서 빠진 종목이 모집단에 남으면
     * "코스피200 내 상위 20%"가 실제로는 다른 모집단에 대한 순위가 된다.
     *
     * <p>{@code PERCENT_RANK()}는 최솟값에 0, 최댓값에 1을 준다. 종목이 하나뿐이면 0이 되는데,
     * 그 경우 순위 자체가 무의미하므로 아래 {@code HAVING}으로 걸러낸다.
     */
    private static final String UPSERT_PERCENTILES = """
            INSERT INTO indicator_value (stock_id, trade_date, indicator_code, value, updated_at)
            SELECT ranked.stock_id, ranked.trade_date, ?, ranked.pct, now()
            FROM (
                SELECT iv.stock_id,
                       iv.trade_date,
                       PERCENT_RANK() OVER (ORDER BY iv.value) AS pct
                FROM indicator_value iv
                WHERE iv.indicator_code = ?
                  AND iv.trade_date = ?
                  AND EXISTS (
                      SELECT 1 FROM universe_membership m
                      WHERE m.stock_id = iv.stock_id AND m.effective_to IS NULL
                  )
            ) ranked
            ON CONFLICT (trade_date, indicator_code, stock_id) DO UPDATE SET
                value      = EXCLUDED.value,
                updated_at = now()
            """;

    private final JdbcTemplate jdbcTemplate;
    private final List<PercentileSpec> specs;

    public PercentileCalculator(JdbcTemplate jdbcTemplate, List<PercentileSpec> specs) {
        this.jdbcTemplate = jdbcTemplate;
        this.specs = List.copyOf(specs);
    }

    /**
     * 해당 날짜의 모든 백분위 지표를 계산해 저장한다.
     *
     * @return 저장한 행 수
     */
    public long calculate(LocalDate tradeDate) {
        long written = 0;
        for (PercentileSpec spec : specs) {
            // 모집단이 2종목 미만이면 순위가 의미 없다. 그런 날은 자연히 0행이 된다.
            int rows = jdbcTemplate.update(
                    UPSERT_PERCENTILES, spec.code(), spec.sourceCode(), tradeDate);
            if (rows > 0) {
                written += rows;
            }
        }
        log.info("백분위를 계산했다. tradeDate={} 지표={}개 저장={}행",
                tradeDate, specs.size(), written);
        return written;
    }

    /** 등록된 백분위 선언. 카탈로그 동기화가 이것을 읽어 지표 행을 만든다. */
    public List<PercentileSpec> specs() {
        return specs;
    }
}
