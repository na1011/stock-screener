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
 * 52주(252거래일) 절대 수익률.
 *
 * <p>린치의 회피 조건 하나가 이것을 요구한다 — "가장 인기 있는 산업의 가장 인기 있는 종목은
 * 사지 않는다"(피터린치-방법론 5.1). 원본이 건 필터가 {@code Performance 1Y < 100}이고,
 * <b>지수 대비가 아니라 절대값</b>이다. 시장이 통째로 두 배가 된 해에는 초과 수익률이 0인
 * 종목도 「1년에 100% 오른 종목」이므로 {@code EXCESS_RETURN_52W}로 대신할 수 없다.
 *
 * <p>공식은 {@link ExcessReturn#stockReturnAt}에 있다 — 초과 수익률이 쓰는 <b>종목 쪽 항과
 * 같은 것</b>이며 여기서 다시 짜지 않는다. 그 파일이 "MUST NOT: 기간별로 이 공식을 다시 짜지
 * 않는다"를 적어 둔 이유가 수정종가 규칙이 한쪽에만 반영되는 것을 막기 위해서다.
 *
 * <p>지수를 보지 않으므로 {@code EXCESS_RETURN_52W}와 달리 <b>지수가 없는 날짜에도 값이
 * 난다.</b> 커버리지가 더 넓은 것은 결함이 아니라 재는 대상이 다른 것이다.
 */
@Component
@IndicatorMeta(
        name = "52주 수익률",
        description = "1년(252거래일) 전 수정종가와 견준 등락률. 분할·증자의 영향을 걷어낸 값이다.",
        interpretation = "1년 동안 주가가 얼마나 올랐는지를 그대로 보여준다. "
                + "지나치게 크면 이미 오를 만큼 오른 종목일 수 있다 — "
                + "시장 전체와 견준 값은 52주 초과 수익률이 따로 답한다.",
        category = IndicatorCategory.TECHNICAL,
        valueType = IndicatorValueType.PERCENT,
        unit = "%",
        decimalScale = 2,
        direction = IndicatorMeta.Direction.NEUTRAL,
        sortOrder = 134,
        bands = {
                @IndicatorMeta.Band(label = "급등", from = 100),
                @IndicatorMeta.Band(label = "상승", from = 20, to = 100),
                @IndicatorMeta.Band(label = "보합", from = -20, to = 20),
                @IndicatorMeta.Band(label = "하락", to = -20)
        }
)
public class Return52wCalculator implements IndicatorCalculator {

    public static final String CODE = "RETURN_52W";

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public DataRequirement dataRequirement() {
        return DataRequirement.priceBars(ExcessReturn.LOOKBACK_52W + 1);
    }

    @Override
    public List<IndicatorPoint> calculate(IndicatorContext context) {
        var bars = context.priceBars();
        if (bars.size() <= ExcessReturn.LOOKBACK_52W) {
            return List.of();
        }
        List<IndicatorPoint> points = new ArrayList<>(bars.size() - ExcessReturn.LOOKBACK_52W);
        for (int i = ExcessReturn.LOOKBACK_52W; i < bars.size(); i++) {
            OptionalDouble ret =
                    ExcessReturn.stockReturnAt(bars, i, ExcessReturn.LOOKBACK_52W);
            if (ret.isEmpty()) {
                continue;
            }
            points.add(new IndicatorPoint(bars.get(i).tradeDate(),
                    BigDecimal.valueOf(ret.getAsDouble())
                            .setScale(MovingAverages.SCALE, RoundingMode.HALF_UP)));
        }
        return points;
    }
}
