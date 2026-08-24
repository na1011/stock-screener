package com.stockscreener.screening.preset;

import com.stockscreener.indicator.IndicatorMeta;
import com.stockscreener.indicator.IndicatorRegistry;
import com.stockscreener.indicator.PercentileSpec;
import com.stockscreener.indicator.SectorMetricSpec;
import com.stockscreener.screening.ComparisonOperator;
import com.stockscreener.screening.ConditionGroup;
import com.stockscreener.screening.ConditionOperand;
import com.stockscreener.screening.IndicatorCondition;
import com.stockscreener.screening.store.ScreenDefinitionValidator;
import com.stockscreener.support.TestDatabaseGuard;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

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

    @Autowired
    private List<SectorMetricSpec> sectorMetricSpecs;

    @Test
    @DisplayName("spec §9가 요구한 「최소 1개 이상의 사전정의 전략 프리셋」이 있다")
    void declaresAtLeastOnePreset() {
        // IMPORTANT — 이 단언은 한때 VALUE_KR·QVG_KR·MOMENTUM_KR 셋을 이름으로 세면서
        // "spec §3이 요구한"이라고 적었다. spec §3은 "(예: Value, QVG형 전략)"이라 적어
        // 예시를 든 것이고 목록을 요구하지 않는다 — 개수를 정하는 곳은 §9의 "최소 1개 이상"뿐이다.
        // 이름으로 세는 펜스는 프리셋을 개편할 때마다 거짓 실패를 내고, 그러면 다음 세션이
        // 기획 정본을 확인하는 대신 단언을 고치게 된다(QVG_KR 폐기가 그 첫 사례다 — ADR-0066).
        assertThat(presets).extracting(ScreenPreset::code).isNotEmpty();
    }

    @Test
    @DisplayName("IMPORTANT — 프리셋마다 조건의 출처가 javadoc에 갈려 적혀 있다")
    void everyPresetRecordsWhereItsConditionsCameFrom() throws Exception {
        // 파일 전체에서 낱말을 세는 방식은 실패할 수 없는 펜스다 — 기존 프리셋의 표기만으로
        // 임계를 넘겨, 새 프리셋이 출처를 하나도 안 적어도 통과한다(ADR-0061 §결과가 경고한 모양).
        // 그래서 프리셋 하나하나의 팩토리 메서드 javadoc을 본다.
        String source = Files.readString(Path.of(
                "src/main/java/com/stockscreener/screening/preset/PresetCatalog.java"));
        List<String> markers = List.of("원문 그대로", "국내 분포로 재보정",
                "원문에 없는데 더한", "원문보다 약하게 옮긴", "국내 사정으로 바꾼", "원문에서 되살린");

        for (ScreenPreset preset : presets) {
            int declaration = source.indexOf("String " + preset.code() + " =");
            assertThat(declaration)
                    .as("%s의 코드 상수를 찾지 못했다", preset.code())
                    .isGreaterThanOrEqualTo(0);
            // 상수 선언과 그 아래 팩토리 메서드 사이가 그 프리셋의 javadoc이다.
            int body = source.indexOf("ScreenPreset(", declaration);
            String block = source.substring(declaration, body < 0 ? source.length() : body);

            assertThat(markers)
                    .as("%s의 javadoc에 조건 출처 표기가 없다 — 갈리지 않으면 다음 세션이 "
                            + "우리가 정한 값을 문헌이 검증한 값으로 읽는다", preset.code())
                    .anyMatch(block::contains);
        }
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
    @DisplayName("IMPORTANT — 상대 지표 조건의 부등호가 원본 지표의 유불리와 맞는다")
    void relativeConditionsPointInTheFavorableDirection() {
        Map<String, Boolean> higherIsBetterBySource = higherIsBetterBySourceCode();
        Map<String, String> sourceByRelativeCode = sourceByRelativeCode();

        for (ScreenPreset preset : presets) {
            for (IndicatorCondition condition : preset.conditions().conditions()) {
                String code = condition.indicatorCode();
                String sourceCode = sourceByRelativeCode.get(code);
                if (sourceCode == null) {
                    continue;
                }
                Boolean higherIsBetter = higherIsBetterBySource.get(sourceCode);

                assertThat(higherIsBetter)
                        .as("%s의 원본 %s에 유불리 방향이 없어 상위/하위를 말할 수 없다",
                                code, sourceCode)
                        .isNotNull();

                // 백분위는 예외 없이 오름차순이고, 업종 중앙값 배수도 원본과 같은 방향으로
                // 커진다. 그래서 둘에 같은 규칙이 걸린다.
                //   높은 쪽이 좋은 지표(ROE)의 "상위 N%"        → >= (GT/GTE)
                //   낮은 쪽이 좋은 지표(부채비율)의 "업종 이하"  → <= (LT/LTE)
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
        Set<String> percentileCodes = percentileCodes();

        for (ScreenPreset preset : presets) {
            for (IndicatorCondition condition : preset.conditions().conditions()) {
                // 업종 중앙값 배수는 여기 걸리지 않는다 — 1.0이 중앙이고 위로 열려 있어
                // 1.5가 정상값이다. 백분위와 같은 범위를 요구하면 그 조건을 못 쓴다.
                if (!percentileCodes.contains(condition.indicatorCode())) {
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
    @DisplayName("IMPORTANT — 선언한 프리셋이 시더의 검증을 실제로 통과한다")
    void everyPresetPassesTheSeedValidator() {
        // PresetSynchronizer가 ScreenDefinitionValidator를 그대로 쓰므로, 여기서 걸리는 것은
        // **기동 실패**다. 지금까지 이 검증을 합성 프리셋으로만 돌려서, 진짜 프리셋이 상한을
        // 넘거나 없는 지표를 참조해도 앱을 띄우기 전에는 알 수 없었다.
        Set<String> known = knownIndicatorCodes();

        for (ScreenPreset preset : presets) {
            assertThatCode(() -> ScreenDefinitionValidator.validate(preset.conditions(), known))
                    .as("%s가 시더 검증을 통과하지 못한다 — 기동이 실패한다", preset.code())
                    .doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("가장 큰 프리셋의 조건 수가 숫자로 못박혀 있다")
    void pinsTheLargestPresetConditionCount() {
        // 상한을 넘는 것은 위 검증이 잡는다. 여기서 고정하는 것은 **지금 가장 큰 프리셋이
        // 몇 개를 쓰는가**다 — 그 수가 코드 어디에도 없으면 다음 세션이 조건을 더하다가
        // 기동 실패로 그때서야 상한을 만난다.
        for (ScreenPreset preset : presets) {
            assertThat(preset.conditions().conditions().size())
                    .as("%s의 조건 수", preset.code())
                    .isLessThanOrEqualTo(ScreenDefinitionValidator.MAX_CONDITIONS);
        }

        // MUST NOT: 「상한과의 여유가 N 이상」으로 되돌리지 않는다. `SWING_TREND_KR`(24개)이
        // 폐기되면서 최대가 17로 내려와 여유가 8이 됐고, 그 형태의 단언은 **실패할 수
        // 없는 펜스**가 된다(2026-08-20). 여유가 아니라 최대값 자체를 못박는다.
        // MUST NOT: 프리셋 코드를 박지 않는다. 어느 프리셋이 가장 큰지는 바뀌어도 되고,
        // 바뀌는 것 자체는 이 단언이 물어야 할 일이 아니다.
        //
        // MUST: 이 수가 **조건 개수**임을 안다. 버핏 프리셋의 「배점 총합 17」과 값이 같아지는
        // 시기가 있는데 **서로 무관한 수다** — 이것은 조건 몇 줄인가이고 저것은 가중치의 합이다.
        // 17 → 18은 ADR-0083이 세 프리셋에 이익소각 배점을 하나씩 더한 결과다.
        int largest = presets.stream()
                .mapToInt(p -> p.conditions().conditions().size())
                .max().orElseThrow();
        assertThat(largest)
                .as("가장 큰 프리셋의 조건 수. 상한은 %d다", ScreenDefinitionValidator.MAX_CONDITIONS)
                .isEqualTo(18);
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
        sectorMetricSpecs.forEach(spec -> {
            codes.add(spec.code());
            codes.add(spec.peerCountCode());
        });
        return codes;
    }

    /**
     * 상대 지표 코드 → 원본 지표 코드.
     *
     * <p>IMPORTANT — <b>코드에서 접미사를 문자열로 떼지 않는다.</b> 그렇게 하면
     * {@code PER_SECTOR_PCTL}에서 {@code _PCTL}만 떨어져 {@code PER_SECTOR}라는 없는 지표를
     * 찾게 되고, 접미사가 하나 늘 때마다 이 검사가 조용히 헛돈다. 선언이 원본을 이미 알고 있다.
     *
     * <p>표본 수({@code _SECTOR_N})는 넣지 않는다. 개수라서 원본의 유불리와 방향이 무관하다.
     */
    private Map<String, String> sourceByRelativeCode() {
        Map<String, String> byCode = new HashMap<>();
        percentileSpecs.forEach(spec -> byCode.put(spec.code(), spec.sourceCode()));
        sectorMetricSpecs.forEach(spec -> byCode.put(spec.code(), spec.sourceCode()));
        return byCode;
    }

    /** 값이 0~1인 지표. 유니버스 백분위와 업종 백분위 둘 다이며 중앙값 배수는 아니다. */
    private Set<String> percentileCodes() {
        Set<String> codes = percentileSpecs.stream()
                .map(PercentileSpec::code).collect(Collectors.toCollection(java.util.HashSet::new));
        sectorMetricSpecs.stream()
                .filter(spec -> spec.kind() == SectorMetricSpec.Kind.PERCENTILE)
                .forEach(spec -> codes.add(spec.code()));
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
    @DisplayName("상대 지표를 조건으로 쓰면 그 값이 실제로 계산되는 지표여야 한다")
    void relativeConditionsUseDeclaredMetrics() {
        // 접미사로 후보를 고르지 않는다. 선언에 없는 코드는 knownIndicatorCodes()가 이미
        // 잡으므로, 여기서는 **선언된 상대 지표만 골라** 그 원본까지 살아 있는지 본다 —
        // 원본이 사라지면 값이 영원히 비고, 그 조건은 조용히 판정불가가 된다.
        Set<String> sources = new java.util.HashSet<>(registry.codes());

        for (ScreenPreset preset : presets) {
            for (String code : preset.conditions().referencedIndicatorCodes()) {
                String source = sourceByRelativeCode().get(code);
                if (source == null) {
                    continue;
                }
                assertThat(sources)
                        .as("%s의 %s는 원본 %s에 기대는데 그 계산기가 없다", preset.code(), code, source)
                        .contains(source);
            }
        }
    }
}
