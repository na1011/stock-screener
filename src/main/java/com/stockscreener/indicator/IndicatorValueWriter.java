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
     *
     * <p>IMPORTANT — {@code WHERE ... IS DISTINCT FROM}을 지우지 않는다. 값이 같은데도 갱신하면
     * MVCC가 행 버전을 새로 만들고, {@code idx_iv_screen}이 {@code value}를 INCLUDE해
     * <b>HOT 갱신이 불가능</b>하므로 인덱스 3개에도 새 엔트리가 생긴다. 러너가 매일
     * lookback 창 전체를 재계산해 거의 같은 값을 다시 쓰기 때문에 이 낭비가 그대로 쌓인다 —
     * 실제로 <b>1.39M행짜리 테이블에 누적 갱신 2억 3천만 건</b>, 인덱스만 1,987MB가 됐다.
     * 있어도 컴파일되고 테스트도 통과하며, 느려지는 것은 몇 달 뒤다.
     *
     * <p>{@code IS DISTINCT FROM}인 이유는 {@code <>}가 NULL에서 NULL을 내기 때문이다. 지금은
     * {@code value}가 NOT NULL이지만 그 전제에 기대지 않는다.
     */
    private static final String UPSERT = """
            INSERT INTO indicator_value
                (stock_id, trade_date, indicator_code, value, updated_at)
            VALUES (?, ?, ?, ?, now())
            ON CONFLICT (trade_date, indicator_code, stock_id) DO UPDATE SET
                value      = EXCLUDED.value,
                updated_at = now()
            WHERE indicator_value.value IS DISTINCT FROM EXCLUDED.value
            """;

    private final JdbcTemplate jdbcTemplate;

    public IndicatorValueWriter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 한 종목·한 지표의 계산 결과를 저장한다.
     *
     * @return <b>값이 실제로 달라진</b> 행 수. 이미 같은 값이 있던 행은 세지 않는다 —
     *         재계산이 대부분 같은 값을 다시 내므로, 같은 날을 두 번 돌리면 0에 가깝다.
     *         "몇 행을 조회했나"가 아니라 "무엇이 바뀌었나"를 답하는 수다.
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
