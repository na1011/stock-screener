package com.stockscreener.screening.preset;

import com.stockscreener.indicator.calculator.ExcessReturn26wCalculator;
import com.stockscreener.indicator.calculator.ichimoku.IchimokuDivergenceCalculator;
import com.stockscreener.indicator.calculator.ichimoku.IchimokuModACalculator;
import com.stockscreener.indicator.calculator.ichimoku.IchimokuModBCalculator;
import com.stockscreener.indicator.calculator.ichimoku.IchimokuModCCalculator;
import com.stockscreener.indicator.calculator.ichimoku.IchimokuModDCalculator;
import com.stockscreener.indicator.calculator.ichimoku.IchimokuModECalculator;
import com.stockscreener.indicator.calculator.ichimoku.IchimokuModHCalculator;
import com.stockscreener.indicator.calculator.ichimoku.IchimokuModMCalculator;
import com.stockscreener.indicator.calculator.ichimoku.IchimokuObvTCalculator;
import com.stockscreener.indicator.calculator.ichimoku.IchimokuPriceVsKumoCalculator;
import com.stockscreener.indicator.calculator.ichimoku.IchimokuTkCrossCalculator;
import com.stockscreener.screening.ComparisonOperator;
import com.stockscreener.screening.EvaluationMode;
import com.stockscreener.screening.IndicatorCondition;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code ICHIMOKU_SP_KR}의 형태와, <b>화면에서만 사라지는 것들</b>.
 *
 * <p>추가 컬럼과 결핍 서술은 지워도 컴파일되고 다른 테스트도 전부 초록이다. 그런데 없으면
 * 사용자는 <b>없는 안전장치를 있다고 믿는다</b> — 서버 정렬이 안 돌고, 값이 없는 것을 조건
 * 탓으로 읽는다(ADR-0039의 MUST를 승계한다).
 */
class IchimokuPresetTest {

    /**
     * 「해당 없음」이 유리한 쪽 극단에 앉은 지표들. 좌변에 두면 부등호가 정반대 집합을 고른다
     * (ADR-0046 §결과, {@code phase-criteria.md} §P12).
     */
    private static final Set<String> SENTINEL_CODES = Set.of(
            IchimokuModECalculator.CODE, IchimokuModHCalculator.CODE, IchimokuModMCalculator.CODE);

    private final List<ScreenPreset> presets = new PresetCatalog().screenPresets();

    private ScreenPreset ichimoku() {
        return presets.stream()
                .filter(preset -> PresetCatalog.ICHIMOKU_SP_KR.equals(preset.code()))
                .findFirst().orElseThrow();
    }

    @Test
    @DisplayName("조건 다섯이 전부 화면에 보이는 정량 조건이다 — 판정이 지표 안에 숨지 않는다")
    void filtersOnVisibleQuantitativeConditions() {
        ScreenPreset preset = ichimoku();

        assertThat(preset.mode())
                .as("점수가 아니라 충족/불충족이다")
                .isEqualTo(EvaluationMode.FILTER);

        // 예전에는 조건이 ICHI_TIER <= 3 하나였고 13 수식자 52셀 매트릭스가 그 안에 통째로
        // 숨어 있었다. 사용자는 왜 걸렸는지 볼 수도, 조건 하나를 빼고 돌려 볼 수도 없었다.
        assertThat(preset.conditions().conditions())
                .extracting(IndicatorCondition::indicatorCode)
                .containsExactly(
                        IchimokuTkCrossCalculator.CODE,
                        IchimokuPriceVsKumoCalculator.CODE,
                        IchimokuModACalculator.CODE,
                        IchimokuModBCalculator.CODE,
                        IchimokuModDCalculator.CODE);

        assertThat(preset.conditions().conditions())
                .extracting(IndicatorCondition::operator)
                .containsOnly(ComparisonOperator.GTE);
    }

    @Test
    @DisplayName("센티널이 섞인 지표가 좌변에 없다 — 부등호가 정반대 집합을 고른다")
    void keepsSentinelIndicatorsOutOfConditions() {
        // 문구가 아니라 조건 자체를 본다. 설명에 「쓰지 말 것」을 적어 두는 것으로는
        // 다음 사람이 실제로 쓰는 것을 막지 못한다.
        for (ScreenPreset preset : presets) {
            assertThat(preset.conditions().referencedIndicatorCodes())
                    .as("%s가 센티널 지표를 조건에 썼다", preset.code())
                    .doesNotContainAnyElementsOf(SENTINEL_CODES);
        }
    }

    @Test
    @DisplayName("우열을 가르는 값이 이 프리셋에만 붙는다 — defaultColumn은 전역이라 못 쓴다")
    void addsRankingColumnsWithoutTouchingOtherScreens() {
        assertThat(PresetCatalog.extraColumns(PresetCatalog.ICHIMOKU_SP_KR))
                .as("조건이 걸러 준 종목들 사이의 우열. 서버 정렬은 FILTER에 없다(ADR-0016)")
                .containsExactly(
                        IchimokuModCCalculator.CODE,
                        IchimokuObvTCalculator.CODE,
                        IchimokuDivergenceCalculator.CODE,
                        ExcessReturn26wCalculator.CODE);

        assertThat(PresetCatalog.extraColumns(PresetCatalog.ICHIMOKU_SP_KR))
                .as("조건 지표는 ScreeningResponseAssembler가 이미 union한다. 여기 또 넣으면 중복이다")
                .doesNotContainAnyElementsOf(
                        ichimoku().conditions().referencedIndicatorCodes());

        for (ScreenPreset preset : presets) {
            if (!PresetCatalog.ICHIMOKU_SP_KR.equals(preset.code())) {
                assertThat(PresetCatalog.extraColumns(preset.code()))
                        .as("%s까지 시끄러워지면 defaultColumn과 다를 바가 없다", preset.code())
                        .isEmpty();
            }
        }
        assertThat(PresetCatalog.extraColumns("사용자가 저장한 화면"))
                .as("커스텀 화면이 프리셋 컬럼을 물려받으면 안 된다")
                .isEmpty();
    }

    @Test
    @DisplayName("옮기지 못한 것이 설명에 있다 — 없는 안전장치를 있다고 믿게 된다")
    void descriptionStatesWhatWasNotPorted() {
        String description = ichimoku().description();

        assertThat(description)
                .as("서버 정렬이 없다는 사실. 이 문장이 없으면 사용자는 화면의 첫 줄을 "
                        + "가장 좋은 종목으로 읽는다")
                .contains("정렬");
        assertThat(description)
                .as("0건의 원인을 가를 수단(§P12). 값이 없어 빠진 것과 조건에 안 맞아 빠진 것은 다르다")
                .contains("207");
    }
}
