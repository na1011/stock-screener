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
     * @return 매출과 매출원가가 둘 다 있고 매출이 양수일 때만 값. 아니면 null
     */
    static BigDecimal of(FinancialFigures figures) {
        if (figures == null) {
            return null;
        }
        BigDecimal revenue = figures.revenue();
        BigDecimal costOfSales = figures.costOfSales();
        if (revenue == null || costOfSales == null || revenue.signum() <= 0) {
            return null;
        }
        return revenue.subtract(costOfSales);
    }
}
