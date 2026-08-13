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
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;

/**
 * 200일선의 1개월 변화율 — {@code (SMA200 ÷ 21거래일 전 SMA200 − 1) × 100}.
 *
 * <p>"이동평균이 우상향인가"를 묻는 두 방식 중 하나다. 이것 단독으로는 <b>횡보 구간에서
 * 노이즈로 통과한다</b> — 21일 전보다 0.01% 높기만 해도 우상향이 되기 때문이다. 그래서
 * {@link Sma200Slope60Calculator}와 함께 쓴다. 반대로 회귀 기울기 단독은 최근 며칠의 꺾임을
 * 평활해서 놓치므로, 두 조건을 AND로 걸면 양쪽 오류가 상쇄된다.
 *
 * <p>비교 시점은 <b>거래일 기준</b> 21칸 전이다. 달력 한 달로 세면 휴장일 수에 따라 창이
 * 흔들려 같은 조건이 종목·시기마다 다른 기간을 보게 된다.
 *
 * <p>무차원(%)이라 종목 간 비교가 되고 구간을 붙일 수 있다. 200일선 자체에는 붙일 수 없다
 * (주가 스케일 — ADR-0023).
 */
@Component
@IndicatorMeta(
        name = "200일선 1개월 변화율",
        description = "200일 이동평균이 21거래일 전보다 몇 퍼센트 올랐는지. 장기 추세선 자체가 오르고 있는지를 본다.",
        interpretation = "0보다 크면 장기 추세선이 상승 중이라는 뜻이다. "
                + "다만 0을 겨우 넘는 값은 횡보와 구분되지 않으므로, "
                + "회귀 기울기와 함께 보아야 추세 전환을 판단할 수 있다.",
        category = IndicatorCategory.TECHNICAL,
        valueType = IndicatorValueType.PERCENT,
        unit = "%",
        decimalScale = 2,
        direction = IndicatorMeta.Direction.HIGHER_IS_BETTER,
        sortOrder = 111,
        bands = {
                @IndicatorMeta.Band(label = "강한 상승", from = 3),
                @IndicatorMeta.Band(label = "상승", from = 0.5, to = 3),
                @IndicatorMeta.Band(label = "횡보", from = -0.5, to = 0.5),
                @IndicatorMeta.Band(label = "하락", to = -0.5)
        }
)
public class Sma200Momentum21Calculator implements IndicatorCalculator {

    public static final String CODE = "SMA_200_MOM_21";

    /** 대략 한 달. 거래일 기준이다. */
    static final int LOOKBACK = 21;

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public DataRequirement dataRequirement() {
        return DataRequirement.derivedFrom(LOOKBACK, Sma200Calculator.CODE);
    }

    @Override
    public List<IndicatorPoint> calculate(IndicatorContext context) {
        NavigableMap<LocalDate, BigDecimal> sma = context.dependency(Sma200Calculator.CODE);
        if (sma.size() <= LOOKBACK) {
            return List.of();
        }

        // 날짜가 아니라 순번으로 21칸을 센다. 달력으로 빼면 휴장일 수에 따라 창이 흔들린다.
        List<LocalDate> dates = new ArrayList<>(sma.size());
        List<BigDecimal> values = new ArrayList<>(sma.size());
        for (Map.Entry<LocalDate, BigDecimal> entry : sma.entrySet()) {
            dates.add(entry.getKey());
            values.add(entry.getValue());
        }

        List<IndicatorPoint> points = new ArrayList<>(values.size() - LOOKBACK);
        for (int i = LOOKBACK; i < values.size(); i++) {
            BigDecimal past = values.get(i - LOOKBACK);
            if (past.signum() == 0) {
                continue;
            }
            BigDecimal change = values.get(i).divide(past, MovingAverages.MC)
                    .subtract(BigDecimal.ONE)
                    .multiply(HUNDRED)
                    .setScale(MovingAverages.SCALE, RoundingMode.HALF_UP);
            points.add(new IndicatorPoint(dates.get(i), change));
        }
        return points;
    }
}
