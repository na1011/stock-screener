package com.stockscreener.indicator.calculator;

import com.stockscreener.indicator.DataRequirement;
import com.stockscreener.indicator.FinancialFigures;
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
 * 세후 투하자본이익률 =
 * {@code 영업이익(TTM) × (1 − 유효세율) ÷ (자산총계 − 유동부채)}.
 *
 * <p><b>표준 ROIC다.</b> {@link RoicPretaxCalculator}가 법인세비용이 없어 세전으로 남아 있던
 * 자리이고, 그 컬럼이 적재되면서 이 값이 성립한다.
 *
 * <h2>기간이 섞인다 — 분자는 네 분기, 세율은 한 회계연도다</h2>
 *
 * <p>분자의 영업이익은 <b>최근 4개 분기 합</b>인데 유효세율은 <b>직전 회계연도</b>의 것이다.
 * 분기 법인세를 적재하지 않기 때문이고, 그 선택의 이유는 세율이 연 단위로 안정적이라기보다
 * <b>분기 세금이 Q4에서 비어 TTM 합이 한 칸 빈다</b>는 것이다(어댑터의 기간 가드 javadoc).
 * <b>MUST: 이 혼합을 {@code description}이 말하게 둔다</b> — 안 적으면 그 값이 무엇인지
 * 화면에서 알 수 없다.
 *
 * <p>as-of가 연초여서 직전 회계연도가 아직 공시되지 않았으면 <b>그 전 해</b>의 세율이 붙는다
 * ({@link FinancialMath#byPublication}이 그 시점에 보이던 것만 준다). 연간 공시 지연이
 * 중앙값 80일이라 그 창이 실재한다.
 *
 * <h2>세율의 정의역을 자른다 — {@code EFFECTIVE_TAX_RATE}와 일부러 다르다</h2>
 *
 * <p>{@link FinancialMath#taxRateForNopat}이 {@code [0, 1]}로 자른다.
 * <b>{@code t > 1}은 영업흑자를 세후 적자로 뒤집고</b>, <b>{@code t < 0}은 NOPAT을 영업이익보다
 * 크게 만든다</b> — 뒤의 것은 부호가 아니라 뜻의 문제다. 환입의 원인은 과거 사건의 회계적
 * 인식이라 <b>영업과 무관하고 지속되지 않는데</b>, 그대로 두면 같은 지표가 어떤 해는 영업의
 * 수익성을 어떤 해는 그것에 과거 환입을 더한 것을 잰다. 관측값은 {@code EFFECTIVE_TAX_RATE}가
 * 클램프 없이 그대로 낸다.
 *
 * <p>금융업에서 값이 없는 것은 {@code ROIC_PRETAX}와 같은 이유다 — K-IFRS 금융업은
 * 재무상태표를 유동/비유동으로 가르지 않아 분모의 재료가 오지 않는다.
 */
@Component
@IndicatorMeta(
        name = "TTM 투하자본이익률(세후)",
        description = "최근 4개 분기 영업이익에서 법인세 몫을 뺀 뒤 영업에 묶인 자본"
                + "(자산총계 − 유동부채)으로 나눈 비율. "
                + "세율은 최근 5개 회계연도 유효세율의 중앙값을 쓰므로 분자와 세율의 기간이 다르다. "
                + "세금을 돌려받은 해는 그 해 세율을 0으로 보고 중앙값에 넣는다. "
                + "유동부채를 구분하지 않는 금융업에서는 값이 나오지 않는다."
                + IndicatorMeta.PERIOD_BASIS_NOTE,
        interpretation = "높을수록 사업에 묶어 둔 자본 1원이 세금까지 내고 남기는 몫이 크다. "
                + "자기자본이익률과 달리 빚을 늘려 부풀릴 수 없어, 둘을 함께 보면 "
                + "수익성이 사업에서 온 것인지 레버리지에서 온 것인지 갈린다. "
                + "세전 값과 벌어지는 폭은 유효세율이 정하므로 그 값을 함께 본다.",
        category = IndicatorCategory.FUNDAMENTAL,
        valueType = IndicatorValueType.PERCENT,
        unit = "%",
        direction = IndicatorMeta.Direction.HIGHER_IS_BETTER,
        sortOrder = 57,
        bands = {
                @IndicatorMeta.Band(label = "매우 높음", from = 15),
                @IndicatorMeta.Band(label = "높음", from = 9, to = 15),
                @IndicatorMeta.Band(label = "보통", from = 4, to = 9),
                @IndicatorMeta.Band(label = "낮음", from = 0, to = 4),
                @IndicatorMeta.Band(label = "영업적자", to = 0)
        }
)
public class RoicAfterTaxCalculator implements IndicatorCalculator {

    /** 정규화 세율을 몇 해에서 구하는가. 5년 계열과 같게 두어 TTM과 5년 지표가 같은 세율을 쓴다. */
    static final int TAX_RATE_YEARS = 5;

    public static final String CODE = "ROIC_AFTERTAX";

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public DataRequirement dataRequirement() {
        // 연간 2행을 받는다 — TTM 분자 외에 직전 회계연도 세율이 필요하고, 창 앞머리에서
        // 정규화 세율이 TAX_RATE_YEARS개 연간을 보고, 창 앞머리에서 최신 FY가 아직 공시되지
        // 않아 한 해가 미끄러지는 것을 +1이 받는다.
        return DataRequirement.priceAndFinancial(1, TAX_RATE_YEARS + 1);
    }

    @Override
    public List<IndicatorPoint> calculate(IndicatorContext context) {
        return FinancialMath.byPublication(context, RoicAfterTaxCalculator::valueOf);
    }

    /** 그 봉 시점에 공시돼 있던 재무로 낸 값. 없으면 {@code null}이다(ADR-0128). */
    private static BigDecimal valueOf(IndicatorContext context) {
        FinancialMath.PeriodSeries series = FinancialMath.seriesOf(context);
        BigDecimal operatingIncome =
                series.ttm(FinancialMath::operatingIncomeOf, 0);
        if (operatingIncome == null) {
            return null;
        }

        List<FinancialFigures> annual = context.annualFigures();
        if (annual.isEmpty()) {
            return null;
        }
        // MUST: 직전 한 해가 아니라 정규화 세율이다. 한 해에 매달리면 그 해가 이상치일 때
        // TTM이 통째로 흔들린다 — 실측으로 직전 세율이 35% 이상인 미국 종목이 57, 0인 종목이
        // 37인데 5년 중앙값으로는 35 · 20이다.
        BigDecimal rate = FinancialMath.normalizedTaxRate(annual, TAX_RATE_YEARS);
        if (rate == null) {
            return null;
        }

        // 잔액이므로 최근 분기 하나를 쓴다. 합산하면 네 배가 되는데 값이 그럴듯해 드러나지 않는다.
        FinancialFigures latest = series.latestPoint();
        BigDecimal assets = latest.totalAssets();
        BigDecimal currentLiabilities = latest.currentLiabilities();
        if (assets == null || currentLiabilities == null) {
            // 금융업은 유동부채 자체가 오지 않는다. 위 javadoc 참조.
            return null;
        }
        BigDecimal investedCapital = assets.subtract(currentLiabilities);
        if (investedCapital.signum() <= 0) {
            return null;
        }
        BigDecimal nopat = operatingIncome.multiply(BigDecimal.ONE.subtract(rate));
        return FinancialMath.percentage(nopat, investedCapital);
    }
}
