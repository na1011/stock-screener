package com.stockscreener.indicator.calculator.ichimoku;

import com.stockscreener.indicator.PriceBar;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-0046이 승인한 <b>이탈 1·2</b>와 수정 축척이 실제로 걸리는가.
 *
 * <p>셋 다 위반해도 값이 나오고 그럴듯하다는 것이 요점이다 — 207주 미만은 {@code nz()}가 0을
 * 채워 <b>강세 방향 거짓 양성</b>이 되고, 지수 결손은 D5를 절대 수익률로 바꾸며, 원주가는
 * 분할을 −80% 폭락으로 만든다. 어느 것도 에러를 내지 않는다.
 */
class IchimokuAnalyzerTest {

    private static final LocalDate START = LocalDate.of(2018, 1, 1);
    private static final WeekFields ISO = WeekFields.ISO;

    @Test
    @DisplayName("이탈 1 — 207 주봉을 못 채우면 한 행도 내지 않는다")
    void emitsNothingBelow207Weeks() {
        List<PriceBar> bars = dailyBars(206 * 5);
        assertThat(weeksOf(bars)).isEqualTo(206);

        assertThat(IchimokuAnalyzer.analyze(bars, index(bars))).isEmpty();
    }

    @Test
    @DisplayName("207 주봉째부터 값이 나오고, 그 앞 일자에는 없다")
    void startsExactlyAt207Weeks() {
        // 207주를 채운 뒤 5주를 더 둔다. 산출은 207주째가 시작되는 날부터다.
        List<PriceBar> bars = dailyBars(212 * 5);
        NavigableMap<LocalDate, IchimokuAnalysis> result = IchimokuAnalyzer.analyze(bars, index(bars));

        assertThat(result).isNotEmpty();
        // 206주까지의 마지막 거래일 — 여기에는 값이 없어야 한다.
        LocalDate lastOf206 = bars.get(206 * 5 - 1).tradeDate();
        assertThat(result.headMap(lastOf206, true))
                .as("206주까지는 span_b_arr[129]의 소비 구간을 못 채운다")
                .isEmpty();
        // 207주째의 첫 거래일 — 여기부터 값이 있다.
        assertThat(result).containsKey(bars.get(206 * 5).tradeDate());
        // 창 안 산출 일자 수는 (전체 − 워밍업)이다. 5주를 더했으니 그만큼이다.
        assertThat(result).hasSize(212 * 5 - 206 * 5);
    }

    @Test
    @DisplayName("이탈 2 — 지수가 없는 날짜에는 Tier도 나머지 29개도 내지 않는다")
    void emitsNothingWhenTheMarketIndexIsMissing() {
        List<PriceBar> bars = dailyBars(212 * 5);
        NavigableMap<LocalDate, BigDecimal> full = index(bars);
        LocalDate hole = bars.getLast().tradeDate();

        NavigableMap<LocalDate, BigDecimal> holed = new TreeMap<>(full);
        holed.remove(hole);
        // floorEntry가 직전 값을 집어 채우지 않는지 본다 — 채우면 D5가 조용히 어제 값이 된다.
        holed.remove(bars.get(bars.size() - 2).tradeDate());

        assertThat(IchimokuAnalyzer.analyze(bars, full)).containsKey(hole);
        assertThat(IchimokuAnalyzer.analyze(bars, holed))
                .as("지수가 없는 날짜는 통째로 빠진다")
                .doesNotContainKey(hole);
        assertThat(IchimokuAnalyzer.analyze(bars, new TreeMap<>()))
                .as("지수가 아예 없으면 한 행도 없다")
                .isEmpty();
    }

    @Test
    @DisplayName("분할은 수정 축척으로 흡수된다 — 가격이 구름 아래로 떨어지지 않는다")
    void splitDoesNotPushThePriceBelowTheCloud() {
        // 마지막 60거래일에서 5:1 분할이 일어난다. 원주가는 1/5로 떨어지지만 adj_close는
        // 이어진다. 원주가를 쓰면 종가만 20으로 꺾이고 구름은 과거의 100 근처에 남아
        // ICHI_PRICE_VS_KUMO가 −1(구름 아래)이 된다 — 값이 정상 범위 안이라 그럴듯하고,
        // 그 종목은 모든 일목 프리셋에서 조용히 탈락한다.
        int size = 212 * 5;
        List<PriceBar> bars = new ArrayList<>(size);
        LocalDate date = START;
        for (int i = 0; i < size; i++) {
            boolean afterSplit = i >= size - 60;
            BigDecimal raw = afterSplit ? new BigDecimal("20") : new BigDecimal("100");
            BigDecimal adjusted = new BigDecimal("100");
            bars.add(new PriceBar(date, raw, raw, raw, raw, adjusted,
                    1000L, new BigDecimal("1000"), new BigDecimal("1000")));
            date = nextTradingDay(date);
        }

        IchimokuAnalysis last = IchimokuAnalyzer.analyze(bars, index(bars)).lastEntry().getValue();

        assertThat(last.priceVsKumo())
                .as("수정 축척이면 가격이 평평해 구름 안(0)이다. 원주가를 쓰면 −1이 된다")
                .isZero();
        assertThat(last.kaizenWeekly())
                .as("호전 분류도 종가 비교를 품는다 — 26주 전보다 낮다고 읽히면 함께 깎인다")
                .isEqualTo(0);
    }

    /** 종가가 평평한 일봉. 축척 검증 외에는 값 자체가 중요하지 않다. */
    private static List<PriceBar> dailyBars(int count) {
        List<PriceBar> bars = new ArrayList<>(count);
        LocalDate date = START;
        for (int i = 0; i < count; i++) {
            BigDecimal price = new BigDecimal("100");
            bars.add(new PriceBar(date, price, price, price, price, price,
                    1000L, new BigDecimal("1000"), new BigDecimal("1000")));
            date = nextTradingDay(date);
        }
        return bars;
    }

    /**
     * 주말을 건너뛴다. START가 <b>월요일</b>이라 5봉이 정확히 한 ISO주가 되고,
     * 그래야 "몇 주째부터 값이 나오는가"를 봉 수로 셀 수 있다.
     */
    private static LocalDate nextTradingDay(LocalDate from) {
        LocalDate next = from.plusDays(1);
        while (next.getDayOfWeek() == DayOfWeek.SATURDAY || next.getDayOfWeek() == DayOfWeek.SUNDAY) {
            next = next.plusDays(1);
        }
        return next;
    }

    private static NavigableMap<LocalDate, BigDecimal> index(List<PriceBar> bars) {
        NavigableMap<LocalDate, BigDecimal> index = new TreeMap<>();
        for (PriceBar bar : bars) {
            index.put(bar.tradeDate(), new BigDecimal("2500"));
        }
        return index;
    }

    private static int weeksOf(List<PriceBar> bars) {
        return (int) bars.stream()
                .map(bar -> bar.tradeDate().get(ISO.weekBasedYear()) * 100L
                        + bar.tradeDate().get(ISO.weekOfWeekBasedYear()))
                .distinct().count();
    }
}
