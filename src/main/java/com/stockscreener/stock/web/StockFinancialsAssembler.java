package com.stockscreener.stock.web;

import com.stockscreener.indicator.calculator.YearOverYear;
import com.stockscreener.stock.StockReader;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
        Set<Integer> mixedYears = yearsWithMixedCurrency(rows);

        List<StockFinancialsResponse.Period> annual = new ArrayList<>();
        List<StockFinancialsResponse.Period> quarterly = new ArrayList<>();
        for (StockReader.FinancialRow row : rows) {
            StockReader.FinancialRow prior =
                    byPeriod.get(key(row.fiscalYear() - 1, row.fiscalPeriod()));
            // MUST: 분모 쪽도 함께 본다. 오염된 유도 4분기는 **자기 값만 지워서는 안 닫힌다** —
            // 다음 해 같은 분기가 그것을 전년 동기로 삼아 그럴듯한 증감률을 만든다(실측 +100.0%).
            StockFinancialsResponse.Period period = toPeriod(row,
                    unusable(prior, mixedYears) ? null : prior,
                    unusable(row, mixedYears));
            (ANNUAL.equals(row.fiscalPeriod()) ? annual : quarterly).add(period);
        }

        return new StockFinancialsResponse(
                List.copyOf(annual), List.copyOf(quarterly),
                StockFinancialsResponse.Notice.standard());
    }

    /**
     * 한 회계연도 안에서 보고 통화가 갈리는 해.
     *
     * <p>회사가 보고 통화를 갈아탄 해가 그렇다 — 241560이 2023년에 KRW에서 USD로 옮겼고,
     * 그 해 사업보고서는 USD인데 1~3분기는 KRW다. <b>그러면 우리가 유도하는 4분기가 통화를
     * 건너뛰며 빼게 된다.</b>
     */
    private static Set<Integer> yearsWithMixedCurrency(List<StockReader.FinancialRow> rows) {
        Map<Integer, Set<String>> byYear = new HashMap<>();
        for (StockReader.FinancialRow row : rows) {
            byYear.computeIfAbsent(row.fiscalYear(), k -> new HashSet<>()).add(row.currencyCode());
        }
        Set<Integer> mixed = new HashSet<>();
        byYear.forEach((year, currencies) -> {
            if (currencies.size() > 1) {
                mixed.add(year);
            }
        });
        return mixed;
    }

    /**
     * <b>통화를 건너뛰며 뺀 유도 4분기인가.</b>
     *
     * <p>그 행의 손익은 「틀린 매출」이 아니라 <b>매출이 아닌 값</b>이다 — 뜻이 다른 값을 같은
     * 이름으로 부르지 않는다(ADR-0039). 실측으로 241560의 2023 Q4 매출이
     * {@code -7,435,793,333,222}인데, USD 사업보고서에서 KRW 1~3분기 누적을 뺀 것이다.
     */
    private static boolean unusable(StockReader.FinancialRow row, Set<Integer> mixedYears) {
        return row != null
                && DERIVED_PERIOD.equals(row.fiscalPeriod())
                && mixedYears.contains(row.fiscalYear());
    }

    /**
     * @param unusable 이 행이 통화를 건너뛴 유도 4분기인가. 손익을 <b>내보내지 않는다</b> —
     *                 내보내면 화면이 그것을 매출로 그린다. 잔액(자산·부채·자본)은 뺄셈으로
     *                 만든 값이 아니라 사업보고서가 준 그대로이므로 남긴다.
     */
    private static StockFinancialsResponse.Period toPeriod(StockReader.FinancialRow row,
                                                           StockReader.FinancialRow prior,
                                                           boolean unusable) {
        boolean derived = DERIVED_PERIOD.equals(row.fiscalPeriod());
        return new StockFinancialsResponse.Period(
                row.fiscalYear(),
                row.fiscalPeriod(),
                row.publishedAt(),
                row.revisionNo() > 0,
                derived,
                row.currencyCode(),
                unusable ? null : row.revenue(),
                unusable ? null : row.operatingIncome(),
                unusable ? null : row.netIncome(),
                unusable ? null : row.operatingCashFlow(),
                row.totalAssets(),
                row.totalLiabilities(),
                row.totalEquity(),
                unusable ? null : yoy(row, prior, StockReader.FinancialRow::revenue),
                unusable ? null : yoy(row, prior, StockReader.FinancialRow::operatingIncome),
                unusable ? null : yoy(row, prior, StockReader.FinancialRow::netIncome),
                unusable ? null : row.discontinuedIncome(),
                unusable ? null : row.commonIncome(),
                row.commonEquity());
    }

    /**
     * 전년 동기 대비 증감률.
     *
     * <p>지표 계산기와 <b>같은 식을 쓴다</b>({@link YearOverYear#between}). 다시 쓰면 분모의
     * 절댓값 처리가 갈리고, 적자 기업에서 같은 사실이 화면 두 곳에 반대 부호로 나온다.
     *
     * <p>유량(매출·이익)에만 붙인다. 자산·부채·자본은 시점 잔액이라 "전년 동기 대비"가
     * 같은 뜻이 아니고, 유도된 4분기 잔액은 애초에 연간과 같은 값이다.
     *
     * <p><b>MUST: 통화가 갈린 쌍은 접는다.</b> 환산 없이 뺀 값은 증감률이 아니다 —
     * ADR-0094가 지표 계층에 게이트를 건 이유와 같은 사실이고, 여기서는 창을 더하는 대신
     * 두 해를 빼는 형태로 나타난다.
     *
     * <p><b>MUST NOT: 이 통화 대조가 유도 4분기의 오염까지 막는다고 읽지 않는다.</b> 그 행은
     * 자기도 다음 해 짝도 <b>같은 통화로 라벨돼 있어</b> 여기를 그대로 통과한다 — 막는 것은
     * {@link #toPeriod}의 {@code unusable}이다.
     */
    private static BigDecimal yoy(StockReader.FinancialRow row, StockReader.FinancialRow prior,
                                  Function<StockReader.FinancialRow, BigDecimal> field) {
        if (prior == null || !row.currencyCode().equals(prior.currencyCode())) {
            return null;
        }
        return YearOverYear.between(field.apply(row), field.apply(prior));
    }

    private static String key(int fiscalYear, String fiscalPeriod) {
        return fiscalYear + ":" + fiscalPeriod;
    }
}
