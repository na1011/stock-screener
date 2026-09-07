package com.stockscreener.indicator.calculator;

import com.stockscreener.indicator.FinancialFigures;

import java.math.BigDecimal;
import java.util.List;

/**
 * 매출총이익 = 매출 − 매출원가. <b>연간(FY) 기준</b>이며 소비자가 둘이다
 * ({@code GROSS_MARGIN}·{@code GP_TO_ASSETS}).
 *
 * <p>IMPORTANT — <b>연간만 쓴다.</b> 분기 매출원가는 받지 않았다(ADR-0062 — 연간만 받으면
 * 종목당 6콜이고 분기까지면 네 배다). 그래서 이 값은 <b>연 1회만 갱신되고 최대 8개월까지
 * 낡는다</b> — 같은 화면의 {@code OPERATING_MARGIN}은 분기 TTM이라 기저가 다르다.
 *
 * <p><b>매출원가가 없는 것은 결손이 아닐 수 있다.</b> 금융업·서비스업은 개념 자체가 없어
 * 원래 오지 않는다(실측: NAVER는 FY2021에만 있고 FY2022부터 없다). 그래서 값이 없는
 * 종목을 「수집 실패」로 읽으면 안 되며, <b>업종에 따라 이 축이 통째로 성립하지 않는다</b>는
 * 사실이 지표 설명에 있어야 한다.
 */
final class GrossProfit {

    private GrossProfit() {
    }

    /** 가장 최근 회계연도. 읽기가 {@code period_end} 내림차순이라 앞이 더 최근이다. */
    static FinancialFigures latestAnnual(List<FinancialFigures> annual) {
        return annual.isEmpty() ? null : annual.get(0);
    }

    /**
     * <b>회사가 신고한 매출총이익이 이긴다.</b> 없을 때만 매출 − 매출원가로 유도한다.
     *
     * <p>예전에는 신고값을 <b>한 번도 보지 않고</b> 언제나 유도했다. 적재는 되고 있었고
     * (US_TOP1000의 417종목이 갖는다) 소비자만 안 읽은 것이라, ADR-0104 §규칙의
     * <i>"소스가 낸 값이 언제나 이긴다"</i>와 방향이 반대였다.
     *
     * <p><b>매출이 양수여야 한다는 조건은 유도 갈래에만 건다.</b> 신고값은 그 자체가 결과이므로
     * 매출로 나눌 쪽({@code GROSS_MARGIN})이 자기 자리에서 판단한다.
     *
     * @return 신고값 또는 유도값. 둘 다 불가능하면 null
     */
    static BigDecimal of(FinancialFigures figures) {
        if (figures == null) {
            return null;
        }
        if (figures.grossProfit() != null) {
            return figures.grossProfit();
        }
        BigDecimal revenue = figures.revenue();
        BigDecimal costOfSales = figures.costOfSales();
        if (revenue == null || costOfSales == null || revenue.signum() <= 0) {
            return null;
        }
        return revenue.subtract(costOfSales);
    }
}
