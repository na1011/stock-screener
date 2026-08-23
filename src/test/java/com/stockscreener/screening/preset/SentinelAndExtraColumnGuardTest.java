package com.stockscreener.screening.preset;

import com.stockscreener.indicator.calculator.ichimoku.IchimokuModECalculator;
import com.stockscreener.indicator.calculator.ichimoku.IchimokuModHCalculator;
import com.stockscreener.indicator.calculator.ichimoku.IchimokuModMCalculator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 어느 프리셋도 밟으면 안 되는 두 함정.
 *
 * <p>이 파일은 {@code ICHIMOKU_SP_KR}의 형태를 재던 것이었다. 그 프리셋은 폐기됐지만
 * (ADR-0061) <b>함정이 사라진 것이 아니다</b> — 센티널이 섞인 지표 18개는 카탈로그에
 * 그대로 있고 조건 빌더에서도 고를 수 있다. 프리셋이 없어졌다고 검사를 지우면, 다음에
 * 그 지표를 쓰는 프리셋이 생길 때 아무도 막지 않는다.
 */
class SentinelAndExtraColumnGuardTest {

    /**
     * 「해당 없음」이 유리한 쪽 극단에 앉은 지표들. 좌변에 두면 부등호가 정반대 집합을 고른다
     * (ADR-0046 §결과, {@code phase-criteria.md} §P12).
     *
     * <p>{@code ICHI_MOD_E}는 기준 시점이 없으면 −1, {@code ICHI_MOD_H}는 판정 불가가 −2,
     * {@code ICHI_MOD_M}은 구름이 뒤집히지 않으면 −1이다. 「{@code >= 1}」로 걸면 걸러지지만
     * 「{@code <= 0}」이나 「{@code < 1}」로 걸면 <b>해당 없는 종목이 전부 걸린다.</b>
     */
    private static final Set<String> SENTINEL_CODES = Set.of(
            IchimokuModECalculator.CODE, IchimokuModHCalculator.CODE, IchimokuModMCalculator.CODE);

    private final List<ScreenPreset> presets = new PresetCatalog().screenPresets();

    @Test
    @DisplayName("센티널이 섞인 지표가 어느 프리셋의 조건에도 없다")
    void keepsSentinelIndicatorsOutOfEveryPreset() {
        // 문구가 아니라 조건 자체를 본다. 설명에 「쓰지 말 것」을 적어 두는 것으로는
        // 다음 사람이 실제로 쓰는 것을 막지 못한다.
        for (ScreenPreset preset : presets) {
            assertThat(preset.conditions().referencedIndicatorCodes())
                    .as("%s가 센티널 지표를 조건에 썼다", preset.code())
                    .doesNotContainAnyElementsOf(SENTINEL_CODES);
        }
    }

    @Test
    @DisplayName("추가 컬럼이 프리셋 경계를 넘지 않는다 — 넘으면 defaultColumn과 다를 바가 없다")
    void extraColumnsNeverLeakAcrossScreens() {
        // 버핏 프리셋이 다섯을 쓴다(유니버스 백분위 둘 + 할인율 셋). 이 검사가 막는 것은
        // 「모든 화면에 뜬다」가 조용한 기본값이 되는 것이다 — extraColumns는 그 프리셋에서만
        // 걸려야 하고, 조건 지표와 겹치면 응답 조립이 이미 union하므로 뜻이 없다.
        for (ScreenPreset preset : presets) {
            assertThat(PresetCatalog.extraColumns(preset.code()))
                    .as("%s의 추가 컬럼은 조건 지표와 겹치면 안 된다 — 응답 조립이 이미 union한다",
                            preset.code())
                    .doesNotContainAnyElementsOf(preset.conditions().referencedIndicatorCodes());
        }
        assertThat(PresetCatalog.extraColumns("사용자가 저장한 화면"))
                .as("커스텀 화면이 프리셋 컬럼을 물려받으면 안 된다")
                .isEmpty();
    }
}
