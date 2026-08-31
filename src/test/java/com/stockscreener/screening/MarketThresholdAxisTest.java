package com.stockscreener.screening;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 임계가 시장을 따라가는가 — 그리고 <b>없을 때 조용히 다른 값을 쓰지 않는가</b>.
 *
 * <p>이 시험이 대조군이다. 통화 축을 되돌리면 아래 둘이 붉는다 — 다 초록이면 축이 아무것도
 * 안 하고 있는 것이고, 그 상태는 축을 세운 것과 구분되지 않는다.
 */
class MarketThresholdAxisTest {

    private static final String KRW = "KRW";
    private static final String USD = "USD";

    private static IndicatorCondition marketCap(Map<String, ConditionOperand.Literal> byCurrency) {
        return IndicatorCondition.of("MARKET_CAP", ComparisonOperator.GTE,
                new ConditionOperand.MarketLiteral(byCurrency)).asRequired();
    }

    @Test
    @DisplayName("그 시장의 임계를 고른다 — 해석 뒤에는 평범한 리터럴이라 아래 계층이 축을 모른다")
    void resolvesToTheThresholdOfThatMarket() {
        Map<String, ConditionOperand.Literal> byCurrency = new LinkedHashMap<>();
        byCurrency.put(KRW, ConditionOperand.Literal.of("1000000000000"));
        byCurrency.put(USD, ConditionOperand.Literal.of("7943000000"));

        ConditionGroup group = ConditionGroup.and(List.of(marketCap(byCurrency)));

        ConditionOperand krw = group.resolveFor(KRW).conditions().get(0).operand();
        ConditionOperand usd = group.resolveFor(USD).conditions().get(0).operand();

        assertThat(krw).isInstanceOf(ConditionOperand.Literal.class);
        assertThat(((ConditionOperand.Literal) krw).value())
                .isEqualByComparingTo(new BigDecimal("1000000000000"));
        assertThat(((ConditionOperand.Literal) usd).value())
                .isEqualByComparingTo(new BigDecimal("7943000000"));
    }

    @Test
    @DisplayName("그 시장의 임계가 없으면 다른 통화의 값으로 대신하지 않고 요청 단위로 거절한다")
    void refusesInsteadOfBorrowingAnotherCurrency() {
        ConditionGroup group = ConditionGroup.and(List.of(
                marketCap(Map.of(KRW, ConditionOperand.Literal.of("1000000000000")))));

        // 조건 단위 「판정불가」로 접으면 필수 게이트가 전 종목을 빼 행이 0개가 되고,
        // outcomes가 Row 안에만 있어 사유가 실릴 자리 자체가 없다(P27 축④).
        assertThatThrownBy(() -> group.resolveFor(USD))
                .isInstanceOf(MarketThresholdMissingException.class)
                .hasMessageContaining("MARKET_CAP");

        assertThat(group.resolveFor(KRW).conditions()).hasSize(1);
    }

    @Test
    @DisplayName("시장 중립 조건은 어느 통화에서도 그대로다 — 부호·개수·백분위가 그 근거다")
    void neutralConditionsSurviveEveryCurrency() {
        ConditionGroup group = ConditionGroup.and(List.of(
                IndicatorCondition.of("ACCRUAL_RATIO", ComparisonOperator.LTE,
                        ConditionOperand.Literal.of("0")),
                IndicatorCondition.of("ADJ_CLOSE", ComparisonOperator.GT,
                        ConditionOperand.IndicatorRef.of("SMA_200"))));

        assertThat(group.resolveFor(USD).conditions())
                .isEqualTo(group.resolveFor(KRW).conditions());
    }

    @Test
    @DisplayName("해석하지 않은 시장별 임계로 판정하면 던진다 — 조용히 통과시키지 않는다")
    void unresolvedThresholdThrowsInsteadOfPassing() {
        IndicatorCondition condition =
                marketCap(Map.of(KRW, ConditionOperand.Literal.of("1000000000000")));

        assertThatThrownBy(() -> condition.evaluate(
                Map.of("MARKET_CAP", new BigDecimal("2000000000000"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("resolveFor");
    }
}
