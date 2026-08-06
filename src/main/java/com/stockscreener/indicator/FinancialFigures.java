package com.stockscreener.indicator;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 재무제표 한 기(期)의 수치.
 *
 * <p>모든 금액이 nullable이다. 외부 소스가 항목을 누락하는 일이 흔하고, null이면 그 지표를
 * 계산하지 않는 것이 옳다 — 0으로 대체하면 조용히 틀린 값이 저장된다.
 *
 * <p>{@code periodEnd}가 기간의 근거다. 회계연도가 달력연도와 다를 수 있으므로
 * ({@code fiscalYear}는 라벨일 뿐) TTM 합산은 반드시 {@code periodEnd} 순으로 한다.
 *
 * <p>IMPORTANT — {@code eps}·{@code bps}는 <b>소스가 준 값을 그대로 담는 자리가 아니다.</b>
 * 스키마에 컬럼이 있어 수집은 되지만, 지표 계산은 spec §7과 {@code data-pipeline.md}에 따라
 * 언제나 자체 계산을 쓴다. 이 두 필드는 수집값과 자체 계산값을 대조해 어긋남을 발견하는
 * 용도이며, 지표 계산기가 이것을 읽어 그대로 저장하면 "외부 계산값 미의존" 원칙이 깨진다.
 */
public record FinancialFigures(
        int fiscalYear,
        FiscalPeriod fiscalPeriod,
        LocalDate periodStart,
        LocalDate periodEnd,
        LocalDate publishedAt,
        String currencyCode,
        BigDecimal revenue,
        BigDecimal operatingIncome,
        BigDecimal netIncome,
        BigDecimal totalAssets,
        BigDecimal totalEquity,
        BigDecimal totalLiabilities,
        BigDecimal operatingCashFlow,
        BigDecimal sharesOutstanding,
        BigDecimal eps,
        BigDecimal bps
) {

    public enum FiscalPeriod {
        FY, Q1, Q2, Q3, Q4;

        public boolean isQuarterly() {
            return this != FY;
        }
    }
}
