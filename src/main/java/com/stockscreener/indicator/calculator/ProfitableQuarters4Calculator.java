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
 * 최근 4분기 중 영업이익 흑자 분기 수(0~4).
 *
 * <p>"지금 돈을 벌고 있는가"를 묻는다. 흑자 전환 스토리나 미래 전망에 기대는 종목을 배제하는
 * 가장 단순한 게이트다.
 *
 * <p>순이익이 아니라 <b>영업이익</b>을 센다. 순이익은 자산 처분·평가손익·세금 효과로 흔들려
 * 사업이 돈을 버는지와 어긋나는 분기가 생긴다.
 *
 * <p>4개 전부가 아니라 개수를 내는 이유는 <b>계절성</b> 때문이다. 소매·여행처럼 특정 분기에
 * 정상적으로 적자를 내는 업종이 있고, 그것을 부실로 읽으면 멀쩡한 기업이 통째로 빠진다.
 * 몇 분기를 요구할지는 조건이 정하고, 지표는 사실만 센다.
 *
 * <p>MUST NOT: 분기가 4개에 못 미칠 때 있는 것만 세지 않는다. 2분기 중 2분기 흑자가 4로
 * 읽히면 <b>이력이 짧은 종목일수록 게이트를 잘 통과한다.</b> 값을 내지 않는 것이 옳다.
 */
@Component
@IndicatorMeta(
        name = "흑자 분기 수(최근 4분기)",
        description = "최근 4개 분기 중 영업이익이 0보다 큰 분기의 개수. 순이익이 아니라 영업이익 기준이다.",
        interpretation = "4에 가까울수록 사업이 꾸준히 이익을 내고 있다는 뜻이다. "
                + "계절성이 강한 업종은 특정 분기에 정상적으로 적자를 내므로 "
                + "3이어도 문제가 아닐 수 있다. 0이면 최근 1년 내내 영업적자다.",
        category = IndicatorCategory.FUNDAMENTAL,
        valueType = IndicatorValueType.COUNT,
        decimalScale = 0,
        direction = IndicatorMeta.Direction.HIGHER_IS_BETTER,
        sortOrder = 45,
        bands = {
                @IndicatorMeta.Band(label = "전 분기 흑자", from = 4),
                @IndicatorMeta.Band(label = "대체로 흑자", from = 3, to = 4),
                @IndicatorMeta.Band(label = "흑자·적자 혼재", from = 1, to = 3),
                @IndicatorMeta.Band(label = "연중 적자", to = 1)
        }
)
public class ProfitableQuarters4Calculator implements IndicatorCalculator {

    public static final String CODE = "PROFITABLE_QUARTERS_4";

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
        List<FinancialFigures> quarters = context.quarterlyFigures();
        if (quarters.size() < FinancialMath.TTM_QUARTERS) {
            return List.of();
        }

        int profitable = 0;
        for (int i = 0; i < FinancialMath.TTM_QUARTERS; i++) {
            BigDecimal operatingIncome = FinancialMath.operatingIncomeOf(quarters.get(i));
            // 공시가 빠진 분기가 하나라도 있으면 세지 않는다. 없는 것을 적자로도 흑자로도
            // 읽을 수 없고, 어느 쪽으로 읽든 그 종목의 판정이 조용히 틀린다.
            if (operatingIncome == null) {
                return List.of();
            }
            if (operatingIncome.signum() > 0) {
                profitable++;
            }
        }

        return FinancialMath.spreadOverTradeDates(context.priceBars(), BigDecimal.valueOf(profitable));
    }
}
