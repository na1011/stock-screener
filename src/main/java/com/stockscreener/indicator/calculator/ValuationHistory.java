package com.stockscreener.indicator.calculator;

import com.stockscreener.indicator.FinancialFigures;
import com.stockscreener.indicator.IndicatorContext;
import com.stockscreener.indicator.IndicatorPoint;
import com.stockscreener.indicator.PriceBar;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 「자기 자신의 5년 밸류에이션 이력」을 만드는 공용 계산. {@code PER}·{@code PBR}·{@code PSR}의
 * 5년 중앙값이 이것을 공유한다.
 *
 * <p>IMPORTANT — <b>원본 지표의 시계열을 그대로 쓰면 안 된다.</b> {@code PerCalculator}·
 * {@code PbrCalculator}는 최신 재무 <b>하나</b>를 구해 모든 봉에 적용하므로(루프 밖에서 한 번
 * 계산한다), 그 시계열의 중앙값은 「5년간의 배수 중앙값」이 아니라 <b>「오늘의 재무로 환산한 과거
 * 주가 수준」</b>이다. 이름은 밸류에이션 이력이라 말하면서 재는 것은 주가 이력이 되고, <b>값이
 * 그럴듯해서 드러나지 않는다.</b> 그래서 여기서는 봉마다 <b>그 시점에 공시돼 있던</b> 재무로
 * 분모를 다시 세운다({@link FinancialMath#publishedIndexOn}).
 *
 * <p>중앙값을 평균이 아니라 중앙값으로 두는 이유는 극단값 때문이다. 이익이 0에 가까운 분기가
 * 한 번만 끼어도 배수가 수천 배로 튀고, 평균은 그 한 점에 끌려간다.
 *
 * <p>값 하나를 시세 날짜 전체에 펼친다({@link FinancialMath#spreadOverTradeDates}). 즉 과거
 * 날짜의 행도 <b>최신 시점에 계산한 중앙값</b>이며, 그것은 {@code OCF_SUM_5Y}를 비롯한 기존
 * 재무 지표와 같은 성질이다.
 */
final class ValuationHistory {

    /** 되돌아보는 거리. {@link EpsCagr5yCalculator}와 같은 창을 쓴다. */
    static final int LOOKBACK = EpsCagr5yCalculator.LOOKBACK;

    /**
     * 중앙값을 낼 최소 표본. 5년치 거래일의 절반이다.
     *
     * <p>표본이 적으면 중앙값이 특정 국면 하나를 가리키는데 값은 그대로 나온다 —
     * 「5년 중앙값」이라는 이름이 그때 거짓이 된다.
     */
    static final int MIN_SAMPLES = LOOKBACK / 2;

    /**
     * 그 봉 시점의 <b>주당 분모</b>. 양수가 아니면 그 봉을 표본에서 뺀다는 뜻으로 {@code null}을
     * 돌려준다.
     *
     * @param publishedIndex 그 날짜에 이미 공시돼 있던 첫 분기의 인덱스.
     *                       <b>유량이면 여기서부터 넷을 더하고, 저량이면 이 한 분기의 잔액을 쓴다</b>
     */
    interface PerShareDenominator {
        BigDecimal on(IndicatorContext context, PriceBar bar,
                      List<FinancialFigures> quarters, int publishedIndex);
    }

    private ValuationHistory() {
    }

    /** 5년 배수 중앙값 하나를 만들어 시세 날짜 전체에 편다. 표본이 모자라면 빈 목록이다. */
    static List<IndicatorPoint> median(IndicatorContext context, PerShareDenominator denominator) {
        List<FinancialFigures> quarters = context.quarterlyFigures();
        List<PriceBar> bars = context.priceBars();
        if (quarters.isEmpty() || bars.size() <= LOOKBACK) {
            return List.of();
        }

        int from = bars.size() - LOOKBACK - 1;
        List<Double> ratios = new ArrayList<>(LOOKBACK + 1);
        for (int i = from; i < bars.size(); i++) {
            PriceBar bar = bars.get(i);
            int published = FinancialMath.publishedIndexOn(quarters, bar.tradeDate());
            if (published < 0) {
                continue;
            }
            BigDecimal perShare = denominator.on(context, bar, quarters, published);
            if (perShare == null || perShare.signum() <= 0) {
                continue;
            }
            ratios.add(bar.close()
                    .divide(perShare, FinancialMath.SCALE, RoundingMode.HALF_UP)
                    .doubleValue());
        }
        if (ratios.size() < MIN_SAMPLES) {
            return List.of();
        }

        double[] values = new double[ratios.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = ratios.get(i);
        }
        BigDecimal median = BigDecimal.valueOf(SeriesMath.median(values))
                .setScale(FinancialMath.SCALE, RoundingMode.HALF_UP);
        return FinancialMath.spreadOverTradeDates(bars, median);
    }
}
