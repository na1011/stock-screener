package com.stockscreener.indicator;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 계산된 지표 값을 {@code indicator_value}에 쓴다.
 *
 * <p>{@code ON CONFLICT}로 갱신하는 이유는 배치가 멱등해야 하기 때문이다. 같은 날짜로 두 번
 * 돌려도 행이 늘지 않고, 원자료가 정정되어 다시 계산한 값이 반영된다.
 *
 * <p>MUST NOT: 계산할 수 없는 날짜에 행을 만들지 않는다(ADR-0002). NULL 행을 쓰면 조건 판정의
 * {@code EXISTS}가 참이 되어 "값이 없는 종목"과 "조건을 만족하지 않는 종목"이 구별되지 않는다.
 * 계산기가 빈 목록을 주면 여기서도 아무것도 쓰지 않는 것이 그 계약의 이행이다.
 */
@Component
public class IndicatorValueWriter {

    /** application.yaml의 {@code hibernate.jdbc.batch_size}와 맞춘다. */
    private static final int BATCH_SIZE = 100;

    /**
     * IMPORTANT — {@code ON CONFLICT}의 컬럼 순서는 PK 순서
     * {@code (trade_date, indicator_code, stock_id)}와 같아야 한다(ADR-0002 MUST).
     * 순서를 바꾸면 PostgreSQL이 이 인덱스를 충돌 판정에 쓰지 못한다.
     */
    private static final String UPSERT = """
            INSERT INTO indicator_value
                (stock_id, trade_date, indicator_code, value, updated_at)
            VALUES (?, ?, ?, ?, now())
            ON CONFLICT (trade_date, indicator_code, stock_id) DO UPDATE SET
                value      = EXCLUDED.value,
                updated_at = now()
            """;

    private final JdbcTemplate jdbcTemplate;

    public IndicatorValueWriter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 한 종목·한 지표의 계산 결과를 저장한다.
     *
     * @return 실제로 저장한 행 수
     */
    public long write(long stockId, String indicatorCode, List<IndicatorPoint> points) {
        if (points.isEmpty()) {
            return 0L;
        }

        int[][] results = jdbcTemplate.batchUpdate(UPSERT, points, BATCH_SIZE, (ps, point) -> {
            ps.setLong(1, stockId);
            ps.setObject(2, point.tradeDate());
            ps.setString(3, indicatorCode);
            ps.setBigDecimal(4, point.value());
        });

        long written = 0;
        for (int[] batch : results) {
            for (int updated : batch) {
                // 드라이버가 SUCCESS_NO_INFO(-2)를 주는 경우가 있어 음수를 1행으로 센다.
                written += updated < 0 ? 1 : updated;
            }
        }
        return written;
    }
}
