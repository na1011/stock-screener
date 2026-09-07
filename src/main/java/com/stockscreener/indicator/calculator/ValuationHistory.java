package com.stockscreener.indicator.calculator;

import com.stockscreener.indicator.FinancialFigures;
import com.stockscreener.indicator.IndicatorContext;
import com.stockscreener.indicator.IndicatorPoint;
import com.stockscreener.indicator.PriceBar;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
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
 * <p><b>중앙값은 봉마다 그 봉까지의 창으로 낸다</b>(ADR-0128). 값 하나를 창 전체에 펴던 시절에는
 * 과거 일자의 「5년 중앙값」이 <b>그 뒤의 종가</b>로 계산돼 있었다 — 재무 쪽 look-ahead를 닫으면서
 * 가격 쪽을 남기면 이 지표는 여전히 미래를 본다. 창을 한 칸씩 미는 형태라 비용은
 * <b>정렬 하나가 아니라 삽입·삭제 하나씩</b>이다.
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

    /**
     * 봉마다 그 봉까지의 5년 창으로 배수 중앙값을 낸다. 표본이 모자라는 봉은 값을 내지 않는다.
     *
     * <p>두 단계다. ① 봉마다 배수를 하나 만든다 — 분모의 재무는 <b>그 봉 시점에 공시돼 있던
     * 판</b>만 본다({@link IndicatorContext#visibleAt}). ② 정렬된 창을 한 칸씩 밀며 중앙값을
     * 읽는다.
     *
     * <p>MUST: ②의 창을 매번 새로 정렬하지 않는다. 봉당 들고 나는 것이 각각 하나뿐이라
     * 삽입·삭제 하나로 끝나는데, 다시 정렬하면 창 길이 × 봉 수의 로그배가 붙는다.
     *
     * <p>MUST: {@code visible}을 <b>공시일을 넘을 때만</b> 다시 만든다. 봉마다 만들면
     * 사본이 창 길이만큼 생긴다 — 값은 같고 비용만 는다.
     */
    static List<IndicatorPoint> median(IndicatorContext context, PerShareDenominator denominator) {
        List<PriceBar> bars = context.priceBars();
        if (bars.size() <= LOOKBACK) {
            return List.of();
        }

        Double[] ratios = new Double[bars.size()];
        List<LocalDate> cuts = context.financialPublicationDates();
        int cut = 0;
        List<FinancialFigures> visible = null;
        for (int i = 0; i < bars.size(); i++) {
            PriceBar bar = bars.get(i);
            boolean crossed = false;
            while (cut < cuts.size() && !cuts.get(cut).isAfter(bar.tradeDate())) {
                cut++;
                crossed = true;
            }
            if (visible == null || crossed) {
                visible = context.visibleAt(bar.tradeDate()).quarterlyFigures();
            }
            int published = FinancialMath.publishedIndexOn(visible, bar.tradeDate());
            if (published < 0) {
                continue;
            }
            BigDecimal perShare = denominator.on(context, bar, visible, published);
            if (perShare == null || perShare.signum() <= 0) {
                continue;
            }
            ratios[i] = bar.close()
                    .divide(perShare, FinancialMath.SCALE, RoundingMode.HALF_UP)
                    .doubleValue();
        }

        List<Double> window = new ArrayList<>(LOOKBACK + 1);
        for (int i = 0; i <= LOOKBACK; i++) {
            insertSorted(window, ratios[i]);
        }

        List<IndicatorPoint> points = new ArrayList<>(bars.size() - LOOKBACK);
        for (int i = LOOKBACK; i < bars.size(); i++) {
            if (i > LOOKBACK) {
                removeSorted(window, ratios[i - LOOKBACK - 1]);
                insertSorted(window, ratios[i]);
            }
            if (window.size() < MIN_SAMPLES) {
                continue;
            }
            points.add(new IndicatorPoint(bars.get(i).tradeDate(),
                    BigDecimal.valueOf(medianOf(window))
                            .setScale(FinancialMath.SCALE, RoundingMode.HALF_UP)));
        }
        return points;
    }

    private static void insertSorted(List<Double> window, Double value) {
        if (value == null) {
            return;
        }
        int at = Collections.binarySearch(window, value);
        window.add(at < 0 ? -at - 1 : at, value);
    }

    private static void removeSorted(List<Double> window, Double value) {
        if (value == null) {
            return;
        }
        int at = Collections.binarySearch(window, value);
        // 같은 값이 여럿이면 binarySearch가 아무 자리나 준다. 지우는 개수만 맞으면 되므로
        // 그 자리를 그대로 쓴다 — 창은 다중집합이고 순서만 유지하면 중앙값은 같다.
        window.remove(at);
    }

    /** 정렬된 창의 중앙값. 짝수면 가운데 둘의 평균이다. */
    private static double medianOf(List<Double> window) {
        int n = window.size();
        int mid = n / 2;
        return n % 2 == 1 ? window.get(mid) : (window.get(mid - 1) + window.get(mid)) / 2.0;
    }
}
