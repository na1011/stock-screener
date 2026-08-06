package com.stockscreener.indicator.calculator;

import com.stockscreener.indicator.FinancialFigures;

import java.math.BigDecimal;
import java.util.List;
import java.util.function.Function;

/**
 * 전년 대비 증감률 계산.
 *
 * <p>매출·영업이익 등 성장률 지표가 모두 같은 형태라 한곳에 둔다. 계산기마다 다시 쓰면
 * 그중 하나만 분모 처리를 다르게 해도 성장률끼리 비교가 어긋난다.
 */
final class YearOverYear {

    private YearOverYear() {
    }

    /**
     * 연간 재무 목록(최신 우선)에서 전년 대비 증감률을 백분율로 계산한다.
     *
     * <p>연간 재무가 2개 미만이거나 어느 한 해의 값이 비어 있으면 null이다.
     *
     * <p>분모는 <b>절댓값</b>이다. 직전 값이 음수일 때 부호가 뒤집혀 악화가 개선으로
     * 보이는 것을 막는다 — 적자가 커진 기업의 이익 성장률이 양수로 나오는 것이 그 예다.
     */
    static BigDecimal growth(List<FinancialFigures> annual,
                             Function<FinancialFigures, BigDecimal> field) {
        if (annual.size() < 2) {
            return null;
        }
        BigDecimal current = field.apply(annual.get(0));
        BigDecimal previous = field.apply(annual.get(1));
        if (current == null || previous == null || previous.signum() == 0) {
            return null;
        }
        return FinancialMath.percentage(current.subtract(previous), previous.abs());
    }
}
