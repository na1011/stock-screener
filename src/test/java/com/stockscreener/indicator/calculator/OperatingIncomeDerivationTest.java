package com.stockscreener.indicator.calculator;

import com.stockscreener.indicator.FinancialFigures;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 영업이익을 <b>영업수익 − 영업비용</b>으로 유도하는 규칙(2026-08-31 사용자 결정).
 *
 * <p><b>이 시험이 지키는 것은 대칭이다.</b> K-IFRS는 은행도 영업이익을 표시해 국내 활성
 * 유니버스의 금융업 <b>50종목이 FY 영업이익을 100%</b> 갖는데, US-GAAP은 그것을 잘 안 써서
 * 미국 금융업 <b>170 중 119(70%)</b>가 판정불가였다. 같은 이름의 조건이 시장마다 다른 것을
 * 재고 있었다.
 *
 * <p><b>이 값을 쓰는 계산기는 아홉이다</b> — {@code OPM_MIN_5Y}·{@code PROFIT_STREAK_Y}·
 * {@code ROIC_MIN_3Y}·{@code OPERATING_MARGIN}(착수 시 넷) ·
 * {@code PROFITABLE_QUARTERS_4}·{@code ALTMAN_Z}·{@code ALTMAN_Z_DOUBLE_PRIME}(그 뒤에 붙었다) ·
 * {@code ROIC_PRETAX}·{@code OPERATING_INCOME_GROWTH}(둘은 <b>raw를 읽고 있었다</b>).
 *
 * <p><b>그 둘이 빠져 있던 것이 화면에 드러나 있었다.</b> {@code ROIC_MIN_3Y}는 3년 연속을
 * 요구하므로 <b>{@code ROIC_PRETAX}의 부분집합처럼 보여야 하는데</b>, 미국에서 「3년 최저
 * ROIC는 있고 현재 ROIC는 없는」 종목이 <b>49</b>였고 국내는 <b>0</b>이었다. 49를 전수로 가르면
 * 21이 정확히 이 비대칭이고, 나머지는 분기 재무가 없는 20-F 제출자(10) 등이다.
 * <b>{@code ROIC_MIN_3Y} ⊄ {@code ROIC_PRETAX}는 원리적으로 0이 될 수 없다</b> — 둘은 읽는 행이
 * 다르다(연간 3개 대 분기 TTM). 그래서 회귀는 「분기 넷을 유도로 채울 수 있는데 값이 없는 종목」
 * 으로 재야 한다.
 *
 * <p><b>MUST: 「국내는 안 바뀐다」를 함께 잠근다.</b> 유도가 국내 값을 건드리면 그것은 대칭이
 * 아니라 다른 물건이 된 것이다.
 */
class OperatingIncomeDerivationTest {

    private static FinancialFiguresFixture fy() {
        return FinancialFiguresFixture.annual(LocalDate.of(2025, 12, 31));
    }

    private static BigDecimal n(String v) {
        return new BigDecimal(v);
    }

    @Test
    @DisplayName("소스가 낸 영업이익이 언제나 이긴다 — 국내가 이 갈래이고 값이 안 바뀐다")
    void reportedValueAlwaysWins() {
        FinancialFigures reported = fy()
                .operatingIncome(n("100"))
                // 재료가 함께 있어도 유도로 넘어가지 않는다.
                .revenue(n("1000")).costsAndExpenses(n("500"))
                .build();

        assertThat(FinancialMath.operatingIncomeOf(reported))
                .as("이 단언이 깨지면 국내 영업이익이 조용히 유도값으로 갈아탄 것이다")
                .isEqualByComparingTo("100");
    }

    @Test
    @DisplayName("은행 — (이자·배당수익 + 비이자수익) − (이자비용 + 비이자비용)")
    void derivesForBanks() {
        FinancialFigures bank = fy()
                .interestAndDividendIncome(n("800")).noninterestIncome(n("400"))
                .interestExpense(n("300")).noninterestExpense(n("500"))
                .build();

        assertThat(FinancialMath.operatingIncomeOf(bank)).isEqualByComparingTo("400");
    }

    @Test
    @DisplayName("보험 — 영업수익 − 보험금·손실·비용")
    void derivesForInsurers() {
        FinancialFigures insurer = fy()
                .revenue(n("2000")).benefitsLossesAndExpenses(n("1700"))
                .build();

        assertThat(FinancialMath.operatingIncomeOf(insurer)).isEqualByComparingTo("300");
    }

    @Test
    @DisplayName("비금융 — 매출총이익 − 영업비용이 매출 − 총원가보다 먼저다")
    void prefersGrossProfitOverCostsAndExpenses() {
        FinancialFigures maker = fy()
                .grossProfit(n("900")).operatingExpenses(n("400"))
                .revenue(n("3000")).costsAndExpenses(n("2800"))
                .build();

        assertThat(FinancialMath.operatingIncomeOf(maker))
                .as("앞 갈래가 이겨 500이다. 200이면 순서가 뒤집힌 것이다")
                .isEqualByComparingTo("500");
    }

    @Test
    @DisplayName("재료가 하나라도 없으면 그 갈래를 건너뛴다 — 0으로 대체하지 않는다")
    void skipsBranchWhenAnyInputIsMissing() {
        // 이자비용이 없다. 0으로 두면 영업이익이 1,200으로 부풀고 값이 그럴듯하다.
        FinancialFigures partial = fy()
                .interestAndDividendIncome(n("800")).noninterestIncome(n("400"))
                .noninterestExpense(n("500"))
                .build();

        assertThat(FinancialMath.operatingIncomeOf(partial)).isNull();
    }

    @Test
    @DisplayName("아무 재료도 없으면 null이다 — 침묵이 거짓보다 낫다")
    void producesNothingWithoutInputs() {
        assertThat(FinancialMath.operatingIncomeOf(fy().build())).isNull();
        assertThat(FinancialMath.operatingIncomeOf(null)).isNull();
    }
}
