package com.stockscreener.indicator.calculator;

import com.stockscreener.indicator.IndicatorContext;
import com.stockscreener.indicator.PriceBar;
import com.stockscreener.indicator.calculator.ichimoku.IchimokuAnalyses;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 메모이저 둘의 캐시가 <b>스레드별</b>인가를 잰다(ADR-0138).
 *
 * <p>{@code IndicatorBackfillJob}이 종목 루프를 팬아웃하므로 워커들이 <b>서로 다른 종목</b>을
 * 동시에 돈다. 캐시가 싱글턴 필드 하나면 서로를 밀어내 전부 미스가 되고, 계산이 락 안이라
 * 재계산이 전 워커에 걸쳐 직렬화된다 — <b>정확성은 그대로라 값으로는 드러나지 않는다.</b>
 * 실측으로 확장이 4워커 2.56배에서 천장을 치고 8워커에서 1.85배로 <b>되돌아갔다</b>
 * (2026-09-11 · 160종목 × 1거래일).
 *
 * <h2>거짓 초록이 나는 자리가 둘이다 — 이 시험의 설계가 거기서 나왔다</h2>
 * <ol>
 *   <li>{@link WeeklyMacdVSeries}는 완결 주가 {@code MIN_COMPLETED_WEEKS}(200) 이하면
 *       {@code Collections.emptyNavigableMap()}을 내는데 그것은 <b>JDK 싱글턴</b>이다. 두
 *       스레드가 같은 인스턴스를 받아 {@code assertSame}이 그냥 통과한다 — 그래서 봉을
 *       <b>201주 이상</b> 준다.
 *   <li>{@link IchimokuAnalyses}는 빈 {@code priceBars} 조기 반환이 같은 싱글턴이다 —
 *       그래서 {@code IndicatorContext.empty}를 쓰지 않는다.
 * </ol>
 *
 * <p><b>MUST: 두 스레드의 첫 값에 {@code assertNotSame}을 먼저 단언한다.</b> 그것이 픽스처가
 * 실제로 서로 다른 인스턴스를 만들었다는 증거이고, 없으면 위 싱글턴 경로를 못 가른다.
 */
class MemoizerThreadIsolationTest {

    /** 일봉 수. 5로 나눠 240주라 {@code MIN_COMPLETED_WEEKS}(200)를 넘는다. */
    private static final int BARS = 1_200;

    @Test
    @DisplayName("IchimokuAnalyses — 다른 종목을 도는 옆 스레드가 내 캐시를 밀어내지 않는다")
    void ichimokuMemoIsPerThread() throws Exception {
        IchimokuAnalyses analyses = new IchimokuAnalyses();
        assertMemoSurvivesInterleaving(analyses::of);
    }

    @Test
    @DisplayName("WeeklyMacdVSeries — 다른 종목을 도는 옆 스레드가 내 캐시를 밀어내지 않는다")
    void weeklyMacdVMemoIsPerThread() throws Exception {
        WeeklyMacdVSeries series = new WeeklyMacdVSeries();
        assertMemoSurvivesInterleaving(series::of);
    }

    /**
     * 두 스레드가 <b>서로 다른 종목</b>으로 교대한다. 각자 한 번 부르고, 배리어에서 둘 다
     * 캐시에 쓴 것을 확인한 뒤, 다시 부른다.
     *
     * <p>캐시가 공유면 뒤에 쓴 쪽이 앞을 밀어내므로 <b>적어도 한 스레드의 두 번째 호출이 새
     * 인스턴스</b>가 된다. 스레드별이면 둘 다 자기 것을 그대로 받는다.
     */
    private void assertMemoSurvivesInterleaving(Function<IndicatorContext, ?> memoize)
            throws Exception {
        IndicatorContext first = context(1L, 100.0);
        IndicatorContext second = context(2L, 250.0);

        CyclicBarrier bothCached = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Object[]> a = pool.submit(callable(memoize, first, bothCached));
            Future<Object[]> b = pool.submit(callable(memoize, second, bothCached));
            Object[] fromA = a.get(60, TimeUnit.SECONDS);
            Object[] fromB = b.get(60, TimeUnit.SECONDS);

            // 픽스처 검증이 먼저다. 둘이 같은 인스턴스면(= 빈 싱글턴) 아래 단언은 아무것도
            // 검사하지 않는다.
            assertThat(fromA[0])
                    .as("두 종목이 같은 인스턴스를 받았다 — 빈 싱글턴 경로를 탔다는 뜻이고,"
                            + " 그러면 이 시험은 아무것도 검사하지 못한다")
                    .isNotSameAs(fromB[0]);

            assertThat(fromA[1]).as("스레드 A의 캐시를 옆 스레드가 밀어냈다").isSameAs(fromA[0]);
            assertThat(fromB[1]).as("스레드 B의 캐시를 옆 스레드가 밀어냈다").isSameAs(fromB[0]);
        } finally {
            pool.shutdownNow();
        }
    }

    private Callable<Object[]> callable(Function<IndicatorContext, ?> memoize,
                                        IndicatorContext context, CyclicBarrier bothCached) {
        return () -> {
            Object beforeBarrier = memoize.apply(context);
            bothCached.await(60, TimeUnit.SECONDS);
            Object afterBarrier = memoize.apply(context);
            return new Object[] {beforeBarrier, afterBarrier};
        };
    }

    /** 평일만으로 {@link #BARS}개의 봉을 만든다. 가격은 종목마다 다르게 흔든다. */
    private IndicatorContext context(long stockId, double base) {
        List<PriceBar> bars = new ArrayList<>(BARS);
        LocalDate date = LocalDate.of(2020, 1, 6); // 월요일
        for (int i = 0; i < BARS; i++) {
            while (date.getDayOfWeek() == DayOfWeek.SATURDAY
                    || date.getDayOfWeek() == DayOfWeek.SUNDAY) {
                date = date.plusDays(1);
            }
            double close = base + Math.sin(i / 9.0) * (base / 10.0) + i * 0.01;
            bars.add(new PriceBar(date,
                    bd(close - 0.3), bd(close + 0.8), bd(close - 0.9), bd(close), bd(close),
                    10_000L + i, bd(close * 10_000), bd(1_000_000)));
            date = date.plusDays(1);
        }
        return new IndicatorContext(stockId, bars, List.of(), List.of(), Map.of());
    }

    private static BigDecimal bd(double value) {
        return BigDecimal.valueOf(Math.round(value * 100) / 100.0);
    }
}
