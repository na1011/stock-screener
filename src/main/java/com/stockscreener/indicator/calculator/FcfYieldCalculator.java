package com.stockscreener.indicator.calculator;

import com.stockscreener.indicator.DataRequirement;
import com.stockscreener.indicator.IndicatorCalculator;
import com.stockscreener.indicator.IndicatorCategory;
import com.stockscreener.indicator.IndicatorContext;
import com.stockscreener.indicator.IndicatorMeta;
import com.stockscreener.indicator.IndicatorPoint;
import com.stockscreener.indicator.IndicatorValueType;
import com.stockscreener.indicator.PriceBar;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.NavigableMap;
import java.util.Set;

import org.springframework.stereotype.Component;

/**
 * 잉여현금흐름 수익률 = {@code (영업활동현금흐름 − 자본적지출) ÷ 시가총액 × 100}.
 *
 * <p>버핏 문서 4.2, <b>안전마진</b>이다. AAII 구현이 밸류에이션 축으로 PER이 아니라 P/FCF를
 * 쓰는 것이 이 지표의 근거이며, 문서가 <i>"버핏이 FCF를 중시하므로"</i>라고 적은 자리다.
 *
 * <p><b>{@code PFCF}를 따로 만들지 않는다.</b> 그것은 이 값의 역수라 <b>같은 값을 두 이름으로
 * 부르는 것</b>이 된다 — 문서 4.2가 {@code P/FCF ≤ 20}과 {@code FCF Yield ≥ 5}를 나란히
 * 적지만 둘은 같은 조건이다. 수익률 쪽을 택한 이유는 <b>잉여현금흐름이 음수일 때도 뜻이
 * 있기 때문</b>이다 — 역수는 그 자리에서 부호가 뒤집혀 「아주 싼 종목」으로 보인다.
 *
 * <p>시가총액은 {@link MarketCapCalculator}에 의존한다. 다시 계산하지 않는 이유는 그쪽이
 * <b>원주가와 원장 보정 주식수</b>를 쓰기 때문이며(권리락 구간의 처리가 거기 있다), 갈라
 * 구현하면 두 값이 어긋난 채 둘 다 그럴듯한 수를 낸다.
 *
 * <p>계산과 그 함정은 {@link FreeCashFlow}에 있다 — 특히 <b>오너 어닝이 아니라는 것</b>.
 */
@Component
@IndicatorMeta(
        name = "잉여현금흐름 수익률",
        description = "가장 최근 회계연도의 영업활동현금흐름에서 자본적지출을 뺀 값을 "
                + "그날의 시가총액으로 나눈 비율. 잉여현금흐름은 연간 기준이고 시가총액은 매일 바뀐다. "
                + "영업활동현금흐름이나 자본적지출 어느 한쪽이 없으면 값을 내지 않는다.",
        interpretation = "높을수록 지금 시가에 견줘 회사가 만들어 내는 현금이 많다는 뜻이다. "
                + "PER이 회계상 이익을 보는 데 비해 이 값은 실제로 남는 현금을 보므로, "
                + "둘이 크게 어긋나면 이익과 현금흐름의 괴리를 의심할 자리다. "
                + "음수면 설비 투자를 빼고 나면 현금이 오히려 나가고 있다는 뜻이다.",
        category = IndicatorCategory.FUNDAMENTAL,
        valueType = IndicatorValueType.PERCENT,
        unit = "%",
        direction = IndicatorMeta.Direction.HIGHER_IS_BETTER,
        sortOrder = 65,
        bands = {
                @IndicatorMeta.Band(label = "매우 높음", from = 10),
                @IndicatorMeta.Band(label = "높음", from = 5, to = 10),
                @IndicatorMeta.Band(label = "보통", from = 2, to = 5),
                @IndicatorMeta.Band(label = "낮음", from = 0, to = 2),
                @IndicatorMeta.Band(label = "현금이 나가고 있다", to = 0)
        }
)
public class FcfYieldCalculator implements IndicatorCalculator {

    public static final String CODE = "FCF_YIELD";

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public DataRequirement dataRequirement() {
        return new DataRequirement(1, 1, Set.of(MarketCapCalculator.CODE));
    }

    @Override
    public List<IndicatorPoint> calculate(IndicatorContext context) {
        BigDecimal freeCashFlow = FreeCashFlow.latestAnnual(context.annualFigures());
        if (freeCashFlow == null) {
            return List.of();
        }
        NavigableMap<LocalDate, BigDecimal> marketCap =
                context.dependency(MarketCapCalculator.CODE);

        List<IndicatorPoint> points = new ArrayList<>(context.priceBars().size());
        for (PriceBar bar : context.priceBars()) {
            BigDecimal cap = marketCap.get(bar.tradeDate());
            if (cap == null || cap.signum() <= 0) {
                continue;
            }
            points.add(new IndicatorPoint(
                    bar.tradeDate(), FinancialMath.percentage(freeCashFlow, cap)));
        }
        return points;
    }
}
