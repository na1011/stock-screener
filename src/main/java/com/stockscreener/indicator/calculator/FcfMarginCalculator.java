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
 * 잉여현금흐름 마진 = {@code (영업활동현금흐름 − 자본적지출) ÷ 매출 × 100}. 연간 기준이다.
 *
 * <p>버핏 문서 1.6(자본 소요가 적은가)·3.3(오너 어닝의 대체)·5.4(과도한 자본집약 회피)가 같은
 * 값을 요구한다. 걸러 내려는 것은 <b>순이익은 나는데 현금이 안 남는 회사</b>다 — 번 돈을 설비
 * 유지에 계속 되돌려 넣어야 하는 사업은 주주에게 돌아오는 것이 없고, 그것이 2019년 주주서한이
 * <i>"영업에 투입된 순유형자본 대비 좋은 수익률"</i>을 첫 기준으로 든 이유다.
 *
 * <p>계산과 그 함정은 {@link FreeCashFlow}에 있다 — 특히 <b>오너 어닝이 아니라는 것</b>과
 * <b>자본적지출이 없을 때 0으로 보지 않는다는 것</b>.
 *
 * <p><b>연간 기준이라</b> 분기 기준인 {@code OPERATING_MARGIN}·{@code NET_MARGIN}과 기준
 * 시점이 다르고, 1년에 한 번만 바뀐다.
 */
@Component
@IndicatorMeta(
        name = "잉여현금흐름 마진",
        description = "가장 최근 회계연도의 영업활동현금흐름에서 자본적지출을 뺀 값을 "
                + "같은 해 매출로 나눈 비율. 연간 기준이라 1년에 한 번 바뀐다. "
                + "영업활동현금흐름이나 자본적지출 어느 한쪽이 없으면 값을 내지 않는다.",
        // MUST NOT: 이 문구에 소스 한계를 적지 않는다. 화면은 두 시장에 같은 문장을 보여 주는데
        // 소스 계약은 시장마다 다르고, 한 번 적히면 아무도 다시 안 묻는다 — 여기 있던
        // "감가상각비를 소스가 주지 않는다"가 미국에서 거짓이었다(2026-09-19 실측).
        interpretation = "높을수록 벌어들인 현금 중 설비 유지·확장에 되돌려 넣고도 남는 몫이 크다. "
                + "이익률은 높은데 이 값이 낮으면 번 돈이 계속 설비로 나가는 자본집약적 사업이라는 뜻이다. "
                + "다만 이 값은 오너 어닝이 아니다 — 운전자본 증분과 감가상각·상각을 반영하지 않았다.",
        category = IndicatorCategory.FUNDAMENTAL,
        valueType = IndicatorValueType.PERCENT,
        unit = "%",
        direction = IndicatorMeta.Direction.HIGHER_IS_BETTER,
        sortOrder = 64,
        bands = {
                @IndicatorMeta.Band(label = "매우 높음", from = 15),
                @IndicatorMeta.Band(label = "높음", from = 8, to = 15),
                @IndicatorMeta.Band(label = "보통", from = 3, to = 8),
                @IndicatorMeta.Band(label = "낮음", from = 0, to = 3),
                @IndicatorMeta.Band(label = "현금이 나가고 있다", to = 0)
        }
)
public class FcfMarginCalculator implements IndicatorCalculator {

    public static final String CODE = "FCF_MARGIN";

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public DataRequirement dataRequirement() {
        return DataRequirement.priceAndFinancial(1, 1);
    }

    @Override
    public List<IndicatorPoint> calculate(IndicatorContext context) {
        return FinancialMath.byPublication(context, FcfMarginCalculator::valueOf);
    }

    /** 그 봉 시점에 공시돼 있던 재무로 낸 값. 없으면 {@code null}이다(ADR-0128). */
    private static BigDecimal valueOf(IndicatorContext context) {
        List<FinancialFigures> annual = context.annualFigures();
        BigDecimal freeCashFlow = FreeCashFlow.latestAnnual(annual);
        if (freeCashFlow == null) {
            return null;
        }
        BigDecimal revenue = annual.get(0).revenue();
        if (revenue == null || revenue.signum() <= 0) {
            return null;
        }
        return FinancialMath.percentage(freeCashFlow, revenue);
    }
}
