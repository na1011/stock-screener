package com.stockscreener.stock.web;

import com.stockscreener.stock.StockReader;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실적 표가 <b>통화를 건너뛴 값을 실적으로 부르지 않는지</b> 고정한다.
 *
 * <p>재무 통화는 <b>행 단위 사실</b>이고 회사가 중간에 갈아탄다. 실측으로 241560이 2023년에
 * KRW → USD로 옮겼는데 그 해 사업보고서만 USD이고 1~3분기는 KRW라, <b>우리가 유도하는
 * 4분기가 통화를 건너뛰며 뺀다</b> — 저장된 2023 Q4 매출이 {@code -7,435,793,333,222}다.
 *
 * <p><b>이 시험이 잡는 것은 둘이고 서로 다른 자리다.</b>
 * <ul>
 *   <li>통화가 갈린 <b>전년 동기</b>와의 증감률 — 환산 없이 뺀 값은 증감률이 아니다.
 *   <li>통화를 건너뛴 <b>유도 4분기</b> 자체 — 이것은 위 대조를 <b>통과한다.</b> 그 행도
 *       다음 해 짝도 같은 통화({@code USD})로 라벨돼 있기 때문이다. 그래서 자기 값을 지우는
 *       것만으로도 안 닫힌다 — 다음 해가 그것을 분모로 삼아 <b>+100.0%</b>를 만든다.
 * </ul>
 * 두 단언이 서로를 대신하지 못한다는 것이 요점이다.
 */
class StockFinancialsAssemblerTest {

    private static final StockFinancialsAssembler assembler = new StockFinancialsAssembler();

    private static StockReader.FinancialRow row(int year, String period, String currency,
                                                String revenue) {
        BigDecimal value = revenue == null ? null : new BigDecimal(revenue);
        return new StockReader.FinancialRow(year, period, LocalDate.of(year + 1, 3, 20), 0, "11011",
                currency, value, value, value, value, value, value, value, null);
    }

    private static Map<String, StockFinancialsResponse.Period> byLabel(
            List<StockFinancialsResponse.Period> periods) {
        return periods.stream().collect(java.util.stream.Collectors.toMap(
                p -> p.fiscalYear() + ":" + p.fiscalPeriod(), Function.identity()));
    }

    @Test
    @DisplayName("통화가 같은 전년 동기에는 증감률이 난다 — 게이트가 전부를 접지 않는다")
    void computesGrowthWhenBothYearsShareACurrency() {
        List<StockFinancialsResponse.Period> quarterly = assembler.assemble(List.of(
                row(2025, "Q1", "USD", "1500"),
                row(2024, "Q1", "USD", "1000"))).quarterly();

        StockFinancialsResponse.Period latest = byLabel(quarterly).get("2025:Q1");
        assertThat(latest.currencyCode()).isEqualTo("USD");
        assertThat(latest.revenueYoy()).isEqualByComparingTo("50.00");
    }

    @Test
    @DisplayName("통화가 갈린 전년 동기와는 증감률을 내지 않는다 — 환산 없이 뺀 값이다")
    void refusesGrowthAcrossACurrencyChange() {
        List<StockFinancialsResponse.Period> quarterly = assembler.assemble(List.of(
                row(2024, "Q1", "USD", "1802536337"),
                row(2023, "Q1", "KRW", "2405114691000"))).quarterly();

        StockFinancialsResponse.Period latest = byLabel(quarterly).get("2024:Q1");
        assertThat(latest.currencyCode()).isEqualTo("USD");
        assertThat(latest.revenueYoy())
                .as("KRW 2.4조에서 USD 18억으로 「떨어졌다」는 증감률은 사실이 아니다")
                .isNull();
    }

    @Test
    @DisplayName("IMPORTANT — 통화가 갈린 해의 유도 4분기는 손익을 내보내지 않는다")
    void suppressesTheDerivedFourthQuarterOfAMixedCurrencyYear() {
        // 실측 그대로다. 2023 FY만 USD이고 Q1~Q3은 KRW라, FY − 누적이 음수 매출이 됐다.
        List<StockFinancialsResponse.Period> quarterly = assembler.assemble(List.of(
                row(2023, "FY", "USD", "7475740778"),
                row(2023, "Q1", "KRW", "2405114691000"),
                row(2023, "Q2", "KRW", "2672118060000"),
                row(2023, "Q3", "KRW", "2366036323000"),
                row(2023, "Q4", "USD", "-7435793333222"))).quarterly();

        StockFinancialsResponse.Period q4 = byLabel(quarterly).get("2023:Q4");
        assertThat(q4.derived()).isTrue();
        assertThat(q4.currencyCode()).isEqualTo("USD");
        assertThat(q4.revenue())
                .as("음수 매출은 「틀린 매출」이 아니라 매출이 아닌 값이다")
                .isNull();
        assertThat(q4.operatingIncome()).isNull();
        assertThat(q4.netIncome()).isNull();
        assertThat(q4.totalAssets())
                .as("잔액은 뺄셈으로 만든 값이 아니라 사업보고서가 준 그대로다 — 남긴다")
                .isNotNull();
    }

    @Test
    @DisplayName("IMPORTANT — 오염된 유도 4분기를 다음 해가 분모로 쓰지 않는다")
    void doesNotUseAPoisonedFourthQuarterAsTheGrowthBasis() {
        // 이 자리가 통화 대조로는 안 걸린다. 2023 Q4도 2024 Q4도 라벨이 USD라 같은 통화이고,
        // YearOverYear가 분모에 abs()를 쓰므로 실측으로 +100.0%라는 그럴듯한 값이 나왔다.
        List<StockFinancialsResponse.Period> quarterly = assembler.assemble(List.of(
                row(2024, "Q4", "USD", "1531957274"),
                row(2023, "FY", "USD", "7475740778"),
                row(2023, "Q1", "KRW", "2405114691000"),
                row(2023, "Q2", "KRW", "2672118060000"),
                row(2023, "Q3", "KRW", "2366036323000"),
                row(2023, "Q4", "USD", "-7435793333222"))).quarterly();

        StockFinancialsResponse.Period q4 = byLabel(quarterly).get("2024:Q4");
        assertThat(q4.revenue())
                .as("2024년은 통화가 안 갈렸으므로 이 해의 값 자체는 살아 있어야 한다")
                .isEqualByComparingTo("1531957274");
        assertThat(q4.revenueYoy())
                .as("전년 동기가 통화를 건너뛴 유도값이면 증감률은 없다 — +100.0%가 나오면 결함이다")
                .isNull();
    }

    @Test
    @DisplayName("통화가 한 번도 안 갈린 종목의 유도 4분기는 그대로 나온다 — 게이트가 국내를 안 건드린다")
    void keepsTheDerivedFourthQuarterWhenTheCurrencyNeverChanged() {
        List<StockFinancialsResponse.Period> quarterly = assembler.assemble(List.of(
                row(2025, "FY", "KRW", "10000"),
                row(2025, "Q1", "KRW", "2000"),
                row(2025, "Q2", "KRW", "3000"),
                row(2025, "Q3", "KRW", "2500"),
                row(2025, "Q4", "KRW", "2500"),
                row(2024, "Q4", "KRW", "2000"))).quarterly();

        StockFinancialsResponse.Period q4 = byLabel(quarterly).get("2025:Q4");
        assertThat(q4.revenue()).isEqualByComparingTo("2500");
        assertThat(q4.revenueYoy()).isEqualByComparingTo("25.00");
    }
}
