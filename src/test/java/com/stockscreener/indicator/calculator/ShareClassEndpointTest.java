package com.stockscreener.indicator.calculator;

import com.stockscreener.indicator.FinancialFigures;
import com.stockscreener.indicator.IndicatorContext;
import com.stockscreener.indicator.PriceBar;
import com.stockscreener.pipeline.datasource.us.shareclass.ShareClassLine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/** 관문이 켜진 미국 행을 주식수 계산이 읽는 법 — 숨김은 앞 판으로 안 내려가고, 비 지표의 끝은 분모 n이다. */
class ShareClassEndpointTest {

    private static final LocalDate BAR = LocalDate.of(2026, 3, 2);

    @Test
    @DisplayName("숨긴 판은 주식수가 없어도 앞 판으로 안 내려간다 — 꺼진 행(상태 없음)은 예전처럼 내려간다")
    void hiddenStopsTheWalk() {
        FinancialFigures older = quarter(LocalDate.of(2025, 9, 30), LocalDate.of(2025, 11, 1), "500", null, null);
        FinancialFigures hidden = quarter(LocalDate.of(2025, 12, 31), LocalDate.of(2026, 2, 1), null,
                ShareClassLine.State.HIDDEN, null);
        FinancialFigures off = quarter(LocalDate.of(2025, 12, 31), LocalDate.of(2026, 2, 1), null, null, null);

        assertThat(FinancialMath.sharesOf(context(), bar(), List.of(hidden, older))).isNull();
        assertThat(FinancialMath.sharesOf(context(), bar(), List.of(off, older))).isEqualByComparingTo("500");
    }

    @Test
    @DisplayName("비 지표의 끝은 클래스 축이 읽은 행이면 분모 n — n이 없으면 시점 주식수로 안 내려간다")
    void endpointUsesTheDenominator() {
        FinancialFigures withN = quarter(LocalDate.of(2025, 12, 31), LocalDate.of(2026, 2, 1), "1000",
                ShareClassLine.State.SHOWN, new BigDecimal("980"));
        FinancialFigures withoutN = quarter(LocalDate.of(2025, 12, 31), LocalDate.of(2026, 2, 1), "1000",
                ShareClassLine.State.SHOWN, null);
        FinancialFigures off = quarter(LocalDate.of(2025, 12, 31), LocalDate.of(2026, 2, 1), "1000", null, null);

        assertThat(FinancialMath.endpointShares(context(), bar(), series(withN))).isEqualByComparingTo("980");
        assertThat(FinancialMath.endpointShares(context(), bar(), series(withoutN))).isNull();
        assertThat(FinancialMath.endpointShares(context(), bar(), series(off))).isEqualByComparingTo("1000");
    }

    private static FinancialMath.PeriodSeries series(FinancialFigures row) {
        return FinancialMath.seriesOf(new IndicatorContext(1L, null, List.of(bar()), List.of(), List.of(row),
                List.of(), List.of(), new TreeMap<>(), List.of(), Map.of()));
    }

    private static IndicatorContext context() {
        return new IndicatorContext(1L, null, List.of(bar()), List.of(), List.of(), List.of(), List.of(), new TreeMap<>(),
                List.of(), Map.of());
    }

    private static PriceBar bar() {
        return new PriceBar(BAR, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, 1000L,
                null, null);
    }

    private static FinancialFigures quarter(LocalDate periodEnd, LocalDate publishedAt, String shares,
                                            ShareClassLine.State state, BigDecimal weighted) {
        return FinancialFiguresFixture.quarter(periodEnd).publishedAt(publishedAt)
                .sharesOutstanding(shares == null ? null : new BigDecimal(shares))
                .shareClass(state, weighted).build();
    }
}
