package com.stockscreener.indicator.calculator;

import com.stockscreener.indicator.DataRequirement;
import com.stockscreener.indicator.IndicatorCalculator;
import com.stockscreener.indicator.IndicatorCategory;
import com.stockscreener.indicator.IndicatorContext;
import com.stockscreener.indicator.IndicatorMeta;
import com.stockscreener.indicator.IndicatorPoint;
import com.stockscreener.indicator.IndicatorValueType;
import com.stockscreener.indicator.PriceBar;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 변화율(ROC) — 12거래일 전 종가 대비 몇 % 움직였는가.
 *
 * <p>가장 단순한 모멘텀 지표다. MACD와 방향은 대체로 같이 가지만 <b>임계를 숫자로 걸기 쉽다</b> —
 * MACD는 가격 단위라 종목 간 비교가 안 되는 반면 이것은 %다.
 *
 * <p>전 구간 수정종가를 쓴다. 원주가를 쓰면 분할일에 −80% 같은 값이 나오고, 그것이 정상적인
 * 급락과 구별되지 않는다.
 */
@Component
@IndicatorMeta(
        name = "변화율(12일)",
        description = "12거래일 전 종가와 견준 등락률. 3주가 채 안 되는 기간의 순수한 가격 모멘텀이다.",
        interpretation = "양수면 오르는 중이고 음수면 내리는 중이다. "
                + "±5%를 넘으면 방향이 뚜렷하다고 보며, 그 안이면 횡보로 읽는다. "
                + "값이 크다고 계속 오른다는 뜻은 아니어서 과열 지표와 함께 본다.",
        category = IndicatorCategory.OSCILLATOR,
        valueType = IndicatorValueType.PERCENT,
        unit = "%",
        decimalScale = 2,
        direction = IndicatorMeta.Direction.HIGHER_IS_BETTER,
        sortOrder = 207,
        bands = {
                @IndicatorMeta.Band(label = "상승 모멘텀", from = 5),
                @IndicatorMeta.Band(label = "횡보", from = -5, to = 5),
                @IndicatorMeta.Band(label = "하락 모멘텀", to = -5)
        }
)
public class Roc12Calculator implements IndicatorCalculator {

    public static final String CODE = "ROC_12";
    private static final int PERIOD = 12;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public DataRequirement dataRequirement() {
        // 12일 전 봉과 견주므로 그 봉까지 포함해 13개가 있어야 첫 값이 선다.
        return DataRequirement.priceBars(PERIOD + 1);
    }

    @Override
    public List<IndicatorPoint> calculate(IndicatorContext context) {
        List<PriceBar> bars = context.priceBars();
        if (bars.size() <= PERIOD) {
            return List.of();
        }

        List<IndicatorPoint> points = new ArrayList<>(bars.size() - PERIOD);
        for (int i = PERIOD; i < bars.size(); i++) {
            BigDecimal past = bars.get(i - PERIOD).adjClose();
            if (past.signum() == 0) {
                // 수정종가가 0이면 수집 결함이다. 분모로 쓸 수 없으니 그 날짜는 내지 않는다.
                continue;
            }
            BigDecimal change = bars.get(i).adjClose().subtract(past)
                    .multiply(HUNDRED)
                    .divide(past, MovingAverages.MC)
                    .setScale(MovingAverages.SCALE, RoundingMode.HALF_UP);
            points.add(new IndicatorPoint(bars.get(i).tradeDate(), change));
        }
        return points;
    }
}
