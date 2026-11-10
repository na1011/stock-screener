package com.stockscreener.indicator;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * <b>국내 실적 발표 공시 한 장</b>(잠정실적 I002 · 손익구조 변동 I001 · 국내 잠정 관문 켬만) — 지표 계산이 정식 보고서 전 그 분기의 손익 행을
 * 지을 재료다({@code ReportingBasis.provisionalQuarter}). 값은 문서 단위 그대로이고 원 = 값 × {@link #unitWon}. 끝자리의 반 칸이 반올림
 * 폭이다({@link #roundingWon}).
 *
 * @param basisMatches 양식 기준(연결 · 별도)이 그 종목 정식 판의 기준과 같고 정식 판 통화가 원화다 — 아니면 이 공시로 행을 짓지 않는다(연결 기준
 *                     회사의 별도 양식 · USD 정식 판의 원화 환산 잠정)
 * @param current      당기(I002 3개월 · I001 결산기간) — I001은 {@code cumulative}와 같다
 */
public record ProvisionalRelease(String rceptNo, LocalDate filedOn, LocalDate visibleFrom, boolean basisMatches,
                                 LocalDate periodStart, LocalDate periodEnd, LocalDate cumulativeStart, LocalDate cumulativeEnd,
                                 long unitWon, Lines current, Lines cumulative) {

    /** 손익 다섯 줄(문서 단위). 없으면 {@code null}. */
    public record Lines(BigDecimal revenue, BigDecimal operatingIncome, BigDecimal profitBeforeTax, BigDecimal netIncome,
                        BigDecimal netIncomeControlling) {

        public BigDecimal get(Item item) {
            return switch (item) {
                case REVENUE -> revenue;
                case OPERATING_INCOME -> operatingIncome;
                case PROFIT_BEFORE_TAX -> profitBeforeTax;
                case NET_INCOME -> netIncome;
                case NET_INCOME_CONTROLLING -> netIncomeControlling;
            };
        }
    }

    /** 잠정 행이 싣는 손익 항목 — 나머지 칸은 비운다(잔액 · 현금흐름 · 보통주 몫은 잠정이 안 준다). */
    public enum Item {
        REVENUE, OPERATING_INCOME, PROFIT_BEFORE_TAX, NET_INCOME, NET_INCOME_CONTROLLING;

        public BigDecimal of(FinancialFigures row) {
            return switch (this) {
                case REVENUE -> row.revenue();
                case OPERATING_INCOME -> row.operatingIncome();
                case PROFIT_BEFORE_TAX -> row.profitBeforeTax();
                case NET_INCOME -> row.netIncome();
                case NET_INCOME_CONTROLLING -> row.netIncomeControlling();
            };
        }
    }

    /** 보이기 시작하는 날 — 없으면 접수일. */
    public LocalDate visibleOn() {
        return visibleFrom != null ? visibleFrom : filedOn;
    }

    /** 문서 값 → 원. */
    public BigDecimal won(BigDecimal value) {
        return value == null ? null : value.multiply(BigDecimal.valueOf(unitWon));
    }

    /** 그 값의 반올림 폭(원) — 문서 끝자리 한 칸의 반. */
    public BigDecimal roundingWon(BigDecimal value) {
        return value.ulp().multiply(BigDecimal.valueOf(unitWon)).divide(BigDecimal.valueOf(2));
    }
}
