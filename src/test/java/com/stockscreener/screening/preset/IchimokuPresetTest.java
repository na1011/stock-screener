package com.stockscreener.screening.preset;

import com.stockscreener.indicator.calculator.ExcessReturn26wCalculator;
import com.stockscreener.indicator.calculator.ichimoku.IchimokuExternalSignalCalculator;
import com.stockscreener.indicator.calculator.ichimoku.IchimokuFreshnessCalculator;
import com.stockscreener.indicator.calculator.ichimoku.IchimokuPillarCalculator;
import com.stockscreener.indicator.calculator.ichimoku.IchimokuSignalCalculator;
import com.stockscreener.indicator.calculator.ichimoku.IchimokuStageCalculator;
import com.stockscreener.indicator.calculator.ichimoku.IchimokuTierCalculator;
import com.stockscreener.screening.ComparisonOperator;
import com.stockscreener.screening.EvaluationMode;
import com.stockscreener.screening.IndicatorCondition;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code ICHIMOKU_SP_KR}의 형태와, <b>화면에서만 사라지는 두 가지</b>.
 *
 * <p>우선순위 컬럼과 결핍 서술은 지워도 컴파일되고 다른 테스트도 전부 초록이다. 그런데 없으면
 * 사용자는 <b>없는 안전장치를 있다고 믿는다</b> — 원본의 4단 정렬이 서버에서 안 돌고,
 * 센티널이 섞인 값에 부등호를 걸면 정반대 집합이 걸린다(ADR-0039의 MUST를 승계한다).
 */
class IchimokuPresetTest {

    private final List<ScreenPreset> presets = new PresetCatalog().screenPresets();

    private ScreenPreset ichimoku() {
        return presets.stream()
                .filter(preset -> PresetCatalog.ICHIMOKU_SP_KR.equals(preset.code()))
                .findFirst().orElseThrow();
    }

    @Test
    @DisplayName("조건은 ICHI_TIER <= 3 하나다 — 판정이 이미 지표 안에서 끝났다")
    void filtersOnTierAlone() {
        ScreenPreset preset = ichimoku();

        assertThat(preset.mode())
                .as("점수가 아니라 충족/불충족이다. Tier는 이미 순위를 담고 있다")
                .isEqualTo(EvaluationMode.FILTER);
        assertThat(preset.conditions().conditions()).hasSize(1);

        IndicatorCondition condition = preset.conditions().conditions().getFirst();
        assertThat(condition.indicatorCode()).isEqualTo(IchimokuTierCalculator.CODE);
        assertThat(condition.operator()).isEqualTo(ComparisonOperator.LTE);
    }

    @Test
    @DisplayName("우선순위 컬럼 넷이 이 프리셋에만 붙는다 — defaultColumn은 전역이라 못 쓴다")
    void addsPriorityColumnsWithoutTouchingOtherScreens() {
        assertThat(PresetCatalog.extraColumns(PresetCatalog.ICHIMOKU_SP_KR))
                .as("원본의 4단 사전식 우선순위(신호강도 → 신선도 → 외부시그널 → 기둥)를 "
                        + "값으로라도 보여 준다. 서버 정렬은 FILTER에 없다(ADR-0016)")
                .containsExactly(
                        IchimokuSignalCalculator.CODE,
                        IchimokuFreshnessCalculator.CODE,
                        IchimokuExternalSignalCalculator.CODE,
                        IchimokuPillarCalculator.CODE,
                        IchimokuStageCalculator.CODE,
                        ExcessReturn26wCalculator.CODE);

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
                        + "원본의 1순위로 읽는다")
                .contains("우선순위");
        assertThat(description)
                .as("센티널이 섞였다는 사실. 「신선도 5 이하」를 걸면 −1(해당 없음)이 함께 걸린다")
                .contains("조건의 좌변");
        assertThat(description)
                .as("0건의 원인을 가를 수단(§P12). 207주 미만과 지수 결손은 조건 탓이 아니다")
                .contains("207").contains("지수");
    }
}
