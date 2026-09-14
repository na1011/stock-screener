package com.stockscreener.indicator;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 매출총이익률이 서 있는 전제 — <b>{@code cost_of_sales}가 그 매출에 대응하는 원가 전체인가</b>.
 *
 * <p><b>지금까지 그 전제를 검사하는 코드가 없었다.</b> 소스가 전제를 깨는 값을 줘도 그대로
 * 100%에 가까운 매출총이익률이 화면에 나갔다. 실측(2026-09-14)이 병의 자리를 특정한다 —
 * <b>회사가 신고한 {@code gross_profit}을 쓰는 416종목에는 {@code GM ≥ 95%}가 한 건도 없고,
 * 매출원가로 유도하는 204종목에만 13건 있다.</b> 그래서 <b>유도 경로만</b> 검사한다.
 *
 * <p><b>MUST: 값을 버리지 않는다.</b> {@code financial_statement}는 한 행도 안 바뀐다 —
 * 바뀌는 것은 <b>지표가 무엇을 주장하는가</b>뿐이다. 그래서 ADR-0126 §결정의
 * <i>"입력 값을 자동으로 버리는 게이트를 만들지 않는다"</i>에 걸리지 않는다. 다만
 * {@code AltmanZCalculator.isInapplicable}과도 형태가 <b>같지는 않다</b> — 그쪽은 업종키 하나로
 * 정의역을 자르고 이쪽은 <b>그 회사의 재무 이력</b>으로 자른다. ADR에 그 차이를 적었다.
 *
 * <p><b>MUST NOT: 수준 축으로 판정하지 않는다 — 재보고 기각했다.</b>
 * <ul>
 *   <li>「최신 원가율 &lt; 2%」는 {@code BBIO}(1.75%)와 {@code JEF}(1.76%)를 <b>원리적으로</b>
 *       못 가른다. 앞은 매출이 24배 큰 정상 성장이고 뒤는 태그가 갈린 자리다.</li>
 *   <li>「원가 ÷ 총비용 &lt; 5%」는 10종목을 잡는데 <b>6이 {@code SIC:28} 제약사</b>다
 *       ({@code IONS} 1.20% · {@code CORT} 1.81% · {@code NBIX} 2.32% …). 로열티 매출 기업의
 *       98% 매출총이익률은 결함이 아니라 {@code 버핏 우량주}가 찾는 해자 신호다.</li>
 * </ul>
 * <b>그래서 비율이 아니라 「원가 절대액의 전이」를 본다.</b>
 *
 * <p><b>MUST: 이 판정은 봉 시점 재무로 돈다</b>(ADR-0128). 최신 단면 하나로 재면 창의 절반에서만
 * 발화하는 것을 못 본다 — 실제로 {@code BBIO}를 최신 단면으로 재고 「안 걸린다」고 적었는데,
 * 창 255일 중 <b>116일</b>에서 그때의 최신 연간이 FY2024(원가율 1.75%)였다.
 *
 * <p><b>MUST: 이 판정이 「몇 년을 적재해 뒀는가」의 함수임을 알고 있는다.</b>
 * {@code --financial-years}를 줄여 붕괴 이전 연도가 사라지면 같은 회사가 <b>조용히</b> 값으로
 * 되돌아온다.
 */
public final class GrossProfitPremise {

    /**
     * 원가가 <b>1/10 이하</b>로 무너진 것을 전이로 본다. 실측(활성 편입 전 시장)에서 이 문턱을
     * 넘는 전이는 {@code WEX} 0.0094 · {@code CNP} 0.0106 · {@code AJG} 0.0195뿐이고,
     * 정상 성장인 {@code BBIO}는 <b>1.5854</b>(원가가 오히려 늘었다)라 한참 밖이다.
     */
    private static final BigDecimal COLLAPSE = new BigDecimal("0.10");

    /**
     * 같은 해에 매출이 <b>절반 미만</b>으로 줄었으면 사업이 줄어든 것이지 태그가 갈린 것이
     * 아니다. 위 셋의 매출배는 1.228 · 0.653 · 1.042다.
     */
    private static final BigDecimal REVENUE_KEPT = new BigDecimal("0.5");

    /** 붕괴 전 원가율의 <b>절반</b>을 회복선으로 둔다. {@code WEX}는 45.05% → 41.36%로 회복했다. */
    private static final BigDecimal RECOVERED = new BigDecimal("0.5");

    /**
     * 보험 손익에서 원가가 <b>급부총액의 20% 미만</b>이면 조각이다. 실측 —
     * {@code CI} 80.9%(약국·의료비가 실제 원가다, 매출총이익률 21.79%는 그럴듯하다) ·
     * {@code ELV} 11.0% · {@code MCY} 0.28%.
     */
    private static final BigDecimal INSURANCE_SHARE = new BigDecimal("0.20");

    private GrossProfitPremise() {
    }

    /** 전제가 깨진 방식. 화면 사유가 이 갈래마다 다른 문장을 쓴다. */
    public enum Break {
        /** 매출원가가 음수다. 회계적 사실이 아니다 — 실측 연간 5행(국내 3 · 미국 2). */
        NEGATIVE_COST,
        /**
         * 보험 손익 구조인데 매출원가가 급부총액의 조각이다.
         *
         * <p><b>MUST NOT: {@code revenue − benefits_losses_and_expenses}로 대체하지 않는다.</b>
         * 그것은 매출총이익이 아니라 <b>세전이익</b>이다 — 실측으로 그 차가
         * {@code profit_before_tax}와 <b>완전히 일치하는 종목이 40 중 33</b>이고
         * {@code MCY}는 663,649,000으로 소수점까지 같다.
         */
        INSURANCE_FRAGMENT,
        /**
         * 회사가 그 태그에 담는 내용을 바꿔 원가가 무너진 뒤 회복되지 않았다.
         * 실측 {@code CNP}(FY2017 39.37% → FY2018 0.64%) · {@code JEF} · {@code MUR}.
         */
        COST_COLLAPSED
    }

    /**
     * 전제가 깨졌는가. <b>깨지지 않았으면 {@code null}</b>이다.
     *
     * @param annual {@code period_end} 내림차순 연간 행. 판 이력이 섞여 있어도 된다 —
     *               회계연도마다 <b>가장 먼저 오는 행</b>(= 그 시점의 최신 판)만 본다
     */
    public static Break of(List<FinancialFigures> annual) {
        List<FinancialFigures> years = latestPerYear(annual);
        if (years.isEmpty()) {
            return null;
        }
        FinancialFigures latest = years.get(0);
        // 회사가 신고한 매출총이익이 있으면 유도하지 않으므로 이 전제 자체가 안 선다.
        if (latest.grossProfit() != null) {
            return null;
        }
        BigDecimal cost = latest.costOfSales();
        BigDecimal revenue = latest.revenue();
        if (cost == null || revenue == null || revenue.signum() <= 0) {
            // 값이 애초에 안 나온다 — 그 침묵은 소스 부재 축이 답한다(ADR-0147).
            return null;
        }
        if (cost.signum() < 0) {
            return Break.NEGATIVE_COST;
        }
        BigDecimal benefits = latest.benefitsLossesAndExpenses();
        if (benefits != null && benefits.signum() > 0
                && cost.compareTo(benefits.multiply(INSURANCE_SHARE)) < 0) {
            return Break.INSURANCE_FRAGMENT;
        }
        return collapsed(years, cost.divide(revenue, MathContext.DECIMAL64)) ? Break.COST_COLLAPSED : null;
    }

    /**
     * 이력 안에 <b>원가 절대액이 무너지고 매출은 유지된 해</b>가 있고 그 뒤 회복되지 않았는가.
     *
     * <p>연속한 회계연도끼리만 본다 — 사이가 비면 그 사이에 무슨 일이 있었는지 모르고,
     * 모르는 것을 전이로 읽으면 상장 초기 종목이 통째로 걸린다.
     */
    private static boolean collapsed(List<FinancialFigures> years, BigDecimal latestRatio) {
        for (int i = 0; i + 1 < years.size(); i++) {
            FinancialFigures after = years.get(i);
            FinancialFigures before = years.get(i + 1);
            if (after.fiscalYear() - before.fiscalYear() != 1) {
                continue;
            }
            BigDecimal beforeCost = before.costOfSales();
            BigDecimal beforeRevenue = before.revenue();
            BigDecimal afterCost = after.costOfSales();
            BigDecimal afterRevenue = after.revenue();
            if (beforeCost == null || afterCost == null || beforeRevenue == null || afterRevenue == null
                    || beforeCost.signum() <= 0 || beforeRevenue.signum() <= 0) {
                continue;
            }
            boolean costCollapsed =
                    afterCost.divide(beforeCost, MathContext.DECIMAL64).compareTo(COLLAPSE) <= 0;
            boolean revenueKept =
                    afterRevenue.divide(beforeRevenue, MathContext.DECIMAL64).compareTo(REVENUE_KEPT) >= 0;
            if (!costCollapsed || !revenueKept) {
                continue;
            }
            BigDecimal beforeRatio = beforeCost.divide(beforeRevenue, MathContext.DECIMAL64);
            if (latestRatio.compareTo(beforeRatio.multiply(RECOVERED)) < 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * 회계연도마다 <b>가장 먼저 오는 행</b>만 남긴다.
     *
     * <p>{@code KNOWN_ON} 가시성은 그 시점까지의 <b>판 이력 전부</b>를 주므로(ADR-0128) 같은
     * 회계연도가 여러 번 온다. 안 접으면 <b>같은 해의 두 판을 이웃 연도로 읽어</b> 전이가
     * 없는데도 있는 것으로 본다.
     */
    private static List<FinancialFigures> latestPerYear(List<FinancialFigures> annual) {
        List<FinancialFigures> years = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for (FinancialFigures figures : annual) {
            // MUST: 인접만 보지 않고 본 해를 기억한다. 한 회계연도가 period_end가 다른 두 행으로
            // 올 수 있고(결산월 변경), 인접 비교만 하면 그 둘이 떨어져 들어와 같은 해가 두 번
            // 남는다 — 그러면 fiscalYear 차가 0이라 전이 판정에서 조용히 빠진다.
            if (seen.add(figures.fiscalYear())) {
                years.add(figures);
            }
        }
        return years;
    }
}
