package com.stockscreener.pipeline.collector;

import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.DataSourceRegistry;
import com.stockscreener.pipeline.datasource.MarketIndexDataSource;
import com.stockscreener.pipeline.datasource.RawIndexBar;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * 시장 지수 시계열을 받아 {@code market_index_daily}에 적는다.
 *
 * <p>{@link PriceCollector}와 나란히 있지만 <b>실패의 단위가 다르다.</b> 시세는 한 종목이
 * 실패해도 나머지가 저장되지만, 지수는 한 줄이 없으면 그날 <b>전 종목의 {@code ICHI_*}가
 * 통째로 안 쓰인다</b>(ADR-0046 §승인된 이탈 2). 그래서 부분 결과를 삼키지 않고 예외로 올린다.
 *
 * <p>MUST NOT: 손상된 봉을 통째로 버리면서 조용히 넘어가지 않는다. 지수 종가가 0 이하로
 * 오는 것은 수집 결함이므로 몇 건인지 로그에 남긴다 — 저장하면 26주 수익률의 분모가 되어
 * <b>조용히 0%</b>가 된다.
 */
@Component
public class MarketIndexCollector {

    private static final Logger log = LoggerFactory.getLogger(MarketIndexCollector.class);

    /**
     * IMPORTANT — {@code ON CONFLICT}의 컬럼 순서는 PK 순서 {@code (index_code, trade_date)}와
     * 같아야 한다. 그리고 {@code IS DISTINCT FROM} 가드를 지우지 않는다 — 수집이 15일 창으로
     * 돌아 같은 행을 평일마다 11~12번 다시 쓰기 때문이다({@code IndicatorValueWriter}와 같은 자리).
     */
    private static final String UPSERT = """
            INSERT INTO market_index_daily
                (index_code, trade_date, open, high, low, close, volume, trade_value,
                 constituent_count, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, now())
            ON CONFLICT (index_code, trade_date) DO UPDATE SET
                open              = EXCLUDED.open,
                high              = EXCLUDED.high,
                low               = EXCLUDED.low,
                close             = EXCLUDED.close,
                volume            = EXCLUDED.volume,
                trade_value       = EXCLUDED.trade_value,
                constituent_count = EXCLUDED.constituent_count,
                updated_at        = now()
            WHERE market_index_daily.close  IS DISTINCT FROM EXCLUDED.close
               OR market_index_daily.open   IS DISTINCT FROM EXCLUDED.open
               OR market_index_daily.high   IS DISTINCT FROM EXCLUDED.high
               OR market_index_daily.low    IS DISTINCT FROM EXCLUDED.low
               OR market_index_daily.volume IS DISTINCT FROM EXCLUDED.volume
            """;

    private static final int BATCH_SIZE = 100;

    private final DataSourceRegistry registry;
    private final JdbcTemplate jdbcTemplate;

    public MarketIndexCollector(DataSourceRegistry registry, JdbcTemplate jdbcTemplate) {
        this.registry = registry;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 한 지수의 {@code [from, to]} 구간을 받아 저장한다.
     *
     * @return 값이 실제로 달라진 행 수. 같은 값을 다시 받은 행은 세지 않는다 —
     *         창으로 도는 수집에서 0이 정상이다
     */
    @Transactional(rollbackFor = Exception.class)
    public long collect(String indexCode, LocalDate from, LocalDate to) throws DataSourceException {
        MarketIndexDataSource source = registry.marketIndexSource(indexCode);
        List<RawIndexBar> bars = source.fetchDailyBars(indexCode, from, to);

        List<RawIndexBar> valid = bars.stream().filter(RawIndexBar::isValid).toList();
        if (valid.size() < bars.size()) {
            // 조용히 버리지 않는다. 지수 종가가 0 이하인 것은 벤더 결함의 신호다.
            log.warn("지수 봉 {}개 중 {}개가 정합성 위반이라 버렸다. index={} {}~{}",
                    bars.size(), bars.size() - valid.size(), indexCode, from, to);
        }
        if (valid.isEmpty()) {
            log.info("받은 지수 봉이 없다. index={} {}~{} — 휴장이거나 소스가 아직 안 냈다.",
                    indexCode, from, to);
            return 0L;
        }

        int[][] results = jdbcTemplate.batchUpdate(UPSERT, valid, BATCH_SIZE, (ps, bar) -> {
            ps.setString(1, indexCode);
            ps.setObject(2, bar.tradeDate());
            ps.setBigDecimal(3, bar.open());
            ps.setBigDecimal(4, bar.high());
            ps.setBigDecimal(5, bar.low());
            ps.setBigDecimal(6, bar.close());
            ps.setObject(7, bar.volume());
            ps.setBigDecimal(8, bar.tradeValue());
            ps.setObject(9, bar.constituentCount());
        });

        long written = 0;
        for (int[] batch : results) {
            for (int updated : batch) {
                // 드라이버가 SUCCESS_NO_INFO(-2)를 주는 경우가 있어 음수를 1행으로 센다.
                written += updated < 0 ? 1 : updated;
            }
        }
        log.info("지수를 적재했다. index={} {}~{} 수신={}봉 반영={}행",
                indexCode, from, to, valid.size(), written);
        return written;
    }
}
