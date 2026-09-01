package com.stockscreener.indicator;

import com.stockscreener.indicator.calculator.DividendPayoutCalculator;
import com.stockscreener.indicator.calculator.DividendYieldCalculator;
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
 *
 * <p><b>축이 시장에서 (지표, 시장)으로 내려갔다</b>(P28). 미국에 배당 소스가 붙어 열셋 중
 * <b>둘만</b> 오게 되면서, 시장 하나로 열셋을 판정하던 형태가 깨졌다 — 그때 배당 둘에
 * 「국내 공시에서만 옵니다」는 거짓이고 나머지 열하나에 「수집되지 않았다」는 <b>기다리면
 * 온다는 거짓말</b>이다. 아래 시험은 그 둘을 <b>같은 시장 안에서</b> 가른다.
 */
class GovernanceUnavailabilityReasonTest {

    private final List<UnavailabilityRule> rules =
            new IndicatorUnavailabilityConfig().unavailabilityRules();

    @Test
    @DisplayName("소스가 안 덮는 지표만 「이 시장에는 없다」로 말한다 — 같은 시장 안에서 갈린다")
    void reasonFollowsTheIndicatorNotJustTheMarket() {
        // 국내는 열셋 다 온다. 안 오면 기다리면 되는 것이다.
        assertThat(reasonFor(DividendPayoutCalculator.CODE, "KOSPI"))
                .isNotNull()
                .contains("수집되지 않아");
        assertThat(reasonFor(MinorityHolderStakeCalculator.CODE, "KOSPI"))
                .isNotNull()
                .contains("수집되지 않아");

        // 미국은 배당 둘만 온다. 나머지는 기다려도 안 온다.
        assertThat(reasonFor(MinorityHolderStakeCalculator.CODE, "US"))
                .isNotNull()
                .contains("국내 공시에서만")
                .doesNotContain("DART");
        assertThat(reasonFor(DividendPayoutCalculator.CODE, "US"))
                .as("미국에 배당 소스가 붙었으므로 「국내 공시에서만 옵니다」는 이제 거짓이다")
                .isNotNull()
                .doesNotContain("국내 공시에서만");
    }

    @Test
    @DisplayName("소스가 덮는 시장의 지표는 문구가 벤더 이름을 말하지 않는다")
    void reasonDoesNotNameOneVendor() {
        // 「DART」를 문구에 박으면 같은 문장이 미국 종목에도 나가고, 그때 거짓이 된다.
        for (String market : List.of("KOSPI", "KOSDAQ", "US")) {
            assertThat(reasonFor(DividendPayoutCalculator.CODE, market))
                    .as("%s의 배당 결손 사유", market)
                    .isNotNull()
                    .doesNotContain("DART");
        }
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
    @DisplayName("그 시장에서 값이 날 수 있는 지표에 값이 있으면 아무 말도 하지 않는다")
    void staysSilentWhenGovernanceDataExists() {
        // 국내: 국내 지표에 값이 있으면 원자료가 온 것이다.
        UnavailabilityRule.Context kr = new UnavailabilityRule.Context(
                Map.of(MinorityHolderStakeCalculator.CODE, new BigDecimal("42.0")), null, "KOSPI");
        assertThat(reasonFor(DividendPayoutCalculator.CODE, kr)).isNull();

        // 미국: 판정 술어도 시장을 봐야 한다. 국내 전용 지표로 물으면 미국에서는 언제나
        // 「비었다」가 참이라, 배당을 실제로 받아 온 종목까지 「한 행도 수집되지 않았다」는
        // 말을 듣는다 — 문구만 시장별로 갈라서는 안 닫히는 자리다.
        UnavailabilityRule.Context us = new UnavailabilityRule.Context(
                Map.of(DividendYieldCalculator.CODE, new BigDecimal("2.5")), null, "US");
        assertThat(reasonFor(DividendPayoutCalculator.CODE, us))
                .as("배당수익률이 나왔는데 배당성향에 「수집되지 않았다」가 붙으면 거짓이다")
                .isNull();

        // 그리고 그 종목의 국내 전용 지표는 여전히 「이 시장에는 없다」로 말해야 한다.
        assertThat(reasonFor(MinorityHolderStakeCalculator.CODE, us))
                .isNotNull()
                .contains("국내 공시에서만");
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
