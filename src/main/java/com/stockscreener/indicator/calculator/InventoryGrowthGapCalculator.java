package com.stockscreener.indicator.calculator;

import com.stockscreener.indicator.DataRequirement;
import com.stockscreener.indicator.FinancialFigures;
import com.stockscreener.indicator.IndicatorCalculator;
import com.stockscreener.indicator.IndicatorCategory;
import com.stockscreener.indicator.IndicatorContext;
import com.stockscreener.indicator.IndicatorMeta;
import com.stockscreener.indicator.IndicatorPoint;
import com.stockscreener.indicator.IndicatorValueType;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * 재고 증가율 − 매출 증가율(%p). 린치 3.14의 이식이다.
 *
 * <p>린치가 재고를 본 이유는 재고 자체가 아니라 <b>둘의 속도 차</b>다. 매출보다 재고가 빨리
 * 늘면 팔리지 않은 물건이 쌓이는 중이고, 그것은 손익계산서에 아직 안 나타난다. 그래서 한쪽만
 * 지표로 두면 뜻이 없다 — 값 하나로 만들어 <b>음수가 좋은 방향</b>이 되게 한다.
 *
 * <p><b>두 증가율을 같은 두 회계연도에서 계산한다.</b> {@code REVENUE_GROWTH}를 조건의 우변으로
 * 참조해 비교할 수도 있었지만, 그러면 재고가 빠진 해가 있는 종목에서 <b>두 지표가 서로 다른 해를
 * 짝지어</b> 비교가 조용히 어긋난다.
 *
 * <p><b>MUST: 회계연도가 연속인지 확인한다.</b> {@link YearOverYear#growth}는 목록의 0번과 1번을
 * 무조건 짝짓고 <b>연도 간격을 보지 않는다.</b> 공시가 한 해 빠진 종목에서 그대로 쓰면 2년치
 * 변화를 1년치라고 부르게 된다 — {@code EPS_CAGR_5Y}가 {@code spansFiveYears}로 막아 둔 것과
 * 같은 함정이고, 여기서는 {@code fiscalYear}가 있어 뺄셈으로 끝난다.
 *
 * <p><b>연간 전용이다.</b> {@code financial_statement.inventories}가 FY 행에만 있고 분기는 전부
 * 비어 있다(ADR-0069). {@code full-statement-refill}이 {@code --quarters=true} 없이는 그 경로를
 * 돌지 않는다. FY2021 이전도 0행이라 <b>실질적으로 최근 두 해만 성립한다.</b>
 *
 * <p>직전 재고가 0이면 값을 내지 않는다({@link YearOverYear#between}의 규약). 재고를 갖지 않는
 * 사업모델이 대부분이고, 0에서 늘어난 것을 무한대 증가율로 부르는 것보다 침묵이 낫다.
 */
@Component
@IndicatorMeta(
        name = "재고-매출 증가율 차",
        description = "재고자산 증가율에서 매출 증가율을 뺀 값(%p). 최근 두 회계연도로 계산한다.",
        interpretation = "음수면 매출이 재고보다 빨리 늘고 있다는 뜻이다. "
                + "양수가 크면 팔리지 않은 재고가 쌓이는 중일 수 있고, 그것은 손익계산서에 "
                + "아직 드러나지 않는다. 재고를 갖지 않는 업종에는 값이 없다.",
        category = IndicatorCategory.FUNDAMENTAL,
        valueType = IndicatorValueType.PERCENT,
        unit = "%p",
        direction = IndicatorMeta.Direction.LOWER_IS_BETTER,
        decimalScale = 2,
        sortOrder = 73,
        bands = {
                @IndicatorMeta.Band(label = "재고 급증", from = 20),
                @IndicatorMeta.Band(label = "재고가 더 빠름", from = 0, to = 20),
                @IndicatorMeta.Band(label = "매출이 더 빠름", to = 0)
        }
)
public class InventoryGrowthGapCalculator implements IndicatorCalculator {

    public static final String CODE = "INVENTORY_GROWTH_GAP";

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public DataRequirement dataRequirement() {
        // 전년 대비이므로 연간 재무 2개가 필요하다.
        return DataRequirement.priceAndFinancial(1, 2);
    }

    @Override
    public List<IndicatorPoint> calculate(IndicatorContext context) {
        return FinancialMath.byPublication(context, InventoryGrowthGapCalculator::valueOf);
    }

    /** 그 봉 시점에 공시돼 있던 재무로 낸 값. 없으면 {@code null}이다(ADR-0128). */
    private static BigDecimal valueOf(IndicatorContext context) {
        List<FinancialFigures> annual = context.annualFigures();
        if (!YearOverYear.consecutive(annual)) {
            return null;
        }
        BigDecimal inventoryGrowth = YearOverYear.growth(annual, FinancialFigures::inventories);
        BigDecimal revenueGrowth = YearOverYear.growth(annual, FinancialFigures::revenue);
        if (inventoryGrowth == null || revenueGrowth == null) {
            return null;
        }
        return inventoryGrowth.subtract(revenueGrowth);
    }
}
