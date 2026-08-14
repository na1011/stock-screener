package com.stockscreener.stock.web;

import com.stockscreener.indicator.calculator.YearOverYear;
import com.stockscreener.stock.StockReader;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 재무제표 행을 실적 표로 옮긴다.
 *
 * <p>하는 일이 셋이다 — <b>연간과 분기를 가르고</b>, <b>전년 동기 증감률을 붙이고</b>,
 * <b>우리가 유도한 행에 표시를 단다.</b> 셋 다 화면이 하면 안 되는 일이다: 앞의 둘은
 * 서버가 이미 아는 사실이고(`frontend.md`), 셋째는 사실 자체가 서버에만 있다.
 */
@Component
public class StockFinancialsAssembler {

    /**
     * DART에 4분기 보고서가 없어 4분기 손익을 우리가 유도한다.
     *
     * <p>{@code report_code}가 그 흔적이다 — 실측으로 Q4 행 2,136건이 전부 사업보고서
     * 코드({@code 11011})를 달고 있다. 그래도 판정을 분기 라벨로 하는 이유는 <b>그것이 사실의
     * 이유이기 때문</b>이다. 보고서 코드는 결과이고, 소스가 코드 체계를 바꾸면 함께 흔들린다.
     */
    private static final String DERIVED_PERIOD = "Q4";

    private static final String ANNUAL = "FY";

    public StockFinancialsResponse assemble(List<StockReader.FinancialRow> rows) {
        // 전년 동기를 찾는 색인. 연간은 (FY, 연도-1), 분기는 (같은 분기, 연도-1)이다.
        Map<String, StockReader.FinancialRow> byPeriod = new HashMap<>();
        for (StockReader.FinancialRow row : rows) {
            byPeriod.put(key(row.fiscalYear(), row.fiscalPeriod()), row);
        }

        List<StockFinancialsResponse.Period> annual = new ArrayList<>();
        List<StockFinancialsResponse.Period> quarterly = new ArrayList<>();
        for (StockReader.FinancialRow row : rows) {
            StockReader.FinancialRow prior =
                    byPeriod.get(key(row.fiscalYear() - 1, row.fiscalPeriod()));
            StockFinancialsResponse.Period period = toPeriod(row, prior);
            (ANNUAL.equals(row.fiscalPeriod()) ? annual : quarterly).add(period);
        }

        return new StockFinancialsResponse(
                List.copyOf(annual), List.copyOf(quarterly),
                StockFinancialsResponse.Notice.standard());
    }

    private static StockFinancialsResponse.Period toPeriod(StockReader.FinancialRow row,
                                                           StockReader.FinancialRow prior) {
        return new StockFinancialsResponse.Period(
                row.fiscalYear(),
                row.fiscalPeriod(),
                row.publishedAt(),
                row.revisionNo() > 0,
                DERIVED_PERIOD.equals(row.fiscalPeriod()),
                row.revenue(),
                row.operatingIncome(),
                row.netIncome(),
                row.operatingCashFlow(),
                row.totalAssets(),
                row.totalLiabilities(),
                row.totalEquity(),
                yoy(row, prior, StockReader.FinancialRow::revenue),
                yoy(row, prior, StockReader.FinancialRow::operatingIncome),
                yoy(row, prior, StockReader.FinancialRow::netIncome));
    }

    /**
     * 전년 동기 대비 증감률.
     *
     * <p>지표 계산기와 <b>같은 식을 쓴다</b>({@link YearOverYear#between}). 다시 쓰면 분모의
     * 절댓값 처리가 갈리고, 적자 기업에서 같은 사실이 화면 두 곳에 반대 부호로 나온다.
     *
     * <p>유량(매출·이익)에만 붙인다. 자산·부채·자본은 시점 잔액이라 "전년 동기 대비"가
     * 같은 뜻이 아니고, 유도된 4분기 잔액은 애초에 연간과 같은 값이다.
     */
    private static BigDecimal yoy(StockReader.FinancialRow row, StockReader.FinancialRow prior,
                                  Function<StockReader.FinancialRow, BigDecimal> field) {
        if (prior == null) {
            return null;
        }
        return YearOverYear.between(field.apply(row), field.apply(prior));
    }

    private static String key(int fiscalYear, String fiscalPeriod) {
        return fiscalYear + ":" + fiscalPeriod;
    }
}
