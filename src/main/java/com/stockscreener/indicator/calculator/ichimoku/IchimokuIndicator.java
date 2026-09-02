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
 * <p>31개가 {@link IchimokuAnalyses}의 분석 하나를 나눠 쓴다. 각자 분석하면 31번 재계산이
 * 되고, 그러면 값이 맞아도 백필이 실용 시간 안에 끝나지 않는다({@code phase-criteria.md} §P12).
 *
 * <p>MUST: 하위 클래스마다 {@code @IndicatorMeta}를 붙인다.
 * {@code IndicatorCatalogSynchronizer}가 {@code calculator.getClass()}에서 찾으므로
 * <b>여기에 붙이면 31개가 같은 이름으로 투영된다.</b>
 *
 * <p>IMPORTANT — 러너의 읽기 창은 {@link com.stockscreener.indicator.IndicatorRegistry}의
 * {@code readWindowBars()}가 정한다 — <b>전 지표 워밍업의 최댓값에 보존 일수를 얹은 값</b>이다
 * (ADR-0079). 여기의 {@code priceBarsNeeded}가 한동안 그 워밍업 최댓값이었지만(751 → 1,100, P12)
 * ADR-0066이 더 긴 것을 요구하는 지표를 들이면서 자리를 내줬다 — 2026-08-21 기준 워밍업 최댓값은
 * 1,261, 읽기 창은 <b>1,441</b>이다. <b>그래서 이 클래스를 지워도 창은 내려가지 않는다.</b>
 *
 * <p>창은 종목당 한 번 읽히므로 모든 지표가 그것을 함께 쓰고, <b>창이 움직이면</b> 평활 시드가
 * 그만큼 앞으로 가 <b>저장값이 미세하게 바뀐다.</b> 대상은 일봉 재귀 넷({@code ATR_14}·
 * {@code RSI_14}·{@code MACD}·{@code MACD_V})만이 아니다 — {@code ADX_14}·{@code RSI_2}와
 * <b>여기 {@code ICHI_*} 자신</b>도 그렇다. <b>이쪽이 더 크게 움직인다</b>: {@link WeeklySeries}의
 * RMA는 <b>주봉</b>을 평활하므로 시드까지의 거리가 창의 봉 수가 아니라 그 1/5쯤인 주 수이고,
 * 잔차가 일봉의 그것보다 자릿수로 크다. 그 회귀는 테스트가 원리적으로 못 잡는다 — 픽스처가 창보다
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
        return needsMarketIndex()
                ? DataRequirement.priceAndMarketIndex(LOOKBACK)
                : DataRequirement.priceBars(LOOKBACK);
    }

    /**
     * D5(26주 초과 수익률)에 기대는 <b>일곱</b>만 {@code true}를 낸다 —
     * tier·signal·extSignal·confidence·conclusion·bull·bear.
     *
     * <p>MUST: 선언한 쪽만 지수를 요구한다. 스물넷은 지수를 쓰지 않으므로 함께 묶으면
     * <b>지수가 결손인 날 멀쩡한 지표까지 사라진다</b> — 그렇게 하던 게이트가 철회된
     * 결정이다(ADR-0046 §승인된 이탈 2).
     */
    protected boolean needsMarketIndex() {
        return false;
    }

    @Override
    public final List<IndicatorPoint> calculate(IndicatorContext context) {
        NavigableMap<LocalDate, IchimokuAnalysis> byDate = analyses.of(context);
        if (byDate.isEmpty()) {
            return List.of();
        }
        List<IndicatorPoint> points = new ArrayList<>(byDate.size());
        for (Map.Entry<LocalDate, IchimokuAnalysis> entry : byDate.entrySet()) {
            BigDecimal value = valueOf(entry.getValue());
            // MUST: null이면 그 일자를 담지 않는다 — 지수가 없어 D5 축을 못 낸 자리다.
            // 0을 채우면 "지수 없음"이 "중립"이 되고 조건 `<= 0`이 그것을 전부 통과시킨다.
            if (value == null) {
                continue;
            }
            points.add(new IndicatorPoint(entry.getKey(), value));
        }
        return points;
    }

    /** 이 지표가 분석에서 꺼내는 값 하나. */
    protected abstract BigDecimal valueOf(IchimokuAnalysis analysis);

    /** 정수 값을 담는다. {@code double} 오버로드를 타면 {@code 4.0}이 되어 자릿수가 늘어난다. */
    protected static BigDecimal of(int value) {
        return BigDecimal.valueOf(value);
    }

    /** 지수 결손으로 낼 수 없는 값. {@code null}이면 그 일자를 담지 않는다. */
    protected static BigDecimal of(Integer value) {
        return value == null ? null : BigDecimal.valueOf(value);
    }
}
