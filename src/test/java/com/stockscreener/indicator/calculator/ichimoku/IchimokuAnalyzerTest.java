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
 * ADR-0046이 승인한 <b>이탈 1</b>과 수정 축척이 실제로 걸리는가.
 *
 * <p>둘 다 위반해도 값이 나오고 그럴듯하다는 것이 요점이다 — 207주 미만은 {@code nz()}가 0을
 * 채워 <b>강세 방향 거짓 양성</b>이 되고, 원주가는 분할을 −80% 폭락으로 만들어 종가를 구름
 * 아래로 밀어낸다. 어느 것도 에러를 내지 않는다.
 */
class IchimokuAnalyzerTest {

    private static final LocalDate START = LocalDate.of(2018, 1, 1);
    private static final WeekFields ISO = WeekFields.ISO;

    @Test
    @DisplayName("이탈 1 — 207 주봉을 못 채우면 한 행도 내지 않는다")
    void emitsNothingBelow207Weeks() {
        List<PriceBar> bars = dailyBars(206 * 5);
        assertThat(weeksOf(bars)).isEqualTo(206);

        assertThat(IchimokuAnalyzer.analyze(bars, flatIndex(bars))).isEmpty();
    }

    @Test
    @DisplayName("207 주봉째부터 값이 나오고, 그 앞 일자에는 없다")
    void startsExactlyAt207Weeks() {
        // 207주를 채운 뒤 5주를 더 둔다. 산출은 207주째가 시작되는 날부터다.
        List<PriceBar> bars = dailyBars(212 * 5);
        NavigableMap<LocalDate, IchimokuAnalysis> result =
                IchimokuAnalyzer.analyze(bars, flatIndex(bars));

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
    @DisplayName("지수가 없으면 D5에 기댄 일곱만 null이고 나머지는 그대로 나온다")
    void suppressesOnlyTheIndexDependentSeven() {
        List<PriceBar> bars = dailyBars(212 * 5);

        NavigableMap<LocalDate, IchimokuAnalysis> without =
                IchimokuAnalyzer.analyze(bars, new TreeMap<>());
        assertThat(without)
                .as("지수 결손이 그 일자를 통째로 버리던 게이트는 철회됐다(ADR-0046 §승인된 이탈 2)")
                .isNotEmpty();

        IchimokuAnalysis a = without.lastEntry().getValue();
        assertThat(a.tier()).isNull();
        assertThat(a.signal()).isNull();
        assertThat(a.extSignal()).isNull();
        assertThat(a.confidence()).isNull();
        assertThat(a.conclusion()).isNull();
        assertThat(a.bull()).isNull();
        assertThat(a.bear()).isNull();
        assertThat(a.excessReturn()).isNull();

        // MUST: 관측 축은 지수를 쓰지 않는다. 여기가 무너지면 지수 결손일에 멀쩡한 지표
        // 열여덟이 함께 사라진다 — 그것이 철회된 게이트가 하던 일이다.
        assertThat(a.pillar()).isNotNull();
        assertThat(a.stage()).isNotNull();
        assertThat(a.leaf()).isNotNull();
        assertThat(a.freshness()).isNotNull();
        assertThat(a.t3yeok()).isNotNull();
        assertThat(a.kaizenMatch()).isNotNull();

        // 대조군 — 같은 봉에 지수를 주면 일곱이 값을 갖는다. 없으면 "언제나 null"인
        // 구현도 위 단언을 통과한다.
        IchimokuAnalysis withIndex =
                IchimokuAnalyzer.analyze(bars, flatIndex(bars)).lastEntry().getValue();
        assertThat(withIndex.tier()).isNotNull();
        assertThat(withIndex.excessReturn()).isNotNull();
    }

    @Test
    @DisplayName("분할은 수정 축척으로 흡수된다 — 26주 초과 수익률이 절벽을 갖지 않는다")
    void splitDoesNotCreateACliffInTheExcessReturn() {
        // 마지막 60거래일에서 5:1 분할이 일어난다. 원주가는 1/5로 떨어지지만 adj_close는
        // 이어진다 — 원주가를 쓰면 stock_ret_26w가 −80%가 되어 score_d5가 −ext_base로 붙는다.
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

        IchimokuAnalysis last =
                IchimokuAnalyzer.analyze(bars, flatIndex(bars)).lastEntry().getValue();

        assertThat(last.excessReturn())
                .as("수정 축척이면 가격이 평평하므로 초과 수익률이 0이다. 원주가를 쓰면 −80%다")
                .isEqualTo(0.0);
    }

    /** 종목 봉과 같은 날짜에 값이 있는 평평한 지수. 초과 수익률이 0이 된다. */
    private static NavigableMap<LocalDate, BigDecimal> flatIndex(List<PriceBar> bars) {
        NavigableMap<LocalDate, BigDecimal> index = new TreeMap<>();
        for (PriceBar bar : bars) {
            index.put(bar.tradeDate(), new BigDecimal("100"));
        }
        return index;
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


    private static int weeksOf(List<PriceBar> bars) {
        return (int) bars.stream()
                .map(bar -> bar.tradeDate().get(ISO.weekBasedYear()) * 100L
                        + bar.tradeDate().get(ISO.weekOfWeekBasedYear()))
                .distinct().count();
    }
}
