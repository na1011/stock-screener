package com.stockscreener.indicator;

import com.stockscreener.indicator.calculator.GrossMarginCalculator;
import com.stockscreener.indicator.calculator.GrossProfitToAssetsCalculator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 전제가 깨져 값을 안 내는 자리가 <b>화면에서 침묵하지 않는가</b>.
 *
 * <p>이 시험이 있는 이유는 하나다 — {@link UnavailabilityRule.Context}가 들고 있던 넷
 * (값·업종·시장·소스 부재) 중 <b>어느 것도 재무 이력이 아니었다.</b> 그대로 전제 검사를 넣으면
 * {@code CNP}·{@code JEF}·{@code MUR}·{@code ELV}·{@code MCY}의 값이 <b>사유 없이 그냥
 * 사라진다.</b> 침묵은 「모른다」인데 우리는 알고 있다.
 *
 * <p><b>두 축이 서로 다른 것을 말한다.</b> 「소스가 그 항목을 안 낸다」는 <b>기다려도 안 오는
 * 것</b>이고 「값은 오는데 뜻이 다르다」는 <b>값이 있는데 못 쓰는 것</b>이다. 사용자가 할 일이
 * 갈리므로 한 문장으로 뭉치면 안 된다(ADR-0111 §규칙).
 */
class GrossProfitPremiseReasonTest {

    private final List<UnavailabilityRule> rules =
            new IndicatorUnavailabilityConfig().unavailabilityRules();

    @Test
    @DisplayName("전제가 깨진 갈래마다 다른 문장이 나가고, 소비자 둘 다에 붙는다")
    void everyBreakSpeaksAndBothConsumersGetIt() {
        for (GrossProfitPremise.Break kind : GrossProfitPremise.Break.values()) {
            assertThat(reasonFor(GrossMarginCalculator.CODE, context(kind)))
                    .as("매출총이익률 — %s", kind)
                    .isNotNull();
            assertThat(reasonFor(GrossProfitToAssetsCalculator.CODE, context(kind)))
                    .as("총이익÷총자산도 같은 술어를 쓴다 — 한쪽만 고치면 다른 쪽이 결함을 안고 남는다")
                    .isNotNull();
        }
        assertThat(reasonFor(GrossMarginCalculator.CODE,
                context(GrossProfitPremise.Break.INSURANCE_FRAGMENT)))
                .contains("보험금·손실·비용");
        assertThat(reasonFor(GrossMarginCalculator.CODE,
                context(GrossProfitPremise.Break.NEGATIVE_COST)))
                .contains("음수");
        assertThat(reasonFor(GrossMarginCalculator.CODE,
                context(GrossProfitPremise.Break.COST_COLLAPSED)))
                .contains("다른 기준으로");
    }

    @Test
    @DisplayName("전제가 멀쩡하면 침묵한다 — 사유는 부재에 대한 설명이지 값에 대한 주석이 아니다")
    void staysSilentWhenThePremiseHolds() {
        assertThat(reasonFor(GrossMarginCalculator.CODE, context(null))).isNull();
        assertThat(reasonFor(GrossProfitToAssetsCalculator.CODE, context(null))).isNull();
    }

    @Test
    @DisplayName("소스 부재 축과 겹치지 않는다 — 겹치면 등록 순서가 문구를 정한다")
    void doesNotCollideWithTheSourceAbsenceAxis() {
        // 소스 부재는 「그 컬럼을 한 번도 안 냈다」라 값이 없고, 전제 파괴는 「값은 있는데 뜻이
        // 다르다」라 값이 있다. 둘은 원리적으로 같은 종목에서 동시에 참이 될 수 없지만,
        // Context는 그것을 강제하지 않으므로 겹쳤을 때 무엇이 나가는지를 시험이 고정한다.
        UnavailabilityRule.Context both = new UnavailabilityRule.Context(
                Map.of(), null, "US", Set.of("cost_of_sales", "gross_profit"),
                GrossProfitPremise.Break.COST_COLLAPSED);
        assertThat(matchCount(GrossMarginCalculator.CODE, both))
                .as("둘 다 맞으면 reasonFor가 먼저 맞는 하나를 쓰므로 등록 순서가 뜻을 정한다")
                .isEqualTo(2);
        assertThat(reasonFor(GrossMarginCalculator.CODE, both))
                .as("소스 부재가 먼저 등록돼 있다 — 값이 아예 없는 쪽이 더 강한 사실이다")
                .contains("공시에 내지 않습니다");
    }

    private static UnavailabilityRule.Context context(GrossProfitPremise.Break kind) {
        return new UnavailabilityRule.Context(Map.of(), null, "US", Set.of(), kind);
    }

    private long matchCount(String code, UnavailabilityRule.Context context) {
        return rules.stream().filter(rule -> rule.code().equals(code) && rule.when().test(context)).count();
    }

    private String reasonFor(String code, UnavailabilityRule.Context context) {
        return rules.stream()
                .filter(rule -> rule.code().equals(code) && rule.when().test(context))
                .map(UnavailabilityRule::reason)
                .findFirst().orElse(null);
    }
}
