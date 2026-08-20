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
 * <p>18개가 {@link IchimokuAnalyses}의 분석 하나를 나눠 쓴다. 각자 분석하면 18번 재계산이
 * 되고, 그러면 값이 맞아도 백필이 실용 시간 안에 끝나지 않는다({@code phase-criteria.md} §P12).
 *
 * <p>MUST: 하위 클래스마다 {@code @IndicatorMeta}를 붙인다.
 * {@code IndicatorCatalogSynchronizer}가 {@code calculator.getClass()}에서 찾으므로
 * <b>여기에 붙이면 18개가 같은 이름으로 투영된다.</b>
 *
 * <p>IMPORTANT — 러너의 읽기 창은 {@link com.stockscreener.indicator.IndicatorRegistry}의
 * {@code maxEffectiveLookback()}이 <b>매번 전 지표의 최댓값으로</b> 정한다. 여기의
 * {@code priceBarsNeeded}가 한동안 그 최댓값이었지만(751 → 1,100, P12) ADR-0066이 더 긴 창을
 * 요구하는 지표를 들이면서 그 자리를 내줬다 — 2026-08-20 기준 최댓값은 1,261이다.
 * <b>그래서 이 클래스를 지워도 창은 내려가지 않는다.</b>
 *
 * <p>창은 종목당 한 번 읽히므로 모든 지표가 그것을 함께 쓰고, <b>창이 움직이면</b> 재귀
 * 지표({@code ATR_14}·{@code RSI_14}·{@code MACD}·{@code MACD_V})의 시드가 그만큼 앞으로 가
 * <b>저장값이 미세하게 바뀐다.</b> 그 회귀는 테스트가 원리적으로 못 잡는다 — 픽스처가 창보다
 * 짧아 값이 그대로다.
 */
abstract class IchimokuIndicator implements IndicatorCalculator {

    /**
     * 읽기 창. 207 주봉(≈971봉) 워밍업 + 산출 구간이다.
     *
     * <p>산출 일자 수는 {@code min(창 − 971 + 1, 보존 180)}이고 지금은 보존이 먼저 자른다 —
     * 실측 종목당 평균 177.4일자(311종목, 2026-08-20). 이 값을 971 아래로 내리면
     * <b>한 종목도 값을 내지 못한다.</b>
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
