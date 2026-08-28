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
 * 시장 지수 대비 52주(252거래일) 초과 수익률.
 *
 * <p>공식의 정본은 {@link ExcessReturn}이고 {@code EXCESS_RETURN_26W}와 기간만 다르다.
 * <b>26주판이 있는데 이것을 따로 두는 이유</b> — 상대강도를 다루는 문헌이 쓰는 기간은
 * 12개월이다. 26주로 대신하면 조건은 통과시키지만 그것은 다른 기간을 재는 값이고,
 * 그 사실이 조건 목록 어디에도 남지 않는다.
 *
 * <p>이 값의 <b>유니버스 백분위</b>({@code EXCESS_RETURN_52W_PCTL})가 트렌드 템플릿의
 * 「상대강도 등급 70 이상」에 대응한다. MUST NOT: 그것을 <b>IBD의 RS 등급이라 부르지
 * 않는다</b> — IBD는 분기별 2-1-1-1 가중을 쓰고 우리는 단일 구간 수익률을 쓴다.
 */
@Component
@IndicatorMeta(
        name = "52주 초과 수익률",
        description = "이 종목의 252거래일 수익률에서 같은 구간 시장 지수 수익률을 뺀 값. "
                + "코스피 종목은 코스피 지수와, 코스닥 종목은 코스닥 지수와 견준다.",
        interpretation = "양수면 1년 동안 시장을 이겼다는 뜻이다. "
                + "지수가 없는 날짜에는 값이 아예 없다 — 0으로 채우면 초과 수익률이 "
                + "절대 수익률로 뜻이 바뀌는데 화면에서는 구별되지 않기 때문이다.",
        category = IndicatorCategory.TECHNICAL,
        valueType = IndicatorValueType.PERCENT,
        unit = "%",
        decimalScale = 2,
        direction = IndicatorMeta.Direction.HIGHER_IS_BETTER,
        sortOrder = 136,
        bands = {
                @IndicatorMeta.Band(label = "시장 대비 강함", from = 5),
                @IndicatorMeta.Band(label = "시장 수준", from = -5, to = 5),
                @IndicatorMeta.Band(label = "시장 대비 약함", to = -5)
        }
)
public class ExcessReturn52wCalculator implements IndicatorCalculator {

    public static final String CODE = "EXCESS_RETURN_52W";

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public DataRequirement dataRequirement() {
        return DataRequirement.priceAndMarketIndex(ExcessReturn.LOOKBACK_52W + 1);
    }

    @Override
    public List<IndicatorPoint> calculate(IndicatorContext context) {
        var bars = context.priceBars();
        var index = context.marketIndex();
        if (bars.size() <= ExcessReturn.LOOKBACK_52W || index.isEmpty()) {
            return List.of();
        }
        List<IndicatorPoint> points = new ArrayList<>(bars.size() - ExcessReturn.LOOKBACK_52W);
        for (int i = ExcessReturn.LOOKBACK_52W; i < bars.size(); i++) {
            OptionalDouble excess =
                    ExcessReturn.at(bars, index, i, ExcessReturn.LOOKBACK_52W);
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
