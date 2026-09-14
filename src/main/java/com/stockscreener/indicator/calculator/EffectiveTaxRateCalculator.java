package com.stockscreener.indicator.calculator;

import com.stockscreener.indicator.DataRequirement;
import com.stockscreener.indicator.IndicatorCalculator;
import com.stockscreener.indicator.IndicatorCategory;
import com.stockscreener.indicator.IndicatorContext;
import com.stockscreener.indicator.IndicatorMeta;
import com.stockscreener.indicator.IndicatorPoint;
import com.stockscreener.indicator.IndicatorValueType;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.stereotype.Component;

/**
 * 유효세율 = {@code 법인세비용 ÷ 세전이익 × 100} (직전 회계연도).
 *
 * <p><b>세후 투하자본이익률 계열의 재료이자 그 값을 읽는 열쇠다.</b> 세후 값이 세전과 크게
 * 다르거나 5년 지표가 비어 있을 때, 그 이유가 여기에 있다 — 이 값이 있는데 세후 5년이 없으면
 * <b>5년 중 세전 적자인 해가 있다</b>는 뜻이고, 이 값도 없으면 <b>세율 재료 자체가 없다</b>.
 *
 * <h2>클램프하지 않는다 — NOPAT 쪽과 일부러 다르다</h2>
 *
 * <p>{@link FinancialMath#taxRateForNopat}은 정의역을 {@code [0, 1]}로 자르지만 <b>이 지표는
 * 관측값을 그대로 낸다.</b> 세금을 돌려받은 해는 음수로 뜨고 그것이 사실이다 — 실측에서
 * 삼성전자 2023이 <b>−40.7%</b>였다. 두 값이 나란히 있어야 세후 투하자본이익률이 왜 그렇게
 * 나왔는지 화면에서 갈린다.
 *
 * <h2>부호는 항등식으로 판정한다</h2>
 *
 * <p>법인세비용의 음수가 두 뜻이라 {@link FinancialMath#effectiveTaxRateOf}가 그것을 가른다 —
 * <b>진짜 환입</b>(순이익 &gt; 세전이익)과 <b>차감 항목 표기</b>(순이익 &lt; 세전이익)다.
 * <b>MUST NOT: 크기(abs)로 읽지 않는다</b> — 자본적지출과 달리 환입이 실재하므로 그러면
 * 진짜 환입의 부호가 뒤집힌다.
 *
 * <p>세전이익이 0 이하인 해는 값을 내지 않는다. 음수 분모는 부호를 뒤집어 세율이 뜻을 잃는다.
 */
@Component
@IndicatorMeta(
        name = "유효세율",
        description = "직전 회계연도에 회사가 실제로 부담한 법인세 비율. "
                + "법인세비용을 세전이익으로 나눈 값이며, 세금을 돌려받은 해는 음수로 나온다. "
                + "세전이익이 적자인 해는 비율이 뜻을 갖지 않아 값을 내지 않는다.",
        interpretation = "세후 투하자본이익률이 세전과 얼마나 벌어지는지를 정하는 값이다. "
                + "낮다고 무조건 좋은 것은 아니다 — 이월결손금이나 일회성 공제로 한 해만 낮아진 것일 수 있고, "
                + "그런 해는 다음 해에 제자리로 돌아온다. 여러 해를 함께 보면 그 구분이 드러난다. "
                + "음수는 그 해에 세금을 돌려받았다는 뜻이다.",
        category = IndicatorCategory.FUNDAMENTAL,
        valueType = IndicatorValueType.PERCENT,
        unit = "%",
        sortOrder = 49,
        bands = {
                @IndicatorMeta.Band(label = "높음", from = 30),
                @IndicatorMeta.Band(label = "보통", from = 20, to = 30),
                @IndicatorMeta.Band(label = "낮음", from = 10, to = 20),
                @IndicatorMeta.Band(label = "매우 낮음", from = 0, to = 10),
                @IndicatorMeta.Band(label = "환급", to = 0)
        }
)
public class EffectiveTaxRateCalculator implements IndicatorCalculator {

    public static final String CODE = "EFFECTIVE_TAX_RATE";

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public DataRequirement dataRequirement() {
        // MUST NOT: financialYears만 선언하지 않는다. priceBarsNeeded가 0이면
        // UnadjustedActionGuard가 창 0으로 불려 미보정 기업행위 억제(ADR-0055)가 무효가 된다.
        return DataRequirement.priceAndFinancial(1, 1);
    }

    @Override
    public List<IndicatorPoint> calculate(IndicatorContext context) {
        return FinancialMath.byPublication(context, EffectiveTaxRateCalculator::valueOf);
    }

    /** 그 봉 시점에 공시돼 있던 직전 회계연도로 낸 값. 없으면 {@code null}이다(ADR-0128). */
    private static BigDecimal valueOf(IndicatorContext context) {
        List<com.stockscreener.indicator.FinancialFigures> annual = context.annualFigures();
        if (annual.isEmpty()) {
            return null;
        }
        return FinancialMath.effectiveTaxRateOf(annual.get(0));
    }
}
