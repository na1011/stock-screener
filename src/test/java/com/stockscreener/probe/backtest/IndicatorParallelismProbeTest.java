package com.stockscreener.probe.backtest;

import com.stockscreener.indicator.IndicatorDataReader.FinancialVisibility;
import com.stockscreener.indicator.IndicatorRunner;
import com.stockscreener.support.ReadOnlyDatabaseGuard;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>임시 탐침 — 답이 나오면 지운다.</b> 지표 계산의 종목 루프가 스레드 수에 따라 얼마나
 * 확장되는가를 <b>프로덕션 코드를 고치기 전에</b> 잰다(계획서 §0단계).
 *
 * <p>{@link IndicatorRunner#computeAt}을 쓴다 — <b>저장도 삭제도 하지 않는</b> 경로다.
 * 그래서 운영 DB에 쓰기가 0이고(프로파일의 {@code readOnlyMode=always}가 한 번 더 막는다),
 * {@code readRawData} + {@code compute}라는 <b>비용의 대부분</b>은 그대로 태운다.
 * <b>빠지는 것은 UPSERT/DELETE뿐이다 — 이 탐침은 CPU 확장성만 답하고 DB 쓰기 경합은
 * 답하지 못한다.</b>
 *
 * <p>이 클래스를 두 번 돌린다. 한 번은 메모이저가 <b>싱글턴 크기-1 {@code synchronized}
 * 캐시</b>인 지금 상태(A행), 한 번은 그것을 스레드별로 옮긴 뒤(B행). A행이 대조군이다.
 */
@Tag("backtest")
@SpringBootTest(classes = BacktestHarnessContext.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        // 하네스 기본 풀이 2다("단일 스레드로 as-of를 순회한다"). K스레드가 각자 읽으므로 올린다.
        properties = "spring.datasource.hikari.maximum-pool-size=12")
@ActiveProfiles("backtest")
@Import(ReadOnlyDatabaseGuard.class)
class IndicatorParallelismProbeTest {

    /** 측정할 스레드 수. */
    private static final int[] WORKERS = {1, 2, 4, 6, 8};

    /** 한 판에 도는 종목 수. 1·2·4·8로 나누어떨어지고 6에서도 편차가 작게 잡았다. */
    private static final int STOCKS = 160;

    /** JIT 예열. 없으면 1스레드 판이 컴파일 비용을 혼자 뒤집어쓴다. */
    private static final int WARMUP_STOCKS = 24;

    @Autowired
    private IndicatorRunner indicatorRunner;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("종목 루프가 스레드 수에 따라 얼마나 확장되는가 — 구현 전 실측")
    void measuresStockLoopScaling() throws Exception {
        LocalDate asOf = busiestRecentTradeDate();
        List<Long> stockIds = activeStocksWithBarOn(asOf, STOCKS);
        assertThat(stockIds)
                .as("대상이 비면 잰 것이 없다 — 0건을 초록으로 접지 않는다")
                .hasSizeGreaterThanOrEqualTo(WORKERS[WORKERS.length - 1] * 4);

        Map<String, NavigableMap<LocalDate, BigDecimal>> marketIndexes =
                indicatorRunner.marketIndexesFor(asOf);

        System.out.printf("%n=== 지표 종목 루프 확장성 · asOf=%s · 종목=%d · 코어=%d ===%n",
                asOf, stockIds.size(), Runtime.getRuntime().availableProcessors());

        // 예열. 측정에 안 센다.
        runWith(1, stockIds.subList(0, Math.min(WARMUP_STOCKS, stockIds.size())),
                asOf, marketIndexes);

        long baseline = 0;
        for (int workers : WORKERS) {
            Result result = runWith(workers, stockIds, asOf, marketIndexes);
            if (workers == 1) {
                baseline = result.millis();
            }
            System.out.printf(
                    "workers=%d  %,7dms  배수=%.2f  종목/초=%.1f  값합=%d  스레드=%d%n",
                    workers, result.millis(), baseline / (double) result.millis(),
                    stockIds.size() * 1000.0 / result.millis(),
                    result.valueCount(), result.distinctThreads());

            // 병렬 판이 정말 여러 스레드를 태웠는지 — 초록만으로는 못 가른다.
            assertThat(result.distinctThreads())
                    .as("workers=%d인데 실제로 쓴 스레드가 %d개다", workers, result.distinctThreads())
                    .isEqualTo(workers);
        }
    }

    /**
     * 한 판. 같은 종목 집합을 {@code workers}개 스레드로 나눠 {@code computeAt}을 부른다.
     *
     * <p>MUST: 값의 총량을 함께 센다. 스레드 수를 바꿔도 같아야 하고, 달라지면 그 자체가
     * 발견이다 — 「빨라졌다」만 보면 값이 갈린 것을 놓친다.
     */
    private Result runWith(int workers, List<Long> stockIds, LocalDate asOf,
                           Map<String, NavigableMap<LocalDate, BigDecimal>> marketIndexes)
            throws Exception {
        AtomicLong values = new AtomicLong();
        Map<String, Boolean> threads = new ConcurrentHashMap<>();
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        long startedAt = System.nanoTime();
        try {
            List<Future<?>> futures = new ArrayList<>(stockIds.size());
            for (long stockId : stockIds) {
                futures.add(pool.submit(() -> {
                    threads.put(Thread.currentThread().getName(), Boolean.TRUE);
                    values.addAndGet(indicatorRunner
                            .computeAt(stockId, asOf, marketIndexes, FinancialVisibility.KNOWN_ON)
                            .size());
                }));
            }
            for (Future<?> future : futures) {
                future.get();
            }
        } finally {
            pool.shutdown();
            if (!pool.awaitTermination(30, TimeUnit.SECONDS)) {
                pool.shutdownNow();
            }
        }
        return new Result(Duration.ofNanos(System.nanoTime() - startedAt).toMillis(),
                values.get(), threads.size());
    }

    private record Result(long millis, long valueCount, int distinctThreads) {
    }

    /** 최근 거래일 중 봉이 가장 많은 날. 시장이 엇갈려 쉬는 날을 피한다. */
    private LocalDate busiestRecentTradeDate() {
        return jdbcTemplate.queryForObject("""
                SELECT trade_date FROM price_daily
                WHERE trade_date >= current_date - INTERVAL '30 days'
                GROUP BY trade_date ORDER BY count(*) DESC, trade_date DESC LIMIT 1
                """, LocalDate.class);
    }

    /**
     * 그날 실제로 봉이 있는 활성 유니버스 종목.
     *
     * <p>{@code computeAt}은 <b>마지막 봉이 {@code asOf}와 정확히 같을 때만</b> 값을 내므로
     * (거래정지 종목이 화면과 어긋나지 않게 한 계약), 그 조인이 곧 대상 조건이다.
     */
    private List<Long> activeStocksWithBarOn(LocalDate asOf, int limit) {
        return jdbcTemplate.queryForList("""
                SELECT DISTINCT um.stock_id
                FROM universe_membership um
                JOIN universe u ON u.code = um.universe_code AND u.is_active
                JOIN price_daily pd ON pd.stock_id = um.stock_id AND pd.trade_date = ?
                WHERE um.effective_to IS NULL
                ORDER BY um.stock_id
                LIMIT ?
                """, Long.class, asOf, limit);
    }
}
