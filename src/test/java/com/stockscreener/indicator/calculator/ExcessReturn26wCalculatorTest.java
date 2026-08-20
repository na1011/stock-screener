package com.stockscreener.indicator.calculator;

import com.stockscreener.indicator.IndicatorContext;
import com.stockscreener.indicator.IndicatorPoint;
import com.stockscreener.indicator.PriceBar;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 시장 지수가 없을 때 <b>값을 내지 않는가</b>.
 *
 * <p>이 계약을 여기서 재는 이유가 있다. 예전에는 {@code IchimokuAnalyzer}가 지수 결손일을
 * 통째로 건너뛰었고 그 게이트의 테스트가 이 계약을 <b>간접적으로</b> 지키고 있었다. 게이트를
 * 걷으면서(ADR-0046 §승인된 이탈 2) 지수에 기대는 지표가 이것 하나만 남았으므로, 그 하나를
 * 직접 잰다.
 *
 * <p>0으로 메우면 초과 수익률이 <b>절대 수익률로 뜻이 바뀌는데</b> 값의 범위가 같아 화면에서
 * 구별되지 않는다. 그리고 운영 알림 둘({@code DailyUpdateJob}·{@code MarketIndexJob})이
 * "지수가 없으면 이 지표가 안 써진다"고 사용자에게 말하므로, 이 계약이 깨지면 그 알림이
 * 거짓이 된다.
 */
class ExcessReturn26wCalculatorTest {

    private static final LocalDate START = LocalDate.of(2024, 1, 2);
    private final ExcessReturn26wCalculator calculator = new ExcessReturn26wCalculator();

    @Test
    @DisplayName("지수가 아예 없으면 한 행도 내지 않는다 — 0으로 메우지 않는다")
    void emitsNothingWithoutAnyIndex() {
        List<PriceBar> bars = risingBars(200);

        assertThat(calculator.calculate(context(bars, new TreeMap<>()))).isEmpty();
    }

    @Test
    @DisplayName("지수에 구멍이 있으면 그 날짜만 빠진다 — 직전 값으로 채우지 않는다")
    void skipsOnlyTheDatesWhereTheIndexIsMissing() {
        List<PriceBar> bars = risingBars(200);
        NavigableMap<LocalDate, BigDecimal> full = flatIndex(bars);
        LocalDate hole = bars.getLast().tradeDate();

        NavigableMap<LocalDate, BigDecimal> holed = new TreeMap<>(full);
        holed.remove(hole);

        assertThat(dates(calculator.calculate(context(bars, full)))).contains(hole);
        // floorEntry가 어제 값을 집어 채우면 초과 수익률이 조용히 어제 기준이 된다.
        assertThat(dates(calculator.calculate(context(bars, holed))))
                .as("구멍 난 날짜는 값이 없다")
                .doesNotContain(hole);
    }

    @Test
    @DisplayName("종목과 지수가 같은 비율로 오르면 초과 수익률이 0이다")
    void matchingReturnsCancelOut() {
        List<PriceBar> bars = risingBars(200);
        NavigableMap<LocalDate, BigDecimal> index = new TreeMap<>();
        for (PriceBar bar : bars) {
            // 종가와 같은 궤적. 비율이 같으므로 차이가 0이어야 한다.
            index.put(bar.tradeDate(), bar.adjClose());
        }

        List<IndicatorPoint> points = calculator.calculate(context(bars, index));

        assertThat(points).isNotEmpty();
        assertThat(points.getLast().value()).isEqualByComparingTo("0.00");
    }

    // ── 픽스처 ────────────────────────────────────────────────────────────

    /** 매일 1씩 오르는 종가. 수정계수는 1이다. */
    private static List<PriceBar> risingBars(int count) {
        List<PriceBar> bars = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            BigDecimal price = BigDecimal.valueOf(100 + i);
            bars.add(new PriceBar(START.plusDays(i), price, price, price, price, price,
                    1000L, null, null));
        }
        return bars;
    }

    private static NavigableMap<LocalDate, BigDecimal> flatIndex(List<PriceBar> bars) {
        NavigableMap<LocalDate, BigDecimal> index = new TreeMap<>();
        for (PriceBar bar : bars) {
            index.put(bar.tradeDate(), BigDecimal.valueOf(2500));
        }
        return index;
    }

    private static IndicatorContext context(List<PriceBar> bars,
                                            NavigableMap<LocalDate, BigDecimal> index) {
        return new IndicatorContext(1L, bars, List.of(), List.of(), Map.of()).withMarketIndex(index);
    }

    private static List<LocalDate> dates(List<IndicatorPoint> points) {
        return points.stream().map(IndicatorPoint::tradeDate).toList();
    }
}
