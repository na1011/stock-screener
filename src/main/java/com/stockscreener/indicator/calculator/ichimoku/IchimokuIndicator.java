package com.stockscreener.indicator.calculator.ichimoku;

import com.stockscreener.indicator.DataRequirement;
import com.stockscreener.indicator.IndicatorCalculator;
import com.stockscreener.indicator.IndicatorContext;
import com.stockscreener.indicator.IndicatorPoint;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;

/**
 * {@code ICHI_*} 계산기의 공통 뼈대. 하위 클래스는 <b>코드와 값 하나</b>만 정한다.
 *
 * <p>29개가 {@link IchimokuAnalyses}의 분석 하나를 나눠 쓴다. 각자 분석하면 30번 재계산이
 * 되고, 그러면 값이 맞아도 백필이 실용 시간 안에 끝나지 않는다({@code phase-criteria.md} §P12).
 *
 * <p>MUST: 하위 클래스마다 {@code @IndicatorMeta}를 붙인다.
 * {@code IndicatorCatalogSynchronizer}가 {@code calculator.getClass()}에서 찾으므로
 * <b>여기에 붙이면 29개가 같은 이름으로 투영된다.</b>
 *
 * <p>IMPORTANT — {@code priceBarsNeeded}가 <b>러너의 읽기 창을 1,100봉으로 끌어올린다.</b>
 * 그전 전역 최대는 {@code SharesGrowth3yCalculator}의 751이었다. 창은 종목당 한 번 읽히므로
 * 모든 지표가 그것을 함께 쓰고, 재귀 지표({@code ATR_14}·{@code RSI_14}·{@code MACD}·
 * {@code MACD_V})의 시드가 349봉 앞으로 가 <b>저장값이 미세하게 바뀐다.</b> 그 회귀는
 * 테스트가 원리적으로 못 잡는다 — 픽스처가 창보다 짧아 값이 그대로다.
 */
abstract class IchimokuIndicator implements IndicatorCalculator {

    /**
     * 읽기 창. 207 주봉(≈971봉) 워밍업 + 산출 구간이다.
     *
     * <p>이 값이 실행당 산출 일자 수를 정한다 — 실측으로 종목당 평균 136.8일자이고
     * 활성 315종목 합계 43,093 종목·일자다(2026-08-13). 줄이면 산출 일자가 줄고,
     * 971 아래로 내리면 <b>한 종목도 값을 내지 못한다.</b>
     */
    static final int LOOKBACK = 1100;

    private final IchimokuAnalyses analyses;

    protected IchimokuIndicator(IchimokuAnalyses analyses) {
        this.analyses = analyses;
    }

    @Override
    public DataRequirement dataRequirement() {
        return DataRequirement.priceBars(LOOKBACK);
    }

    @Override
    public final List<IndicatorPoint> calculate(IndicatorContext context) {
        NavigableMap<LocalDate, IchimokuAnalysis> byDate = analyses.of(context);
        if (byDate.isEmpty()) {
            return List.of();
        }
        List<IndicatorPoint> points = new ArrayList<>(byDate.size());
        for (Map.Entry<LocalDate, IchimokuAnalysis> entry : byDate.entrySet()) {
            points.add(new IndicatorPoint(entry.getKey(), valueOf(entry.getValue())));
        }
        return points;
    }

    /** 이 지표가 분석에서 꺼내는 값 하나. */
    protected abstract BigDecimal valueOf(IchimokuAnalysis analysis);

    /** 정수 값을 담는다. {@code double} 오버로드를 타면 {@code 4.0}이 되어 자릿수가 늘어난다. */
    protected static BigDecimal of(int value) {
        return BigDecimal.valueOf(value);
    }
}
