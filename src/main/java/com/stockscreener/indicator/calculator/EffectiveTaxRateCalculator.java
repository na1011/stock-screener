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
        name = "유효세율(5년 중앙값)",
        description = "최근 5개 회계연도에 회사가 실제로 부담한 법인세 비율의 중앙값. "
                + "해마다 법인세비용을 세전이익으로 나눈 뒤 0~100% 안으로 자르고 그 가운뎃값을 쓴다. "
                + "세전이익이 적자인 해는 비율이 뜻을 갖지 않아 그 해를 빼고 센다. "
                + "세후 투하자본이익률 세 지표가 모두 이 값을 쓴다.",
        interpretation = "세후 투하자본이익률이 세전과 얼마나 벌어지는지를 정하는 값이다. "
                + "한 해 값이 아니라 여러 해의 가운뎃값이므로, 이월결손금이나 일회성 공제로 한 해만 "
                + "낮아진 것에 끌려가지 않는다. 그래서 세후 값이 해마다 널뛰지 않는다. "
                + "0%는 관측된 해가 모두 세금을 돌려받았거나 면제받았다는 뜻이다.",
        category = IndicatorCategory.FUNDAMENTAL,
        valueType = IndicatorValueType.PERCENT,
        unit = "%",
        sortOrder = 49,
        bands = {
                @IndicatorMeta.Band(label = "높음", from = 30),
                @IndicatorMeta.Band(label = "보통", from = 20, to = 30),
                @IndicatorMeta.Band(label = "낮음", from = 10, to = 20),
                @IndicatorMeta.Band(label = "매우 낮음", to = 10)
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
        // 정규화 세율이 보는 해와 같게 두고, +1은 창 앞머리에서 최신 FY가 아직 공시되지 않아
        // 한 해가 미끄러지는 것을 받는다.
        return DataRequirement.priceAndFinancial(1, RoicAfterTaxCalculator.TAX_RATE_YEARS + 1);
    }

    @Override
    public List<IndicatorPoint> calculate(IndicatorContext context) {
        return FinancialMath.byPublication(context, EffectiveTaxRateCalculator::valueOf);
    }

    /**
     * 그 봉 시점에 공시돼 있던 최근 다섯 해의 정규화 세율. 없으면 {@code null}이다(ADR-0128).
     *
     * <p><b>MUST: 세후 지표가 실제로 쓰는 값과 같아야 한다.</b> 여기만 「직전 한 해의 관측값」을
     * 내면 화면에서 {@code 세후 ≈ 세전 × (1 − 유효세율)}이 성립하지 않고, 사용자는 두 카드가
     * 어긋난 이유를 알 길이 없다. 그래서 클램프까지 같은 것을 쓴다.
     */
    private static BigDecimal valueOf(IndicatorContext context) {
        List<com.stockscreener.indicator.FinancialFigures> annual = context.annualFigures();
        BigDecimal ratio =
                FinancialMath.normalizedTaxRate(annual, RoicAfterTaxCalculator.TAX_RATE_YEARS);
        if (ratio == null) {
            return null;
        }
        return ratio.multiply(java.math.BigDecimal.valueOf(100));
    }
}
