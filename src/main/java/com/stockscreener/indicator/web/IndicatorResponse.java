package com.stockscreener.indicator.web;

import com.stockscreener.indicator.IndicatorBand;
import com.stockscreener.indicator.IndicatorCategory;
import com.stockscreener.indicator.IndicatorDescriptor;
import com.stockscreener.indicator.IndicatorPopulation;
import com.stockscreener.indicator.IndicatorValueType;
import com.stockscreener.screening.ComparisonOperator;

import java.math.BigDecimal;
import java.util.List;

/**
 * 조건 빌더가 지표 하나를 그리는 데 필요한 전부.
 *
 * <p>{@code description}·{@code interpretation}이 반드시 실린다 — spec §5와 frontend.md가
 * "지표를 값만 나열하지 않는다"를 요구한다. 초보 사용자는 PER 8.1이 좋은 것인지 알 수 없다.
 *
 * @param percentile 백분위 지표일 때만 채워진다.
 * @param population 무엇과 견주어 나온 값인가. 상대 지표가 아니면 null.
 *                   <b>{@code percentile}이 있는 것과 다르다</b> — 업종 중앙값 배수는 모집단이
 *                   있지만 백분위가 아니라 "상위 20%" 안내가 붙지 않는다(ADR-0044).
 * @param bands      값 구간 프리셋. 제시할 수 없는 지표는 빈 목록이고, 그때 화면은 직접 입력만 낸다.
 */
public record IndicatorResponse(
        String code,
        String name,
        String description,
        String interpretation,
        IndicatorCategory category,
        IndicatorValueType valueType,
        String unit,
        int decimalScale,
        Boolean higherIsBetter,
        boolean defaultColumn,
        boolean detailDefault,
        int sortOrder,
        String percentileOf,
        PercentileGuide percentile,
        IndicatorPopulation population,
        List<BandResponse> bands
) {

    public static IndicatorResponse of(IndicatorDescriptor descriptor, IndicatorDescriptor source) {
        return new IndicatorResponse(
                descriptor.code(), descriptor.name(), descriptor.description(),
                descriptor.interpretation(), descriptor.category(), descriptor.valueType(),
                descriptor.unit(), descriptor.decimalScale(), descriptor.higherIsBetter(),
                descriptor.defaultColumn(), descriptor.detailDefault(), descriptor.sortOrder(),
                descriptor.sourceIndicatorCode(),
                descriptor.isPercentile() ? PercentileGuide.of(source) : null,
                descriptor.population(),
                descriptor.bands().stream().map(BandResponse::of).toList());
    }

    /**
     * 값 구간 하나를 <b>그대로 적용 가능한 조건</b>으로 내린다.
     *
     * <p>IMPORTANT — 부등호를 서버가 정한다. {@link PercentileGuide}가 "상위 20%"의 부등호를
     * 서버에서 정하는 것과 같은 이유다({@code .claude/rules/frontend.md}: 지표 코드로 분기하지
     * 않는다). 화면이 {@code from}/{@code to}에서 연산자를 유도하려 들면 그 유도 규칙이 두 곳에
     * 생기고, 어긋나도 값이 그럴듯해 드러나지 않는다.
     *
     * <p>{@code BETWEEN}의 상한은 도메인에서 <b>미포함</b>인데 {@link ComparisonOperator#BETWEEN}은
     * 양끝을 포함한다. 구간 경계에 정확히 걸리는 값은 두 구간 모두에 뽑히지만, 이것은 사용자가
     * 고른 하나의 조건일 뿐 구간 분류가 아니므로 문제가 되지 않는다. 경계값을 놓치는 쪽이 나쁘다.
     *
     * @param label       화면에 나오는 구간 이름
     * @param operator    이 구간을 표현하는 연산자
     * @param value       비교값. BETWEEN이면 하한.
     * @param upperBound  BETWEEN일 때만 채워진다.
     */
    public record BandResponse(
            String label,
            ComparisonOperator operator,
            BigDecimal value,
            BigDecimal upperBound
    ) {

        static BandResponse of(IndicatorBand band) {
            if (band.isOpenAbove()) {
                return new BandResponse(band.label(), ComparisonOperator.GTE, band.from(), null);
            }
            if (band.isOpenBelow()) {
                return new BandResponse(band.label(), ComparisonOperator.LT, band.to(), null);
            }
            return new BandResponse(band.label(), ComparisonOperator.BETWEEN, band.from(), band.to());
        }
    }

    /**
     * 백분위를 조건으로 쓸 때의 방향 안내.
     *
     * <p>IMPORTANT — 이 필드가 있는 이유는 <b>백분위가 언제나 오름차순인데 "상위 20%"의
     * 부등호는 원본 지표의 유불리에 따라 뒤집히기</b> 때문이다. PER은 낮을수록 좋으므로
     * "상위 20%"가 {@code <= 0.2}이고, ROE는 높을수록 좋으므로 {@code >= 0.8}이다.
     *
     * <p>프론트가 스스로 판단하려면 지표 코드를 알아야 하고, 그것은 프론트로 옮겨간
     * 하드코딩 분기다. 서버가 계산하는 것은 지표 코드에 대한 분기가 아니라 원본의
     * {@code higher_is_better}에 대한 순수 함수다.
     *
     * @param ascending  항상 참. 예외가 없다는 사실 자체를 계약으로 남긴다.
     * @param favorableEnd HIGH / LOW / UNKNOWN
     * @param topQuintileOperator "상위 20%"를 표현하는 연산자. 방향을 모르면 null.
     * @param topQuintileThreshold 그 경계값. 방향을 모르면 null.
     */
    public record PercentileGuide(
            boolean ascending,
            IndicatorDescriptor.FavorableEnd favorableEnd,
            IndicatorDescriptor.ComparisonOperatorHint topQuintileOperator,
            BigDecimal topQuintileThreshold,
            String label
    ) {

        static PercentileGuide of(IndicatorDescriptor source) {
            IndicatorDescriptor.FavorableEnd end = IndicatorDescriptor.favorableEndOf(source);
            String label = switch (end) {
                case HIGH -> "값이 클수록 유리하다. 상위 20%는 0.8 이상이다.";
                case LOW -> "값이 작을수록 유리하다. 상위 20%는 0.2 이하다.";
                // 방향이 없는 지표(RSI처럼 중간이 좋은 것)에 "상위/하위"를 제시하면
                // 사용자가 없는 의미를 읽는다.
                case UNKNOWN -> "이 지표는 유불리 방향이 정해져 있지 않다. 값의 위치만 나타낸다.";
            };
            return new PercentileGuide(
                    true, end, end.topQuintileOperator(), end.topQuintileThreshold(), label);
        }
    }
}
