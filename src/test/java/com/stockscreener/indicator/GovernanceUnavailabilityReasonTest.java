package com.stockscreener.indicator;

import com.stockscreener.indicator.calculator.DividendPayoutCalculator;
import com.stockscreener.indicator.calculator.MinorityHolderStakeCalculator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 거버넌스 결손 사유가 <b>그 시장에 대해 참인가</b>(P27 축④, ADR-0111).
 *
 * <p>이 자리는 미국을 켠 뒤 <b>1,000종목에서 거짓말을 하고 있었다</b> —
 * <i>"DART 정기보고서 주요정보가 한 행도 수집되지 않아"</i>가 미국 종목 상세의 거버넌스
 * 지표 13개에 그대로 붙었다. 미국에 DART는 애초에 없다.
 *
 * <p><b>두 문장을 가르는 것이 사용자가 할 일을 가른다.</b> 「수집되지 않았다」는 기다리면
 * 되는 것이고 「이 시장에는 없다」는 영영 안 오는 것이다. 시험이 재는 것은 문구의 존재가
 * 아니라 <b>둘이 갈리는가</b>이며, 그래서 두 시장을 나란히 단언한다.
 */
class GovernanceUnavailabilityReasonTest {

    private final List<UnavailabilityRule> rules =
            new IndicatorUnavailabilityConfig().unavailabilityRules();

    @Test
    @DisplayName("국내 종목은 「수집되지 않았다」로, 미국 종목은 「이 시장에는 없다」로 말한다")
    void reasonFollowsTheMarket() {
        assertThat(reasonFor(DividendPayoutCalculator.CODE, "KOSPI"))
                .isNotNull()
                .contains("수집되지 않아");
        assertThat(reasonFor(DividendPayoutCalculator.CODE, "US"))
                .isNotNull()
                .contains("국내 공시에서만")
                .doesNotContain("DART");
    }

    @Test
    @DisplayName("거버넌스 지표 전부가 두 시장에서 사유를 갖는다 — 하나가 빠지면 그것만 침묵한다")
    void everyGovernanceCodeHasAReasonInBothMarkets() {
        List<String> codes = rules.stream()
                .map(UnavailabilityRule::code)
                .filter(code -> reasonFor(code, "KOSPI") != null)
                .distinct()
                .toList();

        assertThat(codes).as("거버넌스 사유가 붙는 지표가 하나도 없다 — 규칙이 통째로 빠졌다")
                .isNotEmpty();
        for (String code : codes) {
            assertThat(reasonFor(code, "US"))
                    .as("%s가 미국에서는 사유 없이 조용히 빠진다", code)
                    .isNotNull();
        }
    }

    @Test
    @DisplayName("거버넌스 값이 하나라도 있으면 아무 말도 하지 않는다 — 무배당은 사유가 아니다")
    void staysSilentWhenGovernanceDataExists() {
        UnavailabilityRule.Context withData = new UnavailabilityRule.Context(
                Map.of(MinorityHolderStakeCalculator.CODE, new BigDecimal("42.0")), null, "US");

        assertThat(reasonFor(DividendPayoutCalculator.CODE, withData)).isNull();
    }

    private String reasonFor(String code, String marketCode) {
        return reasonFor(code, new UnavailabilityRule.Context(Map.of(), null, marketCode));
    }

    private String reasonFor(String code, UnavailabilityRule.Context context) {
        return rules.stream()
                .filter(rule -> rule.code().equals(code) && rule.when().test(context))
                .map(UnavailabilityRule::reason)
                .findFirst().orElse(null);
    }
}
