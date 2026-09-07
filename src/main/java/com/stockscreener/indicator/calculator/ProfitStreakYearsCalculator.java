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
 * 연속 영업흑자 연수 = 최근 회계연도부터 거슬러 {@code 영업이익 > 0}이 이어진 해의 수.
 *
 * <p>버핏 문서 0.2(입증된 일관된 수익력)·1.2(일관된 영업 이력)·5.1(턴어라운드 회피)이 같은 것을
 * 요구한다. <i>"미래 추정치는 관심 없다. 과거에 실제로 벌어온 기록만 본다"</i>이고, AAII 구현이
 * 그것을 <b>최근 7개 회계연도 각각의 영업이익 흑자</b>로 계량했다. 문서는 이 조건을 <b>"과거
 * 연도별 조건은 필터로 표현 불가"</b>로 분류하지만 그것은 TradingView의 한계다(ADR-0067).
 *
 * <h2>상한 7을 계산기가 스스로 자른다 — 목록 길이에서 유도하지 않는다</h2>
 *
 * <p>{@code IndicatorRegistry.maxFinancialYears()}는 <b>전 계산기의 최댓값 하나</b>이고 러너가
 * 그 하나로 모두에게 같은 목록을 준다. 그러므로 여기서 {@code annual.size()}만큼 세면,
 * 나중에 누군가 {@code financialYears(10)}짜리 지표를 하나 만드는 순간 이 값이 8·9·10까지
 * 나오고 <b>{@code >= 7} 게이트가 조용히 헐거워진다.</b> 그 변화는 어떤 테스트도 깨뜨리지 않는다.
 *
 * <h2>일곱 해가 없으면 값을 내지 않는다</h2>
 *
 * <p>있는 만큼만 세면 <b>이력이 짧은 종목일수록 게이트를 잘 통과한다</b> — 상장 3년차 회사가
 * "3년 연속 흑자"로 3을 받는 것이 아니라, 필요한 이력이 없다는 사실 자체가 판정불가여야 한다.
 * 필수 조건에서 판정불가는 충족이 아니므로 그 종목은 걸러진다.
 *
 * <p>MUST NOT: 이 게이트를 <b>금융업 제외</b>로 읽지 않는다. 일부 금융지주의 FY2021·2022가
 * 소스 정책상 오지 않아 금융업 통과율이 낮지만(실측 50종목 중 14), 그것은 <b>설계가 아니라
 * 부작용</b>이고 열넷은 그대로 통과한다.
 */
@Component
@IndicatorMeta(
        name = "연속 영업흑자 연수",
        description = "최근 회계연도부터 거슬러 영업이익이 0보다 큰 해가 몇 해 이어졌는지 센다. "
                + "최대 7년까지 세며, 연속한 7개 회계연도가 없으면 값을 내지 않는다 — "
                + "있는 만큼만 세면 이력이 짧은 종목이 오히려 유리해지기 때문이다.",
        interpretation = "클수록 오래 흑자를 이어 왔다는 뜻이다. 7이면 관측 구간 전체에서 "
                + "한 해도 영업적자가 없었다는 뜻이고, 회생주와 사이클 바닥에 있는 종목이 여기서 갈린다. "
                + "영업이익 기준이므로 영업 밖 손익으로 순손실이 난 해는 이 값에 반영되지 않는다.",
        category = IndicatorCategory.FUNDAMENTAL,
        valueType = IndicatorValueType.COUNT,
        unit = "년",
        decimalScale = 0,
        direction = IndicatorMeta.Direction.HIGHER_IS_BETTER,
        sortOrder = 48,
        bands = {
                @IndicatorMeta.Band(label = "전 구간 흑자", from = 7),
                @IndicatorMeta.Band(label = "길다", from = 5, to = 7),
                @IndicatorMeta.Band(label = "보통", from = 3, to = 5),
                @IndicatorMeta.Band(label = "짧다", from = 1, to = 3),
                @IndicatorMeta.Band(label = "직전 해가 영업적자", to = 1)
        }
)
public class ProfitStreakYearsCalculator implements IndicatorCalculator {

    public static final String CODE = "PROFIT_STREAK_Y";

    /**
     * AAII 구현이 요구하는 연수이자 이 지표의 상한.
     *
     * <p>MUST: 상한을 여기서 정한다. 위 javadoc의 「목록 길이에서 유도하지 않는다」가 이 상수의
     * 존재 이유다.
     */
    static final int YEARS = 7;

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public DataRequirement dataRequirement() {
        return DataRequirement.priceAndFinancial(1, YEARS);
    }

    @Override
    public List<IndicatorPoint> calculate(IndicatorContext context) {
        return FinancialMath.byPublication(context, ProfitStreakYearsCalculator::streak);
    }

    /**
     * @return 일곱 해가 연속으로 있고 <b>그 해들의 영업이익이 전부 채워져 있을 때만</b> 흑자 연수.
     *         아니면 null
     */
    private static BigDecimal streak(IndicatorContext context) {
        List<FinancialFigures> annual = context.annualFigures();
        if (!FinancialMath.isConsecutiveAnnual(annual, YEARS)) {
            return null;
        }
        int streak = 0;
        for (int i = 0; i < YEARS; i++) {
            BigDecimal operatingIncome = FinancialMath.operatingIncomeOf(annual.get(i));
            if (operatingIncome == null) {
                // MUST: 계정이 비어 있으면 값을 내지 않는다. 예전엔 여기서 끊고 지금까지 센 수를
                // 냈는데, 그러면 **결손이 「짧은 흑자 연수」라는 그럴듯한 수로 나온다.** 특히 첫
                // 해가 비면 0이 나가고 밴드가 그것을 「직전 해가 영업적자」로 그린다 — 그 회사에
                // 대한 거짓 사실이다.
                //
                // 국내에서는 이 갈래가 한 번도 안 걸려 무증상이었다(실측 0종목). 미국을 재니
                // 값이 나오는 828종목 중 **350**이 이 갈래였다(1년차 결손 201 + 2~7년차 결손 149).
                // 「끊는다」가 「거짓을 말한다」로 바뀐 것이 이 결정의 근거다.
                return null;
            }
            if (operatingIncome.signum() <= 0) {
                break;
            }
            streak++;
        }
        return BigDecimal.valueOf(streak);
    }
}
