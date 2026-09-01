package com.stockscreener.indicator.calculator;

import com.stockscreener.indicator.DataRequirement;
import com.stockscreener.indicator.IndicatorCalculator;
import com.stockscreener.indicator.IndicatorCategory;
import com.stockscreener.indicator.IndicatorContext;
import com.stockscreener.indicator.IndicatorMeta;
import com.stockscreener.indicator.IndicatorPoint;
import com.stockscreener.indicator.IndicatorValueType;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;

/**
 * MACD-V 히스토그램이 5거래일 동안 얼마나 움직였는가.
 *
 * <p>{@link MacdVHistCalculator 히스토그램}이 「지금 어느 쪽이고 얼마나 가까운가」를 답하고,
 * 이 값이 <b>어느 쪽으로 가고 있는가</b>를 답한다. 둘을 묶으면 조건 계층이 못 쓰던 것이 열린다 —
 * {@code MACD_V_HIST < 0 AND MACD_V_HIST_CHG_5 > 0}이 「데드 상태인데 골든크로스로 다가가고
 * 있다」이다(2026-09-01 실측 324종목 / 1,348).
 *
 * <p>MUST NOT: 비율(%)로 내지 않는다. {@link Sma200Momentum21Calculator}는 200일선이 늘 양수라
 * 비율이 성립하지만 <b>히스토그램은 부호가 바뀌는 값</b>이라 ① 0 근처에서 비율이 발산하고
 * ② 음수 대비 음수의 「몇 % 증가」가 방향을 뒤집어 읽힌다. 차이로 낸다.
 *
 * <p>IMPORTANT — <b>5칸 전이 아니라 5거래일 전을 찾는다.</b> 부모에 억제 구멍이 나면
 * (ADR-0055) 목록의 5칸 전이 실제로는 훨씬 먼 날짜가 되는데, 값은 그럴듯하게 나온다.
 * {@link IndicatorContext#priceBars()}의 거래일 순번으로 찾고, 그 날짜에 부모 값이 없으면
 * 행을 쓰지 않는다.
 */
@Component
@IndicatorMeta(
        name = "MACD-V 히스토그램 변화량(5일)",
        description = "MACD-V 히스토그램이 5거래일 전보다 얼마나 커졌는지. "
                + "양수면 골든크로스 쪽으로, 음수면 데드크로스 쪽으로 움직이고 있다는 뜻이다.",
        interpretation = "히스토그램이 음수인데 이 값이 양수면 「아직 데드 상태지만 골든크로스로 "
                + "다가가고 있다」는 뜻이고, 히스토그램이 양수인데 이 값이 음수면 그 반대다. "
                + "5거래일은 한 주에 해당하며, 주봉판과 같은 길이를 재도록 맞춘 것이다.",
        category = IndicatorCategory.DERIVED,
        valueType = IndicatorValueType.COUNT,
        direction = IndicatorMeta.Direction.HIGHER_IS_BETTER,
        decimalScale = 2,
        sortOrder = 303,
        bands = {
                @IndicatorMeta.Band(label = "골든크로스 쪽으로", from = 0),
                @IndicatorMeta.Band(label = "데드크로스 쪽으로", to = 0)
        }
)
public class MacdVHistChange5Calculator implements IndicatorCalculator {

    public static final String CODE = "MACD_V_HIST_CHG_5";

    /**
     * 며칠 전과 견주는가. 주봉판의 1주와 같은 길이다.
     *
     * <p>MUST: 이 값을 {@link #dataRequirement()}에 그대로 선언한다 — 실제로 훑는 범위가
     * 선언보다 넓으면 미보정 기업행위 억제가 새고, 그때 증상은 "값이 나온다"다.
     */
    static final int LOOKBACK = 5;

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public DataRequirement dataRequirement() {
        return DataRequirement.derivedFrom(LOOKBACK, MacdVHistCalculator.CODE);
    }

    @Override
    public List<IndicatorPoint> calculate(IndicatorContext context) {
        NavigableMap<LocalDate, BigDecimal> hist = context.dependency(MacdVHistCalculator.CODE);
        if (hist.isEmpty()) {
            return List.of();
        }
        Map<LocalDate, Integer> calendar = MacdVHistCalculator.tradingDayIndex(context.priceBars());

        Map<Integer, BigDecimal> byTradingDay = new HashMap<>(hist.size() * 2);
        for (Map.Entry<LocalDate, BigDecimal> entry : hist.entrySet()) {
            Integer index = calendar.get(entry.getKey());
            if (index != null) {
                byTradingDay.put(index, entry.getValue());
            }
        }

        List<IndicatorPoint> points = new ArrayList<>();
        for (Map.Entry<LocalDate, BigDecimal> entry : hist.entrySet()) {
            Integer index = calendar.get(entry.getKey());
            if (index == null) {
                continue;
            }
            BigDecimal past = byTradingDay.get(index - LOOKBACK);
            if (past == null) {
                // 워밍업이거나 그 사이가 억제됐다. 값을 지어내지 않는다.
                continue;
            }
            points.add(new IndicatorPoint(entry.getKey(), entry.getValue().subtract(past)));
        }
        return points;
    }
}
