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
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.springframework.stereotype.Component;

/**
 * 5년 누적 영업활동현금흐름 = {@code sum(operatingCashFlow, 최근 5개 회계연도)}.
 *
 * <p>원본 명세 H-05다. 회계상 이익이 아니라 실제 현금으로 확인하려는 것이며, 단일 연도가
 * 아니라 누적으로 보는 이유는 투자 사이클상 특정 연도 마이너스가 정상인 업종이 있기
 * 때문이다.
 *
 * <p><b>연간(FY) 재무를 쓴다.</b> 분기 현금흐름표의 금액이 3개월인지 누적인지는 실측되지
 * 않았고({@code dart-open-api.md} §11-E), 모른 채로 합치면 4배나 1/4로 조용히 틀린다.
 *
 * <h2>다섯 해가 실제로 다섯 해인지 확인한다</h2>
 *
 * <p>{@code IndicatorDataReader.readAnnualFigures}는 {@code ORDER BY period_end DESC LIMIT ?}라
 * <b>행 수만 맞으면 통과한다.</b> 실제로 267종목이 FY 행을 7개 갖고 있어, FY2021이 빠진 종목도
 * 2020·2022~2025 다섯 행을 받는다 — 구멍이 <b>보이지 않는다.</b> 그래서 여기서 직접 잰다.
 *
 * <p>재는 것은 {@code fiscalYear} 라벨이 아니라 {@code periodEnd} 간격이다.
 * {@link FinancialFigures}가 <b>"fiscalYear는 라벨일 뿐"</b>이라고 못 박고 있고, 결산월이
 * 12월이 아닌 회사에서 그 라벨과 실제 기간이 어긋난다.
 *
 * <p>MUST NOT: 다섯 해 중 하나라도 없을 때 0으로도 네 해 합으로도 접지 않는다. 접으면
 * <b>이력이 짧은 종목일수록 게이트를 잘 통과한다</b> — 정확히 반대다. 값을 내지 않으면 그
 * 종목은 판정불가가 되고, 필수 조건에서 판정불가는 충족이 아니다.
 */
@Component
@IndicatorMeta(
        name = "5년 누적 영업현금흐름",
        description = "최근 5개 회계연도의 영업활동현금흐름을 모두 더한 값. "
                + "다섯 해가 모두 있어야 계산하며, 한 해라도 없으면 값을 내지 않는다.",
        interpretation = "0보다 크면 장기간에 걸쳐 영업으로 현금을 벌어들였다는 뜻이다. "
                + "회계상 이익은 발생주의라 조정의 여지가 있지만 현금흐름은 상대적으로 조작이 어렵다. "
                + "다만 절대금액이므로 회사 규모에 비례한다 — 종목 간 크기 비교에는 쓰지 않는다.",
        category = IndicatorCategory.FUNDAMENTAL,
        valueType = IndicatorValueType.CURRENCY,
        unit = "원",
        direction = IndicatorMeta.Direction.HIGHER_IS_BETTER,
        decimalScale = 0,
        sortOrder = 61
        // MUST NOT: bands를 붙이지 않는다. 절대금액이라 "이 값 이상이면 높음"이 종목마다 다른
        // 뜻이 된다(ADR-0023). IndicatorExtensibilityTest가 CURRENCY의 bands를 거절한다.
)
public class OperatingCashFlow5ySumCalculator implements IndicatorCalculator {

    public static final String CODE = "OCF_SUM_5Y";

    /** 원본 명세가 정한 누적 기간. */
    static final int YEARS = 5;

    /**
     * 연속한 회계연도로 인정하는 {@code period_end} 간격.
     *
     * <p>1년은 365일 또는 366일이다. 폭을 조금 두는 이유는 결산월 변경으로 한 회계기간이
     * 늘거나 줄 수 있기 때문이며, 폭을 넓히면 한 해 건너뛴 구멍이 통과한다 — 그 경우 간격이
     * 730일 안팎이라 위 상한과 멀찍이 떨어져 있다.
     */
    private static final long MIN_GAP_DAYS = 300;
    private static final long MAX_GAP_DAYS = 430;

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
        return FinancialMath.spreadOverTradeDates(context.priceBars(), sumOfFiveYears(context));
    }

    /** @return 다섯 해가 연속하고 모두 값이 있을 때만 합. 아니면 null */
    private static BigDecimal sumOfFiveYears(IndicatorContext context) {
        List<FinancialFigures> annual = context.annualFigures();
        if (annual.size() < YEARS) {
            return null;
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = 0; i < YEARS; i++) {
            FinancialFigures figures = annual.get(i);
            BigDecimal value = figures.operatingCashFlow();
            if (value == null) {
                return null;
            }
            // 읽기가 period_end 내림차순이므로 앞이 더 최근이다.
            if (i > 0 && !isPreviousYear(annual.get(i - 1), figures)) {
                return null;
            }
            sum = sum.add(value);
        }
        return sum;
    }

    private static boolean isPreviousYear(FinancialFigures newer, FinancialFigures older) {
        long gap = ChronoUnit.DAYS.between(older.periodEnd(), newer.periodEnd());
        return gap >= MIN_GAP_DAYS && gap <= MAX_GAP_DAYS;
    }
}
