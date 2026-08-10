package com.stockscreener.screening.preset;

import com.stockscreener.indicator.IndicatorMeta;
import com.stockscreener.indicator.IndicatorRegistry;
import com.stockscreener.indicator.PercentileSpec;
import com.stockscreener.screening.ComparisonOperator;
import com.stockscreener.screening.ConditionGroup;
import com.stockscreener.screening.ConditionOperand;
import com.stockscreener.screening.IndicatorCondition;
import com.stockscreener.support.TestDatabaseGuard;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.test.context.ActiveProfiles;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 프리셋 선언의 무결성. DB를 쓰지 않고 <b>선언 자체</b>만 본다.
 *
 * <p>가장 중요한 검사는 백분위 부등호 방향이다. {@code PER_PCTL >= 0.8}처럼 뒤집어 적으면
 * "저평가 가치주" 프리셋이 <b>가장 비싼 종목</b>을 최상위로 올리는데, 점수도 결과도
 * 그럴듯해서 화면을 봐도 드러나지 않는다. 이것을 자동으로 잡을 수 있는 유일한 지점이다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestDatabaseGuard.class)
class PresetCatalogTest {

    @Autowired
    private List<ScreenPreset> presets;

    @Autowired
    private IndicatorRegistry registry;

    @Autowired
    private List<PercentileSpec> percentileSpecs;

    @Test
    @DisplayName("spec §3이 요구한 프리셋이 선언되어 있다")
    void declaresRequiredPresets() {
        assertThat(presets).extracting(ScreenPreset::code)
                .contains("VALUE_KR", "QVG_KR", "MOMENTUM_KR");
    }

    @Test
    @DisplayName("프리셋 코드가 중복되지 않는다 — uq_screen_code가 기동을 막기 전에 잡는다")
    void presetCodesAreUnique() {
        assertThat(presets).extracting(ScreenPreset::code).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("모든 프리셋이 v1 형태다 — 깊이 1, AND 결합")
    void allPresetsAreFlatAndGroups() {
        for (ScreenPreset preset : presets) {
            assertThat(preset.conditions().childGroups())
                    .as("%s에 중첩 그룹이 있다", preset.code()).isEmpty();
            assertThat(preset.conditions().logicalOperator())
                    .as("%s가 AND가 아니다", preset.code())
                    .isEqualTo(ConditionGroup.LogicalOperator.AND);
            assertThat(preset.conditions().conditions())
                    .as("%s에 조건이 없다", preset.code()).isNotEmpty();
        }
    }

    @Test
    @DisplayName("참조하는 지표가 모두 실재한다 — 오타는 기동 시 FK 위반이 된다")
    void allReferencedIndicatorsExist() {
        Set<String> known = knownIndicatorCodes();

        for (ScreenPreset preset : presets) {
            assertThat(preset.conditions().referencedIndicatorCodes())
                    .as("%s가 없는 지표를 참조한다", preset.code())
                    .allSatisfy(code -> assertThat(known).contains(code));
        }
    }

    @Test
    @DisplayName("IMPORTANT — 백분위 조건의 부등호가 원본 지표의 유불리와 맞는다")
    void percentileConditionsPointInTheFavorableDirection() {
        Map<String, Boolean> higherIsBetterBySource = higherIsBetterBySourceCode();

        for (ScreenPreset preset : presets) {
            for (IndicatorCondition condition : preset.conditions().conditions()) {
                String code = condition.indicatorCode();
                if (!code.endsWith(PercentileSpec.SUFFIX)) {
                    continue;
                }
                String sourceCode = code.substring(0, code.length() - PercentileSpec.SUFFIX.length());
                Boolean higherIsBetter = higherIsBetterBySource.get(sourceCode);

                assertThat(higherIsBetter)
                        .as("%s의 원본 %s에 유불리 방향이 없어 상위/하위를 말할 수 없다",
                                code, sourceCode)
                        .isNotNull();

                // 백분위는 예외 없이 오름차순이다. 따라서
                //   높은 쪽이 좋은 지표(ROE)의 "상위 N%"  → >= (GT/GTE)
                //   낮은 쪽이 좋은 지표(PER)의 "하위 N%"  → <= (LT/LTE)
                // 이 대응이 깨지면 프리셋이 정반대 종목을 고른다.
                ComparisonOperator operator = condition.operator();
                boolean pointsUp = operator == ComparisonOperator.GT || operator == ComparisonOperator.GTE;
                boolean pointsDown = operator == ComparisonOperator.LT || operator == ComparisonOperator.LTE;

                assertThat(pointsUp || pointsDown)
                        .as("%s의 %s 조건은 방향이 있는 연산자여야 한다", preset.code(), code)
                        .isTrue();
                assertThat(pointsUp)
                        .as("%s의 %s: 원본이 %s인데 부등호가 %s다 — 정반대 종목이 선정된다",
                                preset.code(), code,
                                higherIsBetter ? "높을수록 좋음" : "낮을수록 좋음", operator)
                        .isEqualTo(higherIsBetter);
            }
        }
    }

    @Test
    @DisplayName("백분위 조건의 경계값이 0~1 안에 있다")
    void percentileThresholdsAreWithinUnitRange() {
        for (ScreenPreset preset : presets) {
            for (IndicatorCondition condition : preset.conditions().conditions()) {
                if (!condition.indicatorCode().endsWith(PercentileSpec.SUFFIX)) {
                    continue;
                }
                // 0~1 비율인데 20(퍼센트)으로 적으면 모든 종목이 조건을 만족하거나
                // 아무도 만족하지 않는다. 둘 다 오류처럼 보이지 않는다.
                assertThat(condition.operand()).isInstanceOf(ConditionOperand.Literal.class);
                var literal = (ConditionOperand.Literal) condition.operand();
                assertThat(literal.value())
                        .as("%s의 %s 경계값", preset.code(), condition.indicatorCode())
                        .isBetween(java.math.BigDecimal.ZERO, java.math.BigDecimal.ONE);
            }
        }
    }

    @Test
    @DisplayName("점수형 프리셋의 점수 조건에 가중치가 있다 — 없으면 1로 취급되어 균형이 조용히 어긋난다")
    void scoringPresetsWeightEveryScoredCondition() {
        // 필수 조건은 점수에 참여하지 않으므로 가중치를 가질 수 없다(ADR-0038). 여기서
        // 함께 요구하면 게이트를 둔 프리셋이 통과할 수 없는 검사가 된다 — 그러나 이 검사의
        // 취지("가중치를 빼먹으면 균형이 조용히 어긋난다")는 점수 조건에 그대로 살아 있다.
        presets.stream()
                .filter(p -> p.mode() == com.stockscreener.screening.EvaluationMode.SCORING)
                .forEach(preset -> assertThat(preset.conditions().conditions())
                        .filteredOn(c -> !c.required())
                        .as("%s에 가중치 없는 점수 조건이 있다", preset.code())
                        .allSatisfy(c -> assertThat(c.weight()).isNotNull()));
    }

    @Test
    @DisplayName("게이트를 가진 프리셋은 점수 조건도 함께 갖는다 — 전부 필수면 점수가 0점 만점이 된다")
    void gatedPresetsKeepScoredConditions() {
        presets.stream()
                .filter(p -> p.mode() == com.stockscreener.screening.EvaluationMode.SCORING)
                .filter(p -> p.conditions().conditions().stream().anyMatch(c -> c.required()))
                .forEach(preset -> assertThat(preset.conditions().conditions())
                        .as("%s가 전부 필수라 줄 세울 근거가 없다", preset.code())
                        .anySatisfy(c -> assertThat(c.required()).isFalse()));
    }

    @Test
    @DisplayName("두 판정 방식이 모두 시딩된다 — 판정 방식은 조건이 아니라 실행의 속성이다")
    void seedsBothEvaluationModes() {
        assertThat(presets).extracting(ScreenPreset::mode)
                .contains(com.stockscreener.screening.EvaluationMode.SCORING,
                        com.stockscreener.screening.EvaluationMode.FILTER);
    }

    private Set<String> knownIndicatorCodes() {
        Set<String> codes = new java.util.HashSet<>(registry.codes());
        percentileSpecs.forEach(spec -> codes.add(spec.code()));
        return codes;
    }

    /** 계산기의 {@code @IndicatorMeta}에서 유불리 방향을 읽는다. 카탈로그 테이블의 정본이다. */
    private Map<String, Boolean> higherIsBetterBySourceCode() {
        Map<String, Boolean> byCode = new HashMap<>();
        for (String code : registry.codes()) {
            registry.find(code).ifPresent(calculator -> {
                IndicatorMeta meta = AnnotationUtils.findAnnotation(calculator.getClass(), IndicatorMeta.class);
                if (meta != null) {
                    byCode.put(code, meta.direction().toHigherIsBetter());
                }
            });
        }
        return byCode;
    }

    @Test
    @DisplayName("백분위를 조건으로 쓰는 지표는 실제로 백분위가 계산되는 지표다")
    void percentileConditionsUseDeclaredPercentiles() {
        Set<String> declared = percentileSpecs.stream()
                .map(PercentileSpec::code).collect(Collectors.toSet());

        for (ScreenPreset preset : presets) {
            preset.conditions().referencedIndicatorCodes().stream()
                    .filter(code -> code.endsWith(PercentileSpec.SUFFIX))
                    .forEach(code -> assertThat(declared)
                            .as("%s가 선언되지 않은 백분위 %s를 쓴다 — 값이 영원히 없다",
                                    preset.code(), code)
                            .contains(code));
        }
    }
}
