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
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;

/**
 * 시장 지수 대비 26주(130거래일) 초과 수익률 — 원본 {@code DRV-55}.
 *
 * <p>MUST NOT: {@code ICHI_} 접두어를 붙이지 않는다. 이 값은 일목 전용이 아니라
 * <b>{@code BETA_104W}와 공유</b>한다. 공식의 정본은 {@link ExcessReturn} 한 곳이다.
 *
 * <p>일목 지표 18개와 달리 <b>207 주봉 가드에 걸리지 않는다.</b> 필요한 것은 131봉과 지수뿐이라
 * 이력이 짧은 종목도 값을 낼 수 있고, 산출 일자도 그만큼 많다. 같은 가드를 걸면 T-08이
 * 나중에 그것을 풀어야 한다.
 *
 * <p>기준지수는 <b>이 종목이 속한 시장</b>의 것이다(ADR-0053 §이탈 4). 러너가 골라서
 * {@link IndicatorContext#marketIndex()}에 담아 준다 — 원본 Pine은 통화만 보고 코스피 하나로
 * 접지만 그것은 판단이 아니라 <b>{@code syminfo.currency}로는 코스피/코스닥을 표현할 수 없다는
 * 한계</b>였다.
 */
@Component
@IndicatorMeta(
        name = "26주 초과 수익률",
        description = "이 종목의 130거래일 수익률에서 같은 구간 시장 지수 수익률을 뺀 값. "
                + "코스피 종목은 코스피 지수와, 코스닥 종목은 코스닥 지수와 견준다.",
        interpretation = "양수면 시장을 이겼다는 뜻이다. ±5%가 노이즈와 신호를 가르는 임계로 "
                + "쓰이며, 그 안이면 방향을 말하지 않는다. "
                + "지수가 없는 날짜에는 값이 아예 없다 — 0으로 채우면 초과 수익률이 "
                + "절대 수익률로 뜻이 바뀌는데 화면에서는 구별되지 않기 때문이다.",
        category = IndicatorCategory.TECHNICAL,
        valueType = IndicatorValueType.PERCENT,
        unit = "%",
        decimalScale = 2,
        direction = IndicatorMeta.Direction.HIGHER_IS_BETTER,
        sortOrder = 135,
        bands = {
                @IndicatorMeta.Band(label = "시장 대비 강함", from = 5),
                @IndicatorMeta.Band(label = "시장 수준", from = -5, to = 5),
                @IndicatorMeta.Band(label = "시장 대비 약함", to = -5)
        }
)
public class ExcessReturn26wCalculator implements IndicatorCalculator {

    public static final String CODE = "EXCESS_RETURN_26W";

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public DataRequirement dataRequirement() {
        return DataRequirement.priceBars(ExcessReturn.LOOKBACK + 1);
    }

    @Override
    public List<IndicatorPoint> calculate(IndicatorContext context) {
        var bars = context.priceBars();
        var index = context.marketIndex();
        if (bars.size() <= ExcessReturn.LOOKBACK || index.isEmpty()) {
            return List.of();
        }
        List<IndicatorPoint> points = new ArrayList<>(bars.size() - ExcessReturn.LOOKBACK);
        for (int i = ExcessReturn.LOOKBACK; i < bars.size(); i++) {
            OptionalDouble excess = ExcessReturn.at(bars, index, i);
            if (excess.isEmpty()) {
                // 지수가 없는 날짜는 행을 쓰지 않는다. 0으로 채우면 "시장과 같았다"로 읽힌다.
                continue;
            }
            points.add(new IndicatorPoint(bars.get(i).tradeDate(),
                    BigDecimal.valueOf(excess.getAsDouble())
                            .setScale(MovingAverages.SCALE, RoundingMode.HALF_UP)));
        }
        return points;
    }
}
