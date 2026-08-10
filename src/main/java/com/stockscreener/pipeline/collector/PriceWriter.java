package com.stockscreener.pipeline.collector;

import com.stockscreener.pipeline.datasource.RawPriceBar;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

/**
 * 수집한 시세를 {@code price_daily}에 쓴다. 벤더를 모른다.
 *
 * <p>{@code ON CONFLICT}로 갱신하는 이유는 배치가 멱등해야 하기 때문이다. 같은 날 두 번
 * 돌려도 행이 늘지 않고, 소스가 뒤늦게 정정한 값이 반영된다.
 *
 * <p>MUST NOT: 계산할 수 없는 값을 채워 넣지 않는다. 거래대금이 없으면 NULL로 두며
 * {@code close × volume}으로 합성하지 않는다 — 다른 값이고, 실측과 합성이 섞이면
 * 파생 지표가 시장마다 미묘하게 달라진다.
 */
@Component
public class PriceWriter {

    private static final Logger log = LoggerFactory.getLogger(PriceWriter.class);

    /** {@code adj_close numeric(18,6)}에 맞춘다. 더 정밀하게 계산해도 저장에서 잘린다. */
    private static final int ADJ_CLOSE_SCALE = 6;

    /** application.yaml의 {@code hibernate.jdbc.batch_size}와 맞춘다. */
    private static final int BATCH_SIZE = 100;

    private static final String UPSERT = """
            INSERT INTO price_daily
                (stock_id, trade_date, open, high, low, close, adj_close, adj_factor,
                 volume, trade_value, listed_shares, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now(), now())
            ON CONFLICT (stock_id, trade_date) DO UPDATE SET
                open          = EXCLUDED.open,
                high          = EXCLUDED.high,
                low           = EXCLUDED.low,
                close         = EXCLUDED.close,
                volume        = EXCLUDED.volume,
                trade_value   = EXCLUDED.trade_value,
                listed_shares = EXCLUDED.listed_shares,
                -- MUST NOT: 재적재가 adj_factor를 EXCLUDED로 덮지 않는다. 수집이 낸 값은
                -- 언제나 1이므로(수집 시점에는 미래의 분할을 알 수 없다) 덮으면 그 날짜의
                -- 수정계수가 1로 **되돌아간다**. 일일 갱신이 창으로 돌아 매일 과거 열흘치를
                -- 다시 쓰는 이상(ADR-0042), 분할 기준일이 창 안에 있는 며칠 동안 계수가
                -- 매일 리셋된다. 수정계수는 봉의 속성이 아니라 **이후 기업 행위의 함수**이고
                -- AdjustmentRecalculator가 그 정본이다.
                adj_factor    = price_daily.adj_factor,
                adj_close     = round(EXCLUDED.close * price_daily.adj_factor, 6),
                updated_at    = now()
            """;

    private final JdbcTemplate jdbcTemplate;

    public PriceWriter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 한 종목의 시세를 저장한다.
     *
     * <p>{@code adj_factor}는 신규 저장 시 1이다. 분할이 발견되면
     * {@link AdjustmentRecalculator}가 과거 구간을 소급해 다시 쓴다 — 수집 시점에는
     * 미래의 분할을 알 수 없으므로 이것이 유일하게 옳은 초기값이다.
     *
     * <p><b>재적재는 그 계수를 보존한다</b>(위 UPSERT의 주석). 초기값이 옳다는 것과 그것으로
     * 기존 값을 덮어도 된다는 것은 다르다.
     *
     * <p>손상된 봉({@link RawPriceBar#isValid()} 실패)은 건너뛰고 <b>로그를 남긴다.</b>
     * DB 제약에 맡기면 손상된 봉 하나가 그 종목의 정상 봉까지 트랜잭션과 함께 날린다.
     * 조용히 버리지 않는 이유는 대량 발생 시 소스 문제의 신호이기 때문이다.
     *
     * @return 실제로 저장한 행 수
     */
    public long write(long stockId, List<RawPriceBar> bars) {
        if (bars.isEmpty()) {
            return 0L;
        }

        List<RawPriceBar> valid = new ArrayList<>(bars.size());
        List<RawPriceBar> rejected = new ArrayList<>();
        for (RawPriceBar bar : bars) {
            (bar.isValid() ? valid : rejected).add(bar);
        }
        if (!rejected.isEmpty()) {
            log.warn("OHLC 정합성 위반으로 {}봉을 건너뛴다. stockId={} 첫 위반일={}",
                    rejected.size(), stockId, rejected.get(0).tradeDate());
        }
        if (valid.isEmpty()) {
            return 0L;
        }

        // 배치 크기를 나눠 넘기는 오버로드라 반환이 int[][]다 — 배치마다 한 배열이다.
        int[][] results = jdbcTemplate.batchUpdate(UPSERT, valid, BATCH_SIZE, (ps, bar) -> {
            ps.setLong(1, stockId);
            ps.setObject(2, bar.tradeDate());
            ps.setBigDecimal(3, bar.open());
            ps.setBigDecimal(4, bar.high());
            ps.setBigDecimal(5, bar.low());
            ps.setBigDecimal(6, bar.close());
            // 신규 저장 시 adj_factor = 1이므로 adj_close = close다.
            ps.setBigDecimal(7, bar.close().setScale(ADJ_CLOSE_SCALE, RoundingMode.HALF_UP));
            ps.setBigDecimal(8, BigDecimal.ONE);
            ps.setLong(9, bar.volume());
            if (bar.tradeValue() == null) {
                ps.setNull(10, Types.NUMERIC);
            } else {
                ps.setBigDecimal(10, bar.tradeValue());
            }
            if (bar.listedShares() == null) {
                ps.setNull(11, Types.NUMERIC);
            } else {
                ps.setBigDecimal(11, bar.listedShares());
            }
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
